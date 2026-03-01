package com.schemaguard.queue;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable record representing a single indexing event published to the Redis Stream.
 *
 * Fields are kept as plain strings so the record serialises cleanly into the
 * Map<String, String> format required by Redis Streams (XADD).
 *
 * Fields:
 *   eventId      — UUID generated at publish time; useful for deduplication in the consumer
 *   operation    — UPSERT / PATCH / DELETE
 *   documentId   — objectId of the plan resource
 *   resourceType — always "plan" for now; extensible for other resource types later
 *   etag         — current SHA-256 ETag at the time of the event
 *   timestamp    — ISO-8601 instant at publish time
 */
public record IndexEvent(
        String eventId,
        String operation,
        String documentId,
        String resourceType,
        String etag,
        String timestamp
) {
    /**
     * Factory method — generates eventId and timestamp automatically.
     */
    public static IndexEvent of(IndexEventOperation op, String documentId, String etag) {
        return new IndexEvent(
                UUID.randomUUID().toString(),
                op.name(),
                documentId,
                "plan",
                etag != null ? etag : "",
                Instant.now().toString()
        );
    }

    /**
     * Converts the event to a flat Map<String, String> for Redis XADD.
     * Redis Streams store each entry as a set of field–value pairs.
     */
    public java.util.Map<String, String> toStreamFields() {
        return java.util.Map.of(
                "eventId",      eventId,
                "operation",    operation,
                "documentId",   documentId,
                "resourceType", resourceType,
                "etag",         etag,
                "timestamp",    timestamp
        );
    }
}
