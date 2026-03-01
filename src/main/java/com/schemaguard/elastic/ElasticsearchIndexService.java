package com.schemaguard.elastic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.schemaguard.elastic.PlanIndexConstants.*;

/**
 * Elasticsearch implementation of IndexService.
 *
 * Uses plain HTTP via RestTemplate for all ES REST API calls.
 * This avoids known media-type header incompatibilities between the
 * Elasticsearch Java API Client version bundled in Spring Data Elasticsearch
 * and Elasticsearch 8.13 when sending JSON bodies.
 *
 * All write operations use the Elasticsearch Index API (PUT /<index>/_doc/<id>)
 * which provides upsert semantics by default — re-indexing a document with
 * the same id replaces it without creating duplicates.
 *
 * deleteChildren uses the Delete By Query API (POST /<index>/_delete_by_query)
 * with routing = parentId and a parent_id term query so only children of the
 * specified parent are removed, and only the correct shard is targeted.
 *
 * No document bodies are logged — only ids and routing values.
 */
@Service
public class ElasticsearchIndexService implements IndexService {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchIndexService.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper;

    @Value("${elastic.host:localhost}")
    private String host;

    @Value("${elastic.port:9200}")
    private int port;

    public ElasticsearchIndexService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────────────────────
    // indexParent
    // ─────────────────────────────────────────────────────────
    @Override
    public void indexParent(String parentId, JsonNode parentDoc,
                            String etag, Map<String, Object> metadata) {
        try {
            ObjectNode doc = buildDocument(parentDoc, etag, metadata);
            // join field value for a parent is the plain string "plan"
            doc.put(JOIN_FIELD, TYPE_PLAN);

            String url = docUrl(parentId, null);
            put(url, doc);
            log.info("indexed parent id={}", parentId);
        } catch (Exception ex) {
            log.warn("failed to index parent id={} — {}", parentId, ex.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────
    // indexChild
    // ─────────────────────────────────────────────────────────
    @Override
    public void indexChild(String parentId, String childId, JsonNode childDoc,
                           String etag, Map<String, Object> metadata) {
        try {
            ObjectNode doc = buildDocument(childDoc, etag, metadata);
            // join field for child is an object: { "name": "child", "parent": "<parentId>" }
            ObjectNode joinValue = objectMapper.createObjectNode();
            joinValue.put("name", TYPE_CHILD);
            joinValue.put("parent", parentId);
            doc.set(JOIN_FIELD, joinValue);

            // routing = parentId is mandatory — guarantees co-location with parent on same shard
            String url = docUrl(childId, parentId);
            put(url, doc);
            log.info("indexed child id={} routing={}", childId, parentId);
        } catch (Exception ex) {
            log.warn("failed to index child id={} routing={} — {}", childId, parentId, ex.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────
    // patchParent — replace parent with patched version
    // ─────────────────────────────────────────────────────────
    @Override
    public void patchParent(String parentId, JsonNode patchedParentDoc, String etag) {
        // patchParent is a full re-index of the parent with the merged document.
        // Upsert semantics apply — same as indexParent.
        indexParent(parentId, patchedParentDoc, etag, null);
        log.info("patched (re-indexed) parent id={}", parentId);
    }

    // ─────────────────────────────────────────────────────────
    // deleteParent
    // ─────────────────────────────────────────────────────────
    @Override
    public void deleteParent(String parentId) {
        try {
            String url = docUrl(parentId, null);
            restTemplate.exchange(url, HttpMethod.DELETE, jsonEntity(null), String.class);
            log.info("deleted parent id={}", parentId);
        } catch (HttpClientErrorException.NotFound ex) {
            // idempotent — already gone is fine
            log.info("delete parent id={} — not found, already absent", parentId);
        } catch (Exception ex) {
            log.warn("failed to delete parent id={} — {}", parentId, ex.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────
    // deleteChildren — delete_by_query with parent_id filter
    // ─────────────────────────────────────────────────────────
    @Override
    public void deleteChildren(String parentId) {
        try {
            // delete_by_query with routing=parentId ensures we only hit the correct shard.
            // parent_id query matches all child documents whose join parent = parentId.
            String url = baseUrl() + "/_delete_by_query?routing=" + parentId + "&refresh=true";

            String body = """
                    {
                      "query": {
                        "parent_id": {
                          "type": "%s",
                          "id": "%s"
                        }
                      }
                    }
                    """.formatted(TYPE_CHILD, parentId);

            restTemplate.exchange(url, HttpMethod.POST, jsonEntity(body), String.class);
            log.info("deleted children for parent id={}", parentId);
        } catch (Exception ex) {
            log.warn("failed to delete children for parent id={} — {}", parentId, ex.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────
    // private helpers
    // ─────────────────────────────────────────────────────────

    /** Merges parentDoc fields + etag + optional metadata into a single ObjectNode. */
    private ObjectNode buildDocument(JsonNode source, String etag, Map<String, Object> metadata) {
        ObjectNode doc = objectMapper.createObjectNode();
        if (source != null && source.isObject()) {
            doc.setAll((ObjectNode) source.deepCopy());
        }
        if (etag != null) {
            doc.put("_etag", etag);
        }
        if (metadata != null) {
            metadata.forEach((k, v) -> doc.put(k, v == null ? null : v.toString()));
        }
        return doc;
    }

    /** Builds the document URL, appending ?routing=<parentId> for child docs. */
    private String docUrl(String docId, String routing) {
        String url = baseUrl() + "/_doc/" + docId;
        if (routing != null) {
            url += "?routing=" + routing;
        }
        return url;
    }

    private String baseUrl() {
        return "http://" + host + ":" + port + "/" + INDEX_NAME;
    }

    /** Executes an HTTP PUT with JSON content type. */
    private void put(String url, Object body) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        restTemplate.exchange(url, HttpMethod.PUT, jsonEntity(json), String.class);
    }

    /** Builds an HttpEntity with Content-Type: application/json. */
    private HttpEntity<String> jsonEntity(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
