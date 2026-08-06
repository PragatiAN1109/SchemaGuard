package com.schemaguard.queue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaguard.elastic.IndexService;
import com.schemaguard.model.StoredDocument;
import com.schemaguard.store.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.schemaguard.config.RabbitMQConfig.QUEUE_NAME;

/**
 * RabbitMQ consumer that processes indexing events and
 * synchronises Elasticsearch via IndexService.
 *
 * Listens on: schemaguard.index.queue
 *
 * Processing:
 *   UPSERT → re-index parent + all children
 *   PATCH  → re-index parent + all children (count unchanged)
 *   DELETE → delete children first, then parent (cascaded)
 *
 * Stale-event guard (UPSERT / PATCH): if the event etag no longer
 * matches the current KV etag, a newer write has superseded this
 * event and we skip indexing.
 *
 * Stale-event guard (DELETE): a DELETE event carries no etag to
 * compare against post-deletion, so staleness is detected differently —
 * by checking whether the KV store currently holds a document for this
 * id. If it does, a new object was created (or recreated) with the same
 * objectId after this DELETE was published, and removing it from
 * Elasticsearch now would incorrectly delete a document that reflects
 * live Redis state. The event is skipped in that case. If no document
 * exists, the DELETE proceeds as normal.
 *
 * Spring AMQP auto-ack: if the listener returns normally the
 * message is acknowledged. If it throws, the message is nacked
 * and requeued (Spring default retry handles this).
 *
 * Active only on the 'redis' profile.
 */
@Component
@Profile("redis")
public class RabbitMQIndexListener {

    private static final Logger log = LoggerFactory.getLogger(RabbitMQIndexListener.class);

    private final IndexService indexService;
    private final KeyValueStore kvStore;
    private final ObjectMapper objectMapper;
    private final PlanDocumentSplitter splitter;

    public RabbitMQIndexListener(IndexService indexService,
                                  KeyValueStore kvStore,
                                  ObjectMapper objectMapper,
                                  PlanDocumentSplitter splitter) {
        this.indexService = indexService;
        this.kvStore = kvStore;
        this.objectMapper = objectMapper;
        this.splitter = splitter;
    }

    @RabbitListener(queues = QUEUE_NAME)
    public void onIndexEvent(IndexEvent event) {
        log.info("[DEMO] RABBITMQ consumed {} event for id={} etag={} from queue={}",
                event.operation(), event.documentId(), event.etag(), QUEUE_NAME);

        try {
            switch (event.operation()) {
                case "UPSERT" -> handleUpsert(event.documentId(), event.etag());
                case "PATCH"  -> handlePatch(event.documentId(), event.etag());
                case "DELETE" -> handleDelete(event.documentId());
                default -> log.warn("[DEMO] unknown operation '{}' for id={} — skipping",
                        event.operation(), event.documentId());
            }
        } catch (Exception ex) {
            log.error("[DEMO] RABBITMQ failed to process {} event for id={} — {}",
                    event.operation(), event.documentId(), ex.getMessage());
            // Rethrow so Spring AMQP can nack/retry the message
            throw new RuntimeException("Index event processing failed", ex);
        }
    }

    // ─────────────────────────────────────────────────────────
    // UPSERT — index parent + all children
    // ─────────────────────────────────────────────────────────
    private void handleUpsert(String documentId, String etag) throws Exception {
        StoredDocument doc = kvStore.get(documentId)
                .orElseThrow(() -> new IllegalStateException(
                        "document not found in KV store for id=" + documentId));


        // Stale-event guard
        if (!etag.equals(doc.getEtag())) {
            log.info("[DEMO] RABBITMQ skipping stale UPSERT for id={} eventEtag={} currentEtag={}",
                    documentId, etag, doc.getEtag());
            return;
        }

        log.info("[DEMO] RABBITMQ processing UPSERT id={} etag={}", documentId, etag);

        JsonNode parentNode = objectMapper.readTree(doc.getJson());
        indexService.indexParent(documentId, parentNode, doc.getEtag(), null);

        List<PlanDocumentSplitter.ChildEntry> children = splitter.extractChildren(doc.getJson());
        for (PlanDocumentSplitter.ChildEntry child : children) {
            indexService.indexChild(documentId, child.childId(), child.childDoc(),
                    doc.getEtag(), null);
        }
        log.info("[DEMO] RABBITMQ UPSERT complete id={} → indexed 1 parent + {} children",
                documentId, children.size());
    }

    // ─────────────────────────────────────────────────────────
    // PATCH — re-index parent + all children (count unchanged)
    // ─────────────────────────────────────────────────────────
    private void handlePatch(String documentId, String etag) throws Exception {
        StoredDocument doc = kvStore.get(documentId)
                .orElseThrow(() -> new IllegalStateException(
                        "document not found in KV store for id=" + documentId));

        // Stale-event guard
        if (!etag.equals(doc.getEtag())) {
            log.info("[DEMO] RABBITMQ skipping stale PATCH for id={} eventEtag={} currentEtag={}",
                    documentId, etag, doc.getEtag());
            return;
        }

        log.info("[DEMO] RABBITMQ processing PATCH id={} etag={} — re-indexing parent + all children",
                documentId, etag);

        JsonNode parentNode = objectMapper.readTree(doc.getJson());
        indexService.indexParent(documentId, parentNode, doc.getEtag(), null);

        List<PlanDocumentSplitter.ChildEntry> children = splitter.extractChildren(doc.getJson());
        for (PlanDocumentSplitter.ChildEntry child : children) {
            indexService.indexChild(documentId, child.childId(), child.childDoc(),
                    doc.getEtag(), null);
        }
        log.info("[DEMO] RABBITMQ PATCH complete id={} → re-indexed 1 parent + {} children (count unchanged)",
                documentId, children.size());
    }

    // ─────────────────────────────────────────────────────────
    // DELETE — cascaded removal (children first, then parent)
    // ─────────────────────────────────────────────────────────
    private void handleDelete(String documentId) {
        // Stale-event guard: if the KV store has a document for this id right now,
        // it was (re)created after this DELETE was published — the delete is stale
        // and must not remove the newer document's Elasticsearch entries.
        if (kvStore.exists(documentId)) {
            log.info("[DEMO] RABBITMQ skipping stale DELETE for id={} — a document with this id currently exists in KV (likely recreated after this event was published)",
                    documentId);
            return;
        }

        log.info("[DEMO] RABBITMQ processing DELETE id={} — cascading removal from ES", documentId);

        // Step 1: delete all child documents first
        indexService.deleteChildren(documentId);
        log.info("[DEMO] RABBITMQ DELETE step 1 complete — children removed for parent id={}", documentId);

        // Step 2: delete the parent document
        indexService.deleteParent(documentId);
        log.info("[DEMO] RABBITMQ DELETE step 2 complete — parent removed id={}", documentId);
    }
}
