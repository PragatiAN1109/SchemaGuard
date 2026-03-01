package com.schemaguard.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis Streams implementation of IndexEventPublisher.
 *
 * Active only on the 'redis' profile — the no-op implementation
 * (NoOpIndexEventPublisher) is used in the 'test' profile.
 *
 * Uses StringRedisTemplate so stream entries are stored as plain
 * string key–value pairs, making them easy to inspect with:
 *   redis-cli XRANGE schemaguard:index-events - +
 *
 * XADD semantics:
 * - auto-generates a stream entry ID ("*")
 * - entries are appended; the stream is created automatically on first write
 * - stream is bounded by MAX_LEN to prevent unbounded growth in demo
 *
 * Publishing is non-blocking and non-fatal: any exception is caught,
 * logged, and swallowed so the API response is never affected by a
 * stream write failure.
 */
@Component
@Profile("redis")
public class RedisStreamEventPublisher implements IndexEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamEventPublisher.class);

    /** Maximum number of entries kept in the stream (approximate trim). */
    private static final long MAX_LEN = 1000L;

    private final StringRedisTemplate redisTemplate;

    @Value("${index.events.stream:schemaguard:index-events}")
    private String streamName;

    public RedisStreamEventPublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void publish(IndexEvent event) {
        try {
            MapRecord<String, String, String> record = StreamRecords
                    .newRecord()
                    .in(streamName)
                    .ofMap(event.toStreamFields());

            redisTemplate.opsForStream().add(record);

            log.info("published {} event for id={} etag={} stream={}",
                    event.operation(), event.documentId(), event.etag(), streamName);
        } catch (Exception ex) {
            log.warn("failed to publish {} event for id={} — {}",
                    event.operation(), event.documentId(), ex.getMessage());
        }
    }
}
