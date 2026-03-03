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
 * Two query strategies are implemented using plain HTTP via RestTemplate,
 * consistent with ElasticsearchIndexService:
 *
 * 1. searchParentsByChildField — has_child query
 *    Finds parent (plan) documents that have at least one child document
 *    matching a given field/value. Optionally filtered further by a free-text
 *    query on the parent itself (q param).
 *
 * 2. findChildrenByParent — has_parent query
 *    Returns all child documents belonging to a specific parentId.
 *    Uses routing=parentId so the query targets the correct shard only.
 *
 * Join field contract (must match PlanIndexInitializer mapping):
 *   my_join_field = "plan"                               (parent)
 *   my_join_field = {"name":"child","parent":"<id>"}     (child)
 *
 * Routing contract:
 *   child documents MUST be queried with routing=parentId.
 *   Parent documents use default routing (their own id).
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
    // searchParentsByChildField
    //
    // Builds a has_child query so parents are returned when at least one of
    // their children matches the given field/value.
    //
    // If childField/childValue are blank, falls back to match_all so the
    // endpoint still works as a plain "list all parents" call.
    //
    // If q is provided, it is combined via bool/must with the has_child clause
    // so the parent document itself must also match.
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> searchParentsByChildField(
            String childField, String childValue, String q) {

        String queryBody = buildParentSearchQuery(childField, childValue, q);
        log.info("searchParentsByChildField childField={} childValue={} q={}",
                childField, childValue, q);

        try {
            String url = baseUrl() + "/_search";
            String raw = post(url, queryBody);
            return parseParentResults(raw, childField, childValue);
        } catch (Exception ex) {
            log.warn("searchParentsByChildField failed — {}", ex.getMessage());
            throw new RuntimeException("Elasticsearch query failed: " + ex.getMessage(), ex);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // findChildrenByParent
    //
    // Uses a has_parent query filtered to parentId so only children of that
    // specific parent are returned.
    //
    // routing=parentId is appended to the URL — this is REQUIRED for
    // parent-child joins. Without it, the query scatters across all shards
    // and may miss child documents (or return results from wrong parents).
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> findChildrenByParent(String parentId) {
        log.info("findChildrenByParent parentId={}", parentId);

        // has_parent query: find children whose parent document has objectId == parentId
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
            // routing=parentId ensures we only hit the shard where this parent's children live
            String url = baseUrl() + "/_search?routing=" + parentId;
            String raw = post(url, queryBody);
            return parseChildResults(raw, parentId);
        } catch (Exception ex) {
            log.warn("findChildrenByParent failed parentId={} — {}", parentId, ex.getMessage());
            throw new RuntimeException("Elasticsearch query failed: " + ex.getMessage(), ex);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Query builders
    // ─────────────────────────────────────────────────────────────────────────

    private String buildParentSearchQuery(String childField, String childValue, String q) {
        // Inner child query:
        // If childField+childValue supplied, use multi_match targeting that specific field
        // (supports dotted paths like "linkedService.name") plus a wildcard fallback so
        // partial paths also work. If no filter, match_all so the endpoint lists all parents.
        String innerChildQuery;
        if (hasValue(childField) && hasValue(childValue)) {
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

        // has_child clause — type must match the child relation name in the join mapping
        String hasChildClause = """
                {
                  "has_child": {
                    "type": "%s",
                    "query": %s
                  }
                }
                """.formatted(TYPE_CHILD, innerChildQuery);

        // If q param provided, combine has_child + multi_match on parent with bool/must
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
    // Response parsers — convert raw ES JSON into clean response maps
    // ─────────────────────────────────────────────────────────────────────────

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
                entry.put("score",    hit.path("_score").asDouble());
                entry.put("source",   objectMapper.convertValue(
                        hit.path("_source"), Map.class));

                // matchedBy — tells the caller which filter was applied
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
                entry.put("source",  objectMapper.convertValue(
                        hit.path("_source"), Map.class));
                children.add(entry);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("parentId", parentId);
        response.put("count",    children.size());
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
