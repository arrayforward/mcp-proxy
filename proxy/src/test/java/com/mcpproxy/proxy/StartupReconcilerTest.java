package com.mcpproxy.proxy;

import com.mcpproxy.proxy.client.McpBackendClient;
import com.mcpproxy.proxy.health.StartupReconciler;
import com.mcpproxy.proxy.instance.CloudPhoneInstance;
import com.mcpproxy.proxy.instance.InstanceRepository;
import com.mcpproxy.proxy.instance.InstanceStatus;
import com.mcpproxy.proxy.route.InstanceCacheService;
import com.mcpproxy.proxy.service.SandboxService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StartupReconciler 单元测试（全 Mock，不依赖 MySQL/Redis）。
 *
 * <p>覆盖三条分支：PREPARING 恢复 watcher；FAILED 判活校准为 NORMAL；NORMAL/DELETED 跳过。
 *
 * @author hubin
 * @since 2026-08-05
 */
class StartupReconcilerTest {

    private final InstanceRepository repository = mock(InstanceRepository.class);
    private final InstanceCacheService instanceCache = mock(InstanceCacheService.class);
    private final SandboxService sandboxService = mock(SandboxService.class);
    private final McpBackendClient backendClient = mock(McpBackendClient.class);
    private final StartupReconciler reconciler =
            new StartupReconciler(repository, instanceCache, sandboxService, backendClient);

    private CloudPhoneInstance entity(String id, int status) {
        CloudPhoneInstance e = new CloudPhoneInstance();
        e.setInstanceId(id);
        e.setUid("user-10001");
        e.setStatus(status);
        return e;
    }

    @Test
    void preparingInstanceResumesWatcher() {
        when(repository.findAll()).thenReturn(List.of(entity("i-preparing", InstanceStatus.PREPARING)));
        reconciler.run(new DefaultApplicationArguments());
        verify(sandboxService).resumeWatcher("user-10001", "i-preparing");
    }

    @Test
    void failedInstanceAliveIsCalibratedToNormal() {
        CloudPhoneInstance failed = entity("i-failed", InstanceStatus.FAILED);
        failed.setBackendUrl("http://127.0.0.1:9091");
        failed.setStatusReason("healthz-failed");
        when(repository.findAll()).thenReturn(List.of(failed));
        when(backendClient.healthCheck("http://127.0.0.1:9091")).thenReturn(true);

        reconciler.run(new DefaultApplicationArguments());

        assertEquals(InstanceStatus.NORMAL, failed.getStatus());
        assertTrue(failed.isHealthy());
        assertNull(failed.getStatusReason());
        verify(repository).save(failed);
        verify(instanceCache).put(failed);
    }

    @Test
    void readyAndDeletedInstancesAreSkipped() {
        when(repository.findAll()).thenReturn(List.of(
                entity("i-normal", InstanceStatus.NORMAL),
                entity("i-deleted", InstanceStatus.DELETED)));
        reconciler.run(new DefaultApplicationArguments());
        verify(sandboxService, never()).resumeWatcher(any(), any());
        verify(backendClient, never()).healthCheck(any());
    }
}
