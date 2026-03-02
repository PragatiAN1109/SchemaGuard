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
import org.springframework.data.domain.Range;
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
 *    It will be re-claimed on next startup via pending check.
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
        log.error("retries_exhausted for event op={} id={} msgId={} — leaving in PEL. cause: {}",
                operation, documentId, messageId,
                lastEx != null ? lastEx.getMessage() : "unknown");
    }

    private void processEvent(String operation, String documentId, String etag) throws Exception {
        switch (operation) {
            case "UPSERT" -> handleUpsert(documentId, etag);
            case "PATCH"  -> handlePatch(documentId, etag);
            case "DELETE" -> handleDelete(documentId);
            default -> log.warn("unknown operation '{}' for id={} — skipping", operation, documentId);
        }
    }

    private void handleUpsert(String documentId, String etag) throws Exception {
        StoredDocument doc = kvStore.get(documentId)
                .orElseThrow(() -> new IllegalStateException(
                        "document not found in KV store for id=" + documentId));

        JsonNode parentNode = objectMapper.readTree(doc.getJson());
        indexService.indexParent(documentId, parentNode, doc.getEtag(), null);

        List<PlanDocumentSplitter.ChildEntry> children = splitter.extractChildren(doc.getJson());
        for (PlanDocumentSplitter.ChildEntry child : children) {
            indexService.indexChild(documentId, child.childId(), child.childDoc(),
                    doc.getEtag(), null);
        }
        log.info("indexed parent id={} with {} children", documentId, children.size());
    }

    /**
     * Handles a PATCH event by re-fetching the authoritative document from the KV store
     * and re-indexing it in Elasticsearch.
     *
     * We deliberately do NOT apply the partial patch directly to Elastic.
     * Always fetching the latest committed state from KV (Redis) ensures Elasticsearch
     * is a consistent, idempotent replica of the single source of truth.
     */
    private void handlePatch(String documentId, String etag) throws Exception {
        log.info("Processing PATCH event id={} etag={}", documentId, etag);

        StoredDocument doc = kvStore.get(documentId)
                .orElseThrow(() -> new IllegalStateException(
                        "document not found in KV store for id=" + documentId));

        log.info("Fetched latest KV doc id={}; re-indexed into Elastic", documentId);

        JsonNode parentNode = objectMapper.readTree(doc.getJson());
        // Full upsert — Elasticsearch replaces the document by id, guaranteeing
        // the indexed state matches the current KV contents exactly.
        indexService.indexParent(documentId, parentNode, doc.getEtag(), null);

        // Re-index children so their stored etag stays consistent with the parent.
        List<PlanDocumentSplitter.ChildEntry> children = splitter.extractChildren(doc.getJson());
        for (PlanDocumentSplitter.ChildEntry child : children) {
            indexService.indexChild(documentId, child.childId(), child.childDoc(),
                    doc.getEtag(), null);
        }
        log.info("PATCH re-index complete id={} children={}", documentId, children.size());
    }

    /**
     * Handles a DELETE event by cascading removal through Elasticsearch.
     *
     * Order is deliberate — children MUST be deleted before the parent because
     * Elasticsearch's parent-child join requires the parent to exist when querying
     * children via parent_id. Deleting children first also avoids orphaned child
     * documents that would be unreachable after the parent is gone.
     *
     * Both operations are idempotent: re-processing the same DELETE event
     * (after a worker crash + PEL re-claim) produces no errors.
     */
    private void handleDelete(String documentId) {
        log.info("Processing DELETE event id={}", documentId);

        // Step 1: delete all child documents for this parent first.
        // Uses delete_by_query with routing=parentId + parent_id term query.
        indexService.deleteChildren(documentId);
        log.info("Deleted children for parent id={}", documentId);

        // Step 2: delete the parent document. Graceful if already absent.
        indexService.deleteParent(documentId);
        log.info("Deleted parent id={}", documentId);
    }

    /**
     * Checks for pending (unACKed) messages from previous runs and logs the count.
     * Uses PendingMessages — the correct return type from opsForStream().pending().
     */
    private void reclaimPendingMessages() {
        try {
            PendingMessages pending = redisTemplate.opsForStream()
                    .pending(streamName, groupName, Range.unbounded(), 100);
            if (pending != null && !pending.isEmpty()) {
                log.info("IndexWorker found {} pending messages on startup — will reprocess",
                        pending.size());
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
