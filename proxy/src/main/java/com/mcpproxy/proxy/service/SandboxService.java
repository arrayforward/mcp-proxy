package com.mcpproxy.proxy.service;

import com.mcpproxy.proxy.instance.CloudPhoneInstance;
import com.mcpproxy.proxy.instance.InstanceStatus;
import com.mcpproxy.proxy.route.InstanceCacheService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sandbox 编排服务：对 Agent 暴露的一键式云手机沙箱接口（异步 + 后台看守线程模式）。
 *
 * <p>功能：把华为云实例生命周期包装成三个 Agent 接口（对齐阿里云 AgentBay sandbox 语义）：
 * <ul>
 *   <li>{@code create_sandbox}：异步受理——创建实例 + 触发准备后立即返回，
 *       同时启动一个<b>后台看守线程</b>轮询 ShowProgress（真实华为开通约 1~5 分钟）；</li>
 *   <li>{@code sandbox_status}：纯读接口——Agent 轮询，从 Redis 滚动缓存 / MySQL 读状态，
 *       ready / failed / timeout / initializing；</li>
 *   <li>{@code kill_sandbox}：退订释放（包装 DeleteInstance）。</li>
 * </ul>
 *
 * <p>后台看守线程模型（核心设计）：
 * <ul>
 *   <li>每次 create 提交一个 watcher：每隔 {@code sandbox.progress-interval-ms}（默认 3000ms）
 *       调一次 ShowProgress 推进内部准备进度（含 E4 落库 + healthz 判活 + 状态落库 + 缓存镜像）；</li>
 *   <li>实例到达终态（NORMAL/FAILED）→ 线程自行退出；</li>
 *   <li>超过 {@code sandbox.progress-timeout-ms}（默认 900000ms=15min）仍未就绪 →
 *       把实例置 FAILED 且 status_reason=timeout，线程同样自行退出，
 *       Agent 查 sandbox_status 得到 "timeout" 提示创建超时；</li>
 *   <li>状态读路径：Redis 滚动缓存（mcp:instance:{id}，30min，命中续期）优先，
 *       未命中回源 MySQL 并回填。</li>
 * </ul>
 *
 * @author hubin
 * @since 2026-08-05
 */
@Service
public class SandboxService {

    private static final Logger log = LoggerFactory.getLogger(SandboxService.class);

    private final InstanceService instanceService;
    private final InstanceCacheService instanceCache;
    /** watcher 线程池：每个进行中的 sandbox 创建占一个线程，终态/超时即归还 */
    private final ExecutorService watcherExecutor = Executors.newCachedThreadPool();
    /** ShowProgress 轮询间隔（默认 3 秒，真实环境对齐建议轮询频率） */
    private final long progressIntervalMillis;
    /** 创建总超时（默认 900 秒），超时置 FAILED(timeout) */
    private final long progressTimeoutMillis;

    public SandboxService(InstanceService instanceService,
                          InstanceCacheService instanceCache,
                          @Value("${sandbox.progress-interval-ms:3000}") long progressIntervalMillis,
                          @Value("${sandbox.progress-timeout-ms:900000}") long progressTimeoutMillis) {
        this.instanceService = instanceService;
        this.instanceCache = instanceCache;
        this.progressIntervalMillis = progressIntervalMillis;
        this.progressTimeoutMillis = progressTimeoutMillis;
    }

    /** 应用关闭时停止所有 watcher 线程 */
    @PreDestroy
    void shutdown() {
        watcherExecutor.shutdownNow();
    }

