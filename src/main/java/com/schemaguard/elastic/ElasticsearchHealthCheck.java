package com.schemaguard.elastic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Verifies Elasticsearch connectivity once the application context is fully started.
 *
 * Uses a plain HTTP GET to the Elasticsearch root endpoint as a lightweight probe.
 * No Spring Data Elasticsearch classes involved — avoids version-sensitive API surface.
 *
 * The app continues to start whether or not Elasticsearch is reachable.
 * Existing Redis-backed plan operations are unaffected.
 *
 * Success log:
 *   "Elasticsearch cluster reachable at http://<host>:<port>"
 *
 * Failure log (warn, non-fatal):
 *   "Elasticsearch cluster NOT reachable at http://<host>:<port> — <reason>"
 */
@Component
public class ElasticsearchHealthCheck {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchHealthCheck.class);

    @Value("${elastic.host:localhost}")
    private String host;

    @Value("${elastic.port:9200}")
    private int port;

    @EventListener(ApplicationReadyEvent.class)
    public void checkConnectivity() {
        String url = "http://" + host + ":" + port;
        try {
            new RestTemplate().getForObject(url, String.class);
            log.info("Elasticsearch cluster reachable at {}", url);
        } catch (Exception ex) {
            log.warn("Elasticsearch cluster NOT reachable at {} — {}", url, ex.getMessage());
        }
    }
}
