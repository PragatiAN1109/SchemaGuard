package com.schemaguard.queue;

/**
 * Abstraction for publishing indexing events.
 *
 * Separating the interface from the implementation allows a no-op
 * version to be used in the test profile (no broker available) without
 * any conditional logic in the controller.
 */
public interface IndexEventPublisher {

    /**
     * Publish an indexing event to the configured message broker.
     *
     * Publishing is fire-and-forget: the method logs on failure but does
     * not throw, so a broker write failure cannot break the API response.
     *
     * @param event  the event to publish
     */
    void publish(IndexEvent event);
}
