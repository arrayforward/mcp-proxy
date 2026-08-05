package com.mcpproxy.proxy.route;

import com.mcpproxy.proxy.instance.CloudPhoneInstance;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * 实例状态缓存（Redis 滚动缓存）。
 *
 * <p>功能：缓存实例的关键状态（status / healthy / waitingCount / mcp_ip / mcp_port），
 * 与 RouteCacheService（只存路由地址）互补——本缓存存"状态"，供 sandbox_status 等
 * 读路径快速命中，减轻 MySQL 压力。
 *
 * <p>开发思路（与路由缓存一致的滚动策略）：
 * <ul>
 *   <li>写路径：InstanceService 每次状态变更（create/prepare/progress/delete）落库后
 *       同步 put 缓存——MySQL 是权威存储，缓存是镜像；</li>
 *   <li>读路径：命中即续期 30min（滚动/sliding TTL），活跃实例缓存不过期；</li>
 *   <li>退订/删除时 evict，防脏读；</li>
 *   <li>value 用 "|" 分隔的紧凑字符串，null 字段写 "-"，避免 JSON 序列化开销。</li>
 * </ul>
 *
 * @author hubin
 * @since 2026-08-05
 */
@Service
public class InstanceCacheService {

    /** 滚动 TTL：30 分钟，读命中即续期 */
    public static final Duration TTL = Duration.ofMinutes(30);

    private static final String KEY_PREFIX = "mcp:instance:";
    private static final String NULL_MARK = "-";

    private final StringRedisTemplate redisTemplate;

    public InstanceCacheService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 缓存的实例状态快照（字段与 CloudPhoneInstance 的关键状态对齐） */
    public record CachedState(int status, boolean healthy, int waitingCount, String mcpIp, Integer mcpPort) {
    }

    /**
     * 读缓存（命中即滚动续期 30min）。
     *
     * <p>伪代码：GET key -> null: empty；否则 EXPIRE 30min -> 按 "|" 拆 5 段还原 CachedState。
     */
    public Optional<CachedState> get(String instanceId) {
        String key = key(instanceId);
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return Optional.empty();
        }
        redisTemplate.expire(key, TTL);   // 滚动缓存核心：读命中续期
        String[] parts = value.split("\\|", -1);
        return Optional.of(new CachedState(
                Integer.parseInt(parts[0]),
                Boolean.parseBoolean(parts[1]),
                Integer.parseInt(parts[2]),
                NULL_MARK.equals(parts[3]) ? null : parts[3],
                NULL_MARK.equals(parts[4]) ? null : Integer.valueOf(parts[4])));
    }

    /**
     * 写缓存（实例状态变更落库后调用，保持缓存与库一致）。
     *
     * <p>序列化格式：status|healthy|waitingCount|mcpIp|mcpPort（null 字段记 "-"）。
     */
    public void put(CloudPhoneInstance entity) {
        String value = entity.getStatus() + "|"
                + entity.isHealthy() + "|"
                + entity.getWaitingCount() + "|"
                + (entity.getMcpIp() == null ? NULL_MARK : entity.getMcpIp()) + "|"
                + (entity.getMcpPort() == null ? NULL_MARK : entity.getMcpPort());
        redisTemplate.opsForValue().set(key(entity.getInstanceId()), value, TTL);
    }

    /** 失效缓存（退订时调用） */
    public void evict(String instanceId) {
        redisTemplate.delete(key(instanceId));
    }

    private String key(String instanceId) {
        return KEY_PREFIX + instanceId;
    }
}
