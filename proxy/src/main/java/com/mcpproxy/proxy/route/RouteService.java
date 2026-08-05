package com.mcpproxy.proxy.route;

import com.mcpproxy.proxy.client.KooPhoneClient;
import com.mcpproxy.proxy.instance.CloudPhoneInstance;
import com.mcpproxy.proxy.instance.InstanceRepository;
import com.mcpproxy.proxy.web.ApiException;
import org.springframework.stereotype.Service;

/**
 * 路由解析服务：instanceId -> 云机地址（ip:mcpPort）。
 *
 * <p>功能：MCP 转发与 access-info 查询共用的寻址入口。
 *
 * <p>开发思路（三级解析，见 design.md §3.3）：
 * <ol>
 *   <li>Redis 缓存（key=mcp:route:{id}，30min，命中即续期——滑动过期，热实例永不过期）；</li>
 *   <li>MySQL 持久层（有值则回填缓存）；</li>
 *   <li>E4 fetchAccessInfo 回调云控制面获取，先落 MySQL 再写缓存（保证重启不丢）。</li>
 * </ol>
 *
 * @author hubin
 * @since 2026-08-04
 */
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

    /**
     * 解析实例路由。
     *
     * <p>伪代码：
     * <pre>
     *   cache.get(id)        -> 命中: 直接返回（get 内部已续期 30min）
     *   db.findById(id)      -> 不存在: 404
     *   db 无 ip/port        -> fetchAccessInfo 获取 -> UPDATE 落库
     *   cache.put + return
     * </pre>
     *
     * @throws ApiException 404 KOOPHONE.API.4001 实例不存在
     */
    public RouteInfo resolveRoute(String instanceId) {
        var cached = cacheService.get(instanceId);
        if (cached.isPresent()) {
            return cached.get();
        }
        CloudPhoneInstance entity = repository.findById(instanceId)
                .orElseThrow(() -> new ApiException(404, "KOOPHONE.API.4001", "instance not found: " + instanceId));
        if (entity.getMcpIp() == null || entity.getMcpPort() == null) {
            // 缓存与库都没有：回调 E4 接口获取并落库（文档 external-api.md §5 约定）
            KooPhoneClient.AccessInfo accessInfo = kooPhoneClient.fetchAccessInfo(instanceId);
            entity.setMcpIp(accessInfo.ip());
            entity.setMcpPort(accessInfo.mcpPort());
            repository.save(entity);
        }
        RouteInfo route = new RouteInfo(instanceId, entity.getMcpIp(), entity.getMcpPort());
        cacheService.put(route);
        return route;
    }

    /** 退订时主动失效缓存，防止已退订实例还能被路由 */
    public void evict(String instanceId) {
        cacheService.evict(instanceId);
    }
}
