package com.schemaguard.store;

import com.schemaguard.model.StoredDocument;
import com.schemaguard.util.EtagUtil;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
@Profile("redis")
public class RedisKeyValueStore implements KeyValueStore {

    private final RedisTemplate<String, StoredDocument> redisTemplate;
    private static final String KEY_PREFIX = "plan:";

    public RedisKeyValueStore(RedisTemplate<String, StoredDocument> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean create(String objectId, String jsonString) {
        String key = KEY_PREFIX + objectId;
        
        // Check if key already exists
        Boolean exists = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            return false;
        }

        // Create new document with etag
        String etag = EtagUtil.sha256Etag(jsonString);
        StoredDocument doc = new StoredDocument(objectId, jsonString, etag, Instant.now());

        // Store in Redis with 24 hour TTL (optional - can be removed for persistence)
        redisTemplate.opsForValue().set(key, doc, 24, TimeUnit.HOURS);
        
        return true;
    }

    @Override
    public Optional<StoredDocument> get(String objectId) {
        String key = KEY_PREFIX + objectId;
        StoredDocument doc = redisTemplate.opsForValue().get(key);
        return Optional.ofNullable(doc);
    }

    @Override
    public boolean delete(String objectId) {
        String key = KEY_PREFIX + objectId;
        Boolean deleted = redisTemplate.delete(key);
        return Boolean.TRUE.equals(deleted);
    }

    @Override
    public boolean exists(String objectId) {
        String key = KEY_PREFIX + objectId;
        Boolean exists = redisTemplate.hasKey(key);
        return Boolean.TRUE.equals(exists);
    }
}
