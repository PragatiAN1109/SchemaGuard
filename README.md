# SchemaGuard

A Spring Boot REST API service for managing healthcare insurance plans with JSON Schema validation, ETag caching, Redis persistent storage, Elasticsearch parent-child indexing, Redis Streams event publishing + consumer worker, and Google OAuth2 JWT security.

## overview

SchemaGuard is a RESTful web service that provides full CRUD operations for healthcare insurance plan data. It enforces strict JSON Schema validation, supports conditional GET/PUT/PATCH/DELETE using ETags, implements JSON Merge Patch (RFC 7396), and secures all plan endpoints using Google RS256 Bearer tokens.

## features

- JSON Schema validation for all incoming plan data (POST, PUT, PATCH)
- full CRUD: POST, GET, PUT (replace), PATCH (JSON Merge Patch), DELETE
- ETag support for conditional requests (SHA-256 based)
- **Google OAuth2 RS256 JWT security — all `/api/v1/plan/**` endpoints require a valid Bearer token**
- **public endpoint: `GET /api/v1/schema/plan` — no auth required**
- **standardized error contract — every error returns the same JSON shape**
- Redis as primary KV store — data persists across app restarts
- **Redis Streams event publishing — UPSERT/PATCH/DELETE events on every successful write**
- **IndexWorker — background consumer that reads events and syncs Elasticsearch**
- Elasticsearch parent-child index — `plans-index` with join mapping
- `IndexService` abstraction — clean interface for all ES index operations
- Docker Compose for one-command demo startup

## Spring profile → storage + security mapping

| Profile | Storage Backend | Security |
|---------|----------------|----------|
| `redis` (default) | `RedisKeyValueStore` | Google JWT enforced |
| `test` | `InMemoryKeyValueStore` | security auto-config excluded |

---

## starting the full stack

```bash
export GOOGLE_CLIENT_ID=<your-client-id>.apps.googleusercontent.com
docker compose up --build
```

Starts three services: **Redis** (6379), **Elasticsearch** (9200), **app** (8080).

Expected startup logs:
```
IndexWorker created consumer group 'schemaguard-indexers' on stream 'schemaguard:index-events'
IndexWorker started (stream=schemaguard:index-events, group=schemaguard-indexers, consumer=indexer-1)
Elasticsearch index 'plans-index' initialized with parent-child mapping
Started SchemaGuardApplication
```

---

## IndexWorker — Redis Streams consumer

`IndexWorker` is a background `@Scheduled` component (active on `redis` profile only) that
consumes events from the Redis Stream and synchronises Elasticsearch.

### flow

```
POST /api/v1/plan
  → KV store (Redis)
  → publish UPSERT to stream
  → IndexWorker picks up event (within ~1s)
  → fetches doc from KV store
  → IndexService.indexParent() + indexChild() per linkedPlanService
  → Elasticsearch updated
  → XACK (message removed from PEL)
```

### parent-child split

| document | ES type | routing |
|----------|---------|--------|
| plan (top-level) | parent | own objectId (default) |
| each `linkedPlanServices` entry | child | parentId (plan objectId) |

The `planCostShares` object and nested cost shares are stored within the parent document
(searchable via dynamic mapping), not as separate child documents.

### stream / group / consumer config

| config key | env var | default |
|------------|---------|--------|
| `index.events.stream` | `INDEX_EVENTS_STREAM` | `schemaguard:index-events` |
| `index.worker.group` | `INDEX_WORKER_GROUP` | `schemaguard-indexers` |
| `index.worker.consumer` | `INDEX_WORKER_CONSUMER` | `indexer-1` |
| `index.worker.batch-size` | `INDEX_WORKER_BATCH_SIZE` | `10` |
| `index.worker.block-ms` | `INDEX_WORKER_BLOCK_MS` | `2000` |
| `index.worker.poll-interval-ms` | `INDEX_WORKER_POLL_INTERVAL_MS` | `1000` |

### retry strategy

- 3 attempts per message with 250ms / 500ms / 1000ms backoff
- on success: `XACK` — message removed from PEL
- on all retries exhausted: **do NOT ACK** — message stays in PEL and is re-delivered on next startup via pending message check
- app never crashes on indexing failure

### how restarts avoid duplicates

1. **Consumer group + ACK**: each message is only delivered to one consumer at a time
2. **PEL on failure**: unACKed messages are re-delivered, not lost
3. **Idempotent operations**: ES upsert and delete are safe to replay — re-processing the same event has no side effects

---

## demo runbook — end-to-end

```bash
TOKEN="<your Google ID token>"
```

**step 1 — create a plan:**
```bash
curl -X POST http://localhost:8080/api/v1/plan \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d @samples/plan.json
```

**step 2 — confirm event in stream:**
```bash
docker exec -it schemaguard-redis redis-cli XRANGE schemaguard:index-events - +
```

