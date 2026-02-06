package com.schemaguard.store;

import com.schemaguard.model.StoredDocument;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryKeyValueStoreTest {

    @Test
    void create_get_exists_delete_flow() {
        KeyValueStore store = new InMemoryKeyValueStore();

        String objectId = "12xvxc345ssdsds-508";
        String json = "{\"objectId\":\"12xvxc345ssdsds-508\",\"objectType\":\"plan\",\"_org\":\"example.com\"}";

        // create
        boolean created = store.create(objectId, json);
        assertTrue(created);

        // duplicate create should fail
        boolean createdAgain = store.create(objectId, json);
        assertFalse(createdAgain);

        // exists
        assertTrue(store.exists(objectId));
        assertFalse(store.exists("missing-id"));

        // get
        Optional<StoredDocument> fetched = store.get(objectId);
        assertTrue(fetched.isPresent());
        assertEquals(objectId, fetched.get().getObjectId());
        assertEquals(json, fetched.get().getJson());
        assertNotNull(fetched.get().getEtag());
        assertNotNull(fetched.get().getLastModified());

        // delete
        assertTrue(store.delete(objectId));
        assertFalse(store.delete(objectId)); // already deleted

        // after delete
        assertFalse(store.exists(objectId));
        assertTrue(store.get(objectId).isEmpty());
    }
}
