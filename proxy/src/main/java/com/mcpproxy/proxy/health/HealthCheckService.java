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

    @Scheduled(fixedDelayString = "${healthcheck.interval-ms:30000}", initialDelayString = "${healthcheck.interval-ms:30000}")
    public void scheduledCheck() {
        checkActiveInstances();
    }

    public void checkActiveInstances() {
        List<CloudPhoneInstance> instances = repository.findByStatus(InstanceStatus.NORMAL);
        for (CloudPhoneInstance instance : instances) {
            if (activityTracker.isActive(instance.getInstanceId())) {
                checkOne(instance.getInstanceId());
            }
        }
    }

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