**step 3 — confirm worker processed it (all ACKed, PEL empty):**
```bash
docker exec -it schemaguard-redis redis-cli XPENDING schemaguard:index-events schemaguard-indexers - + 10
```
Expected: empty (all messages ACKed)

**step 4 — confirm parent indexed in Elasticsearch:**
```bash
curl "http://localhost:9200/plans-index/_doc/12xvxc345ssdsds-508"
```
Expected: `"found": true` with plan document

**step 5 — confirm children indexed (query by parent_id):**
```bash
curl -X GET "http://localhost:9200/plans-index/_search" \
  -H "Content-Type: application/json" \
  -d '{"query":{"parent_id":{"type":"child","id":"12xvxc345ssdsds-508"}}}'
```
Expected: 2 hits (the two linkedPlanServices entries)

**step 6 — end-to-end PATCH propagation demo (KV → Queue → Elastic):**

> Index: `plans-index` · Stream: `schemaguard:index-events` · Join field: `my_join_field`

**6a — query Elastic BEFORE the patch (baseline):**
```bash
curl -s "http://localhost:9200/plans-index/_doc/12xvxc345ssdsds-508" \
  | python3 -m json.tool | grep planType
# Expected: "planType": "inNetwork"
```

**6b — send the PATCH request (JSON Merge Patch — RFC 7396):**
```bash
curl -X PATCH "http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508" \
  -H "Content-Type: application/merge-patch+json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"planType": "outOfNetwork"}'
```
Expected: `200 OK` with updated JSON body and new `ETag` header.

App log (visible via `docker logs schemaguard-app`):
```
PATCH applied to KV id=12xvxc345ssdsds-508 newEtag=<new-sha256>; published PATCH event
Processing PATCH event id=12xvxc345ssdsds-508 etag=<new-sha256>
Fetched latest KV doc id=12xvxc345ssdsds-508; re-indexed into Elastic
PATCH re-index complete id=12xvxc345ssdsds-508 children=2
```

**6c — confirm PATCH event in the Redis Stream:**
```bash
docker exec -it schemaguard-redis redis-cli XRANGE schemaguard:index-events - +
```
Look for an entry with `operation=PATCH` and `documentId=12xvxc345ssdsds-508`.

**6d — query Elastic AFTER the patch (wait ~1 s for worker):**
```bash
sleep 1
curl -s "http://localhost:9200/plans-index/_doc/12xvxc345ssdsds-508" \
  | python3 -m json.tool | grep planType
# Expected: "planType": "outOfNetwork"
```

**6e — term search to confirm the updated value is queryable:**
```bash
curl -s -X GET "http://localhost:9200/plans-index/_search" \
  -H "Content-Type: application/json" \
  -d '{"query":{"term":{"planType":"outOfNetwork"}}}' \
  | python3 -m json.tool | grep -E '"planType"|"objectId"'
# Expected: 1 hit — objectId=12xvxc345ssdsds-508, planType=outOfNetwork
```

**step 7 — delete and verify removed from ES:**
```bash
curl -X DELETE "http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508" \
  -H "Authorization: Bearer $TOKEN"

# wait ~1s, then:
curl "http://localhost:9200/plans-index/_doc/12xvxc345ssdsds-508"
# Expected: "found": false

curl -X GET "http://localhost:9200/plans-index/_search" \
  -H "Content-Type: application/json" \
  -d '{"query":{"parent_id":{"type":"child","id":"12xvxc345ssdsds-508"}}}'
# Expected: 0 hits (children deleted)
```

**step 8 — observe worker logs:**
```bash
docker logs schemaguard-app | grep -i indexworker
```

Expected:
```
IndexWorker started (stream=schemaguard:index-events, group=schemaguard-indexers, consumer=indexer-1)
processing event op=UPSERT id=12xvxc345ssdsds-508 etag=<sha256> msgId=...
indexed parent id=12xvxc345ssdsds-508 with 2 children
Processing PATCH event id=12xvxc345ssdsds-508 etag=<new-sha256>
Fetched latest KV doc id=12xvxc345ssdsds-508; re-indexed into Elastic
PATCH re-index complete id=12xvxc345ssdsds-508 children=2
processing event op=DELETE id=12xvxc345ssdsds-508 etag=<sha256> msgId=...
deleted parent id=12xvxc345ssdsds-508 and its children from index
```

---

## PATCH propagation — KV → Queue → Elastic

### 10-step flow

```
PATCH /api/v1/plan/{objectId}
  1.  Validate If-Match ETag              → 412 if stale
  2.  Apply JSON Merge Patch (RFC 7396)   → merged document
  3.  Validate merged doc (JSON Schema)   → 400 if invalid
  4.  Write merged doc to KV (Redis)      → new ETag generated
  5.  Publish PATCH event to Redis Stream
      fields: operation=PATCH, documentId, etag=<NEW etag>, timestamp
      (fire-and-forget — API response is NOT blocked by stream write)
  6.  Return 200 with updated body + ETag header
      ↓ ~1 s later
  7.  IndexWorker reads PATCH event via XREADGROUP
  8.  Fetches authoritative document from KV store (not from Elastic)
  9.  Calls IndexService.indexParent() → Elasticsearch upsert by id
  10. Re-indexes all linkedPlanServices children; XACK message
```

