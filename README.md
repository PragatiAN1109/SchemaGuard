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

**step 7 — cascaded delete demo (KV + Elastic parent + Elastic children):**

> Index: `plans-index` · Stream: `schemaguard:index-events` · Join field: `my_join_field`

**7a — verify parent exists in Elastic BEFORE delete:**
```bash
curl -s "http://localhost:9200/plans-index/_doc/12xvxc345ssdsds-508" \
  | python3 -m json.tool | grep -E '"found"|"objectId"'
# Expected: "found": true
```

**7b — verify children exist BEFORE delete:**
```bash
curl -s -X GET "http://localhost:9200/plans-index/_search" \
  -H "Content-Type: application/json" \
  -d '{"query":{"parent_id":{"type":"child","id":"12xvxc345ssdsds-508"}}}' \
  | python3 -m json.tool | grep -E '"total"|"value"'
# Expected: "value": 2  (the two linkedPlanServices entries)
```

**7c — verify KV has the document BEFORE delete:**
```bash
curl -s "http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool | grep objectId
# Expected: "objectId": "12xvxc345ssdsds-508"
```

**7d — send the DELETE request:**
```bash
curl -s -o /dev/null -w "%{http_code}" \
  -X DELETE "http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508" \
  -H "Authorization: Bearer $TOKEN"
# Expected: 204
```

App log (visible via `docker logs schemaguard-app`):
```
DELETE removed from KV id=12xvxc345ssdsds-508; published DELETE event for cascaded Elastic removal
Processing DELETE event id=12xvxc345ssdsds-508
Deleted children for parent id=12xvxc345ssdsds-508
Deleted parent id=12xvxc345ssdsds-508
```

**7e — confirm DELETE event in the Redis Stream:**
```bash
docker exec -it schemaguard-redis redis-cli XRANGE schemaguard:index-events - +
```
Look for an entry with `operation=DELETE` and `documentId=12xvxc345ssdsds-508`.

**7f — verify KV returns 404 AFTER delete (wait ~1 s):**
```bash
sleep 1
curl -s "http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool | grep -E '"status"|"error"'
# Expected: "status": 404, "error": "Not Found"
```

**7g — verify parent is gone from Elastic:**
```bash
curl -s "http://localhost:9200/plans-index/_doc/12xvxc345ssdsds-508" \
  | python3 -m json.tool | grep '"found"'
# Expected: "found": false
```

**7h — verify all children are gone from Elastic:**
```bash
curl -s -X GET "http://localhost:9200/plans-index/_search" \
  -H "Content-Type: application/json" \
  -d '{"query":{"parent_id":{"type":"child","id":"12xvxc345ssdsds-508"}}}' \
  | python3 -m json.tool | grep -E '"total"|"value"'
# Expected: "value": 0  — all children cascaded-deleted
```

**7i — confirm no stuck messages in PEL:**
```bash
docker exec -it schemaguard-redis redis-cli XPENDING schemaguard:index-events schemaguard-indexers - + 10
# Expected: empty list
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
Processing DELETE event id=12xvxc345ssdsds-508
Deleted children for parent id=12xvxc345ssdsds-508
Deleted parent id=12xvxc345ssdsds-508
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

## Cascaded delete — KV + Elastic parent + Elastic children

### Flow

```
DELETE /api/v1/plan/{objectId}
  1.  Fetch document from KV store         → 404 if not found
  2.  Validate If-Match ETag (if provided) → 412 if stale
  3.  Capture etag before deletion
  4.  Delete document from KV store (Redis)
  5.  Publish DELETE event to Redis Stream
      fields: operation=DELETE, documentId, etag=<last-known etag>, timestamp
      (fire-and-forget — 204 response is NOT blocked by stream write)
  6.  Return 204 No Content
      ↓ ~1 s later
  7.  IndexWorker reads DELETE event via XREADGROUP
  8.  Calls IndexService.deleteChildren(parentId)
      → delete_by_query with routing=parentId + parent_id term filter
      → removes ALL child documents for this parent in one query
      → idempotent: no children = no error
  9.  Calls IndexService.deleteParent(parentId)
      → DELETE /<index>/_doc/<parentId>
      → 404 from Elastic handled gracefully (already absent = success)
  10. XACK — message removed from PEL
