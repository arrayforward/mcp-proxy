package com.mcpproxy.proxy.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ActivityTracker {

    private final long activityWindowMillis;
    private final Map<String, Long> lastActivity = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> openConnections = new ConcurrentHashMap<>();

    public ActivityTracker(@Value("${healthcheck.activity-window-ms:180000}") long activityWindowMillis) {
        this.activityWindowMillis = activityWindowMillis;
    }

    public void recordRequest(String instanceId) {
        lastActivity.put(instanceId, System.currentTimeMillis());
    }

    public void connectionOpened(String instanceId) {
        openConnections.computeIfAbsent(instanceId, k -> new AtomicInteger()).incrementAndGet();
        recordRequest(instanceId);
    }

    public void connectionClosed(String instanceId) {
        AtomicInteger count = openConnections.get(instanceId);
        if (count != null) {
            count.decrementAndGet();
        }
    }

    public boolean isActive(String instanceId) {
        AtomicInteger count = openConnections.get(instanceId);
        if (count != null && count.get() > 0) {
            return true;
        }
        Long last = lastActivity.get(instanceId);
        return last != null && System.currentTimeMillis() - last <= activityWindowMillis;
    }
}
