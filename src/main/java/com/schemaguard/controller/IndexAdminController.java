package com.schemaguard.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.schemaguard.elastic.PlanIndexConstants.INDEX_NAME;

/**
 * Lightweight admin endpoint for verifying Elasticsearch index connectivity.
 *
 * GET /api/v1/index/health
 *   - pings the Elasticsearch cluster root
 *   - returns index status and cluster health in a simple JSON response
 *   - no auth required (public endpoint, demo only)
 *
 * Does NOT expose any plan data or internal document details.
 * Does NOT modify any state.
 */
@RestController
@RequestMapping("/api/v1/index")
public class IndexAdminController {

    private static final Logger log = LoggerFactory.getLogger(IndexAdminController.class);

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${elastic.host:localhost}")
    private String host;

    @Value("${elastic.port:9200}")
    private int port;

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        String baseUrl = "http://" + host + ":" + port;

        // ping cluster
        try {
            restTemplate.getForObject(baseUrl, String.class);
            result.put("cluster", "reachable");
        } catch (Exception ex) {
            result.put("cluster", "unreachable: " + ex.getMessage());
        }

        // check index exists
        try {
            restTemplate.getForObject(baseUrl + "/" + INDEX_NAME, String.class);
            result.put("index", INDEX_NAME + " exists");
        } catch (Exception ex) {
            result.put("index", INDEX_NAME + " not found");
        }

        result.put("indexName", INDEX_NAME);
        return ResponseEntity.ok(result);
    }
}