    /**
     * create_sandbox：异步受理沙箱开通，并启动后台看守线程。
     *
     * <p>伪代码：
     * <pre>
     *   create（INSERT CREATED + 缓存镜像）
     *   prepare（PREPARING, waitingCount=3 + 缓存镜像）
     *   提交 watcher 线程（见 watchInstance）
     *   return {sandbox_id, instance_name, sandbox_status:"initializing"}
     * </pre>
     *
     * @param uid     归属用户（x-auth-token 校验结果）
     * @param request 华为风格订阅请求体（os/instanceSkuId/regionId 等）
     */
    public Map<String, Object> createSandbox(String uid, Map<String, Object> request) {
        Map<String, Object> created = instanceService.create(uid, request);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> infos = (List<Map<String, Object>>) created.get("instanceInfos");
        String instanceId = (String) infos.get(0).get("instanceId");
        String instanceName = (String) infos.get(0).get("instanceName");

        instanceService.prepare(uid, List.of(instanceId));
        resumeWatcher(uid, instanceId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sandbox_id", instanceId);
        result.put("instance_name", instanceName);
        result.put("sandbox_status", "initializing");
        return result;
    }

    /**
     * 为指定实例（重新）启动后台看守线程。
     *
     * <p>用途：① create_sandbox 时启动；② 系统启动 reconciler 发现 PREPARING 实例时恢复轮询。
     */
    public void resumeWatcher(String uid, String instanceId) {
        watcherExecutor.submit(() -> watchInstance(uid, instanceId));
    }

    /**
     * 后台看守线程：每 progressIntervalMillis 轮询一次 ShowProgress，直到终态或超时。
     *
     * <p>伪代码：
     * <pre>
     *   deadline = now + progressTimeoutMillis
     *   loop while now < deadline:
     *     progress（waitingCount--；归零时内部完成 E4 落库 + healthz 判活 + 落库 + 缓存）
     *     status == NORMAL/FAILED -> return（线程自毁）
     *     sleep progressIntervalMillis
     *   超时 -> markTimeout（FAILED + reason=timeout + 缓存）-> return
     * </pre>
     */
    private void watchInstance(String uid, String instanceId) {
        long deadline = System.currentTimeMillis() + progressTimeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                instanceService.progress(uid, instanceId);
                CloudPhoneInstance entity = instanceService.requireOwner(uid, instanceId);
                if (entity.getStatus() == InstanceStatus.NORMAL || entity.getStatus() == InstanceStatus.FAILED) {
                    log.info("sandbox {} reached final status {}, watcher exits", instanceId, entity.getStatus());
                    return;   // 终态：线程自毁
                }
                Thread.sleep(progressIntervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("sandbox {} watcher aborted: {}", instanceId, e.getMessage());
                return;
            }
        }
        instanceService.markTimeout(uid, instanceId);   // 超时：FAILED + status_reason=timeout
        log.info("sandbox {} create timeout after {}ms, watcher exits", instanceId, progressTimeoutMillis);
    }

    /**
     * sandbox_status：纯读接口（Redis 滚动缓存优先，未命中回源 MySQL 并回填）。
     *
     * <p>状态映射：NORMAL -> ready（带 mcp 访问信息）；FAILED + timeout -> timeout；
     * FAILED -> failed；其余 -> initializing（带 waiting_count）。
     */
    public Map<String, Object> sandboxStatus(String uid, String sandboxId) {
        CloudPhoneInstance entity = instanceService.requireOwner(uid, sandboxId);   // 归属/存在性校验（404/403）
        var cached = instanceCache.get(sandboxId);
        if (cached.isEmpty()) {
            instanceCache.put(entity);   // 回源回填，下次读命中缓存
        }
        int status = cached.map(InstanceCacheService.CachedState::status).orElse(entity.getStatus());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sandbox_id", sandboxId);
        switch (status) {
            case InstanceStatus.NORMAL -> {
                result.put("sandbox_status", "ready");
                // 缓存优先（缓存与状态同事务写入），实体兜底，避免提交前窗口期读到 null
                String mcpIp = cached.map(InstanceCacheService.CachedState::mcpIp).orElse(entity.getMcpIp());
                Integer mcpPort = cached.map(InstanceCacheService.CachedState::mcpPort).orElse(entity.getMcpPort());
                result.put("healthy", cached.map(InstanceCacheService.CachedState::healthy).orElse(entity.isHealthy()));
                result.put("mcp_url", entity.getMcpUrl());
                result.put("mcp_ip", mcpIp != null ? mcpIp : entity.getMcpIp());
                result.put("mcp_port", mcpPort != null ? mcpPort : entity.getMcpPort());
            }
            case InstanceStatus.FAILED ->
                    result.put("sandbox_status", "timeout".equals(entity.getStatusReason()) ? "timeout" : "failed");
            default -> {
                result.put("sandbox_status", "initializing");
                result.put("waiting_count", cached.map(InstanceCacheService.CachedState::waitingCount)
                        .orElse(entity.getWaitingCount()));
            }
        }
        return result;
    }

    /**
     * kill_sandbox：关闭并释放云手机沙箱（包装 DeleteInstance）。
     *
     * <p>伪代码：requireOwner -> 置 DELETED + 缓存失效；路由缓存由 Controller 清除。
     */
    public void killSandbox(String uid, String sandboxId) {
        instanceService.delete(uid, List.of(sandboxId));
    }
}
