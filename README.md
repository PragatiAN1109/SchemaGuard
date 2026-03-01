# SchemaGuard

A Spring Boot REST API service for managing healthcare insurance plans with JSON Schema validation, ETag caching, Redis persistent storage, Elasticsearch parent-child indexing, Redis Streams event publishing, and Google OAuth2 JWT security.

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
- Elasticsearch parent-child index — `plans-index` created on startup with join mapping
- `IndexService` abstraction — clean interface for all ES index operations
- Docker Compose for one-command demo startup

## Spring profile → storage + security mapping

| Profile | Storage Backend | Security |
|---------|----------------|----------|
| `redis` (default) | `RedisKeyValueStore` | Google JWT enforced |
| `test` | `InMemoryKeyValueStore` | security auto-config excluded |

---

## starting the full stack

### prerequisites
- Docker + Docker Compose installed
- Google Client ID (see Google IDP security section below)

### start all services

```bash
export GOOGLE_CLIENT_ID=<your-client-id>.apps.googleusercontent.com
docker compose up --build
```

This starts three services:
- **Redis** on port `6379`
- **Elasticsearch** on port `9200`
- **SchemaGuard app** on port `8080`

Look for these lines in the app logs to confirm everything is up:
```
configuring Elasticsearch client → elasticsearch:9200
Elasticsearch index 'plans-index' initialized with parent-child mapping
Elasticsearch cluster reachable at http://elasticsearch:9200
Started SchemaGuardApplication
```

---

## queueing (Redis Streams)

Every successful write operation publishes an event to the Redis Stream `schemaguard:index-events`.
The stream is created automatically on first write — no manual setup required.

### event structure

Each stream entry contains these fields:

| field | example value | description |
|-------|---------------|-------------|
| `eventId` | `a3f2...` | UUID, unique per event |
| `operation` | `UPSERT` | `UPSERT`, `PATCH`, or `DELETE` |
| `documentId` | `12xvxc345ssdsds-508` | plan objectId |
| `resourceType` | `plan` | always `plan` for now |
| `etag` | `sha256hex...` | ETag at time of event |
| `timestamp` | `2026-03-01T14:00:00Z` | ISO-8601 instant |

### when events are published

| operation | event type | condition |
|-----------|------------|----------|
| `POST /api/v1/plan` | `UPSERT` | only on 201 Created |
| `PUT /api/v1/plan/{id}` | `UPSERT` | only on 200 OK |
| `PATCH /api/v1/plan/{id}` | `PATCH` | only on 200 OK |
| `DELETE /api/v1/plan/{id}` | `DELETE` | only on 204 No Content |

Events are **never** published for failed operations (400 / 404 / 409 / 412).

### fire-and-forget tradeoff

Publishing is non-blocking and non-fatal. If a stream write fails, the API returns success
to the client and logs a warning. This prioritises API stability over strict event durability
for the demo. In a production system, a transactional outbox pattern would be used instead.

### viewing events in Redis

```bash
# open redis-cli inside the container
docker exec -it schemaguard-redis redis-cli

# read all events from the stream
XRANGE schemaguard:index-events - +

# read only the last 5 events
XREVRANGE schemaguard:index-events + - COUNT 5

# count total events
XLEN schemaguard:index-events
```

### demo: generate and inspect events

**step 1 — set your token:**
```bash
TOKEN="<your Google ID token>"
```

**step 2 — create a plan (publishes UPSERT):**
```bash
curl -X POST http://localhost:8080/api/v1/plan \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d @samples/plan.json
```

**step 3 — patch the plan (publishes PATCH):**
```bash
curl -X PATCH "http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508" \
  -H "Content-Type: application/merge-patch+json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"planType": "outOfNetwork"}'
```

**step 4 — delete the plan (publishes DELETE):**
```bash
ETAG=$(curl -s http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H "Authorization: Bearer $TOKEN" -I | grep -i etag | awk '{print $2}' | tr -d '\r')

curl -X DELETE "http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508" \
  -H "Authorization: Bearer $TOKEN" \
  -H "If-Match: $ETAG"
```

