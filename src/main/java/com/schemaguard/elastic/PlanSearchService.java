package com.schemaguard.elastic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.schemaguard.elastic.PlanIndexConstants.*;
/**
 * Elasticsearch search service for parent-child join queries.
 *
 * Three query strategies:
 *
 * 1. searchAll — match_all across the entire index (parents + children).
 *    Returns total document count — useful for demo visibility.
 *
 * 2. searchParentsByChildField — has_child query.
 *    Finds parent (plan) documents that have at least one child matching
 *    a given field/value. Supports both term match and range queries
 *    (e.g. copay > 100).
 *
 * 3. findChildrenByParent — has_parent query.
 *    Returns all child documents belonging to a specific parentId.
 */
@Service
public class PlanSearchService {

    private static final Logger log = LoggerFactory.getLogger(PlanSearchService.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper;

    @Value("${elastic.host:localhost}")
    private String host;

    @Value("${elastic.port:9200}")
    private int port;

    public PlanSearchService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // searchAll — match_all query returning total document count
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> searchAll() {
        log.info("[DEMO] ES searchAll — match_all query for total document count");
        String queryBody = """
                {
                  "query": { "match_all": {} },
                  "size": 100
                }
                """;
        try {
            String url = baseUrl() + "/_search";
            String raw = post(url, queryBody);
            return parseAllResults(raw);
        } catch (Exception ex) {
            log.warn("[DEMO] searchAll failed — {}", ex.getMessage());
            throw new RuntimeException("Elasticsearch query failed: " + ex.getMessage(), ex);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // searchParentsByChildField — has_child query
    //
    // Supports two modes:
    //   1. Term match: childField=copay & childValue=175
    //   2. Range query: childField=copay & childOp=gt & childValue=100
    //      (childOp can be: gt, gte, lt, lte)
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> searchParentsByChildField(
            String childField, String childValue, String childOp, String q) {

        String queryBody = buildParentSearchQuery(childField, childValue, childOp, q);
        log.info("[DEMO] ES searchParentsByChildField childField={} childOp={} childValue={} q={}",
                childField, childOp, childValue, q);

        try {
            String url = baseUrl() + "/_search";
            String raw = post(url, queryBody);
            return parseParentResults(raw, childField, childValue);
        } catch (Exception ex) {
            log.warn("[DEMO] searchParentsByChildField failed — {}", ex.getMessage());
            throw new RuntimeException("Elasticsearch query failed: " + ex.getMessage(), ex);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // findChildrenByParent — has_parent query
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> findChildrenByParent(String parentId) {
        log.info("[DEMO] ES findChildrenByParent parentId={}", parentId);

        String queryBody = """
                {
                  "query": {
                    "has_parent": {
                      "parent_type": "%s",
                      "query": {
                        "term": {
                          "objectId": "%s"
                        }
                      }
                    }
                  }
                }
                """.formatted(TYPE_PLAN, parentId);

        try {
            String url = baseUrl() + "/_search?routing=" + parentId;
            String raw = post(url, queryBody);
            return parseChildResults(raw, parentId);
        } catch (Exception ex) {
            log.warn("[DEMO] findChildrenByParent failed parentId={} — {}", parentId, ex.getMessage());
            throw new RuntimeException("Elasticsearch query failed: " + ex.getMessage(), ex);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Query builders
    // ─────────────────────────────────────────────────────────────────────────

    private String buildParentSearchQuery(String childField, String childValue,
                                          String childOp, String q) {
        String innerChildQuery;

        if (hasValue(childField) && hasValue(childValue) && hasValue(childOp)) {
            // Range query: e.g. copay > 100
            // childOp must be one of: gt, gte, lt, lte
            innerChildQuery = """
                    {
                      "range": {
                        "%s": {
                          "%s": %s
                        }
                      }
                    }
                    """.formatted(childField, childOp, childValue);
        } else if (hasValue(childField) && hasValue(childValue)) {
            // Term/match query
            innerChildQuery = """
                    {
                      "multi_match": {
                        "query": "%s",
                        "fields": ["%s", "%s.*"],
                        "type": "best_fields",
                        "lenient": true
                      }
                    }
                    """.formatted(childValue, childField, childField);
        } else {
            innerChildQuery = """
                    { "match_all": {} }
                    """;
        }

        // has_child clause
        String hasChildClause = """
                {
                  "has_child": {
                    "type": "%s",
                    "query": %s
                  }
                }
                """.formatted(TYPE_CHILD, innerChildQuery);

        // If q param provided, combine with bool/must
        if (hasValue(q)) {
            return """
                    {
                      "query": {
                        "bool": {
                          "must": [
                            %s,
                            { "multi_match": { "query": "%s", "fields": ["*"], "lenient": true } }
                          ]
                        }
                      }
                    }
                    """.formatted(hasChildClause, q);
        }

        return """
                {
                  "query": %s
                }
                """.formatted(hasChildClause);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Response parsers
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseAllResults(String raw) throws Exception {
        JsonNode root = objectMapper.readTree(raw);
        JsonNode hits = root.path("hits").path("hits");
        long total = root.path("hits").path("total").path("value").asLong(0);

        List<Map<String, Object>> results = new ArrayList<>();
        if (hits.isArray()) {
            for (JsonNode hit : hits) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", hit.path("_id").asText());
                // Determine if parent or child from the join field
                JsonNode joinField = hit.path("_source").path(JOIN_FIELD);
                if (joinField.isTextual()) {
                    entry.put("type", joinField.asText()); // "plan"
                } else if (joinField.isObject()) {
                    entry.put("type", joinField.path("name").asText()); // "child"
                    entry.put("parent", joinField.path("parent").asText());
                }
                entry.put("source", objectMapper.convertValue(
                        hit.path("_source"), Map.class));
                results.add(entry);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalDocuments", total);
        response.put("returned", results.size());
        response.put("documents", results);
        log.info("[DEMO] ES searchAll returned {} total documents", total);
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseParentResults(
            String raw, String childField, String childValue) throws Exception {

        JsonNode root = objectMapper.readTree(raw);
        JsonNode hits = root.path("hits").path("hits");

        List<Map<String, Object>> results = new ArrayList<>();
        if (hits.isArray()) {
            for (JsonNode hit : hits) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("parentId", hit.path("_id").asText());
                entry.put("score", hit.path("_score").asDouble());
                entry.put("source", objectMapper.convertValue(
                        hit.path("_source"), Map.class));

                Map<String, Object> matchedBy = new LinkedHashMap<>();
                matchedBy.put("childField", hasValue(childField) ? childField : null);
                matchedBy.put("childValue", hasValue(childValue) ? childValue : null);
                entry.put("matchedBy", matchedBy);

                results.add(entry);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", results.size());
        response.put("results", results);
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseChildResults(String raw, String parentId) throws Exception {
        JsonNode root = objectMapper.readTree(raw);
        JsonNode hits = root.path("hits").path("hits");

        List<Map<String, Object>> children = new ArrayList<>();
        if (hits.isArray()) {
            for (JsonNode hit : hits) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("childId", hit.path("_id").asText());
                entry.put("source", objectMapper.convertValue(
                        hit.path("_source"), Map.class));
                children.add(entry);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("parentId", parentId);
        response.put("count", children.size());
        response.put("children", children);
        return response;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String post(String url, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        return restTemplate.exchange(url, HttpMethod.POST, entity, String.class).getBody();
    }

    private String baseUrl() {
        return "http://" + host + ":" + port + "/" + INDEX_NAME;
    }

    private static boolean hasValue(String s) {
        return s != null && !s.isBlank();
    }
}
