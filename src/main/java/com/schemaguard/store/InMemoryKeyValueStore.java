package com.schemaguard.store;

import com.schemaguard.model.StoredDocument;
import com.schemaguard.util.EtagUtil;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Primary
@Profile("!redis")
public class InMemoryKeyValueStore implements KeyValueStore {

    private final ConcurrentHashMap<String, StoredDocument> map = new ConcurrentHashMap<>();

    @Override
    public boolean create(String objectId, String jsonString) {
        String etag = EtagUtil.sha256Etag(jsonString);
        StoredDocument doc = new StoredDocument(objectId, jsonString, etag, Instant.now());
        return map.putIfAbsent(objectId, doc) == null;
    }

    @Override
    public Optional<StoredDocument> get(String objectId) {
        return Optional.ofNullable(map.get(objectId));
    }

    @Override
    public boolean update(String objectId, String jsonString) {
        if (!map.containsKey(objectId)) {
            return false;
        }
        String etag = EtagUtil.sha256Etag(jsonString);
        StoredDocument updated = new StoredDocument(objectId, jsonString, etag, Instant.now());
        map.put(objectId, updated);
        return true;
    }

    @Override
    public boolean delete(String objectId) {
        return map.remove(objectId) != null;
    }

    @Override
    public boolean exists(String objectId) {
        return map.containsKey(objectId);
    }
}
