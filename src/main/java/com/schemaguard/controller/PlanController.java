package com.schemaguard.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaguard.exception.ConflictException;
import com.schemaguard.exception.NotFoundException;
import com.schemaguard.exception.PreconditionFailedException;
import com.schemaguard.model.StoredDocument;
import com.schemaguard.queue.IndexEvent;
import com.schemaguard.queue.IndexEventOperation;
import com.schemaguard.queue.IndexEventPublisher;
import com.schemaguard.store.KeyValueStore;
import com.schemaguard.util.JsonUtil;
import com.schemaguard.validation.SchemaValidationException;
import com.schemaguard.validation.SchemaValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/plan")
public class PlanController {

    private static final Logger log = LoggerFactory.getLogger(PlanController.class);

    static final String MERGE_PATCH_CONTENT_TYPE = "application/merge-patch+json";

    private final KeyValueStore store;
    private final SchemaValidator schemaValidator;
    private final ObjectMapper objectMapper;
    private final IndexEventPublisher eventPublisher;

    public PlanController(KeyValueStore store,
                          SchemaValidator schemaValidator,
                          ObjectMapper objectMapper,
                          IndexEventPublisher eventPublisher) {
        this.store = store;
        this.schemaValidator = schemaValidator;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    // ---------------------------
    // GET /api/v1/plan  (list all stored plans — demo/debug)
    // ---------------------------
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> listAllPlans() {
        log.info("[DEMO] GET /api/v1/plan — listing all plans from KV store");
        java.util.List<String> objectIds = store.keys();

        java.util.List<Map<String, Object>> plans = new java.util.ArrayList<>();
        for (String id : objectIds) {
            store.get(id).ifPresent(doc -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("objectId", id);
                entry.put("etag", doc.getEtag());
                entry.put("lastModified", doc.getLastModified().toString());
                plans.add(entry);
            });
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", plans.size());
        response.put("plans", plans);
        log.info("[DEMO] KV store contains {} plans", plans.size());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response);
    }

    // ---------------------------
    // POST /api/v1/plan
    // ---------------------------
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> createPlan(@RequestBody String rawJson) {
        log.info("[DEMO] POST /api/v1/plan — request received");
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

        // publish UPSERT event after successful create
        eventPublisher.publish(IndexEvent.of(IndexEventOperation.UPSERT, objectId, doc.getEtag()));
        log.info("[DEMO] POST created plan id={} etag={} → published UPSERT event to queue", objectId, doc.getEtag());

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
    // ---------------------------
    @GetMapping(value = "/{objectId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getPlan(
            @PathVariable String objectId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        log.info("[DEMO] GET /api/v1/plan/{} — If-None-Match={}", objectId, ifNoneMatch);
        StoredDocument doc = store.get(objectId)
                .orElseThrow(() -> new NotFoundException("Plan not found: " + objectId));

        String storedEtag = doc.getEtag();

        if (ifNoneMatch != null && stripQuotes(ifNoneMatch).equals(storedEtag)) {
            log.info("[DEMO] GET id={} → 304 Not Modified (ETag match)", objectId);
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
        log.info("[DEMO] PUT /api/v1/plan/{} — If-Match={}", objectId, ifMatch);
        StoredDocument existing = store.get(objectId)
                .orElseThrow(() -> new NotFoundException("Plan not found: " + objectId));

        if (ifMatch != null && !stripQuotes(ifMatch).equals(existing.getEtag())) {
            throw new PreconditionFailedException(
                    "ETag mismatch: document has been modified since you last fetched it");
        }

        schemaValidator.validatePlanJson(rawJson);
        store.update(objectId, rawJson);

        StoredDocument updated = store.get(objectId).orElseThrow();

        // publish UPSERT event after successful full replace
        eventPublisher.publish(IndexEvent.of(IndexEventOperation.UPSERT, objectId, updated.getEtag()));
        log.info("[DEMO] PUT replaced plan id={} newEtag={} → published UPSERT event to queue", objectId, updated.getEtag());

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
    // JSON Merge Patch (RFC 7396)
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
        log.info("[DEMO] PATCH /api/v1/plan/{} — If-Match={}", objectId, ifMatch);
        StoredDocument existing = store.get(objectId)
                .orElseThrow(() -> new NotFoundException("Plan not found: " + objectId));

        if (ifMatch != null && !stripQuotes(ifMatch).equals(existing.getEtag())) {
            throw new PreconditionFailedException(
                    "ETag mismatch: document has been modified since you last fetched it");
        }

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

        schemaValidator.validatePlanJson(mergedJson);
        store.update(objectId, mergedJson);

        StoredDocument updated = store.get(objectId).orElseThrow();

        // Publish PATCH event only after KV update succeeds — carries the NEW etag.
        // Never published on 400 / 404 / 412 paths.
        // PATCH updates existing objects — document count in ES stays the same.
        // The worker re-indexes the parent and all children with the merged payload.
        IndexEvent patchEvent = IndexEvent.of(IndexEventOperation.PATCH, objectId, updated.getEtag());
        eventPublisher.publish(patchEvent);
        log.info("[DEMO] PATCH updated plan id={} newEtag={} → published PATCH event to queue (re-indexes parent+children, count stays same)", objectId, updated.getEtag());

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .eTag(updated.getEtag())
                .body(updated.getJson());
    }

    // ---------------------------
    // DELETE /api/v1/plan/{objectId}
    // ---------------------------
    @DeleteMapping("/{objectId}")
    public ResponseEntity<Void> deletePlan(
            @PathVariable String objectId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch
    ) {
        log.info("[DEMO] DELETE /api/v1/plan/{} — If-Match={}", objectId, ifMatch);
        StoredDocument existing = store.get(objectId)
                .orElseThrow(() -> new NotFoundException("Plan not found: " + objectId));

        if (ifMatch != null && !stripQuotes(ifMatch).equals(existing.getEtag())) {
            throw new PreconditionFailedException(
                    "ETag mismatch: document has been modified since you last fetched it");
        }

        // capture etag before deletion so the event carries the last known etag
        String etagBeforeDelete = existing.getEtag();

        store.delete(objectId);

        // Publish DELETE event only after KV delete succeeds — etag captured before deletion.
        // Never published on 404 / 412 error paths.
        IndexEvent deleteEvent = IndexEvent.of(IndexEventOperation.DELETE, objectId, etagBeforeDelete);
        eventPublisher.publish(deleteEvent);
        log.info("[DEMO] DELETE removed plan id={} from KV → published DELETE event to queue (will cascade-remove parent+children from ES)", objectId);

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
