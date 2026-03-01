package com.schemaguard.elastic;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import static com.schemaguard.elastic.PlanIndexConstants.INDEX_NAME;

/**
 * Creates the 'plans-index' Elasticsearch index with parent-child join mapping
 * on application startup, if it does not already exist.
 *
 * Why ApplicationReadyEvent instead of @PostConstruct:
 * @PostConstruct fires during bean initialization, before the full application
 * context is confirmed live. The Elasticsearch connection may not be established
 * yet at that point. ApplicationReadyEvent fires after everything is up,
 * guaranteeing the ElasticsearchClient can actually reach the cluster.
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
 * - objectType (keyword)  — filter by document type
 *
 * Routing:
 * - parent docs: routing = own objectId (Elasticsearch default)
 * - child docs:  routing = parentId (MUST be set explicitly at index and query time)
 *   This guarantees parent and child land on the same shard, required for
 *   has_child, has_parent, and parent_id join queries.
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

    public PlanIndexInitializer(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    /**
     * Runs after the full application context is started and all connections
     * are confirmed live. Idempotent: skips creation if the index already exists.
     */
    @EventListener(ApplicationReadyEvent.class)
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
            log.warn("could not initialize Elasticsearch index '{}' — {}", INDEX_NAME, ex.getMessage());
        }
    }
}
