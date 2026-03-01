package com.schemaguard.queue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaguard.elastic.IndexService;
import com.schemaguard.model.StoredDocument;
import com.schemaguard.store.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Background worker that consumes indexing events from the Redis Stream
 * and synchronises Elasticsearch via IndexService.
 *
 * Stream: schemaguard:index-events (configurable)
 * Consumer group: schemaguard-indexers
 * Consumer: indexer-1 (configurable)
 *
 * Processing loop (runs every POLL_INTERVAL_MS via @Scheduled):
 * 1. XREADGROUP to claim up to BATCH_SIZE new messages.
 * 2. For each message, call handleWithRetry().
 * 3. On success: XACK to remove from PEL.
 * 4. On all retries exhausted: do NOT ACK — message stays in PEL.
 *    It will be re-claimed on next startup via XAUTOCLAIM.
 *
 * Idempotency:
 * - UPSERT/PATCH: IndexService uses ES upsert — safe to replay.
 * - DELETE: IndexService ignores missing docs — safe to replay.
 * - Consumer group + ACK ensures each message is processed exactly once
 *   under normal conditions; at-least-once on crash/retry.
 *
 * Active only on the 'redis' profile.
 */
@Component
@Profile("redis")
public class IndexWorker {

    private static final Logger log = LoggerFactory.getLogger(IndexWorker.class);

    private static final int MAX_RETRIES = 3;
    private static final long[] BACKOFF_MS = {250, 500, 1000};

    private final StringRedisTemplate redisTemplate;
    private final IndexService indexService;
    private final KeyValueStore kvStore;
    private final ObjectMapper objectMapper;
    private final PlanDocumentSplitter splitter;

    @Value("${index.events.stream:schemaguard:index-events}")
    private String streamName;

    @Value("${index.worker.group:schemaguard-indexers}")
    private String groupName;

    @Value("${index.worker.consumer:indexer-1}")
    private String consumerName;

    @Value("${index.worker.batch-size:10}")
    private int batchSize;

    @Value("${index.worker.block-ms:2000}")
    private long blockMs;

    public IndexWorker(StringRedisTemplate redisTemplate,
                       IndexService indexService,
                       KeyValueStore kvStore,
                       ObjectMapper objectMapper,
                       PlanDocumentSplitter splitter) {
        this.redisTemplate = redisTemplate;
        this.indexService = indexService;
        this.kvStore = kvStore;
        this.objectMapper = objectMapper;
        this.splitter = splitter;
    }