**step 5 — inspect all events:**
```bash
docker exec -it schemaguard-redis redis-cli XRANGE schemaguard:index-events - +
```

### sample event record (as seen in redis-cli)

```
1) 1) "1709300400000-0"
   2)  1) "eventId"
       2) "a3f29c1d-84e2-4b7a-9f3c-12e456789abc"
       3) "operation"
       4) "UPSERT"
       5) "documentId"
       6) "12xvxc345ssdsds-508"
       7) "resourceType"
       8) "plan"
       9) "etag"
      10) "d4e8f2a1b3c9..."
      11) "timestamp"
      12) "2026-03-01T14:00:00.123456Z"
```

---

## IndexService abstraction

`IndexService` is the single interface for all Elasticsearch indexing operations.
Implemented by `ElasticsearchIndexService`. The queue consumer (next task) calls
these methods after receiving stream events. Controllers never call `IndexService` directly.

| method | description |
|--------|-------------|
| `indexParent(parentId, doc, etag, metadata)` | upsert the plan document as a parent |
| `indexChild(parentId, childId, doc, etag, metadata)` | upsert a child document with `routing=parentId` |
| `patchParent(parentId, patchedDoc, etag)` | re-index parent with merged document |
| `deleteParent(parentId)` | delete the parent document by id |
| `deleteChildren(parentId)` | delete all children via `delete_by_query` |

All operations are idempotent. `deleteChildren` uses `delete_by_query` with `routing=parentId`
and a `parent_id` filter. See index health: `curl http://localhost:8080/api/v1/index/health`

---

## Elasticsearch parent-child mapping

The `plans-index` is created automatically on startup:

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

| document type | routing | join field value |
|--------------|---------|------------------|
| parent | `objectId` (default) | `"plan"` |
| child | `parentId` (explicit) | `{"name": "child", "parent": "<parentId>"}` |

---

## error contract

Every error response follows this JSON shape:

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

SchemaGuard is an **OAuth2 Resource Server** validating Google RS256 ID tokens against
`https://www.googleapis.com/oauth2/v3/certs`.

**public routes:** `/api/v1/schema/**`, `/api/v1/index/**`
**protected routes:** `/api/v1/plan/**`

---

## running tests

```bash
./mvnw test
```

tests use the `test` profile: `InMemoryKeyValueStore`, `NoOpIndexEventPublisher`,
no Redis, no Elasticsearch, no Google token required.

---

## architecture

```
SchemaGuard/
├── compose.yaml                                         ← Redis + Elasticsearch + app
├── src/main/java/com/schemaguard/
│   ├── config/
│   │   ├── AppConfig.java
│   │   ├── ElasticsearchConfig.java
│   │   ├── RedisConfig.java                     ← added StringRedisTemplate bean
│   │   └── SecurityConfig.java
│   ├── elastic/
│   │   ├── ElasticsearchHealthCheck.java
│   │   ├── ElasticsearchIndexService.java
│   │   ├── IndexService.java
│   │   ├── PlanIndexConstants.java
│   │   ├── PlanIndexInitializer.java
│   │   └── PlanRoutingStrategy.java
│   ├── queue/                                       ← new package
│   │   ├── IndexEvent.java                      ← event record with toStreamFields()
│   │   ├── IndexEventOperation.java             ← UPSERT / PATCH / DELETE enum
│   │   ├── IndexEventPublisher.java             ← interface
│   │   ├── RedisStreamEventPublisher.java       ← @Profile("redis") XADD impl
│   │   └── NoOpIndexEventPublisher.java         ← @Profile("test") no-op
│   ├── controller/
│   │   ├── IndexAdminController.java
│   │   ├── PlanController.java                  ← publishes events after POST/PUT/PATCH/DELETE
│   │   └── SchemaController.java
│   ├── exception/GlobalExceptionHandler.java
│   ├── model/
│   ├── security/
│   ├── store/
│   ├── util/
│   └── validation/
├── src/main/resources/
│   ├── application.properties
│   └── application-redis.properties             ← index.events.stream property added
└── src/test/resources/application-test.properties
```
