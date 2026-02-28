package com.schemaguard.config;

import com.schemaguard.model.StoredDocument;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@Profile("redis")
public class RedisConfig {

    @Bean
    public RedisTemplate<String, StoredDocument> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, StoredDocument> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Use String serializer for keys
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        // Use JSON serializer for values
        Jackson2JsonRedisSerializer<StoredDocument> serializer = 
            new Jackson2JsonRedisSerializer<>(StoredDocument.class);
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);

        template.afterPropertiesSet();
        return template;
    }
}
