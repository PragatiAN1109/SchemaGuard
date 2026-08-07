package com.schemaguard.queue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaguard.elastic.IndexService;
import com.schemaguard.model.StoredDocument;
import com.schemaguard.store.InMemoryKeyValueStore;
import com.schemaguard.store.KeyValueStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Covers the stale-event guard described in the README's Consistency Model
 * section: an event carrying an older etag than the document's current state in the
 * KV store must never reach the index service, regardless of delivery order.
 */
class RabbitMQIndexListenerTest {

    private static final String PLAN_JSON = """
            {
              "objectId": "plan-1",
              "objectType": "plan",
              "planType": "inNetwork",
              "linkedPlanServices": [
                { "objectId": "child-1", "objectType": "planservice" },
                { "objectId": "child-2", "objectType": "planservice" }
              ]
            }
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KeyValueStore kvStore = new InMemoryKeyValueStore();
    private final PlanDocumentSplitter splitter = new PlanDocumentSplitter(objectMapper);
    private IndexService indexService;
    private RabbitMQIndexListener listener;

    @BeforeEach
    void setUp() {
        indexService = mock(IndexService.class);
        listener = new RabbitMQIndexListener(indexService, kvStore, objectMapper, splitter);
    }

    @Test
    void staleUpsertEvent_isSkipped_notIndexed() {
        kvStore.create("plan-1", PLAN_JSON);
        String staleEtag = kvStore.get("plan-1").orElseThrow().getEtag();

        // A newer write lands after the event was published — the event's etag is now stale.
        kvStore.update("plan-1", PLAN_JSON.replace("inNetwork", "outOfNetwork"));

        IndexEvent staleEvent = IndexEvent.of(IndexEventOperation.UPSERT, "plan-1", staleEtag);
        listener.onIndexEvent(staleEvent);

        verifyNoInteractions(indexService);
    }

    @Test
    void stalePatchEvent_isSkipped_notIndexed() {
        kvStore.create("plan-1", PLAN_JSON);
        String staleEtag = kvStore.get("plan-1").orElseThrow().getEtag();

        kvStore.update("plan-1", PLAN_JSON.replace("inNetwork", "outOfNetwork"));

        IndexEvent staleEvent = IndexEvent.of(IndexEventOperation.PATCH, "plan-1", staleEtag);
        listener.onIndexEvent(staleEvent);

        verifyNoInteractions(indexService);
    }

    @Test
    void freshUpsertEvent_indexesParentAndAllChildren() {
        kvStore.create("plan-1", PLAN_JSON);
        StoredDocument doc = kvStore.get("plan-1").orElseThrow();

        IndexEvent freshEvent = IndexEvent.of(IndexEventOperation.UPSERT, "plan-1", doc.getEtag());
        listener.onIndexEvent(freshEvent);

        verify(indexService).indexParent(eq("plan-1"), any(JsonNode.class), eq(doc.getEtag()), isNull());
        verify(indexService).indexChild(eq("plan-1"), eq("child-1"), any(JsonNode.class), eq(doc.getEtag()), isNull());
        verify(indexService).indexChild(eq("plan-1"), eq("child-2"), any(JsonNode.class), eq(doc.getEtag()), isNull());
        verifyNoMoreInteractions(indexService);
    }

    @Test
    void deleteEvent_deletesChildrenBeforeParent_whenDocumentNoLongerExistsInKv() {
        kvStore.create("plan-1", PLAN_JSON);
        StoredDocument doc = kvStore.get("plan-1").orElseThrow();

        // Mirrors PlanController.deletePlan: the KV entry is removed before the
        // DELETE event is published, so by the time the listener sees it, Redis
        // no longer has this id.
        kvStore.delete("plan-1");

        IndexEvent deleteEvent = IndexEvent.of(IndexEventOperation.DELETE, "plan-1", doc.getEtag());
        listener.onIndexEvent(deleteEvent);

        InOrder order = inOrder(indexService);
        order.verify(indexService).deleteChildren("plan-1");
        order.verify(indexService).deleteParent("plan-1");
    }

    @Test
    void staleDeleteAfterRecreationDoesNotDeleteNewDocument() {
        kvStore.create("plan-1", PLAN_JSON);
        StoredDocument original = kvStore.get("plan-1").orElseThrow();
        IndexEvent staleDeleteEvent = IndexEvent.of(IndexEventOperation.DELETE, "plan-1", original.getEtag());

        // The plan is deleted, then a new plan is created with the same objectId —
        // both happen before the delayed DELETE event above is finally processed
        // (e.g. it sat behind a poison message, or the consumer was briefly down).
        kvStore.delete("plan-1");
        kvStore.create("plan-1", PLAN_JSON.replace("inNetwork", "outOfNetwork"));

        listener.onIndexEvent(staleDeleteEvent);

        // The recreated document's own UPSERT event is what should index it — this
        // old DELETE must not touch Elasticsearch at all.
        verifyNoInteractions(indexService);
    }
}
