package com.schemaguard.controller;

import com.schemaguard.elastic.PlanSearchService;
import com.schemaguard.model.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST search endpoints backed by Elasticsearch parent-child join queries.
 *
 * All endpoints require a valid Google Bearer token (authenticated via
 * SecurityConfig — same rule as /api/v1/plan/**).
 *
 * Indexing is asynchronous: documents are indexed by IndexWorker after
 * the API writes to Redis. Search results reflect the Elasticsearch state
 * at query time, which may lag behind KV store by ~1 s after a write.
 *
 * Endpoints:
 *
 *   GET /api/v1/search
 *     Search parent (plan) documents, optionally filtered by child properties.
 *     Uses a has_child query so parents are returned only when a matching
 *     child exists.
 *
 *   GET /api/v1/search/parent/{parentId}/children
 *     Return all child documents belonging to a given parent.
 *     Uses a has_parent query with routing=parentId for correctness.
 */
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private final PlanSearchService searchService;

    public SearchController(PlanSearchService searchService) {
        this.searchService = searchService;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/search
    //
    // Query params (all optional):
    //   q           — free-text match applied to the parent document itself
    //   childField  — field name on a child document to filter by
    //   childValue  — value to match for childField (uses term + match)
    //
    // When childField + childValue are provided, only parents that have at
    // least one child matching that criterion are returned (has_child query).
    // When omitted, all indexed parents are returned (match_all on has_child).
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> searchParents(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String childField,
            @RequestParam(required = false) String childValue,
            HttpServletRequest request
    ) {
        // Validate: childField and childValue must be provided together
        boolean hasField = childField != null && !childField.isBlank();
        boolean hasValue = childValue != null && !childValue.isBlank();
        if (hasField != hasValue) {
            return ResponseEntity.badRequest().body(new ApiError(
                    400, "Bad Request",
                    "childField and childValue must both be provided or both omitted",
                    request.getRequestURI()
            ));
        }

        try {
            Map<String, Object> result =
                    searchService.searchParentsByChildField(childField, childValue, q);
            log.info("GET /api/v1/search childField={} childValue={} q={} → {} results",
                    childField, childValue, q, result.get("count"));
            return ResponseEntity.ok(result);
        } catch (Exception ex) {
            log.warn("GET /api/v1/search failed — {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(
                    500, "Internal Server Error",
                    "Search query failed — Elasticsearch may be unavailable",
                    request.getRequestURI()
            ));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/search/parent/{parentId}/children
    //
    // Returns all child documents for the given parentId.
    // Returns an empty children array (count=0) if none exist — not a 404.
    // Uses has_parent query with routing=parentId for shard targeting.
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping(value = "/parent/{parentId}/children",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getChildrenForParent(
            @PathVariable String parentId,
            HttpServletRequest request
    ) {
        try {
            Map<String, Object> result = searchService.findChildrenByParent(parentId);
            log.info("GET /api/v1/search/parent/{}/children → {} children",
                    parentId, result.get("count"));
            return ResponseEntity.ok(result);
        } catch (Exception ex) {
            log.warn("GET /api/v1/search/parent/{}/children failed — {}", parentId, ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(
                    500, "Internal Server Error",
                    "Search query failed — Elasticsearch may be unavailable",
                    request.getRequestURI()
            ));
        }
    }
}
