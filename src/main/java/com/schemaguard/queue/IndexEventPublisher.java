package com.schemaguard.queue;

/**
 * Abstraction for publishing indexing events.
 *
 * Separating the interface from the implementation allows a no-op
 * version to be used in the test profile (no Redis available) without
 * any conditional logic in the controller.
 */
public interface IndexEventPublisher {

    /**
     * Publish an indexing event to the configured stream.
     *
     * Publishing is fire-and-forget: the method logs on failure but does
     * not throw, so a stream write failure cannot break the API response.
     * This is an explicit tradeoff favouring demo stability over strict
     * durability — documented in README.
     *
     * @param event  the event to publish
     */
    void publish(IndexEvent event);
}
