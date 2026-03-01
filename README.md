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

The app waits for both Redis and Elasticsearch to pass their health checks before starting.

Look for these lines in the app logs to confirm everything is up:
```
configuring Elasticsearch client → elasticsearch:9200
Elasticsearch index 'plans-index' initialized with parent-child mapping
Elasticsearch cluster reachable at http://elasticsearch:9200
Started SchemaGuardApplication
```

---

## Elasticsearch parent-child mapping

### what is a join field?

Elasticsearch does not support relational joins across documents by default.
The `join` field type is the official way to model parent-child relationships
within a single index. Parent and child documents must live on the **same shard**,
enforced by routing every child document with `?routing=<parentId>`.

### index mapping

The `plans-index` is created automatically on startup with this mapping:

```json
{
  "mappings": {
    "dynamic": true,
    "properties": {
      "objectId": {
        "type": "keyword"
      },
      "objectType": {
        "type": "keyword"
      },
      "my_join_field": {
        "type": "join",
        "relations": {
          "plan": "child"
        }
      }
    }
  }
}
```

| field | type | purpose |
|-------|------|---------|
| `objectId` | keyword | exact-match lookups by plan ID |
| `objectType` | keyword | filter by document type |
| `my_join_field` | join | defines `plan` → `child` relation |
| all other fields | dynamic | stored as-is from the JSON payload |

### routing rules

| document type | routing value | join field value |
|--------------|---------------|------------------|
| parent (plan) | `objectId` (default) | `"plan"` |
| child | `parentId` (explicit, required) | `{"name": "child", "parent": "<parentId>"}` |

Routing by parent ID guarantees co-location on the same shard, which is required
for `has_child`, `has_parent`, and `parent_id` queries.

---

## verifying the parent-child index

### check index was created

```bash
curl http://localhost:9200/plans-index
```

### insert a parent document

```bash
curl -X PUT "http://localhost:9200/plans-index/_doc/12xvxc345ssdsds-508" \
  -H "Content-Type: application/json" \
  -d '{
    "objectId": "12xvxc345ssdsds-508",
    "objectType": "plan",
    "planType": "inNetwork",
    "my_join_field": "plan"
  }'
```

### insert a child document (routing = parentId)

```bash
curl -X PUT "http://localhost:9200/plans-index/_doc/1234520xvc30asdf-502?routing=12xvxc345ssdsds-508" \
  -H "Content-Type: application/json" \
  -d '{
    "objectId": "1234520xvc30asdf-502",
    "objectType": "service",
    "name": "Yearly physical",
    "my_join_field": {
      "name": "child",
      "parent": "12xvxc345ssdsds-508"
    }
  }'
```

### query: find all parents that have at least one child

```bash
curl -X GET "http://localhost:9200/plans-index/_search" \
  -H "Content-Type: application/json" \
  -d '{
    "query": {
      "has_child": {
        "type": "child",
        "query": { "match_all": {} }
      }
    }
  }'
```

### query: find all children of a specific parent

```bash
curl -X GET "http://localhost:9200/plans-index/_search" \
  -H "Content-Type: application/json" \
  -d '{
    "query": {
      "parent_id": {
        "type": "child",
        "id": "12xvxc345ssdsds-508"
      }
    }
  }'
```

### query: find the parent of a specific child

```bash
curl -X GET "http://localhost:9200/plans-index/_search" \
  -H "Content-Type: application/json" \
  -d '{
    "query": {
      "has_parent": {
        "parent_type": "plan",
        "query": { "match_all": {} }
      }
    }
  }'
```

---

## verifying Elasticsearch cluster

```bash
# cluster health
curl "http://localhost:9200/_cluster/health?pretty"

# confirm app connected
docker logs schemaguard-app | grep -i elastic
```

---

## error contract

Every error response — including authentication failures from Spring Security — follows this exact JSON shape:

```json
{
  "timestamp": "2026-02-28T10:15:30Z",
  "status": 400,
  "error": "Bad Request",
  "message": "...",
  "path": "/api/v1/plan"
}
```

| field | type | description |
|-------|------|-------------|
| `timestamp` | ISO-8601 string | when the error occurred |
| `status` | integer | HTTP status code |
| `error` | string | HTTP reason phrase |
| `message` | string | human-readable description |
| `path` | string | request URI that caused the error |

