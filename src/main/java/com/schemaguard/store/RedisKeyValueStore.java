package com.schemaguard.store;

import com.schemaguard.model.StoredDocument;
import com.schemaguard.util.EtagUtil;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

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
        Boolean exists = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            return false;
        }
        String etag = EtagUtil.sha256Etag(jsonString);
        StoredDocument doc = new StoredDocument(objectId, jsonString, etag, Instant.now());
        // No TTL — data persists until explicitly deleted or Redis is flushed
        redisTemplate.opsForValue().set(key, doc);
        return true;
    }

    @Override
    public Optional<StoredDocument> get(String objectId) {
        String key = KEY_PREFIX + objectId;
        StoredDocument doc = redisTemplate.opsForValue().get(key);
        return Optional.ofNullable(doc);
    }

    @Override
    public boolean update(String objectId, String jsonString) {
        String key = KEY_PREFIX + objectId;
        Boolean exists = redisTemplate.hasKey(key);
        if (!Boolean.TRUE.equals(exists)) {
            return false;
        }
        String etag = EtagUtil.sha256Etag(jsonString);
        StoredDocument updated = new StoredDocument(objectId, jsonString, etag, Instant.now());
        // No TTL — data persists until explicitly deleted
        redisTemplate.opsForValue().set(key, updated);
        return true;
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
