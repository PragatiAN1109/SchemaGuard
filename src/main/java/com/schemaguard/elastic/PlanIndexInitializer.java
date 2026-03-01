package com.schemaguard.elastic;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import static com.schemaguard.elastic.PlanIndexConstants.INDEX_NAME;

/**
 * Creates the 'plans-index' Elasticsearch index with parent-child join mapping
 * on application startup, if it does not already exist.
 *
 * Why ApplicationReadyEvent instead of @PostConstruct:
 * @PostConstruct fires during bean initialization before all connections are live.
 * ApplicationReadyEvent fires after the full context is up and Elasticsearch
 * is confirmed reachable.
 *
 * Why plain HTTP for the existence check:
 * The Elasticsearch Java API Client's indices.exists() throws on any unexpected
 * response body shape (including the empty-body 404 Elasticsearch returns for
 * missing indices). A plain HEAD/GET via RestTemplate is simpler and reliable.
 *
 * Why plain JSON mapping instead of Spring Data @Document annotations:
 * Elasticsearch join fields (parent-child) are not supported by Spring Data's
 * annotation model. The mapping must be sent as raw JSON via ElasticsearchClient.
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

    private final ElasticsearchClient esClient;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${elastic.host:localhost}")
    private String host;

    @Value("${elastic.port:9200}")
    private int port;

    public PlanIndexInitializer(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initIndex() {
        try {
            if (indexExists()) {
                log.info("Elasticsearch index '{}' already exists — skipping creation", INDEX_NAME);
                return;
            }

            CreateIndexResponse response = esClient.indices()
                    .create(r -> r
                            .index(INDEX_NAME)
                            .withJson(new java.io.StringReader(INDEX_MAPPING))
                    );

            if (Boolean.TRUE.equals(response.acknowledged())) {
                log.info("Elasticsearch index '{}' initialized with parent-child mapping", INDEX_NAME);
            } else {
                log.warn("Elasticsearch index '{}' creation not acknowledged", INDEX_NAME);
            }

        } catch (Exception ex) {
            log.warn("could not initialize Elasticsearch index '{}' — {}", INDEX_NAME, ex.getMessage());
        }
    }

    /**
     * Checks whether the index exists using a plain HTTP GET.
     * Returns true if Elasticsearch responds with 200, false on 404.
     * Avoids the ElasticsearchClient indices.exists() quirk where an
     * empty-body response causes a deserialization error.
     */
    private boolean indexExists() {
        String url = "http://" + host + ":" + port + "/" + INDEX_NAME;
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
            return resp.getStatusCode() == HttpStatus.OK;
        } catch (Exception ex) {
            // 404 or connection error — treat as does not exist
            return false;
        }
    }
}
