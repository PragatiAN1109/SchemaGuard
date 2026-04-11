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
 * All endpoints require a valid Google Bearer token.
 *
 * Endpoints:
 *
 *   GET /api/v1/search/all
 *     match_all query — returns every indexed document with total count.
 *     Shows both parents and children with their type labels.
 *     Primary demo tool for verifying document count after POST/PATCH/DELETE.
 *
 *   GET /api/v1/search
 *     has_child query — find parents by child field/value.
 *     Supports range queries via childOp param (gt, gte, lt, lte).
 *     Example: ?childField=copay&childOp=gt&childValue=100
 *
 *   GET /api/v1/search/parent/{parentId}/children
 *     has_parent query — return all children for a given parent.
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
    // GET /api/v1/search/all
    //
    // match_all query — returns total indexed document count + all documents.
    // This is the primary endpoint for demo visibility:
    //   POST → count increases (1 parent + N children)
    //   PATCH → count stays same (updates in place)
    //   DELETE → count drops to 0
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping(value = "/all", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> searchAll(HttpServletRequest request) {
        log.info("[DEMO] GET /api/v1/search/all — match_all for total document count");
        try {
            Map<String, Object> result = searchService.searchAll();
            log.info("[DEMO] search/all → {} total documents", result.get("totalDocuments"));
            return ResponseEntity.ok(result);
        } catch (Exception ex) {
            log.warn("[DEMO] GET /api/v1/search/all failed — {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(
                    500, "Internal Server Error",
                    "Search query failed — Elasticsearch may be unavailable",
                    request.getRequestURI()
            ));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/search
    //
    // Query params (all optional):
    //   q           — free-text match on the parent document
    //   childField  — field name on a child document to filter by
    //   childValue  — value to match for childField
    //   childOp     — range operator: gt, gte, lt, lte
    //                  When provided with childField+childValue, uses a range
    //                  query instead of term match.
    //                  Example: ?childField=copay&childOp=gt&childValue=100
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> searchParents(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String childField,
            @RequestParam(required = false) String childValue,
            @RequestParam(required = false) String childOp,
            HttpServletRequest request
    ) {
        boolean hasField = childField != null && !childField.isBlank();
        boolean hasValue = childValue != null && !childValue.isBlank();
        if (hasField != hasValue) {
            return ResponseEntity.badRequest().body(new ApiError(
                    400, "Bad Request",
                    "childField and childValue must both be provided or both omitted",
                    request.getRequestURI()
            ));
        }

        // Validate childOp if provided
        if (childOp != null && !childOp.isBlank()) {
            if (!childOp.matches("^(gt|gte|lt|lte)$")) {
                return ResponseEntity.badRequest().body(new ApiError(
                        400, "Bad Request",
                        "childOp must be one of: gt, gte, lt, lte",
                        request.getRequestURI()
                ));
            }
        }

        try {
            Map<String, Object> result =
                    searchService.searchParentsByChildField(childField, childValue, childOp, q);
            log.info("[DEMO] GET /api/v1/search childField={} childOp={} childValue={} q={} → {} results",
                    childField, childOp, childValue, q, result.get("count"));
            return ResponseEntity.ok(result);
        } catch (Exception ex) {
            log.warn("[DEMO] GET /api/v1/search failed — {}", ex.getMessage());
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
    // has_parent query — returns all child documents for a given parent.
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping(value = "/parent/{parentId}/children",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getChildrenForParent(
            @PathVariable String parentId,
            HttpServletRequest request
    ) {
        try {
            Map<String, Object> result = searchService.findChildrenByParent(parentId);

            log.info("[DEMO] GET /api/v1/search/parent/{}/children → {} children",
                    parentId, result.get("count"));
            return ResponseEntity.ok(result);
        } catch (Exception ex) {
            log.warn("[DEMO] GET /api/v1/search/parent/{}/children failed — {}", parentId, ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(
                    500, "Internal Server Error",
                    "Search query failed — Elasticsearch may be unavailable",
                    request.getRequestURI()
            ));
        }
    }
}
