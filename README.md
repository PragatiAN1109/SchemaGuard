# SchemaGuard

A Spring Boot REST API service for managing healthcare insurance plans with JSON Schema validation, ETag caching, Redis persistent storage, Elasticsearch parent-child indexing, and Google OAuth2 JWT security.

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

## IndexService abstraction

`IndexService` is the single interface for all Elasticsearch indexing operations in SchemaGuard.
It is implemented by `ElasticsearchIndexService` and is intentionally decoupled from the
Redis KV store — the queue consumer (next task) calls these methods after receiving a message.
Controllers never call `IndexService` directly.

### methods

| method | description |
|--------|-------------|
| `indexParent(parentId, doc, etag, metadata)` | upsert the plan document as a parent |
| `indexChild(parentId, childId, doc, etag, metadata)` | upsert a child document with `routing=parentId` |
| `patchParent(parentId, patchedDoc, etag)` | re-index parent with merged (post-patch) document |
| `deleteParent(parentId)` | delete the parent document by id |
| `deleteChildren(parentId)` | delete all children for a parent via `delete_by_query` |

### how parent and child documents are represented

**parent document** stored in Elasticsearch:
```json
{
  "objectId": "12xvxc345ssdsds-508",
  "objectType": "plan",
  "planType": "inNetwork",
  "_etag": "<sha256>",
  "my_join_field": "plan"
}
```

**child document** stored with `?routing=<parentId>`:
```json
{
  "objectId": "1234520xvc30asdf-502",
  "objectType": "service",
  "name": "Yearly physical",
  "_etag": "<sha256>",
  "my_join_field": {
    "name": "child",
    "parent": "12xvxc345ssdsds-508"
  }
}
```

### idempotency guarantee

- `indexParent` and `indexChild` use Elasticsearch upsert semantics — re-indexing the same id
  replaces the document without creating duplicates.
- `deleteParent` catches 404 and returns silently — deleting a non-existent document is not an error.
- `deleteChildren` uses `delete_by_query` with a `parent_id` filter — if no children exist,
  the query matches zero documents and succeeds with zero deletions.

### routing requirement for children

Child documents **must** be indexed with `?routing=<parentId>`. This ensures the child lands
on the same Elasticsearch shard as its parent, which is a hard requirement for
`has_child`, `has_parent`, and `parent_id` join queries to work correctly.

### `deleteChildren` implementation

`deleteChildren` sends a `POST /<index>/_delete_by_query?routing=<parentId>` with a
`parent_id` query that matches all child documents whose join parent equals the given id:

```json
{
  "query": {
    "parent_id": {
      "type": "child",
      "id": "<parentId>"
    }
  }
}
```

The `routing=parentId` parameter on the delete query ensures only the correct shard is
targeted, making the operation efficient even on large indices.

### index health endpoint (demo/debug)

```bash
curl http://localhost:8080/api/v1/index/health
```

Returns:
```json
{
  "cluster": "reachable",
  "index": "plans-index exists",
  "indexName": "plans-index"
}
```

No authentication required. No plan data is exposed.

---

## Elasticsearch parent-child mapping

### index mapping

The `plans-index` is created automatically on startup:

```json
{
  "mappings": {
    "dynamic": true,
    "properties": {
      "objectId":      { "type": "keyword" },
      "objectType":   { "type": "keyword" },
      "my_join_field": {
        "type": "join",
        "relations": { "plan": "child" }
      }
    }
  }
}
```

### routing rules

| document type | routing value | join field value |
|--------------|---------------|------------------|
| parent (plan) | `objectId` (default) | `"plan"` |
| child | `parentId` (explicit, required) | `{"name": "child", "parent": "<parentId>"}` |

---

## verifying the index

