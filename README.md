# SchemaGuard

A Spring Boot REST API service for managing healthcare insurance plans with JSON Schema validation, ETag caching, Redis persistent storage, Elasticsearch integration, and Google OAuth2 JWT security.

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
- Elasticsearch — infrastructure wired, indexing coming in next task
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
- Google Client ID (see Google IDP Security section below)

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

Look for this in the app logs to confirm everything is up:
```
Elasticsearch cluster reachable at elasticsearch:9200 — status: green
Started SchemaGuardApplication
```

---

## verifying Elasticsearch

### 1. ping the cluster root endpoint

```bash
curl http://localhost:9200
```

Expected response:
```json
{
  "name" : "schemaguard-elasticsearch",
  "cluster_name" : "docker-cluster",
  "cluster_uuid" : "<uuid>",
  "version" : {
    "number" : "8.13.4",
    "build_flavor" : "default",
    "build_type" : "docker",
    "lucene_version" : "9.10.0",
    "minimum_wire_compatibility_version" : "7.17.0",
    "minimum_index_compatibility_version" : "7.0.0"
  },
  "tagline" : "You Know, for Search"
}
```

### 2. check cluster health

```bash
curl http://localhost:9200/_cluster/health?pretty
```

Expected response:
```json
{
  "cluster_name" : "docker-cluster",
  "status" : "green",
  "number_of_nodes" : 1,
  "number_of_data_nodes" : 1,
  "active_shards" : 0
}
```

### 3. confirm app connectivity in logs

```bash
docker logs schemaguard-app | grep -i elastic
```

Expected output:
```
Elasticsearch cluster reachable at elasticsearch:9200 — status: green
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
  "message": "schema validation failed — $.planType: is missing but it is required; $.creationDate: is missing but it is required",
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
3. fill in:
   - app name: `SchemaGuard`
   - user support email: your email
   - developer contact email: your email
4. click **Save and Continue** through the remaining steps
5. under **Test users**, add your own Google email address
6. click **Back to Dashboard**

### 1.3 create OAuth 2.0 client ID

1. go to **APIs & Services → Credentials**
2. click **Create Credentials → OAuth client ID**
3. application type: **Web application**
4. name: `SchemaGuard Web Client`
5. under **Authorized JavaScript origins**, add:
   ```
   http://localhost:5500
   http://localhost:8080
   ```
6. click **Create**
7. **copy the Client ID** — it looks like: `123456789-abc123.apps.googleusercontent.com`

---

## step 2 — obtain a Google ID token (demo)

### option A — HTML token page (recommended)

a ready-made token page is included at `docs/get-token.html`.

1. open `docs/get-token.html` in a text editor
2. replace `YOUR_GOOGLE_CLIENT_ID` with your actual client ID
3. serve it locally:

```bash
# Python
cd docs && python3 -m http.server 5500

# Node
cd docs && npx serve -p 5500
```

4. open `http://localhost:5500/get-token.html` in a browser
5. click **Sign in with Google**
6. copy the token from the text box

### option B — OAuth Playground

1. go to [https://developers.google.com/oauthplayground](https://developers.google.com/oauthplayground)
2. click settings gear → check **Use your own OAuth credentials**
3. select `openid`, `email` → authorize → exchange for tokens
4. copy the `id_token` value

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

```
src/main/resources/schemas/plan-schema.json
```

```bash
# retrieve at runtime (public endpoint — no auth needed)
curl http://localhost:8080/api/v1/schema/plan
```

---

## architecture

```
SchemaGuard/
├── compose.yaml                                     ← Redis + Elasticsearch + app
├── Dockerfile
├── docs/
│   └── get-token.html
├── src/main/java/com/schemaguard/
│   ├── config/
│   │   ├── AppConfig.java
│   │   ├── ElasticsearchConfig.java         ← RestClient + ElasticsearchClient beans
│   │   ├── RedisConfig.java
│   │   └── SecurityConfig.java
│   ├── elastic/
│   │   └── ElasticsearchHealthCheck.java    ← startup cluster ping via ApplicationReadyEvent
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
│   └── application-redis.properties       ← elastic.host / elastic.port added
└── src/test/resources/
    └── application-test.properties
```
