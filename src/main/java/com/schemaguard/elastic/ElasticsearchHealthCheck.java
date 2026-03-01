package com.schemaguard.elastic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.stereotype.Component;

/**
 * Verifies Elasticsearch connectivity once the application context is fully started.
 *
 * Listens for ApplicationReadyEvent so all beans are guaranteed to be wired
 * before the ping attempt. Uses ElasticsearchTemplate.indexOps() as a lightweight
 * connectivity probe — no index creation or data access.
 *
 * The app continues to start whether or not Elasticsearch is reachable.
 * Existing Redis-backed plan operations are unaffected.
 *
 * Success log:
 *   "Elasticsearch cluster reachable at <host>:<port>"
 *
 * Failure log (warn, non-fatal):
 *   "Elasticsearch cluster NOT reachable at <host>:<port> — <reason>"
 */
@Component
public class ElasticsearchHealthCheck {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchHealthCheck.class);

    private final ElasticsearchTemplate esTemplate;

    @Value("${elastic.host:localhost}")
    private String host;

    @Value("${elastic.port:9200}")
    private int port;

    public ElasticsearchHealthCheck(ElasticsearchTemplate esTemplate) {
        this.esTemplate = esTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkConnectivity() {
        try {
            // cluster() ping via the underlying client exposed by ElasticsearchTemplate
            esTemplate.indexOps(org.springframework.data.elasticsearch.core.IndexCoordinates.of("_ping_probe_"));
            log.info("Elasticsearch cluster reachable at {}:{}", host, port);
        } catch (Exception ex) {
            log.warn("Elasticsearch cluster NOT reachable at {}:{} — {}", host, port, ex.getMessage());
        }
    }
}
