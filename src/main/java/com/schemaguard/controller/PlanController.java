package com.schemaguard.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaguard.exception.ConflictException;
import com.schemaguard.exception.NotFoundException;
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

    // application/merge-patch+json media type constant
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
    // GET /api/v1/plan/{objectId}  (Conditional GET)
    // ---------------------------
    @GetMapping(value = "/{objectId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getPlan(
            @PathVariable String objectId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        Optional<StoredDocument> docOpt = store.get(objectId);
        if (docOpt.isEmpty()) {
            throw new NotFoundException("Plan not found: " + objectId);
        }

        StoredDocument doc = docOpt.get();

        if (ifNoneMatch != null && ifNoneMatch.equals(doc.getEtag())) {
            return ResponseEntity.status(304)
                    .eTag(doc.getEtag())
                    .build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .eTag(doc.getEtag())
                .body(doc.getJson());
    }

    // ---------------------------
    // PUT /api/v1/plan/{objectId}
    // Replaces the entire document.
    // ---------------------------
    @PutMapping(
            value = "/{objectId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<Map<String, Object>> replacePlan(
            @PathVariable String objectId,
            @RequestBody String rawJson
    ) {
        // 1) Resource must already exist
        if (!store.exists(objectId)) {
            throw new NotFoundException("Plan not found: " + objectId);
        }

        // 2) Full schema validation on the replacement body
        schemaValidator.validatePlanJson(rawJson);

        // 3) Replace - generates new ETag internally
        store.update(objectId, rawJson);

        // 4) Fetch updated doc to return new ETag
        StoredDocument doc = store.get(objectId).orElseThrow();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("objectId", objectId);
        response.put("etag", doc.getEtag());

        return ResponseEntity.ok()
                .eTag(doc.getEtag())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }

    // ---------------------------
    // PATCH /api/v1/plan/{objectId}
    // JSON Merge Patch (RFC 7396)
    // ---------------------------
    @PatchMapping(
            value = "/{objectId}",
            consumes = MERGE_PATCH_CONTENT_TYPE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<String> mergePatchPlan(
            @PathVariable String objectId,
            @RequestBody String patchJson
    ) {
        // 1) Resource must exist
        StoredDocument existing = store.get(objectId)
                .orElseThrow(() -> new NotFoundException("Plan not found: " + objectId));

        // 2) Apply JSON Merge Patch (RFC 7396)
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

        // 3) Re-validate the fully merged document against JSON Schema
        schemaValidator.validatePlanJson(mergedJson);

        // 4) Persist merged document (new ETag generated inside update)
        store.update(objectId, mergedJson);

        // 5) Return updated document with new ETag
        StoredDocument updated = store.get(objectId).orElseThrow();

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .eTag(updated.getEtag())
                .body(updated.getJson());
    }

    // ---------------------------
    // DELETE /api/v1/plan/{objectId}
    // ---------------------------
    @DeleteMapping("/{objectId}")
    public ResponseEntity<Void> deletePlan(@PathVariable String objectId) {
        boolean deleted = store.delete(objectId);
        if (!deleted) {
            throw new NotFoundException("Plan not found: " + objectId);
        }
        return ResponseEntity.noContent().build(); // 204
    }

    // ---------------------------
    // RFC 7396 - JSON Merge Patch implementation
    // Rules:
    //   - patch field = null  → remove that field from target
    //   - patch field = value → set/overwrite that field in target
    //   - both are objects    → recurse
    // ---------------------------
    JsonNode applyMergePatch(JsonNode target, JsonNode patch) {
        if (!patch.isObject()) {
            // If patch is not an object, the entire target is replaced
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
                // Remove field per RFC 7396
                result.remove(fieldName);
            } else if (patchValue.isObject() && result.has(fieldName) && result.get(fieldName).isObject()) {
                // Recurse into nested objects
                result.set(fieldName, applyMergePatch(result.get(fieldName), patchValue));
            } else {
                // Set / overwrite
                result.set(fieldName, patchValue);
            }
        });

        return result;
    }

    // ---------------------------
    // Controller-level schema validation error handler
    // (complements GlobalExceptionHandler)
    // ---------------------------
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
