# SchemaGuard

A Spring Boot REST API service for managing healthcare insurance plans with JSON Schema validation, ETag support for efficient caching, and Redis integration for persistent storage.

## Overview

SchemaGuard is a RESTful web service that provides full CRUD operations for healthcare insurance plan data. It enforces strict JSON Schema validation to ensure data integrity, supports conditional GET/PUT/PATCH/DELETE using ETags to prevent lost updates, and implements JSON Merge Patch (RFC 7396) for partial updates.

## Features

- JSON Schema Validation for all incoming plan data (POST, PUT, and post-patch on PATCH)
- Full CRUD: POST, GET, PUT (replace), PATCH (JSON Merge Patch), DELETE
- ETag support for conditional GET requests (SHA-256 based)
- ETag updated on every write (POST, PUT, PATCH)
- **Conditional writes: `If-Match` on PUT/PATCH/DELETE → 412 Precondition Failed on mismatch**
- **Conditional reads: `If-None-Match` on GET → 304 Not Modified when unchanged**
- Dual storage: In-memory (default) or Redis (persistent)
- Comprehensive error handling with detailed validation messages
- Maven-based build system
- Spring Boot 4.0.2 framework

## Technology Stack

- Java 17
- Spring Boot 4.0.2
- Spring Web MVC
- Spring Data Redis
- Jackson for JSON processing (including JsonNode merge patch)
- NetworkNT JSON Schema Validator (v1.0.87)
- Redis (optional, for persistent storage)
- Maven 3.x

## Prerequisites

- Java Development Kit (JDK) 17 or higher
- Maven 3.6 or higher (or use the included Maven wrapper)
- Redis Server (optional, for Redis storage mode)

## Getting Started

### Clone the Repository

```bash
git clone https://github.com/PragatiAN1109/SchemaGuard.git
cd SchemaGuard
```

### Build the Project

```bash
./mvnw clean install
```

### Run with In-Memory Storage (Default)

```bash
./mvnw spring-boot:run
```

### Run with Redis Storage

Start Redis first, then:
```bash
# In application.properties, set:
# spring.profiles.active=redis
./mvnw spring-boot:run
```

The application starts on port **8080**.

---

## API Endpoints

### POST /api/v1/plan — Create a Plan

**Headers:** `Content-Type: application/json`

**Success:** `201 Created` + `ETag` header + `Location` header

```bash
curl -X POST http://localhost:8080/api/v1/plan \
  -H "Content-Type: application/json" \
  -d @samples/plan.json
```

**Errors:** `400` schema invalid · `409` objectId already exists

---

### GET /api/v1/plan/{objectId} — Retrieve a Plan

Supports conditional GET via `If-None-Match`.

```bash
# Full fetch — returns 200 with body and ETag
curl http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508

# Conditional GET — returns 304 (no body) if ETag matches (resource unchanged)
curl http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H 'If-None-Match: "4a5b6e65a673ea3d00e737af24d19a0922b9d2553656a64cc2ecba039573fd6b"'

# If the ETag doesn't match (resource has changed) → 200 with updated body
curl http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H 'If-None-Match: "stale-etag-value"'
```

**Success:** `200 OK` + `ETag` header (or `304 Not Modified` if ETag matches)
**Errors:** `404` not found

---

### PUT /api/v1/plan/{objectId} — Replace a Plan

Replaces the **entire document**. The resource must already exist (no upsert).
Full schema validation runs on the replacement body. A new ETag is generated.

Supports optional `If-Match` for optimistic locking — prevents lost updates when multiple clients
are writing concurrently.

**Headers:** `Content-Type: application/json`

```bash
# PUT without If-Match — always replaces (no concurrency protection)
curl -X PUT http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H "Content-Type: application/json" \
  -d '{
    "planCostShares": {
      "deductible": 3000,
      "_org": "example.com",
      "copay": 50,
      "objectId": "1234vxc2324sdf-501",
      "objectType": "membercostshare"
    },
    "linkedPlanServices": [{
      "linkedService": {
        "_org": "example.com",
        "objectId": "1234520xvc30asdf-502",
        "objectType": "service",
        "name": "Annual checkup"
      },
      "planserviceCostShares": {
        "deductible": 20,
        "_org": "example.com",
        "copay": 10,
        "objectId": "1234512xvc1314asdfs-503",
        "objectType": "membercostshare"
      },
      "_org": "example.com",
      "objectId": "27283xvx9asdff-504",
      "objectType": "planservice"
    }],
    "_org": "example.com",
    "objectId": "12xvxc345ssdsds-508",
    "objectType": "plan",
    "planType": "outOfNetwork",
    "creationDate": "01-01-2025"
  }'

# PUT with If-Match — only replaces if ETag matches (safe concurrent update)
# Returns 200 on success, 412 if ETag is stale
curl -X PUT http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H "Content-Type: application/json" \
  -H 'If-Match: "4a5b6e65a673ea3d00e737af24d19a0922b9d2553656a64cc2ecba039573fd6b"' \
  -d '{ ... same body as above ... }'

# PUT with stale If-Match — returns 412 Precondition Failed
curl -X PUT http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H "Content-Type: application/json" \
  -H 'If-Match: "stale-etag-value"' \
  -d '{ ... body ... }'
```

**Success (`200 OK`):**
```json
{ "objectId": "12xvxc345ssdsds-508", "etag": "<new-sha256-etag>" }
```

**Errors:** `400` schema invalid · `404` not found · `412` ETag mismatch

