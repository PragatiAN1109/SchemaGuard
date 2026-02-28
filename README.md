# SchemaGuard

A Spring Boot REST API service for managing healthcare insurance plans with JSON Schema validation, ETag support for efficient caching, and Redis as the primary persistent store.

## Overview

SchemaGuard is a RESTful web service that provides full CRUD operations for healthcare insurance plan data. It enforces strict JSON Schema validation to ensure data integrity, supports conditional GET/PUT/PATCH/DELETE using ETags to prevent lost updates, and implements JSON Merge Patch (RFC 7396) for partial updates.

## Features

- JSON Schema Validation for all incoming plan data (POST, PUT, and post-patch on PATCH)
- Full CRUD: POST, GET, PUT (replace), PATCH (JSON Merge Patch), DELETE
- ETag support for conditional GET requests (SHA-256 based)
- ETag updated on every write (POST, PUT, PATCH)
- **Conditional writes: `If-Match` on PUT/PATCH/DELETE → 412 Precondition Failed on mismatch**
- **Conditional reads: `If-None-Match` on GET → 304 Not Modified when unchanged**
- **Standalone JSON Schema file — retrievable at `GET /api/v1/schema/plan`**
- **Redis as primary store — data persists across app restarts**
- **Docker Compose for one-command demo startup**
- Comprehensive error handling with detailed validation messages
- Spring Boot 4.0.2 framework

## Spring Profile → Storage Backend Mapping

| Profile | Storage Backend | When Used |
|---------|----------------|-----------|
| `redis` (default) | `RedisKeyValueStore` | All runtime and demo deployments |
| `test` | `InMemoryKeyValueStore` | `./mvnw test` only — no Redis required |

The default active profile is `redis`. No manual configuration is needed to run the demo.

---

## Demo Runbook — Docker Compose (Recommended)

### Prerequisites
- Docker + Docker Compose installed

### 1. Start everything with one command

```bash
docker compose up --build
```

This will:
- Build the SchemaGuard app image from the repo
- Start a Redis 7 container with a named volume (`redis-data`) for persistence
- Start the SchemaGuard app connected to Redis
- Expose the API on `http://localhost:8080`