```bash
# index health via app
curl http://localhost:8080/api/v1/index/health

# index mapping directly
curl "http://localhost:9200/plans-index/_mapping?pretty"

# insert parent
curl -X PUT "http://localhost:9200/plans-index/_doc/12xvxc345ssdsds-508" \
  -H "Content-Type: application/json" \
  -d '{"objectId":"12xvxc345ssdsds-508","objectType":"plan","my_join_field":"plan"}'

# insert child (routing = parentId)
curl -X PUT "http://localhost:9200/plans-index/_doc/1234520xvc30asdf-502?routing=12xvxc345ssdsds-508" \
  -H "Content-Type: application/json" \
  -d '{"objectId":"1234520xvc30asdf-502","objectType":"service","my_join_field":{"name":"child","parent":"12xvxc345ssdsds-508"}}'

# query: parents with children
curl -X GET "http://localhost:9200/plans-index/_search" \
  -H "Content-Type: application/json" \
  -d '{"query":{"has_child":{"type":"child","query":{"match_all":{}}}}}'

# query: children of a parent
curl -X GET "http://localhost:9200/plans-index/_search" \
  -H "Content-Type: application/json" \
  -d '{"query":{"parent_id":{"type":"child","id":"12xvxc345ssdsds-508"}}}'
```

---

## error contract

Every error response follows this exact JSON shape:

```json
{
  "timestamp": "2026-02-28T10:15:30Z",
  "status": 400,
  "error": "Bad Request",
  "message": "...",
  "path": "/api/v1/plan"
}
```

### example 1 — validation failure (400)

```bash
curl -X POST http://localhost:8080/api/v1/plan \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"objectId": "abc", "objectType": "plan"}'
```

### example 2 — unauthorized / no token (401)

```bash
curl http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508
```

### example 3 — precondition failed (412)

```bash
curl -X DELETE http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H "Authorization: Bearer $TOKEN" \
  -H 'If-Match: "wrong-etag-value"'
```

---

## Google IDP security

SchemaGuard is configured as an **OAuth2 Resource Server**. Every request to `/api/v1/plan/**`
must include a valid `Authorization: Bearer <token>` header — a Google RS256-signed ID token
validated against `https://www.googleapis.com/oauth2/v3/certs`.

**public routes:** `/api/v1/schema/**`, `/api/v1/index/**`

**protected routes:** `/api/v1/plan/**`

---

## running tests

```bash
./mvnw test
```

tests use the `test` profile: `InMemoryKeyValueStore`, no Redis, no Elasticsearch, no Google token required.

---

## JSON Schema

```bash
curl http://localhost:8080/api/v1/schema/plan
```

---

## architecture

```
SchemaGuard/
├── compose.yaml                                         ← Redis + Elasticsearch + app
├── Dockerfile
├── docs/get-token.html
├── src/main/java/com/schemaguard/
│   ├── config/
│   │   ├── AppConfig.java
│   │   ├── ElasticsearchConfig.java
│   │   ├── RedisConfig.java
│   │   └── SecurityConfig.java                  ← /api/v1/index/** added as public
│   ├── elastic/
│   │   ├── ElasticsearchHealthCheck.java
│   │   ├── ElasticsearchIndexService.java       ← IndexService implementation (RestTemplate)
│   │   ├── IndexService.java                    ← interface: indexParent/Child, patch, delete
│   │   ├── PlanIndexConstants.java
│   │   ├── PlanIndexInitializer.java
│   │   └── PlanRoutingStrategy.java
│   ├── controller/
│   │   ├── IndexAdminController.java            ← GET /api/v1/index/health
│   │   ├── PlanController.java
│   │   └── SchemaController.java
│   ├── exception/GlobalExceptionHandler.java
│   ├── model/
│   │   ├── ApiError.java
│   │   └── StoredDocument.java
│   ├── security/
│   │   ├── JwtClaimsLogger.java
│   │   └── SecurityErrorHandler.java
│   ├── store/
│   ├── util/
│   └── validation/
├── src/main/resources/
│   ├── schemas/plan-schema.json
│   ├── application.properties
│   └── application-redis.properties
└── src/test/resources/
    └── application-test.properties
```
