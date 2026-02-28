# SchemaGuard

A Spring Boot REST API service for managing healthcare insurance plans with JSON Schema validation, ETag support for efficient caching, and Redis integration for persistent storage.

## Overview

SchemaGuard is a RESTful web service that provides full CRUD operations for healthcare insurance plan data. It enforces strict JSON Schema validation to ensure data integrity, supports conditional GET requests using ETags for optimized caching, and implements JSON Merge Patch (RFC 7396) for partial updates.

## Features

- JSON Schema Validation for all incoming plan data (POST, PUT, and post-patch on PATCH)
- Full CRUD: POST, GET, PUT (replace), PATCH (JSON Merge Patch), DELETE
- ETag support for conditional GET requests (SHA-256 based)
- ETag updated on every write (POST, PUT, PATCH)
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
# Full fetch
curl -X GET http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508

# Conditional (returns 304 if unchanged)
curl -X GET http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H 'If-None-Match: "abc123..."'
```

**Success:** `200 OK` + `ETag` header (or `304 Not Modified`)  
**Errors:** `404` not found

---

### PUT /api/v1/plan/{objectId} — Replace a Plan

Replaces the **entire document**. The resource must already exist (no upsert).  
Full schema validation runs on the replacement body.  
A new ETag is generated.

**Headers:** `Content-Type: application/json`

**Request body:** Complete, schema-valid plan JSON (same structure as POST)

```bash
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
```

**Success response (`200 OK`):**
```json
{
  "objectId": "12xvxc345ssdsds-508",
  "etag": "<new-sha256-etag>"
}
```

**Errors:** `400` schema invalid · `404` not found

---

### PATCH /api/v1/plan/{objectId} — Partial Update (JSON Merge Patch)

Applies a **JSON Merge Patch** (RFC 7396) to the existing document.

**⚠️ Required Content-Type: `application/merge-patch+json`**

**Merge Patch Rules (RFC 7396):**
- Field in patch body = value → field is set/overwritten
- Field in patch body = `null` → field is **removed** from document
- Fields not in patch body → left unchanged
- Nested objects are merged recursively

After patching, the merged document is re-validated against the full JSON Schema.  
A required field set to `null` will be removed, causing a `400` schema validation error.

```bash
curl -X PATCH http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508 \
  -H "Content-Type: application/merge-patch+json" \
  -d '{
    "planType": "outOfNetwork",
    "planCostShares": {
      "copay": 50
    }
  }'
```

**Example patch body — update planType and one nested field:**
```json
{
  "planType": "outOfNetwork",
  "planCostShares": {
    "copay": 50
  }
}
```

**Example patch body — remove an optional field:**
```json
{
  "someOptionalField": null
}
```

**Success response (`200 OK`):** Full merged document JSON + `ETag` header

**Errors:** `400` invalid patch JSON or schema validation failure after patch · `404` not found

---

### DELETE /api/v1/plan/{objectId} — Delete a Plan

```bash
curl -X DELETE http://localhost:8080/api/v1/plan/12xvxc345ssdsds-508
```

**Success:** `204 No Content`  
**Errors:** `404` not found

---

## ETag Behaviour

| Operation | ETag Changes? |
|-----------|--------------|
| POST      | ✅ New ETag generated |
| GET       | ❌ ETag read only (used for conditional GET) |
| PUT       | ✅ New ETag generated (SHA-256 of new body) |
| PATCH     | ✅ New ETag generated (SHA-256 of merged body) |
| DELETE    | N/A |

ETags are SHA-256 hashes of the stored JSON content. Spring automatically wraps them in quotes in the `ETag` response header.

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
| `INTERNAL_SERVER_ERROR` | 500 | Unexpected error |

---

## Postman Collection

A complete Postman collection is available at `docs/postman/schema-guard-crud.json`.

Import it into Postman to get pre-built requests for all five operations (POST, GET, PUT, PATCH, DELETE) with automated test scripts.

---

## Architecture

### Project Structure

```
SchemaGuard/
├── src/
│   ├── main/java/com/schemaguard/
│   │   ├── controller/      # PlanController (POST/GET/PUT/PATCH/DELETE)
│   │   ├── exception/       # NotFoundException, ConflictException, GlobalExceptionHandler
│   │   ├── model/           # StoredDocument, ErrorResponse
│   │   ├── store/           # KeyValueStore, InMemoryKeyValueStore, RedisKeyValueStore
│   │   ├── util/            # EtagUtil (SHA-256), JsonUtil
│   │   └── validation/      # SchemaValidator, SchemaValidationException
│   └── main/resources/
│       └── schema/schema.json
├── src/test/java/com/schemaguard/
│   ├── controller/
│   │   ├── MergePatchUnitTest.java        # Unit tests for RFC 7396 merge logic
│   │   └── PlanCrudIntegrationTest.java   # Integration tests for PUT/PATCH
│   └── validation/SchemaValidatorTest.java
├── docs/
│   └── postman/schema-guard-crud.json    # Postman collection
└── samples/
    ├── plan.json
    └── invalid.json
```

### Key Design Decisions

**Merge Patch** is implemented directly in `PlanController.applyMergePatch()` using Jackson `JsonNode` tree manipulation. No external library needed — the logic is a clean recursive function matching RFC 7396 exactly.

**Validation runs post-patch** — the merged `JsonNode` is serialized back to JSON string and passed through the same `SchemaValidator.validatePlanJson()` used by POST and PUT. This ensures partial patches cannot bypass required-field constraints.

**ETag updates** are handled inside `KeyValueStore.update()`. Both `InMemoryKeyValueStore` and `RedisKeyValueStore` call `EtagUtil.sha256Etag(newJson)` and store a fresh `StoredDocument`, so the ETag is always content-derived and consistent across both backends.

---

## Running Tests

```bash
./mvnw test
```

Tests run against the in-memory store (test profile). No Redis required.