No stack traces are ever included in the response body.

### example 1 — validation failure (400)

```bash
curl -X POST http://localhost:8080/api/v1/plan \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"objectId": "abc", "objectType": "plan"}'
```

```json
{
  "timestamp": "2026-02-28T15:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "schema validation failed — $.planType: is missing but it is required",
  "path": "/api/v1/plan"
}
```

### example 2 — unauthorized / no token (401)

```bash
curl http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508
```

```json
{
  "timestamp": "2026-02-28T15:30:05Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "authentication required: missing or invalid Bearer token",
  "path": "/api/v1/plan/12xvxc345ssdsds-508"
}
```

### example 3 — precondition failed (412)

```bash
curl -X DELETE http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H "Authorization: Bearer $TOKEN" \
  -H 'If-Match: "wrong-etag-value"'
```

```json
{
  "timestamp": "2026-02-28T15:30:10Z",
  "status": 412,
  "error": "Precondition Failed",
  "message": "ETag mismatch: resource has been modified",
  "path": "/api/v1/plan/12xvxc345ssdsds-508"
}
```

---

## Google IDP security

### how it works

SchemaGuard is configured as an **OAuth2 Resource Server**. Every request to `/api/v1/plan/**` must include a valid `Authorization: Bearer <token>` header. The token is:

1. A Google ID token (RS256-signed JWT)
2. validated against Google's public JWK Set: `https://www.googleapis.com/oauth2/v3/certs`
3. issuer checked: must be `https://accounts.google.com`
4. audience checked: must contain your Google Client ID

On success, the server logs `sub` and `email` from the token and adds `X-User-Sub` / `X-User-Email` response headers.

---

## step 1 — Google Cloud Console setup

### 1.1 create a Google Cloud project

1. go to [https://console.cloud.google.com](https://console.cloud.google.com)
2. click the project dropdown → **New Project**
3. name it `SchemaGuard-Demo` and click **Create**

### 1.2 configure OAuth consent screen

1. navigate to **APIs & Services → OAuth consent screen**
2. choose **External** → **Create**
3. fill in app name, support email, developer contact
4. under **Test users**, add your own Google email
5. click **Back to Dashboard**

### 1.3 create OAuth 2.0 client ID

1. go to **APIs & Services → Credentials**
2. click **Create Credentials → OAuth client ID**
3. application type: **Web application**
4. under **Authorized JavaScript origins**, add `http://localhost:5500` and `http://localhost:8080`
5. click **Create** and copy the Client ID

---

## step 2 — obtain a Google ID token

```bash
# Python static server
cd docs && python3 -m http.server 5500
```

Open `http://localhost:5500/get-token.html`, sign in, copy the token.

---

## step 3 — demo the API

```bash
TOKEN="<your Google ID token>"

# create a plan
curl -X POST http://localhost:8080/api/v1/plan \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d @samples/plan.json

# get a plan
curl http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H "Authorization: Bearer $TOKEN"

# delete a plan
curl -X DELETE http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H "Authorization: Bearer $TOKEN" \
  -H 'If-Match: "<etag-value>"'
```

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
├── docs/
│   └── get-token.html
├── src/main/java/com/schemaguard/
│   ├── config/
│   │   ├── AppConfig.java
│   │   ├── ElasticsearchConfig.java             ← ElasticsearchConfiguration base class
│   │   ├── RedisConfig.java
│   │   └── SecurityConfig.java
│   ├── elastic/
│   │   ├── ElasticsearchHealthCheck.java        ← startup HTTP ping
│   │   ├── PlanIndexConstants.java              ← index name, join field, type names
│   │   ├── PlanIndexInitializer.java            ← creates plans-index with join mapping on startup
│   │   └── PlanRoutingStrategy.java             ← join field shapes + child routing logic
│   ├── security/
│   │   ├── JwtClaimsLogger.java
│   │   └── SecurityErrorHandler.java
│   ├── controller/
│   ├── exception/
│   │   └── GlobalExceptionHandler.java
│   ├── model/
│   │   ├── ApiError.java
│   │   └── StoredDocument.java
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
