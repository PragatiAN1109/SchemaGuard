package com.schemaguard.elastic;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static com.schemaguard.elastic.PlanIndexConstants.INDEX_NAME;

/**
 * Creates the 'plans-index' Elasticsearch index with parent-child join mapping
 * on application startup, if it does not already exist.
 *
 * Why plain JSON mapping instead of Spring Data @Document annotations:
 * Elasticsearch join fields (parent-child) are not supported by Spring Data's
 * annotation model. The mapping must be defined as raw JSON and sent via the
 * low-level ElasticsearchClient.
 *
 * Index mapping:
 * - dynamic: true         — all plan JSON fields stored without pre-declaring them
 * - my_join_field         — join type; defines plan → child relation
 * - objectId (keyword)    — exact-match capable field for plan ID lookups
 *
 * Routing:
 * - parent docs: no explicit routing needed (default routing = document ID)
 * - child docs:  routing = parentId (MUST be set at index time and query time)
 *   This guarantees parent and child land on the same shard, which Elasticsearch
 *   requires for join queries (has_child, has_parent, parent_id).
 */
@Component
public class PlanIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(PlanIndexInitializer.class);

    /**
     * Full index mapping as a JSON string.
     * Kept inline so it is self-contained and visible during code review.
     *
     * Structure:
     * {
     *   "mappings": {
     *     "dynamic": true,
     *     "properties": {
     *       "objectId":       { "type": "keyword" },
     *       "objectType":     { "type": "keyword" },
     *       "my_join_field":  { "type": "join", "relations": { "plan": "child" } }
     *     }
     *   }
     * }
     */
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

    public PlanIndexInitializer(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    /**
     * Runs after the Spring context is fully wired.
     * Idempotent: skips creation if the index already exists.
     */
    @PostConstruct
    public void initIndex() {
        try {
            boolean exists = esClient.indices()
                    .exists(ExistsRequest.of(r -> r.index(INDEX_NAME)))
                    .value();

            if (exists) {
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
            // non-fatal — app can still serve Redis-backed requests
            log.warn("could not initialize Elasticsearch index '{}' — {}", INDEX_NAME, ex.getMessage());
        }
    }
}
