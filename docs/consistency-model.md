# Consistency Model

## ETag-based optimistic concurrency

1. **Calculation** — `EtagUtil.sha256Etag(json)` computes a SHA-256 hex digest of the raw plan JSON string. It is stored unquoted in `StoredDocument.etag`.
2. **Storage** — the ETag lives alongside the document in Redis (`plan:<objectId>` → `{objectId, json, etag, lastModified}`), and in Elasticsearch as an `_etag` field added when the document is indexed.
3. **`If-Match` validation (`PUT`, `PATCH`, `DELETE`)** — if the header is present, `PlanController` strips its surrounding quotes and compares it to the current Redis ETag. A mismatch throws `PreconditionFailedException` → `412 Precondition Failed`. **The header is optional** — a request with no `If-Match` is not rejected; it always applies against the current state.
4. **`If-None-Match` handling (`GET`)** — if present and it matches the current ETag (quotes stripped), the API returns `304 Not Modified` with no body.
5. **How the async consumer uses the ETag** — every successful write publishes an `IndexEvent` carrying the *new* ETag produced by that write. `RabbitMQIndexListener` never trusts the event's own document data; it re-fetches the current document from Redis and compares `event.etag()` to the freshly-read `etag`.
6. **How stale events are detected and skipped** — if the two ETags differ, a newer write has already superseded the event. The listener logs the skip and returns without indexing (no error, no retry — this is treated as expected behavior, not a failure). `DELETE` events are the exception: they carry no version check because delete is idempotent (indexing an already-absent document is a no-op).

## Why this matters

Elasticsearch is not written to synchronously with Redis. Between the API returning `200`/`204` and the consumer picking up the event, another request may have already produced a newer ETag. Without the guard in step 6, a slow or re-delivered event could re-index an older snapshot over a newer one.

```
Client                  API (Redis)                RabbitMQIndexListener        Elasticsearch
  │                          │                           │                    │
  ├─ PATCH v1 ──────────────►│ If-Match: etag_v0         │                    │
  │                          │ precondition passes        │                    │
  │                          │ Redis → etag_v1            │                    │
  │                          │ publish event(etag=v1) ───►│                    │
  │◄─ 200 etag_v1 ───────────│                           │                    │
  │                          │                           │                    │
  ├─ PATCH v2 ──────────────►│ If-Match: etag_v1         │                    │
  │                          │ precondition passes        │                    │
  │                          │ Redis → etag_v2            │                    │
  │                          │ publish event(etag=v2) ───►│                    │
  │◄─ 200 etag_v2 ───────────│                           │                    │
  │                          │                           │                    │
  │                          │          event(etag=v1) ──►│                    │
  │                          │          re-fetch → etag_v2│                    │
  │                          │          v1 ≠ v2 → skip   │                    │
  │                          │                           │                    │
  │                          │          event(etag=v2) ──►│                    │
  │                          │          re-fetch → etag_v2│                    │
  │                          │          v2 = v2 → index ─►│── index v2 ───────►│
```

## What this does and does not guarantee

| Claim | Accurate? |
|-------|-----------|
| A queued event carrying an older ETag will not overwrite a document already updated to a newer ETag | Yes — verified by `RabbitMQIndexListenerTest` |
| Elasticsearch is eventually consistent with Redis, provided the write's event is successfully published and consumed | Yes, with the caveat below |
| Elasticsearch can never contain data that was never committed to Redis | Yes — the listener only ever indexes what it reads from Redis, never the event payload itself |
| Every write is guaranteed to eventually reach Elasticsearch | **No.** If RabbitMQ is unreachable when a write commits, `RabbitMQEventPublisher` catches the exception, logs it, and the event is lost — see [failure-handling.md](failure-handling.md) |
| Delivery is exactly-once | **No.** RabbitMQ's default requeue-on-failure behavior can redeliver a message; indexing operations are upsert/idempotent so redelivery is safe, but this is at-least-once processing, not exactly-once |

For the demo used to observe this behavior end-to-end, see [demo-runbook.md](demo-runbook.md).
