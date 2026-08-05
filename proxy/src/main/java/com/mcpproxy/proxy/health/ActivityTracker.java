package com.mcpproxy.proxy.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实例活跃度追踪器（内存态）。
 *
 * <p>功能：判断某个实例当前是否"活跃"，供 HealthCheckService 决定要不要对其探活——
 * 只有活跃的实例才值得每 30s 调 healthz（需求：长连接存续期，或最近一次 MCP 请求后 3 分钟内）。
 *
 * <p>开发思路：
 * <ul>
 *   <li>两个维度都算活跃：① SSE/WS 长连接数 > 0；② 距最近一次 MCP 请求 ≤ 3min（滑动窗口）；</li>
 *   <li>纯内存 ConcurrentHashMap，不落库——活跃度是瞬态信号，重启丢失无副作用
 *       （顶多漏一轮探活，healthy 字段仍在库里）；</li>
 *   <li>连接计数用 AtomicInteger，SSE/WS 开关成对调用，异常路径也兜底扣减。</li>
 * </ul>
 *
 * @author hubin
 * @since 2026-08-04
 */
@Component
public class ActivityTracker {

    /** 请求活跃窗口：默认 3 分钟（healthcheck.activity-window-ms 可配） */
    private final long activityWindowMillis;
    /** instanceId -> 最近一次 MCP 请求时间戳（ms） */
    private final Map<String, Long> lastActivity = new ConcurrentHashMap<>();
    /** instanceId -> 当前打开的长连接数（SSE + WS） */
    private final Map<String, AtomicInteger> openConnections = new ConcurrentHashMap<>();

    public ActivityTracker(@Value("${healthcheck.activity-window-ms:180000}") long activityWindowMillis) {
        this.activityWindowMillis = activityWindowMillis;
    }

    /** 记录一次 MCP 请求（streamable-http /message / WS 帧都会调用），刷新活跃时间 */
    public void recordRequest(String instanceId) {
        lastActivity.put(instanceId, System.currentTimeMillis());
    }

    /** 长连接建立（SSE/WS）：计数 +1，同时算一次请求刷新活跃时间 */
    public void connectionOpened(String instanceId) {
        openConnections.computeIfAbsent(instanceId, k -> new AtomicInteger()).incrementAndGet();
        recordRequest(instanceId);
    }

    /** 长连接关闭：计数 -1（无计数时忽略，容错开关不成对） */
    public void connectionClosed(String instanceId) {
        AtomicInteger count = openConnections.get(instanceId);
        if (count != null) {
            count.decrementAndGet();
        }
    }

    /**
     * 是否活跃。
     *
     * <p>伪代码：openConnections > 0 -> true；否则 lastActivity 在窗口内 -> true。
     */
    public boolean isActive(String instanceId) {
        AtomicInteger count = openConnections.get(instanceId);
        if (count != null && count.get() > 0) {
            return true;
        }
        Long last = lastActivity.get(instanceId);
        return last != null && System.currentTimeMillis() - last <= activityWindowMillis;
    }
}