### Why re-fetch from KV rather than patch Elastic directly

| Reason | Detail |
|--------|--------|
| **No divergence** | Elastic can never hold a value not committed to KV. If KV write failed, no event is published — Elastic is never touched. |
| **Idempotency** | Re-processing the same event (worker crash + PEL re-claim) always indexes the current KV state — no duplicate or conflicting writes. |
| **No patch logic in worker** | Worker does not need to understand JSON Merge Patch semantics; it simply indexes whatever is in KV. |
| **Safe under rapid PATCHes** | If two events are processed out of order, the last one always indexes the most recent committed KV state regardless of which ETag it carried. |

### Key constants

| Name | Value |
|------|-------|
| Redis Stream | `schemaguard:index-events` |
| Consumer group | `schemaguard-indexers` |
| Elasticsearch index | `plans-index` |
| Join field | `my_join_field` |
| Parent join value | `"plan"` (plain string) |
| Child join value | `{"name":"child","parent":"<parentId>"}` |

---

## queueing (Redis Streams) — publisher

Every successful write publishes an event to `schemaguard:index-events`.

| operation | event type | condition |
|-----------|------------|----------|
| `POST` | `UPSERT` | 201 only |
| `PUT` | `UPSERT` | 200 only |
| `PATCH` | `PATCH` | 200 only |
| `DELETE` | `DELETE` | 204 only |

```bash
# inspect stream
docker exec -it schemaguard-redis redis-cli XRANGE schemaguard:index-events - +

# count events
docker exec -it schemaguard-redis redis-cli XLEN schemaguard:index-events

# check pending (unACKed) messages
docker exec -it schemaguard-redis redis-cli XPENDING schemaguard:index-events schemaguard-indexers - + 10
```

---

## IndexService abstraction

| method | description |
|--------|-------------|
| `indexParent` | upsert plan as parent |
| `indexChild` | upsert child with `routing=parentId` |
| `patchParent` | re-index parent with patched doc |
| `deleteParent` | delete parent by id |
| `deleteChildren` | delete all children via `delete_by_query` |

All idempotent. Health: `curl http://localhost:8080/api/v1/index/health`

---

## Elasticsearch parent-child mapping

```json
{
  "mappings": {
    "dynamic": true,
    "properties": {
      "objectId":       { "type": "keyword" },
      "objectType":     { "type": "keyword" },
      "my_join_field":  { "type": "join", "relations": { "plan": "child" } }
    }
  }
}
```

---

## error contract

```json
{
  "timestamp": "2026-02-28T10:15:30Z",
  "status": 400,
  "error": "Bad Request",
  "message": "...",
  "path": "/api/v1/plan"
}
```

---

## Google IDP security

OAuth2 Resource Server — Google RS256 tokens validated against `https://www.googleapis.com/oauth2/v3/certs`.

**public:** `/api/v1/schema/**`, `/api/v1/index/**`    **protected:** `/api/v1/plan/**`

---

## running tests

```bash
./mvnw test
```

test profile: `InMemoryKeyValueStore`, `NoOpIndexEventPublisher`, no Redis/ES/token needed.

---

## architecture

```
SchemaGuard/
├── compose.yaml                                       ← Redis + Elasticsearch + app
├── src/main/java/com/schemaguard/
│   ├── SchemaGuardApplication.java                  ← @EnableScheduling added
│   ├── config/
│   │   ├── AppConfig.java
│   │   ├── ElasticsearchConfig.java
│   │   ├── RedisConfig.java
│   │   └── SecurityConfig.java
│   ├── elastic/
│   │   ├── ElasticsearchHealthCheck.java
│   │   ├── ElasticsearchIndexService.java
│   │   ├── IndexService.java
│   │   ├── PlanIndexConstants.java
│   │   ├── PlanIndexInitializer.java
│   │   └── PlanRoutingStrategy.java
│   ├── queue/
│   │   ├── IndexEvent.java
│   │   ├── IndexEventOperation.java
│   │   ├── IndexEventPublisher.java
│   │   ├── RedisStreamEventPublisher.java
│   │   ├── NoOpIndexEventPublisher.java
│   │   ├── PlanDocumentSplitter.java              ← extracts linkedPlanServices as children
│   │   └── IndexWorker.java                       ← XREADGROUP consumer, retry, XACK
│   ├── controller/
│   │   ├── IndexAdminController.java
│   │   ├── PlanController.java
│   │   └── SchemaController.java
│   ├── exception/
│   ├── model/
│   ├── security/
│   ├── store/
│   ├── util/
│   └── validation/
├── src/main/resources/
│   ├── application.properties
│   └── application-redis.properties               ← worker config keys added
└── src/test/resources/application-test.properties
```
