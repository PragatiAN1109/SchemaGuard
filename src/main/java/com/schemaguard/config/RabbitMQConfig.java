package com.schemaguard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * RabbitMQ configuration for event-driven indexing.
 *
 * Design:
 *   Exchange:    schemaguard.index.exchange  (topic, durable)
 *   Queue:       schemaguard.index.queue     (durable)
 *   Routing key: index.event
 *
 * All messages are JSON-serialized IndexEvent records.
 * The queue is durable so messages survive broker restarts.
 *
 * Active only on the 'redis' profile (production/demo mode).
 */
@Configuration
@Profile("redis")
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME   = "schemaguard.index.exchange";
    public static final String QUEUE_NAME     = "schemaguard.index.queue";
    public static final String ROUTING_KEY    = "index.event";

    @Bean
    public TopicExchange indexExchange() {
        return new TopicExchange(EXCHANGE_NAME, true, false);
    }

    @Bean
    public Queue indexQueue() {
        return QueueBuilder.durable(QUEUE_NAME).build();
    }

    @Bean
    public Binding indexBinding(Queue indexQueue, TopicExchange indexExchange) {
        return BindingBuilder.bind(indexQueue)
                .to(indexExchange)
                .with(ROUTING_KEY);
    }

    /**
     * Jackson-based message converter so IndexEvent records are
     * serialized as JSON on the wire. This makes messages human-readable
     * in the RabbitMQ Management UI — great for demo visibility.
     */
    @Bean
    public MessageConverter jacksonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter jacksonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setExchange(EXCHANGE_NAME);
        template.setRoutingKey(ROUTING_KEY);
        template.setMessageConverter(jacksonMessageConverter);
        return template;
    }
}