```

### Why children must be deleted before the parent

In Elasticsearch's parent-child join model, child documents are co-located with their
parent on the same shard via `routing=parentId`. Deleting children first ensures:

1. **No orphaned children** — once the parent is gone, children cannot be reached via
   `parent_id` queries and would silently consume index space forever.
2. **Correct shard targeting** — `delete_by_query` uses `routing=parentId` to target
   only the shard where children live. This routing value is available from the event,
   before the parent document is removed from Elastic.
3. **Idempotency** — if the worker crashes between step 8 and 9, re-processing the
   event will attempt `deleteChildren` again (no-op, already gone) then `deleteParent`
   again (404 from Elastic, handled gracefully). No errors, no duplicates.

### deleteChildren implementation

```
POST /plans-index/_delete_by_query?routing=<parentId>&refresh=true
{
  "query": {
    "parent_id": {
      "type": "child",
      "id": "<parentId>"
    }
  }
}
```

- `routing=parentId` — targets only the shard where children are stored
- `parent_id` query — matches only child documents belonging to this parent
- `refresh=true` — ensures the deletion is visible to subsequent searches immediately
- Returns `{ "deleted": N }` — N=0 if no children exist (not an error)

### Key constants

| Name | Value |
|------|-------|
| Redis Stream | `schemaguard:index-events` |
| Consumer group | `schemaguard-indexers` |
| Elasticsearch index | `plans-index` |
| Join field | `my_join_field` |
| Child type name | `child` |
| Parent type name | `plan` |

---

## Search API — parent-child Elasticsearch queries

Search endpoints expose Elasticsearch parent-child join queries as REST APIs.
All endpoints require a valid Google Bearer token (same as `/api/v1/plan/**`).

> **Indexing is asynchronous.** Documents are indexed by `IndexWorker` after the API
> writes to Redis — search results may lag behind the KV store by ~1 s after a write.
> Always allow the worker to process events before running search queries.

### Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/api/v1/search` | Search parent plans, optionally filtered by child properties |
| `GET` | `/api/v1/search/parent/{id}/children` | Return all children for a given parent |

---

### GET /api/v1/search — search parents via has_child

**Query params (all optional):**

| Param | Description |
|-------|-------------|
| `childField` | Field name on a child document to filter by |
| `childValue` | Value to match on `childField` (term + match) |
| `q` | Free-text search applied to the parent document itself |

`childField` and `childValue` must be provided together or both omitted.

**Example — find parents that have a child with `linkedService.name = "Yearly physical"`:**
```bash
curl -s "http://localhost:8080/api/v1/search?childField=linkedService.name&childValue=Yearly%20physical" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

**Example — find parents with a child of objectType = planservice:**
```bash
curl -s "http://localhost:8080/api/v1/search?childField=objectType&childValue=planservice" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

**Example — list all indexed parents (no filter):**
```bash
curl -s "http://localhost:8080/api/v1/search" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

**Example — combine parent text search + child filter:**
```bash
curl -s "http://localhost:8080/api/v1/search?q=inNetwork&childField=objectType&childValue=planservice" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

**Response shape:**
```json
{
  "count": 1,
  "results": [
    {
      "parentId": "12xvxc345ssdsds-508",
      "score": 1.0,
      "source": { "objectId": "...", "planType": "inNetwork", ... },
      "matchedBy": {
        "childField": "name",
        "childValue": "Yearly physical"
      }
    }
  ]
}
```

---

### GET /api/v1/search/parent/{id}/children — list children via has_parent

Returns all child documents (linkedPlanServices entries) belonging to the given parent.
Returns `count: 0` with an empty array if the parent has no children — not a 404.

```bash
curl -s "http://localhost:8080/api/v1/search/parent/12xvxc345ssdsds-508/children" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

**Response shape:**
```json
{
  "parentId": "12xvxc345ssdsds-508",
  "count": 2,
  "children": [
    {
      "childId": "27283xvx9asdff-504",
      "source": { "objectId": "27283xvx9asdff-504", "objectType": "planservice", ... }
    },
    {
      "childId": "27283xvx9sdf-507",
      "source": { "objectId": "27283xvx9sdf-507", "objectType": "planservice", ... }
    }
  ]
}
```

---

### Direct Elasticsearch equivalents (for demo/debugging)

**has_child query — parents with a child matching a field:**
```bash
curl -s -X GET "http://localhost:9200/plans-index/_search" \
  -H "Content-Type: application/json" \
  -d '{
    "query": {
      "has_child": {
        "type": "child",
        "query": { "match": { "name": "Yearly physical" } }
      }
    }
  }' | python3 -m json.tool
```

**has_parent query — children for a specific parent (with routing):**
```bash
curl -s -X GET "http://localhost:9200/plans-index/_search?routing=12xvxc345ssdsds-508" \
  -H "Content-Type: application/json" \
  -d '{
    "query": {
      "has_parent": {
        "parent_type": "plan",
        "query": { "term": { "objectId": "12xvxc345ssdsds-508" } }
      }
    }
  }' | python3 -m json.tool
```

**parent_id query — direct child lookup (most efficient, use routing):**
```bash
curl -s -X GET "http://localhost:9200/plans-index/_search?routing=12xvxc345ssdsds-508" \
  -H "Content-Type: application/json" \
  -d '{
    "query": {
      "parent_id": { "type": "child", "id": "12xvxc345ssdsds-508" }
    }
  }' | python3 -m json.tool
```

---

### Routing and join field notes

| Concept | Detail |
|---------|--------|
| Join field name | `my_join_field` |
| Parent relation | `plan` (plain string value) |
| Child relation | `child` (object: `{"name":"child","parent":"<parentId>"}`) |
| Child routing | `routing=parentId` — **required** so child queries hit the correct shard |
| Why routing matters | Child docs are co-located with their parent on the same shard. Without `routing=parentId`, the query scatters to all shards and may miss documents. |

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
