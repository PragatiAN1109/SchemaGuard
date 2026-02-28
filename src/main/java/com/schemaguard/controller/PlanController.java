package com.schemaguard.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaguard.exception.ConflictException;
import com.schemaguard.exception.NotFoundException;
import com.schemaguard.exception.PreconditionFailedException;
import com.schemaguard.model.StoredDocument;
import com.schemaguard.store.KeyValueStore;
import com.schemaguard.util.JsonUtil;
import com.schemaguard.validation.SchemaValidationException;
import com.schemaguard.validation.SchemaValidator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/plan")
public class PlanController {

    static final String MERGE_PATCH_CONTENT_TYPE = "application/merge-patch+json";

    private final KeyValueStore store;
    private final SchemaValidator schemaValidator;
    private final ObjectMapper objectMapper;

    public PlanController(KeyValueStore store, SchemaValidator schemaValidator, ObjectMapper objectMapper) {
        this.store = store;
        this.schemaValidator = schemaValidator;
        this.objectMapper = objectMapper;
    }

    // ---------------------------
    // POST /api/v1/plan
    // ---------------------------
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> createPlan(@RequestBody String rawJson) {
        schemaValidator.validatePlanJson(rawJson);

        String objectId = JsonUtil.extractTopLevelObjectId(objectMapper, rawJson);
        if (objectId == null) {
            return ResponseEntity.badRequest().body(errorBody("VALIDATION_ERROR", "Missing or invalid objectId"));
        }

        if (store.exists(objectId)) {
            throw new ConflictException("Plan with objectId already exists: " + objectId);
        }

        boolean created = store.create(objectId, rawJson);
        if (!created) {
            return ResponseEntity.status(409).body(errorBody("CONFLICT", "Plan with objectId already exists: " + objectId));
        }

        StoredDocument doc = store.get(objectId).orElseThrow();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("objectId", objectId);
        response.put("etag", doc.getEtag());

        return ResponseEntity.status(201)
                .header(HttpHeaders.LOCATION, "/api/v1/plan/" + objectId)
                .eTag(doc.getEtag())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }

