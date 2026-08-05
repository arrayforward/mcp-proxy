package com.mcpproxy.proxy;

import com.mcpproxy.proxy.instance.CloudPhoneInstance;
import com.mcpproxy.proxy.instance.InstanceStatus;
import com.mcpproxy.proxy.route.InstanceCacheService;
import com.mcpproxy.proxy.service.InstanceService;
import com.mcpproxy.proxy.service.SandboxService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SandboxService 后台看守线程单元测试（小间隔/小超时注入，验证两条退出路径）。
 *
 * <p>覆盖：① 实例就绪 -> watcher 自毁且不调 markTimeout；② 超时 -> 调 markTimeout 置 FAILED(timeout)。
 *
 * @author hubin
 * @since 2026-08-05
 */
class SandboxWatcherTest {

    private final InstanceService instanceService = mock(InstanceService.class);
    private final InstanceCacheService instanceCache = mock(InstanceCacheService.class);
    /** 间隔 50ms、超时 500ms，让两条路径都能在毫秒级跑完 */
    private final SandboxService sandboxService =
            new SandboxService(instanceService, instanceCache, 50L, 500L);

    private CloudPhoneInstance entity(int status) {
        CloudPhoneInstance e = new CloudPhoneInstance();
        e.setInstanceId("sandbox-1");
        e.setUid("user-10001");
        e.setStatus(status);
        return e;
    }

    @Test
    void watcherExitsWhenReadyWithoutTimeout() {
        when(instanceService.create(eq("user-10001"), anyMap())).thenReturn(Map.of(
                "orderId", "CS1",
                "instanceInfos", List.of(Map.of("instanceId", "sandbox-1", "instanceName", "koophone-00001"))));
        when(instanceService.requireOwner(anyString(), eq("sandbox-1"))).thenReturn(entity(InstanceStatus.NORMAL));

        sandboxService.createSandbox("user-10001", Map.of("os", "AOSP14"));

        verify(instanceService, timeout(2000).atLeastOnce()).progress("user-10001", "sandbox-1");
        verify(instanceService, never()).markTimeout(anyString(), anyString());
    }

    @Test
    void watcherMarksTimeoutWhenNeverReady() {
        when(instanceService.create(eq("user-10001"), anyMap())).thenReturn(Map.of(
                "orderId", "CS1",
                "instanceInfos", List.of(Map.of("instanceId", "sandbox-1", "instanceName", "koophone-00001"))));
        when(instanceService.requireOwner(anyString(), eq("sandbox-1")))
                .thenReturn(entity(InstanceStatus.PREPARING));

        sandboxService.createSandbox("user-10001", Map.of("os", "AOSP14"));

        verify(instanceService, timeout(3000)).markTimeout("user-10001", "sandbox-1");
    }
}
