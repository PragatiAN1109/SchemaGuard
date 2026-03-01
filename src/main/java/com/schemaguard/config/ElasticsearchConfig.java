package com.schemaguard.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Elasticsearch Java API Client (co.elastic.clients) for Spring Boot 4.
 *
 * RestHighLevelClient was removed in Spring Boot 3+ / Elasticsearch 8.
 * We use the official new Java API Client backed by a low-level RestClient.
 *
 * Connection is driven by environment variables:
 *   ELASTIC_HOST (default: localhost)
 *   ELASTIC_PORT (default: 9200)
 *
 * Security is disabled on the Elasticsearch side for local demo
 * (xpack.security.enabled=false in Docker Compose), so no credentials needed.
 */
@Configuration
public class ElasticsearchConfig {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchConfig.class);

    @Value("${elastic.host:localhost}")
    private String host;

    @Value("${elastic.port:9200}")
    private int port;

    /**
     * Low-level REST client — manages HTTP connections to Elasticsearch.
     */
    @Bean
    public RestClient restClient() {
        log.info("Configuring Elasticsearch REST client → {}:{}", host, port);
        return RestClient.builder(new HttpHost(host, port, "http")).build();
    }

    /**
     * High-level Java API Client — type-safe, fluent API for all ES operations.
     */
    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient) {
        ElasticsearchTransport transport = new RestClientTransport(
                restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }
}
