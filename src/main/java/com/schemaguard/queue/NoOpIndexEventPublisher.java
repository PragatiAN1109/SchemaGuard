package com.schemaguard.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * No-op implementation of IndexEventPublisher for the 'test' profile.
 *
 * The test profile uses InMemoryKeyValueStore and has no Redis connection.
 * This bean satisfies the IndexEventPublisher dependency in PlanController
 * without attempting any Redis operations.
 */
@Component
@Profile("test")
public class NoOpIndexEventPublisher implements IndexEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(NoOpIndexEventPublisher.class);

    @Override
    public void publish(IndexEvent event) {
        log.debug("[test] no-op publish: {} id={}", event.operation(), event.documentId());
    }
}
