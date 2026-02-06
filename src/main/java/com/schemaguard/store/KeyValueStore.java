package com.schemaguard.store;

import com.schemaguard.model.StoredDocument;

import java.util.Optional;

public interface KeyValueStore {

    /**
     * Creates a new entry.
     * @return true if created successfully, false if key already exists
     */
    boolean create(String objectId, String jsonString);

    /**
     * Fetches an entry by id.
     */
    Optional<StoredDocument> get(String objectId);

    /**
     * Deletes an entry by id.
     * @return true if deleted, false if not found
     */
    boolean delete(String objectId);

    /**
     * Checks if a key exists.
     */
    boolean exists(String objectId);
}
