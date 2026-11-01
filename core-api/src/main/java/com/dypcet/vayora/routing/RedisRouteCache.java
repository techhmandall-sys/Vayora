package com.dypcet.vayora.routing;

import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis-backed. If Redis is down every lookup misses and nothing is stored. */
@Component
public class RedisRouteCache implements RouteCache {

    private static final Logger log = LoggerFactory.getLogger(RedisRouteCache.class);
    private static final String PREFIX = "osrm:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisRouteCache(StringRedisTemplate redis,
            @Value("${vayora.osrm.cache-ttl}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public Optional<String> get(String key) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(PREFIX + key));
        } catch (RuntimeException e) {
            log.warn("Route cache unavailable: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, String value) {
        try {
            redis.opsForValue().set(PREFIX + key, value, ttl);
        } catch (RuntimeException e) {
            log.warn("Route not cached: {}", e.getMessage());
        }
    }
}
