package com.mcpproxy.proxy.route;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
public class RouteCacheService {

    public static final Duration TTL = Duration.ofMinutes(30);

    private static final String KEY_PREFIX = "mcp:route:";

    private final StringRedisTemplate redisTemplate;

    public RouteCacheService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Optional<RouteInfo> get(String instanceId) {
        String key = key(instanceId);
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return Optional.empty();
        }
        redisTemplate.expire(key, TTL);
        int idx = value.lastIndexOf(':');
        return Optional.of(new RouteInfo(instanceId, value.substring(0, idx), Integer.parseInt(value.substring(idx + 1))));
    }

    public void put(RouteInfo route) {
        redisTemplate.opsForValue().set(key(route.instanceId()), route.ip() + ":" + route.mcpPort(), TTL);
    }

    public void evict(String instanceId) {
        redisTemplate.delete(key(instanceId));
    }

    private String key(String instanceId) {
        return KEY_PREFIX + instanceId;
    }
}