---

### PATCH /api/v1/plan/{objectId} — Partial Update (JSON Merge Patch)

Applies a **JSON Merge Patch** (RFC 7396) to the existing document.

**⚠️ Required Content-Type: `application/merge-patch+json`**

Supports optional `If-Match` for optimistic locking.

**Merge Patch Rules (RFC 7396):**
- Field in patch body = value → field is set/overwritten
- Field in patch body = `null` → field is **removed** from document
- Fields not in patch body → left unchanged
- Nested objects are merged recursively

After patching, the merged document is re-validated against the full JSON Schema.

```bash
# PATCH without If-Match — always applies patch
curl -X PATCH http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H "Content-Type: application/merge-patch+json" \
  -d '{ "planType": "outOfNetwork", "planCostShares": { "copay": 50 } }'

# PATCH with If-Match — only applies if ETag matches (safe concurrent update)
# Returns 200 with merged doc on success, 412 if ETag is stale
curl -X PATCH http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H "Content-Type: application/merge-patch+json" \
  -H 'If-Match: "4a5b6e65a673ea3d00e737af24d19a0922b9d2553656a64cc2ecba039573fd6b"' \
  -d '{ "planType": "outOfNetwork" }'

# PATCH with stale If-Match — returns 412 Precondition Failed
curl -X PATCH http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H "Content-Type: application/merge-patch+json" \
  -H 'If-Match: "stale-etag-value"' \
  -d '{ "planType": "outOfNetwork" }'
```

**Success (`200 OK`):** Full merged document JSON + updated `ETag` header

**Errors:** `400` invalid patch or schema failure · `404` not found · `412` ETag mismatch

---

### DELETE /api/v1/plan/{objectId} — Delete a Plan

Supports optional `If-Match` for conditional delete.

```bash
# DELETE without If-Match — always deletes if found
curl -X DELETE http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508

# DELETE with If-Match — only deletes if ETag matches
# Returns 204 on success, 412 if ETag is stale
curl -X DELETE http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H 'If-Match: "4a5b6e65a673ea3d00e737af24d19a0922b9d2553656a64cc2ecba039573fd6b"'

# DELETE with stale If-Match — returns 412 Precondition Failed
curl -X DELETE http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H 'If-Match: "stale-etag-value"'
```

**Success:** `204 No Content`
**Errors:** `404` not found · `412` ETag mismatch

---

## ETag Behaviour

| Operation | ETag Changes? |
|-----------|--------------|
| POST      | ✅ New ETag generated |
| GET       | ❌ ETag read only (used for conditional GET) |
| PUT       | ✅ New ETag generated (SHA-256 of new body) |
| PATCH     | ✅ New ETag generated (SHA-256 of merged body) |
| DELETE    | N/A |

ETags are SHA-256 hashes of the stored JSON content. Spring automatically wraps them in quotes in the `ETag` response header (e.g. `ETag: "abc123"`). When sending `If-Match` or `If-None-Match`, you can include or omit the surrounding quotes — the server strips them before comparing.

---

## Conditional Request Summary

| Header | Method | Effect |
|--------|--------|--------|
| `If-None-Match` | GET | `304 Not Modified` if ETag matches (no body sent) |
| `If-Match` | PUT | `412 Precondition Failed` if ETag doesn't match |
| `If-Match` | PATCH | `412 Precondition Failed` if ETag doesn't match |
| `If-Match` | DELETE | `412 Precondition Failed` if ETag doesn't match |

---

## Error Response Format

All errors follow this structure:

```json
{
  "error": "ERROR_CODE",
  "message": "Human-readable description",
  "details": ["optional array of field-level errors"]
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

### Project Structure

```
SchemaGuard/
├── src/
│   ├── main/java/com/schemaguard/
│   │   ├── controller/      # PlanController (POST/GET/PUT/PATCH/DELETE)
│   │   ├── exception/       # NotFoundException, ConflictException,
│   │   │                    # PreconditionFailedException, GlobalExceptionHandler
│   │   ├── model/           # StoredDocument, ErrorResponse
│   │   ├── store/           # KeyValueStore, InMemoryKeyValueStore, RedisKeyValueStore
│   │   ├── util/            # EtagUtil (SHA-256), JsonUtil
│   │   └── validation/      # SchemaValidator, SchemaValidationException
│   └── main/resources/
│       └── schema/schema.json
├── src/test/java/com/schemaguard/
│   ├── controller/
│   │   └── PlanCrudIntegrationTest.java
│   └── validation/SchemaValidatorTest.java
└── samples/
    ├── plan.json
    └── invalid.json
```

### Key Design Decisions

**Conditional writes** use `If-Match` checked against the stored SHA-256 ETag before any mutation occurs. The header is optional — omitting it allows unconditional writes (backwards compatible).

**ETag quote handling** — stored ETags are raw unquoted SHA-256 strings. Spring's `.eTag()` builder adds quotes to the response header automatically. Incoming `If-Match`/`If-None-Match` values are stripped of surrounding quotes before comparison, so both `"abc123"` and `abc123` work correctly from the client side.

**Merge Patch** is implemented directly in `PlanController.applyMergePatch()` using Jackson `JsonNode` tree manipulation. No external library needed.

**Validation runs post-patch** — the merged `JsonNode` is serialized back to JSON and passed through `SchemaValidator.validatePlanJson()`, identical to POST and PUT.

---

## Running Tests

```bash
./mvnw test
```

Tests run against the in-memory store (test profile). No Redis required.