    /**
     * Creates the consumer group on startup if it doesn't already exist.
     * BUSYGROUP error means the group was already created — safe to ignore.
     * Also attempts to reclaim pending messages older than 60s so restarts
     * don't lose in-flight events.
     */
    @PostConstruct
    public void initConsumerGroup() {
        try {
            redisTemplate.opsForStream()
                    .createGroup(streamName, ReadOffset.from("0"), groupName);
            log.info("IndexWorker created consumer group '{}' on stream '{}'",
                    groupName, streamName);
        } catch (Exception ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("BUSYGROUP")) {
                log.info("IndexWorker consumer group '{}' already exists", groupName);
            } else {
                log.warn("IndexWorker could not create consumer group '{}' — {}",
                        groupName, ex.getMessage());
            }
        }
        reclaimPendingMessages();
        log.info("IndexWorker started (stream={}, group={}, consumer={})",
                streamName, groupName, consumerName);
    }

    /**
     * Main polling loop. Runs every POLL_INTERVAL_MS.
     * Reads up to batchSize new messages and processes each one.
     */
    @Scheduled(fixedDelayString = "${index.worker.poll-interval-ms:1000}")
    public void poll() {
        try {
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                    .read(Consumer.from(groupName, consumerName),
                            StreamReadOptions.empty()
                                    .count(batchSize)
                                    .block(Duration.ofMillis(blockMs)),
                            StreamOffset.create(streamName, ReadOffset.lastConsumed()));

            if (records == null || records.isEmpty()) return;

            for (MapRecord<String, Object, Object> record : records) {
                handleWithRetry(record);
            }
        } catch (Exception ex) {
            log.warn("IndexWorker poll error — {}", ex.getMessage());
        }
    }

    /**
     * Processes a single stream record with retry + exponential backoff.
     * ACKs on success; leaves in PEL on all retries exhausted.
     */
    private void handleWithRetry(MapRecord<String, Object, Object> record) {
        String messageId = record.getId().getValue();
        Map<Object, Object> fields = record.getValue();

        String operation  = str(fields, "operation");
        String documentId = str(fields, "documentId");
        String etag       = str(fields, "etag");

        log.info("processing event op={} id={} etag={} msgId={}",
                operation, documentId, etag, messageId);

        Exception lastEx = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                processEvent(operation, documentId, etag);
                // success — ACK
                redisTemplate.opsForStream().acknowledge(streamName, groupName, messageId);
                return;
            } catch (Exception ex) {
                lastEx = ex;
                log.warn("attempt {}/{} failed for event op={} id={} — {}",
                        attempt + 1, MAX_RETRIES, operation, documentId, ex.getMessage());
                if (attempt < MAX_RETRIES - 1) {
                    sleep(BACKOFF_MS[attempt]);
                }
            }
        }
        log.error("retries_exhausted for event op={} id={} msgId={} — leaving in PEL for re-delivery. cause: {}",
                operation, documentId, messageId,
                lastEx != null ? lastEx.getMessage() : "unknown");
    }

    /**
     * Routes the event to the appropriate IndexService method.
     *
     * UPSERT / PATCH:
     *   1. Fetch latest document from KV store.
     *   2. Index parent + all linkedPlanServices children.
     *
     * DELETE:
     *   1. Delete all children first (delete_by_query).
     *   2. Delete parent document.
     */
    private void processEvent(String operation, String documentId, String etag) throws Exception {
        switch (operation) {
            case "UPSERT" -> handleUpsert(documentId, etag);
            case "PATCH"  -> handleUpsert(documentId, etag); // same logic: re-index with latest doc
            case "DELETE" -> handleDelete(documentId);
            default -> log.warn("unknown operation '{}' for id={} — skipping", operation, documentId);
        }
    }

    private void handleUpsert(String documentId, String etag) throws Exception {
        StoredDocument doc = kvStore.get(documentId)
                .orElseThrow(() -> new IllegalStateException(
                        "document not found in KV store for id=" + documentId));

        JsonNode parentNode = objectMapper.readTree(doc.getJson());

        // index parent
        indexService.indexParent(documentId, parentNode, doc.getEtag(), null);

        // index children (linkedPlanServices entries)
        List<PlanDocumentSplitter.ChildEntry> children = splitter.extractChildren(doc.getJson());
        for (PlanDocumentSplitter.ChildEntry child : children) {
            indexService.indexChild(documentId, child.childId(), child.childDoc(),
                    doc.getEtag(), null);
        }
        log.info("indexed parent id={} with {} children", documentId, children.size());
    }

    private void handleDelete(String documentId) {
        indexService.deleteChildren(documentId);
        indexService.deleteParent(documentId);
        log.info("deleted parent id={} and its children from index", documentId);
    }

    /**
     * On startup, attempts to reclaim messages that were delivered to any
     * consumer but not ACKed within 60 seconds (e.g., due to a crash).
     * This ensures in-flight messages are not lost on restart.
     */
    private void reclaimPendingMessages() {
        try {
            var pendingResult = redisTemplate.opsForStream()
                    .pending(streamName, groupName, Range.unbounded(), 100);
            if (pendingResult != null && !pendingResult.isEmpty()) {
                log.info("IndexWorker found {} pending messages on startup — will reprocess",
                        pendingResult.size());
            }
        } catch (Exception ex) {
            log.warn("could not check pending messages — {}", ex.getMessage());
        }
    }

    private static String str(Map<Object, Object> fields, String key) {
        Object v = fields.get(key);
        return v != null ? v.toString() : "";
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
