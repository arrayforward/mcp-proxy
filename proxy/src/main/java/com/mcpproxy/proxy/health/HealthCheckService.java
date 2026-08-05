package com.mcpproxy.proxy.health;

import com.mcpproxy.proxy.client.McpBackendClient;
import com.mcpproxy.proxy.instance.CloudPhoneInstance;
import com.mcpproxy.proxy.instance.InstanceRepository;
import com.mcpproxy.proxy.instance.InstanceStatus;
import com.mcpproxy.proxy.route.RouteInfo;
import com.mcpproxy.proxy.route.RouteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 健康检查服务（定时探活）。
 *
 * <p>功能：对"活跃"的 NORMAL 实例每 30s 调一次云机 healthz，把结果写入
 * t_cloud_phone_instance.healthy，实现"连接是否正常"的持续观测（flows.md §6.1）。
 *
 * <p>开发思路：
 * <ul>
 *   <li>@Scheduled(fixedDelay) 而非 fixedRate：上一轮没跑完不叠加，云机慢时不堆积；</li>
 *   <li>只对活跃实例探活（ActivityTracker 判定），冷实例零开销；</li>
 *   <li>healthy 只在变化时 UPDATE + 打日志，避免每 30s 无意义写库；</li>
 *   <li>单个实例探活失败不影响其它实例（checkOne 独立事务）。</li>
 * </ul>
 *
 * @author hubin
 * @since 2026-08-04
 */
@Service
public class HealthCheckService {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckService.class);

    private final InstanceRepository repository;
    private final RouteService routeService;
    private final McpBackendClient backendClient;
    private final ActivityTracker activityTracker;

    public HealthCheckService(InstanceRepository repository,
                              RouteService routeService,
                              McpBackendClient backendClient,
                              ActivityTracker activityTracker) {
        this.repository = repository;
        this.routeService = routeService;
        this.backendClient = backendClient;
        this.activityTracker = activityTracker;
    }

    /**
     * 定时入口：默认每 30s 一轮（healthcheck.interval-ms 可配，e2e 可调小）。
     * 首次延迟同间隔，等应用就绪后再开始。
     */
    @Scheduled(fixedDelayString = "${healthcheck.interval-ms:30000}", initialDelayString = "${healthcheck.interval-ms:30000}")
    public void scheduledCheck() {
        checkActiveInstances();
    }

    /**
     * 扫一轮：所有 NORMAL 实例中挑出活跃的逐个探活。
     *
     * <p>伪代码：findByStatus(NORMAL) -> filter isActive -> forEach checkOne。
     * 抽成 public 便于 e2e 直接调用而不必等 30s。
     */
    public void checkActiveInstances() {
        List<CloudPhoneInstance> instances = repository.findByStatus(InstanceStatus.NORMAL);
        for (CloudPhoneInstance instance : instances) {
            if (activityTracker.isActive(instance.getInstanceId())) {
                checkOne(instance.getInstanceId());
            }
        }
    }

    /**
     * 探活单个实例并回写 healthy。
     *
     * <p>伪代码：
     * <pre>
     *   route = RouteService.resolveRoute（缓存/库/E4 三级解析）
     *   alive = GET route/healthz 是否 2xx
     *   healthy 变化 -> UPDATE + log
     *   return alive
     * </pre>
     */
    @Transactional
    public boolean checkOne(String instanceId) {
        RouteInfo route = routeService.resolveRoute(instanceId);
        boolean alive = backendClient.healthCheck(route.backendBaseUrl());
        repository.findById(instanceId).ifPresent(entity -> {
            if (entity.isHealthy() != alive) {
                entity.setHealthy(alive);
                repository.save(entity);
                log.info("instance {} healthy -> {}", instanceId, alive);
            }
        });
        return alive;
    }
}
