# API Reference

Base URL: `http://localhost:8080`

## Authentication

Google OAuth2 RS256 Bearer tokens are validated against `https://www.googleapis.com/oauth2/v3/certs` (issuer `https://accounts.google.com`, audience = `GOOGLE_CLIENT_ID`).

| Path | Access |
|------|--------|
| `/api/v1/schema/**` | Public |
| `/api/v1/index/**` | Public (read-only health check, no plan data exposed) |
| `/api/v1/plan/**` | Requires a valid Bearer token |
| `/api/v1/search/**` | Requires a valid Bearer token |
| everything else | Requires a valid Bearer token (default-deny) |

Missing/invalid tokens return `401`; valid-but-unauthorized requests return `403` — both in the standard error contract below. See [Getting a token](local-development.md#getting-a-google-id-token).

## Error contract

Every error response (validation, not-found, conflict, precondition, auth, or unexpected) has this shape:

```json
{
  "timestamp": "2026-02-28T10:15:30Z",
  "status": 400,
  "error": "Bad Request",
  "message": "...",
  "path": "/api/v1/plan"
}
```

| Status | Cause |
|--------|-------|
| 400 | JSON Schema validation failure, malformed JSON body |
| 401 | Missing or invalid Bearer token |
| 403 | Authenticated but not authorized |
| 404 | Plan not found |
| 409 | `objectId` already exists (POST) |
| 412 | `If-Match` does not match the current ETag |
| 500 | Unhandled server error, or (on search endpoints) Elasticsearch unavailable |

## Plan endpoints (`/api/v1/plan`)

### `POST /api/v1/plan`
Creates a plan. Body is validated against the JSON Schema. Returns `201` with `ETag` and `Location` headers on success, `409` if `objectId` already exists. Publishes an `UPSERT` event on success only.

### `GET /api/v1/plan`
Lists all stored plans (`objectId`, `etag`, `lastModified`) from Redis. Demo/debug endpoint — not paginated.

### `GET /api/v1/plan/{objectId}`
Returns the stored plan JSON with an `ETag` header. Supports `If-None-Match` → `304 Not Modified` (no body) when the ETag matches. `404` if not found.

### `PUT /api/v1/plan/{objectId}`
Full replacement. Optional `If-Match` header — `412` on mismatch. Re-validates against the JSON Schema. Publishes an `UPSERT` event with the new ETag on `200`.

### `PATCH /api/v1/plan/{objectId}`
Content-Type: `application/merge-patch+json`. Applies [RFC 7396 JSON Merge Patch](https://www.rfc-editor.org/rfc/rfc7396): object fields are merged recursively, a `null` value removes the field, and a non-object patch body replaces the target outright. Optional `If-Match` — `412` on mismatch. The merged document is re-validated against the JSON Schema before being written. Publishes a `PATCH` event with the new ETag on `200`.

### `DELETE /api/v1/plan/{objectId}`
Optional `If-Match` — `412` on mismatch. Deletes from Redis, then publishes a `DELETE` event carrying the ETag captured immediately before deletion. Returns `204`.

## Schema endpoint (`/api/v1/schema`)

### `GET /api/v1/schema/plan`
Returns the exact JSON Schema (draft 2020-12) used to validate plan payloads, loaded once from the classpath at startup. Public, no auth required.

## Index admin endpoint (`/api/v1/index`)

### `GET /api/v1/index/health`
Pings the Elasticsearch cluster root and checks whether `plans-index` exists. Returns `{"cluster": "...", "index": "...", "indexName": "plans-index"}`. Does not expose plan data or modify state. Public.

## Search endpoints (`/api/v1/search`)

All search endpoints query Elasticsearch, which lags Redis by however long it takes `RabbitMQIndexListener` to process the corresponding event (typically well under a second, but not bounded).

### `GET /api/v1/search/all`
`match_all` query returning every indexed document (parents and children) with a total count. Useful for confirming index state directly (e.g., count goes up after `POST`, stays flat after `PATCH`, drops to 0 after `DELETE`).

```bash
curl -s "http://localhost:8080/api/v1/search/all" -H "Authorization: Bearer $TOKEN"
```

### `GET /api/v1/search`
`has_child` query — finds parent plans with at least one matching child.

| Param | Required | Description |
|-------|----------|--------------|
| `q` | no | Free-text match against the parent document |
| `childField` | no* | Field name on a child document to filter by |
| `childValue` | no* | Value to match/compare against `childField` |
| `childOp` | no | One of `gt`, `gte`, `lt`, `lte` — switches from a term/multi-match query to a range query (e.g. `childField=copay&childOp=gt&childValue=100`) |

\* `childField` and `childValue` must be provided together, or both omitted — providing only one returns `400`.

```bash
curl -s "http://localhost:8080/api/v1/search?childField=objectType&childValue=planservice" \
  -H "Authorization: Bearer $TOKEN"

curl -s "http://localhost:8080/api/v1/search?childField=copay&childOp=gt&childValue=100" \
  -H "Authorization: Bearer $TOKEN"
```

### `GET /api/v1/search/parent/{parentId}/children`
`has_parent` query — all child documents for a given parent. Returns `count: 0` with an empty array if the parent has no children (not a `404`).

```bash
curl -s "http://localhost:8080/api/v1/search/parent/12xvxc345ssdsds-508/children" \
  -H "Authorization: Bearer $TOKEN"
```

## Elasticsearch mapping

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

Index: `plans-index`. Every other field is picked up by Elasticsearch's dynamic mapping. See [architecture.md](architecture.md) for the parent/child routing rules.

## Postman collection

A ready-to-import collection covering all endpoints in sequence is at [`docs/postman/schema-guard-crud.json`](postman/schema-guard-crud.json).
