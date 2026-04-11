package com.schemaguard.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import static com.schemaguard.config.RabbitMQConfig.*;

/**
 * RabbitMQ implementation of IndexEventPublisher.
 *
 * Publishes IndexEvent records as JSON to the
 * schemaguard.index.exchange with routing key "index.event".
 *
 * Messages are visible in the RabbitMQ Management UI at
 * http://localhost:15672 (guest/guest) → Queues →
 * schemaguard.index.queue → Get Message(s).
 *
 * Publishing is non-blocking and non-fatal: exceptions are
 * caught and logged so a broker failure cannot break the
 * API response.
 *
 * Active only on the 'redis' profile.
 */
@Component
@Profile("redis")
public class RabbitMQEventPublisher implements IndexEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitMQEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public RabbitMQEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publish(IndexEvent event) {
        try {
            rabbitTemplate.convertAndSend(EXCHANGE_NAME, ROUTING_KEY, event);

            log.info("[DEMO] RABBITMQ published {} event for id={} etag={} → exchange={} routingKey={}",
                    event.operation(), event.documentId(), event.etag(),
                    EXCHANGE_NAME, ROUTING_KEY);
        } catch (Exception ex) {
            log.warn("[DEMO] RABBITMQ failed to publish {} event for id={} — {}",
                    event.operation(), event.documentId(), ex.getMessage());
        }
    }
}