    // ---------------------------
    // GET /api/v1/plan/{objectId}
    // Conditional GET via If-None-Match.
    // Strips surrounding quotes from If-None-Match before comparing
    // so both quoted ("abc") and unquoted (abc) values work.
    // ---------------------------
    @GetMapping(value = "/{objectId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getPlan(
            @PathVariable String objectId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        StoredDocument doc = store.get(objectId)
                .orElseThrow(() -> new NotFoundException("Plan not found: " + objectId));

        String storedEtag = doc.getEtag();

        if (ifNoneMatch != null && stripQuotes(ifNoneMatch).equals(storedEtag)) {
            return ResponseEntity.status(304)
                    .eTag(storedEtag)
                    .build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .eTag(storedEtag)
                .body(doc.getJson());
    }

    // ---------------------------
    // PUT /api/v1/plan/{objectId}
    // Full replace. Supports optional If-Match for optimistic locking.
    // If-Match present + mismatch → 412 Precondition Failed.
    // ---------------------------
    @PutMapping(
            value = "/{objectId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<Map<String, Object>> replacePlan(
            @PathVariable String objectId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody String rawJson
    ) {
        StoredDocument existing = store.get(objectId)
                .orElseThrow(() -> new NotFoundException("Plan not found: " + objectId));

        // If-Match check — reject if ETag doesn't match
        if (ifMatch != null && !stripQuotes(ifMatch).equals(existing.getEtag())) {
            throw new PreconditionFailedException(
                    "ETag mismatch: document has been modified since you last fetched it");
        }

        schemaValidator.validatePlanJson(rawJson);
        store.update(objectId, rawJson);

        StoredDocument updated = store.get(objectId).orElseThrow();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("objectId", objectId);
        response.put("etag", updated.getEtag());

        return ResponseEntity.ok()
                .eTag(updated.getEtag())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }

    // ---------------------------
    // PATCH /api/v1/plan/{objectId}
    // JSON Merge Patch (RFC 7396). Supports optional If-Match.
    // ---------------------------
    @PatchMapping(
            value = "/{objectId}",
            consumes = MERGE_PATCH_CONTENT_TYPE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<String> mergePatchPlan(
            @PathVariable String objectId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody String patchJson
    ) {
        StoredDocument existing = store.get(objectId)
                .orElseThrow(() -> new NotFoundException("Plan not found: " + objectId));

        // If-Match check
        if (ifMatch != null && !stripQuotes(ifMatch).equals(existing.getEtag())) {
            throw new PreconditionFailedException(
                    "ETag mismatch: document has been modified since you last fetched it");
        }

        // Apply JSON Merge Patch (RFC 7396)
        String mergedJson;
        try {
            JsonNode target = objectMapper.readTree(existing.getJson());
            JsonNode patch  = objectMapper.readTree(patchJson);
            JsonNode merged = applyMergePatch(target, patch);
            mergedJson = objectMapper.writeValueAsString(merged);
        } catch (Exception e) {
            throw new SchemaValidationException(
                    "Invalid patch payload (parse error)",
                    List.of(e.getMessage())
            );
        }

        // Re-validate merged document
        schemaValidator.validatePlanJson(mergedJson);
        store.update(objectId, mergedJson);

        StoredDocument updated = store.get(objectId).orElseThrow();

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .eTag(updated.getEtag())
                .body(updated.getJson());
    }

    // ---------------------------
    // DELETE /api/v1/plan/{objectId}
    // Supports optional If-Match for conditional delete.
    // ---------------------------
    @DeleteMapping("/{objectId}")
    public ResponseEntity<Void> deletePlan(
            @PathVariable String objectId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch
    ) {
        StoredDocument existing = store.get(objectId)
                .orElseThrow(() -> new NotFoundException("Plan not found: " + objectId));

        // If-Match check
        if (ifMatch != null && !stripQuotes(ifMatch).equals(existing.getEtag())) {
            throw new PreconditionFailedException(
                    "ETag mismatch: document has been modified since you last fetched it");
        }

        store.delete(objectId);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------
    // RFC 7396 — JSON Merge Patch
    // ---------------------------
    JsonNode applyMergePatch(JsonNode target, JsonNode patch) {
        if (!patch.isObject()) {
            return patch;
        }

        com.fasterxml.jackson.databind.node.ObjectNode result =
                target.isObject()
                        ? (com.fasterxml.jackson.databind.node.ObjectNode) target.deepCopy()
                        : objectMapper.createObjectNode();

        patch.fields().forEachRemaining(entry -> {
            String fieldName = entry.getKey();
            JsonNode patchValue = entry.getValue();

            if (patchValue.isNull()) {
                result.remove(fieldName);
            } else if (patchValue.isObject() && result.has(fieldName) && result.get(fieldName).isObject()) {
                result.set(fieldName, applyMergePatch(result.get(fieldName), patchValue));
            } else {
                result.set(fieldName, patchValue);
            }
        });

        return result;
    }

    // ---------------------------
    // Helpers
    // ---------------------------

    /**
     * Strip surrounding double-quotes from an ETag value.
     * HTTP clients may send If-Match as "abc123" or abc123 — both are valid.
     * Our stored ETags are always unquoted SHA-256 strings.
     * Spring's .eTag() call adds the quotes to the response header automatically.
     */
    private String stripQuotes(String value) {
        if (value != null && value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    @ExceptionHandler(SchemaValidationException.class)
    public ResponseEntity<Map<String, Object>> handleSchemaError(SchemaValidationException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "VALIDATION_ERROR");
        body.put("message", e.getMessage());
        body.put("details", e.getErrors());
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private Map<String, Object> errorBody(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return body;
    }
}
