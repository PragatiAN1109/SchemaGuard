package com.schemaguard.queue;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable record representing a single indexing event.
 *
 * Serialized as JSON by Spring AMQP's Jackson2JsonMessageConverter
 * when published to RabbitMQ.
 *
 * Fields:
 *   eventId      — UUID generated at publish time
 *   operation    — UPSERT / PATCH / DELETE
 *   documentId   — objectId of the plan resource
 *   resourceType — always "plan" for now
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
}
