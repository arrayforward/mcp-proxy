package com.mcpproxy.proxy.route;

import com.mcpproxy.proxy.client.KooPhoneClient;
import com.mcpproxy.proxy.instance.CloudPhoneInstance;
import com.mcpproxy.proxy.instance.InstanceRepository;
import com.mcpproxy.proxy.web.ApiException;
import org.springframework.stereotype.Service;

@Service
public class RouteService {

    private final InstanceRepository repository;
    private final KooPhoneClient kooPhoneClient;
    private final RouteCacheService cacheService;

    public RouteService(InstanceRepository repository, KooPhoneClient kooPhoneClient, RouteCacheService cacheService) {
        this.repository = repository;
        this.kooPhoneClient = kooPhoneClient;
        this.cacheService = cacheService;
    }

    public RouteInfo resolveRoute(String instanceId) {
        var cached = cacheService.get(instanceId);
        if (cached.isPresent()) {
            return cached.get();
        }
        CloudPhoneInstance entity = repository.findById(instanceId)
                .orElseThrow(() -> new ApiException(404, "KOOPHONE.API.4001", "instance not found: " + instanceId));
        if (entity.getMcpIp() == null || entity.getMcpPort() == null) {
            KooPhoneClient.AccessInfo accessInfo = kooPhoneClient.fetchAccessInfo(instanceId);
            entity.setMcpIp(accessInfo.ip());
            entity.setMcpPort(accessInfo.mcpPort());
            repository.save(entity);
        }
        RouteInfo route = new RouteInfo(instanceId, entity.getMcpIp(), entity.getMcpPort());
        cacheService.put(route);
        return route;
    }

    public void evict(String instanceId) {
        cacheService.evict(instanceId);
    }
}
