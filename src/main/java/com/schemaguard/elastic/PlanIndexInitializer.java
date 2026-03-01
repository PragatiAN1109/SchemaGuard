package com.schemaguard.elastic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import static com.schemaguard.elastic.PlanIndexConstants.INDEX_NAME;

/**
 * Creates the 'plans-index' Elasticsearch index with parent-child join mapping
 * on application startup, if it does not already exist.
 *
 * Uses plain HTTP via RestTemplate for both the existence check and index creation.
 * This avoids two known issues with the Elasticsearch Java API Client in this stack:
 *   1. indices.exists() throws on the empty-body 404 ES returns for missing indices.
 *   2. indices.create().withJson() triggers a media_type_header_exception in ES 8.13
 *      due to a Content-Type/Accept header incompatibility in the client version
 *      bundled with Spring Data Elasticsearch.
 *
 * Plain HTTP PUT to /<index> with a JSON body is the Elasticsearch REST API standard
 * and works across all 8.x versions regardless of client library version.
 */
@Component
public class PlanIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(PlanIndexInitializer.class);

    static final String INDEX_MAPPING = """
            {
              "mappings": {
                "dynamic": true,
                "properties": {
                  "objectId": {
                    "type": "keyword"
                  },
                  "objectType": {
                    "type": "keyword"
                  },
                  "my_join_field": {
                    "type": "join",
                    "relations": {
                      "plan": "child"
                    }
                  }
                }
              }
            }
            """;

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${elastic.host:localhost}")
    private String host;

    @Value("${elastic.port:9200}")
    private int port;

    @EventListener(ApplicationReadyEvent.class)
    public void initIndex() {
        String indexUrl = "http://" + host + ":" + port + "/" + INDEX_NAME;
        try {
            if (indexExists(indexUrl)) {
                log.info("Elasticsearch index '{}' already exists — skipping creation", INDEX_NAME);
                return;
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> request = new HttpEntity<>(INDEX_MAPPING, headers);

            ResponseEntity<String> response = restTemplate.put(indexUrl, request, String.class);
            // PUT returns 200 on success (no body needed)
            log.info("Elasticsearch index '{}' initialized with parent-child mapping", INDEX_NAME);

        } catch (Exception ex) {
            log.warn("could not initialize Elasticsearch index '{}' — {}", INDEX_NAME, ex.getMessage());
        }
    }

    private boolean indexExists(String indexUrl) {
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(indexUrl, String.class);
            return resp.getStatusCode() == HttpStatus.OK;
        } catch (Exception ex) {
            return false;
        }
    }
}
