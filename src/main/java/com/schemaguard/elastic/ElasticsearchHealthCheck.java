package com.schemaguard.elastic;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Verifies Elasticsearch connectivity once the application context is fully started.
 *
 * Listens for ApplicationReadyEvent so all beans are guaranteed to be wired
 * before the ping attempt. Logs a clear success or failure message — the app
 * continues to start either way (Elasticsearch is not yet required for existing
 * Redis-backed operations).
 *
 * Success log:
 *   "Elasticsearch cluster reachable at <host>:<port> — status: green/yellow"
 *
 * Failure log (warn, non-fatal):
 *   "Elasticsearch cluster NOT reachable at <host>:<port> — <reason>"
 */
@Component
public class ElasticsearchHealthCheck {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchHealthCheck.class);

    private final ElasticsearchClient esClient;

    @Value("${elastic.host:localhost}")
    private String host;

    @Value("${elastic.port:9200}")
    private int port;

    public ElasticsearchHealthCheck(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkConnectivity() {
        try {
            HealthResponse health = esClient.cluster().health();
            log.info("Elasticsearch cluster reachable at {}:{} — status: {}",
                    host, port, health.status());
        } catch (Exception ex) {
            log.warn("Elasticsearch cluster NOT reachable at {}:{} — {}",
                    host, port, ex.getMessage());
        }
    }
}
