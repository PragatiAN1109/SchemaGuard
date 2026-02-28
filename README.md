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
- **Standalone JSON Schema file — retrievable at `GET /api/v1/schema/plan`**
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
- NetworkNT JSON Schema Validator (v1.0.87) — JSON Schema draft 2020-12
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

## JSON Schema

### Schema File Location

The Plan JSON Schema is maintained as a standalone resource file:

```
src/main/resources/schemas/plan-schema.json
```

This file is the **single source of truth** for plan validation. It is loaded from the classpath at application startup and used to validate every write operation:

| Operation | Schema Validation |
|-----------|------------------|
| `POST /api/v1/plan` | ✅ Full schema validation on request body |
| `PUT /api/v1/plan/{id}` | ✅ Full schema validation on replacement body |
| `PATCH /api/v1/plan/{id}` | ✅ Schema validation on the **fully merged** document |
| `GET /api/v1/plan/{id}` | ❌ Read-only, no validation |
| `DELETE /api/v1/plan/{id}` | ❌ No body, no validation |

The schema enforces:
- Required top-level fields: `_org`, `objectId`, `objectType`, `planType`, `creationDate`, `planCostShares`, `linkedPlanServices`
- `objectType` must be `"plan"` (const)
- `planType` must be `"inNetwork"` or `"outOfNetwork"` (enum)
- `creationDate` format: `DD-MM-YYYY` (regex pattern)
- `planCostShares` — nested required fields with non-negative numeric constraints
- `linkedPlanServices` — array with at least one item, each with nested `linkedService` and `planserviceCostShares`

### Retrieve the Schema

```bash
curl http://localhost:8080/api/v1/schema/plan
```

Returns the full JSON Schema with `Content-Type: application/json`. Useful for demo visibility, client-side validation, and documentation.

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

**Validation failure example — missing required field (`objectType`):**
```bash
curl -X POST http://localhost:8080/api/v1/plan \
  -H "Content-Type: application/json" \
  -d '{
    "_org": "example.com",
    "objectId": "bad-plan-001",
    "planType": "inNetwork",
    "creationDate": "01-01-2025",
    "planCostShares": {
      "deductible": 500,
      "_org": "example.com",
      "copay": 10,
      "objectId": "cs-001",
      "objectType": "membercostshare"
    },
    "linkedPlanServices": []
  }'
```

**Expected 400 response:**
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

**Errors:** `400` schema invalid · `409` objectId already exists

---

### GET /api/v1/schema/plan — Retrieve the Plan JSON Schema

Returns the full JSON Schema used to validate all plan write operations.

```bash
curl http://localhost:8080/api/v1/schema/plan
```

**Success:** `200 OK` with full JSON Schema body (`Content-Type: application/json`)

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

Supports optional `If-Match` for optimistic locking.

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
curl -X PUT http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H "Content-Type: application/json" \
  -H 'If-Match: "4a5b6e65a673ea3d00e737af24d19a0922b9d2553656a64cc2ecba039573fd6b"' \
  -d '{ ... same body ... }'

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

Supports optional `If-Match` for optimistic locking. The merged document is fully re-validated against the schema before being saved.

**Merge Patch Rules (RFC 7396):**
- Field in patch body = value → field is set/overwritten
- Field in patch body = `null` → field is **removed** from document
- Fields not in patch body → left unchanged
- Nested objects are merged recursively

```bash
# PATCH without If-Match
curl -X PATCH http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H "Content-Type: application/merge-patch+json" \
  -d '{ "planType": "outOfNetwork", "planCostShares": { "copay": 50 } }'

# PATCH with If-Match
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
# DELETE without If-Match
curl -X DELETE http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508

# DELETE with If-Match
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
│   │   ├── controller/      # PlanController, SchemaController
│   │   ├── exception/       # NotFoundException, ConflictException,
│   │   │                    # PreconditionFailedException, GlobalExceptionHandler
│   │   ├── model/           # StoredDocument, ErrorResponse
│   │   ├── store/           # KeyValueStore, InMemoryKeyValueStore, RedisKeyValueStore
│   │   ├── util/            # EtagUtil (SHA-256), JsonUtil
│   │   └── validation/      # SchemaValidator, SchemaValidationException
│   └── main/resources/
│       ├── schemas/
│       │   └── plan-schema.json   ← standalone JSON Schema (single source of truth)
│       └── application.properties
├── src/test/java/com/schemaguard/
│   ├── controller/PlanCrudIntegrationTest.java
│   └── validation/SchemaValidatorTest.java
└── samples/
    ├── plan.json
    └── invalid.json
```

### Key Design Decisions

**Single schema file** — `schemas/plan-schema.json` is the only place the plan structure is defined. It is loaded once at startup via `SchemaValidator` and shared across all validation calls. The same instance is also served verbatim by `GET /api/v1/schema/plan`, guaranteeing the schema visible to clients is always in sync with what the server enforces.

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
