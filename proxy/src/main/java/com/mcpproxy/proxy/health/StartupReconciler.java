package com.mcpproxy.proxy.health;

import com.mcpproxy.proxy.client.McpBackendClient;
import com.mcpproxy.proxy.instance.CloudPhoneInstance;
import com.mcpproxy.proxy.instance.InstanceRepository;
import com.mcpproxy.proxy.instance.InstanceStatus;
import com.mcpproxy.proxy.route.InstanceCacheService;
import com.mcpproxy.proxy.service.SandboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动状态校准器（ApplicationRunner，系统启动后执行一次）。
 *
 * <p>功能：proxy 重启后，扫描实例表中所有<b>非 ready 且未退订</b>的实例并检查一遍状态，
 * 防止"重启前正在创建的 sandbox"永久卡在中间态：
 * <ul>
 *   <li>PREPARING：恢复后台看守线程（继续每 3s 轮询 ShowProgress，直到终态或 900s 超时）；</li>
 *   <li>CREATED / FAILED：若已有云机地址，做一次 healthz 检查并<b>更新实例状态</b>——
 *       判活 → 校准为 NORMAL（ready）并清除失败原因；判死 → 仅回写 healthy=false。</li>
 * </ul>
 *
 * <p>开发思路：状态持久化在 MySQL（权威），Redis 只是镜像——重启后库里的中间态不丢，
 * 缺的只是"正在跑的轮询线程"，本类负责把它们接回来。
 *
 * @author hubin
 * @since 2026-08-05
 */
@Component
public class StartupReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupReconciler.class);

    private final InstanceRepository repository;
    private final InstanceCacheService instanceCache;
    private final SandboxService sandboxService;
    private final McpBackendClient backendClient;

    public StartupReconciler(InstanceRepository repository,
                             InstanceCacheService instanceCache,
                             SandboxService sandboxService,
                             McpBackendClient backendClient) {
        this.repository = repository;
        this.instanceCache = instanceCache;
        this.sandboxService = sandboxService;
        this.backendClient = backendClient;
    }

    /**
     * 启动校准主流程。
     *
     * <p>伪代码：
     * <pre>
     *   candidates = 全部实例中 status ∉ {NORMAL, DELETED}
     *   for each:
     *     PREPARING -> resumeWatcher（恢复后台轮询）
     *     其它(CREATED/FAILED) 且有 backendUrl -> healthz 检查一次，healthy 变化则落库+缓存
     *   log 汇总数量
     * </pre>
     */
    @Override
    public void run(ApplicationArguments args) {
        List<CloudPhoneInstance> candidates = repository.findAll().stream()
                .filter(e -> e.getStatus() != InstanceStatus.NORMAL && e.getStatus() != InstanceStatus.DELETED)
                .toList();
        int resumed = 0;
        int checked = 0;
        for (CloudPhoneInstance entity : candidates) {
            if (entity.getStatus() == InstanceStatus.PREPARING) {
                sandboxService.resumeWatcher(entity.getUid(), entity.getInstanceId());
                resumed++;
            } else if (entity.getBackendUrl() != null) {
                boolean alive = backendClient.healthCheck(entity.getBackendUrl());
                entity.setHealthy(alive);
                if (alive) {
                    // healthz 判活通过：CREATED/FAILED 一律校准为 NORMAL（ready），清除失败原因
                    entity.setStatus(InstanceStatus.NORMAL);
                    entity.setStatusReason(null);
                }
                repository.save(entity);
                instanceCache.put(entity);
                checked++;
            }
        }
        log.info("startup reconcile: {} non-ready instances found, {} watchers resumed, {} healthz checked",
                candidates.size(), resumed, checked);
    }
}
