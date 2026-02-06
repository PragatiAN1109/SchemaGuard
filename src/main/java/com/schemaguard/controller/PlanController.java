package com.schemaguard.controller;

import com.schemaguard.model.StoredDocument;
import com.schemaguard.store.KeyValueStore;
import com.schemaguard.util.JsonUtil;
import com.schemaguard.validation.SchemaValidationException;
import com.schemaguard.validation.SchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {

    private final KeyValueStore store;
    private final SchemaValidator schemaValidator;
    private final ObjectMapper objectMapper;

    public PlanController(KeyValueStore store, SchemaValidator schemaValidator, ObjectMapper objectMapper) {
        this.store = store;
        this.schemaValidator = schemaValidator;
        this.objectMapper = objectMapper;
    }

    // ---------------------------
    // POST /api/v1/plans
    // ---------------------------
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> createPlan(@RequestBody String rawJson) {

        // 1) Validate against JSON Schema
        schemaValidator.validatePlanJson(rawJson);

        // 2) Extract objectId
        String objectId = JsonUtil.extractTopLevelObjectId(objectMapper, rawJson);
        if (objectId == null) {
            // Should not happen if schema enforces objectId, but keep it defensive
            return ResponseEntity.badRequest().body(errorBody("VALIDATION_ERROR", "Missing or invalid objectId"));
        }

        // 3) Conflict if exists
        if (store.exists(objectId)) {
            return ResponseEntity.status(409).body(errorBody("CONFLICT", "Plan with objectId already exists: " + objectId));
        }

        // 4) Store (store computes etag + lastModified internally)
        boolean created = store.create(objectId, rawJson);
        if (!created) {
            return ResponseEntity.status(409).body(errorBody("CONFLICT", "Plan with objectId already exists: " + objectId));
        }

        // 5) Fetch stored doc to return etag
        StoredDocument doc = store.get(objectId).orElseThrow();

        // 6) Build response
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("objectId", objectId);
        response.put("etag", doc.getEtag());

        return ResponseEntity.status(201)
                .header(HttpHeaders.LOCATION, "/api/v1/plans/" + objectId)
                .eTag(doc.getEtag())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }

    // ---------------------------
    // GET /api/v1/plans/{objectId}  (Conditional GET)
    // ---------------------------
    @GetMapping(value = "/{objectId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getPlan(
            @PathVariable String objectId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        Optional<StoredDocument> docOpt = store.get(objectId);

        if (docOpt.isEmpty()) {
            return ResponseEntity.status(404)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"NOT_FOUND\",\"message\":\"Plan not found\"}");
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
    // DELETE /api/v1/plans/{objectId}
    // ---------------------------
    @DeleteMapping("/{objectId}")
    public ResponseEntity<Void> deletePlan(@PathVariable String objectId) {
        boolean deleted = store.delete(objectId);
        if (!deleted) {
            return ResponseEntity.status(404).build();
        }
        return ResponseEntity.noContent().build(); // 204
    }

    // ---------------------------
    // Error handling (controller-level)
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