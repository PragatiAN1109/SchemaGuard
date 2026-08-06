# Failure Handling

This describes what the code actually does when a dependency is unavailable or an operation fails — not an aspirational reliability model.

## RabbitMQ

| Scenario | Behavior |
|----------|----------|
| RabbitMQ unreachable when publishing | `RabbitMQEventPublisher.publish()` catches the exception and logs a warning. **The event is dropped — there is no retry, no local queue, no outbox.** The Redis write has already committed, so this is a dual-write gap: the KV store now holds a version that Elasticsearch will never learn about until a subsequent write to the same document succeeds in publishing. |
| Publisher confirms | **Not enabled.** `RabbitMQConfig` does not configure publisher confirm callbacks or mandatory returns; a successful `convertAndSend()` call only means the message was handed to the client library, not that the broker persisted it. |
| Consumer throws while processing a message | The exception is logged and rethrown. Spring AMQP's default listener container behavior applies: the message is **nacked and requeued**. |
| Retry / backoff | **Not configured.** There is no retry interceptor, backoff policy, or max-attempts setting anywhere in `RabbitMQConfig` or `application-redis.properties`. All retrying happens through broker redelivery (nack → requeue), not in-memory retry — there is no bounded retry count and no delay between attempts. |
| Poison message (permanently failing) | **Produces a tight redelivery loop.** A message whose processing always throws is nacked, requeued, and immediately redelivered — with no backoff — forever. Each redelivery re-runs `onIndexEvent`, so this repeatedly consumes CPU, writes a fresh error log line, and re-issues whatever downstream call is failing (e.g. an Elasticsearch request) on every attempt, indefinitely, until someone manually removes the message from the queue or stops the consumer. |
| Dead-letter queue | **Not implemented.** The queue has no `x-dead-letter-exchange` argument, so there is no separate destination for a message to land in for inspection — it stays in the redelivery loop above instead. |
| Stale event: UPSERT/PATCH (etag mismatch) | Treated as expected, not a failure — the listener logs the skip and acknowledges the message normally (see [consistency-model.md](consistency-model.md)). |
| Stale event: DELETE (id recreated) | Also treated as expected — the listener checks whether the id currently exists in Redis before deleting from Elasticsearch, and skips (acknowledging normally) if it does, since that means the id was recreated after the delete was published (see [consistency-model.md](consistency-model.md)). |

## Elasticsearch

| Scenario | Behavior |
|----------|----------|
| Unreachable at startup | `ElasticsearchHealthCheck` and `PlanIndexInitializer` both catch the connection failure, log a warning, and let the application continue starting. The `plans-index` mapping is simply not created until the next successful attempt (there is no retry loop — it only runs once, on `ApplicationReadyEvent`). |
| Unreachable during indexing (write path) | `ElasticsearchIndexService` catches the exception per operation, logs a warning, and moves on. The event is considered "processed" (acknowledged) even though the index write failed — there is no re-queue of failed index writes. |
| Unreachable during a search request | `PlanSearchService` lets the exception propagate; `SearchController` catches it and returns `500` with `"Search query failed — Elasticsearch may be unavailable"`. |
| `GET /api/v1/index/health` | Reports `cluster: reachable` / `unreachable: <message>` and whether `plans-index` exists — useful for confirming ES state without inspecting logs. |
| Index lost, corrupted, or a document goes missing without a corresponding Redis change | **Does not recover on its own.** There is no startup reconciliation, no scheduled re-sync, and no admin "rebuild index from Redis" endpoint. A Redis document whose Elasticsearch entry was lost stays missing from search results indefinitely — until *that specific document* is written again (`PUT`/`PATCH`) and its own `IndexEvent` re-indexes it. Recovering the rest of the index requires either restoring an Elasticsearch snapshot (not configured here) or writing a one-off script that iterates `KeyValueStore.keys()` and calls `IndexService.indexParent`/`indexChild` for each — no such script exists in this repository today. |

## Redis

Redis is the authoritative store — if it is unreachable, `RedisKeyValueStore` calls fail and propagate as `500` responses through `GlobalExceptionHandler`. There is no fallback store, no local buffering, and no automatic reconnection logic beyond what Spring Data Redis's underlying client provides.

## What is and is not implemented

**Implemented:**
- Stale UPSERT/PATCH event rejection (etag comparison before indexing)
- Stale DELETE event rejection (existence check before deleting — a recreated id is not deleted)
- Idempotent index/delete operations (safe under redelivery)
- Non-fatal startup checks for Elasticsearch connectivity
- A single dual-write path (Redis write → best-effort RabbitMQ publish) with no transactional guarantee between the two

**Not implemented (see [Limitations](../README.md#limitations-and-future-improvements) in the README):**
- Transactional outbox pattern for the Redis-write-then-publish step
- RabbitMQ publisher confirms
- Bounded retry with backoff for the consumer
- Dead-letter queue for permanently-failing messages
- A full Redis-to-Elasticsearch rebuild/reindex operation — RabbitMQ is used here as a work queue, not a retained event log; once a message is acknowledged it is gone and cannot be replayed
- Exactly-once delivery of any kind
