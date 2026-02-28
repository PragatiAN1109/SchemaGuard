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
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.io.IOException;

@Configuration
@Profile("redis")
public class RedisConfig {

    @Bean
    public RedisTemplate<String, StoredDocument> redisTemplate(RedisConnectionFactory connectionFactory) {

        ObjectMapper redisMapper = new ObjectMapper();
        redisMapper.registerModule(new JavaTimeModule());
        redisMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Custom type-safe serializer for StoredDocument
        RedisSerializer<StoredDocument> valueSerializer = new RedisSerializer<>() {
            @Override
            public byte[] serialize(StoredDocument value) throws SerializationException {
                if (value == null) return null;
                try {
                    return redisMapper.writeValueAsBytes(value);
                } catch (IOException e) {
                    throw new SerializationException("Could not serialize StoredDocument", e);
                }
            }

            @Override
            public StoredDocument deserialize(byte[] bytes) throws SerializationException {
                if (bytes == null) return null;
                try {
                    return redisMapper.readValue(bytes, StoredDocument.class);
                } catch (IOException e) {
                    throw new SerializationException("Could not deserialize StoredDocument", e);
                }
            }
        };

        RedisTemplate<String, StoredDocument> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
