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
 * Covers the stale-event guard described in docs/consistency-model.md: an
 * event carrying an older etag than the document's current state in the
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
    void deleteEvent_deletesChildrenBeforeParent() {
        kvStore.create("plan-1", PLAN_JSON);
        StoredDocument doc = kvStore.get("plan-1").orElseThrow();

        // DELETE carries no version check — it must always proceed, regardless of etag.
        IndexEvent deleteEvent = IndexEvent.of(IndexEventOperation.DELETE, "plan-1", doc.getEtag());
        listener.onIndexEvent(deleteEvent);

        InOrder order = inOrder(indexService);
        order.verify(indexService).deleteChildren("plan-1");
        order.verify(indexService).deleteParent("plan-1");
    }
}
