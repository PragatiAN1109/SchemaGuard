package com.schemaguard.queue;

/**
 * Represents the type of indexing operation that triggered an event.
 *
 * UPSERT — document was created (POST) or fully replaced (PUT)
 * PATCH   — document was partially updated via JSON Merge Patch
 * DELETE  — document was deleted
 */
public enum IndexEventOperation {
    UPSERT,
    PATCH,
    DELETE
}
