package com.schemaguard.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchConfiguration;

/**
 * Wires the Elasticsearch client for Spring Boot 4 / Spring Data Elasticsearch 5.x.
 *
 * Extends ElasticsearchConfiguration — Spring's official abstract base class.
 * This exposes ElasticsearchClient and ElasticsearchOperations beans automatically.
 * No manual RestClient or HttpHost wiring needed; Spring Data manages the transport.
 *
 * Connection is driven by environment variables:
 *   ELASTIC_HOST (default: localhost)
 *   ELASTIC_PORT (default: 9200)
 *
 * xpack.security.enabled=false is set in Docker Compose so no credentials are needed.
 */
@Configuration
public class ElasticsearchConfig extends ElasticsearchConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchConfig.class);

    @Value("${elastic.host:localhost}")
    private String host;

    @Value("${elastic.port:9200}")
    private int port;

    @Override
    public ClientConfiguration clientConfiguration() {
        String endpoint = host + ":" + port;
        log.info("configuring Elasticsearch client → {}", endpoint);
        return ClientConfiguration.builder()
                .connectedTo(endpoint)
                .build();
    }
}