Wait for both containers to be healthy (you'll see `Started SchemaGuardApplication` in the logs).

### 2. Create a plan

```bash
curl -X POST http://localhost:8080/api/v1/plan \
  -H "Content-Type: application/json" \
  -d @samples/plan.json
```

**Expected:** `201 Created` with an `ETag` header.

### 3. Verify the plan is stored in Redis

```bash
# Open a redis-cli session inside the Redis container
docker exec -it schemaguard-redis redis-cli

# List all plan keys
KEYS *

# Get the stored plan document
GET plan:12xvxc345ssdsds-508

# Exit redis-cli
exit
```

### 4. Prove data persists across app restarts

```bash
# Restart only the app container (Redis keeps running — data is still in the named volume)
docker compose restart app

# Wait ~10 seconds for the app to come back up, then GET the same plan
curl http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508
```

**Expected:** `200 OK` with the same plan data — proves Redis persistence across app restarts.

### 5. Prove data persists across full stack restarts

```bash
# Stop everything
docker compose down

# Start again (no --build needed since nothing changed)
docker compose up

# GET the plan — still there because the named volume redis-data was preserved
curl http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508
```

**Expected:** `200 OK` — data survived a full `down` + `up` cycle.

### 6. Stop and clean up

```bash
# Stop containers (keep data volume)
docker compose down

# Stop and delete all data (full reset)
docker compose down -v
```

---

## Running Locally Without Docker

### Prerequisites
- Java 17+, Maven 3.6+
- Redis running locally (`redis-server` or `brew services start redis`)

```bash
# Redis profile is active by default — connects to localhost:6379
./mvnw spring-boot:run
```

---

## Running Tests

```bash
./mvnw test
```

Tests use the `test` profile which activates `InMemoryKeyValueStore`. **No Redis required** to run the test suite.

---

## JSON Schema

### Schema File Location

```
src/main/resources/schemas/plan-schema.json
```

This file is the **single source of truth** for plan validation. It is loaded from the classpath at startup and used to validate every write operation.

| Operation | Schema Validation |
|-----------|------------------|
| `POST /api/v1/plan` | ✅ Full schema validation on request body |
| `PUT /api/v1/plan/{id}` | ✅ Full schema validation on replacement body |
| `PATCH /api/v1/plan/{id}` | ✅ Schema validation on the **fully merged** document |
| `GET /api/v1/plan/{id}` | ❌ Read-only, no validation |
| `DELETE /api/v1/plan/{id}` | ❌ No body, no validation |

### Retrieve the Schema

```bash
curl http://localhost:8080/api/v1/schema/plan
```

---

## API Endpoints

### POST /api/v1/plan — Create a Plan

```bash
curl -X POST http://localhost:8080/api/v1/plan \
  -H "Content-Type: application/json" \
  -d @samples/plan.json
```

**Success:** `201 Created` + `ETag` + `Location`
**Errors:** `400` schema invalid · `409` objectId already exists

**Validation failure example:**
```bash
curl -X POST http://localhost:8080/api/v1/plan \
  -H "Content-Type: application/json" \
  -d '{
    "_org": "example.com",
    "objectId": "bad-plan-001",
    "planType": "inNetwork",
    "creationDate": "01-01-2025",
    "planCostShares": {
      "deductible": 500, "_org": "example.com", "copay": 10,
      "objectId": "cs-001", "objectType": "membercostshare"
    },
    "linkedPlanServices": []
  }'
```

Expected `400`:
```json
{
  "error": "VALIDATION_ERROR",
  "message": "JSON Schema validation failed",
  "details": [
    "$.objectType: is missing but it is required",
    "$.linkedPlanServices: must have at least 1 items but found 0"
  ]
}
```

---

### GET /api/v1/plan/{objectId} — Retrieve a Plan

```bash
# Full fetch
curl http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508

# Conditional GET — 304 if unchanged
curl http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H 'If-None-Match: "4a5b6e65a673ea3d00e737af24d19a0922b9d2553656a64cc2ecba039573fd6b"'
```

---

### GET /api/v1/schema/plan — Retrieve the JSON Schema

```bash
curl http://localhost:8080/api/v1/schema/plan
```

---

### PUT /api/v1/plan/{objectId} — Replace a Plan

```bash
curl -X PUT http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H "Content-Type: application/json" \
  -H 'If-Match: "4a5b6e65a673ea3d00e737af24d19a0922b9d2553656a64cc2ecba039573fd6b"' \
  -d @samples/plan.json
```

**Errors:** `400` · `404` · `412`

---

### PATCH /api/v1/plan/{objectId} — Partial Update

```bash
curl -X PATCH http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H "Content-Type: application/merge-patch+json" \
  -H 'If-Match: "4a5b6e65a673ea3d00e737af24d19a0922b9d2553656a64cc2ecba039573fd6b"' \
  -d '{ "planType": "outOfNetwork" }'
```

**Errors:** `400` · `404` · `412`

---

### DELETE /api/v1/plan/{objectId} — Delete a Plan

```bash
curl -X DELETE http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H 'If-Match: "4a5b6e65a673ea3d00e737af24d19a0922b9d2553656a64cc2ecba039573fd6b"'
```

**Success:** `204 No Content`
**Errors:** `404` · `412`

---

## ETag Behaviour

| Operation | ETag Changes? |
|-----------|--------------|
| POST | ✅ New ETag generated |
| GET | ❌ Read only |
| PUT | ✅ New ETag (SHA-256 of new body) |
| PATCH | ✅ New ETag (SHA-256 of merged body) |
| DELETE | N/A |

---

## Error Response Format

```json
{
  "error": "ERROR_CODE",
  "message": "Human-readable description",
  "details": ["optional field-level errors"]
}
```

| Code | HTTP Status | Meaning |
|------|-------------|---------|
| `VALIDATION_ERROR` | 400 | JSON Schema validation failed |
| `INVALID_JSON` | 400 | Malformed JSON body |
| `NOT_FOUND` | 404 | Resource does not exist |
| `CONFLICT` | 409 | Duplicate objectId on POST |
| `PRECONDITION_FAILED` | 412 | If-Match header didn't match stored ETag |
| `INTERNAL_SERVER_ERROR` | 500 | Unexpected error |

---

## Architecture

```
SchemaGuard/
├── compose.yaml                              ← Docker Compose (app + Redis)
├── Dockerfile                                ← Multi-stage build
├── src/main/java/com/schemaguard/
│   ├── controller/      # PlanController, SchemaController
│   ├── exception/       # GlobalExceptionHandler, custom exceptions
│   ├── model/           # StoredDocument, ErrorResponse
│   ├── store/           # KeyValueStore, InMemoryKeyValueStore, RedisKeyValueStore
│   ├── util/            # EtagUtil (SHA-256), JsonUtil
│   └── validation/      # SchemaValidator, SchemaValidationException
├── src/main/resources/
│   ├── schemas/plan-schema.json              ← JSON Schema (single source of truth)
│   ├── application.properties               ← default profile = redis
│   └── application-redis.properties         ← Redis connection (host/port via env vars)
├── src/test/resources/
│   └── application-test.properties          ← test profile = InMemory, port=0
└── samples/
    ├── plan.json
    └── invalid.json
```
