package com.mcpproxy.proxy.route;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * 路由缓存（Redis）。
 *
 * <p>功能：缓存 instanceId -> "ip:port" 字符串，TTL 30 分钟。
 *
 * <p>开发思路：
 * <ul>
 *   <li><b>滑动过期</b>：每次 get 命中都重新 expire 30min——活跃实例的缓存永不过期，
 *       冷实例 30min 后自动清理（对应需求"每次访问可以增加这个时间"）；</li>
 *   <li>value 用最简单的 "ip:port" 拼串，lastIndexOf(':') 切分，避免引入 JSON 序列化开销；</li>
 *   <li>只存路由，不存状态，状态以 MySQL 为准。</li>
 * </ul>
 *
 * @author hubin
 * @since 2026-08-04
 */
@Service
public class RouteCacheService {

    /** 滑动 TTL：30 分钟 */
    public static final Duration TTL = Duration.ofMinutes(30);

    private static final String KEY_PREFIX = "mcp:route:";

    private final StringRedisTemplate redisTemplate;

    public RouteCacheService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 读缓存并续期。
     *
     * <p>伪代码：GET key -> null 返回 empty；否则 EXPIRE key 30min（续期）-> 拆串返回 RouteInfo。
     */
    public Optional<RouteInfo> get(String instanceId) {
        String key = key(instanceId);
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return Optional.empty();
        }
        redisTemplate.expire(key, TTL);   // 命中即续期：滑动过期的核心
        int idx = value.lastIndexOf(':');
        return Optional.of(new RouteInfo(instanceId, value.substring(0, idx), Integer.parseInt(value.substring(idx + 1))));
    }

    /** 写缓存（带 30min TTL），在 RouteService 解析成功 / E4 回调落库后调用 */
    public void put(RouteInfo route) {
        redisTemplate.opsForValue().set(key(route.instanceId()), route.ip() + ":" + route.mcpPort(), TTL);
    }

    /** 删除缓存（退订时调用） */
    public void evict(String instanceId) {
        redisTemplate.delete(key(instanceId));
    }

    private String key(String instanceId) {
        return KEY_PREFIX + instanceId;
    }
}
