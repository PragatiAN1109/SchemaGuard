package com.schemaguard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.schemaguard.model.StoredDocument;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@Profile("redis")
public class RedisConfig {

    @Bean
    public RedisTemplate<String, StoredDocument> redisTemplate(RedisConnectionFactory connectionFactory) {

        // Build a dedicated ObjectMapper for Redis serialization.
        // Must have JavaTimeModule for Instant and must NOT use default typing
        // (RedisSerializer.json() handles type-safe deserialization via a typed serializer).
        ObjectMapper redisMapper = new ObjectMapper();
        redisMapper.registerModule(new JavaTimeModule());
        redisMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        RedisTemplate<String, StoredDocument> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // String keys
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        // Type-safe JSON serializer: knows to deserialize into StoredDocument
        RedisSerializer<StoredDocument> valueSerializer =
            RedisSerializer.json(redisMapper, StoredDocument.class);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
