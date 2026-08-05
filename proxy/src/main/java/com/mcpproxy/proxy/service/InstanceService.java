package com.mcpproxy.proxy.service;

import com.mcpproxy.proxy.client.KooPhoneClient;
import com.mcpproxy.proxy.client.McpBackendClient;
import com.mcpproxy.proxy.instance.CloudPhoneInstance;
import com.mcpproxy.proxy.instance.InstanceRepository;
import com.mcpproxy.proxy.instance.InstanceStatus;
import com.mcpproxy.proxy.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 实例业务编排服务（事务边界）。
 *
 * <p>功能：云手机实例的全生命周期编排——订阅落库、列表查询、退订（逻辑删除）、
 * 批量准备、准备进度轮询，以及归属/状态校验。
 *
 * <p>开发思路：
 * <ul>
 *   <li>所有写操作加 {@code @Transactional}，保证"改状态 + 落库"原子性；</li>
 *   <li>状态机见 design.md §7：CREATED→PREPARING→NORMAL/FAILED→DELETED，
 *       本类是状态迁移的唯一入口；</li>
 *   <li>准备进度用 waitingCount 递减模拟真实排队（Mock 云控制面），归零时
 *       一次性完成「取访问信息(E4)→落库→healthz 判活→置 NORMAL/FAILED」；</li>
 *   <li>对外响应字段保持华为 KooPhone 风格（下划线命名、error_code 体系），
 *       便于后续无缝切换真实华为后端。</li>
 * </ul>
 *
 * @author hubin
 * @since 2026-08-04
 */
@Service
public class InstanceService {

    /** Mock 排队长度：prepare 后轮询 3 次即就绪（真实环境由云控制面排队时长决定） */
    private static final int PREPARE_WAITING_COUNT = 3;

    private final InstanceRepository repository;
    private final KooPhoneClient kooPhoneClient;
    private final McpBackendClient backendClient;
    private final String proxyBaseUrl;

    public InstanceService(InstanceRepository repository,
                           KooPhoneClient kooPhoneClient,
                           McpBackendClient backendClient,
                           @Value("${mcp.proxy-base-url:http://localhost:8080}") String proxyBaseUrl) {
        this.repository = repository;
        this.kooPhoneClient = kooPhoneClient;
        this.backendClient = backendClient;
        this.proxyBaseUrl = proxyBaseUrl;
    }

    /**
     * 订阅实例（CreateInstance）。
     *
     * <p>API 语义：对应华为 CreateInstance，按 count 批量开通，返回订单号 + 实例 ID 列表。
     *
     * <p>伪代码：
     * <pre>
     *   校验必填(os/instanceSkuId/regionId)，缺一 -> 400 KOOPHONE.API.1000
     *   orderId = kooPhoneClient.createOrderId()
     *   loop count 次:
     *     生成 instanceId/实例名，组装实体（状态 CREATED、mcp_url=网关入口、backendToken=随机）
     *     INSERT t_cloud_phone_instance
     *   return { orderId, instanceInfos[] }
     * </pre>
     *
     * @param uid     实例归属用户（来自 x-auth-token 校验结果）
     * @param request 华为风格订阅请求体（os/skus/region/count/bandSize...）
     * @return {orderId, instanceInfos:[{instanceId, instanceName}]}
     */
    @Transactional
    public Map<String, Object> create(String uid, Map<String, Object> request) {
        require(request, "os");
        require(request, "instanceSkuId");
        require(request, "regionId");
        int count = request.get("count") instanceof Number n ? n.intValue() : 1;
        String prefix = Objects.toString(request.getOrDefault("instanceNamePrefix", "koophone"));
        String orderId = kooPhoneClient.createOrderId();
        List<Map<String, Object>> instanceInfos = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            CloudPhoneInstance entity = new CloudPhoneInstance();
            entity.setInstanceId(kooPhoneClient.newInstanceId());
            entity.setUid(uid);
            entity.setInstanceName(prefix + "-" + String.format("%05d", ThreadLocalRandom.current().nextInt(100000)));
            entity.setOrderId(orderId);
            entity.setStatus(InstanceStatus.CREATED);
            entity.setWaitingCount(0);
            entity.setOs(stringValue(request.get("os")));
            entity.setInstanceSkuId(stringValue(request.get("instanceSkuId")));
            entity.setBandSkuId(stringValue(request.get("bandSkuId")));
            entity.setBandSize(request.get("bandSize") instanceof Number n ? n.doubleValue() : null);
            entity.setRegionId(stringValue(request.get("regionId")));
            entity.setNetwork(stringValue(request.get("network")));
            entity.setAccessMethod("streamable-http");
            // mcp_url 是回给 Agent 的网关入口，不是云机真实地址（云机地址就绪时才获取）
            entity.setMcpUrl(proxyBaseUrl + "/mcp/" + entity.getInstanceId());
            entity.setBackendToken(UUID.randomUUID().toString());
            repository.save(entity);
            instanceInfos.add(Map.of(
                    "instanceId", entity.getInstanceId(),
                    "instanceName", entity.getInstanceName()));
        }
        return Map.of("orderId", orderId, "instanceInfos", instanceInfos);
    }

    /**
     * 查询实例列表（ListInstances，含访问方式与健康状态）。
     *
     * <p>伪代码：按 uid（可选 + instanceIds）查库 -> 过滤掉 DELETED -> 逐条转华为风格 map。
     *
     * @param instanceIds 为空则查该用户全部实例
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String uid, List<String> instanceIds) {
        List<CloudPhoneInstance> entities = instanceIds == null || instanceIds.isEmpty()
                ? repository.findByUid(uid)
                : repository.findByUidAndInstanceIdIn(uid, instanceIds);
        return entities.stream()
                .filter(e -> e.getStatus() != InstanceStatus.DELETED)
                .map(this::toMap)
                .toList();
    }

    /**
     * 退订实例（DeleteInstance，对应 AgentBay kill_sandbox 语义）。
     *
     * <p>伪代码：逐个 requireOwner（不存在/非本人直接抛错）-> 置 DELETED -> save。
     * 逻辑删除保留行，便于审计；路由缓存由 Controller 层 evict。
     */
    @Transactional
    public void delete(String uid, List<String> instanceIds) {
        for (String instanceId : instanceIds) {
            CloudPhoneInstance entity = requireOwner(uid, instanceId);
            entity.setStatus(InstanceStatus.DELETED);
            repository.save(entity);
        }
    }

    /**
     * 实例批量准备（BatchPrepareInstances）。
     *
     * <p>伪代码：
     * <pre>
     *   loop instanceIds:
     *     requireOwner
     *     CREATED/FAILED -> PREPARING + waitingCount=3（模拟重新排队；NORMAL 中幂等跳过）
     *     statusList += {instance_id, 华为风格 status}
     * </pre>
     */
    @Transactional
    public List<Map<String, Object>> prepare(String uid, List<String> instanceIds) {
        List<Map<String, Object>> statusList = new ArrayList<>();
        for (String instanceId : instanceIds) {
            CloudPhoneInstance entity = requireOwner(uid, instanceId);
            if (entity.getStatus() == InstanceStatus.CREATED || entity.getStatus() == InstanceStatus.FAILED) {
                entity.setStatus(InstanceStatus.PREPARING);
                entity.setWaitingCount(PREPARE_WAITING_COUNT);
                repository.save(entity);
            }
            statusList.add(Map.of("instance_id", instanceId, "status", huaweiStatus(entity)));
        }
        return statusList;
    }

    /**
     * 实例准备进度（ShowProgress），Agent 循环调用直到 status==0。
     *
     * <p>核心编排（就绪时刻）：
     * <pre>
     *   PREPARING 时: waitingCount--
     *   归零 -> fetchAccessInfo(E4 mock 取 ip/port) 落库
     *        -> GET healthz 判活
     *        -> 活: NORMAL + healthy=true；死: FAILED + healthy=false
     *   返回华为风格 {status, waitingCount}
     * </pre>
     */
    @Transactional
    public Map<String, Object> progress(String uid, String instanceId) {
        CloudPhoneInstance entity = requireOwner(uid, instanceId);
        if (entity.getStatus() == InstanceStatus.PREPARING) {
            int remaining = entity.getWaitingCount() - 1;
            entity.setWaitingCount(Math.max(remaining, 0));
            if (remaining <= 0) {
                // 就绪：先拿访问信息落库，再判活，判活结果决定最终状态
                KooPhoneClient.AccessInfo accessInfo = kooPhoneClient.fetchAccessInfo(instanceId);
                entity.setMcpIp(accessInfo.ip());
                entity.setMcpPort(accessInfo.mcpPort());
                entity.setBackendUrl("http://" + accessInfo.ip() + ":" + accessInfo.mcpPort());
                boolean alive = backendClient.healthCheck(entity.getBackendUrl());
                entity.setHealthy(alive);
                entity.setStatus(alive ? InstanceStatus.NORMAL : InstanceStatus.FAILED);
            }
            repository.save(entity);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", huaweiStatus(entity));
        result.put("waitingCount", entity.getWaitingCount());
        return result;
    }

    /**
     * 归属与存在性校验（MCP 门控、access-info、delete/prepare/progress 共用）。
     *
     * <p>伪代码：查库无 -> 404；已退订 -> 404；uid 不符 -> 403；否则返回实体。
     *
     * @throws ApiException KOOPHONE.API.4001(404) / KOOPHONE.API.1001(403)
     */
    public CloudPhoneInstance requireOwner(String uid, String instanceId) {
        CloudPhoneInstance entity = repository.findById(instanceId)
                .orElseThrow(() -> new ApiException(404, "KOOPHONE.API.4001", "instance not found: " + instanceId));
        if (entity.getStatus() == InstanceStatus.DELETED) {
            throw new ApiException(404, "KOOPHONE.API.4001", "instance not found: " + instanceId);
        }
        if (!entity.getUid().equals(uid)) {
            throw new ApiException(403, "KOOPHONE.API.1001", "no permission for instance: " + instanceId);
        }
        return entity;
    }

    /** 实体 -> 华为风格响应 map（下划线字段名；healthz 结果也透出给 Agent） */
    private Map<String, Object> toMap(CloudPhoneInstance entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("instance_id", entity.getInstanceId());
        map.put("instance_name", entity.getInstanceName());
        map.put("status", entity.getStatus());
        map.put("access_method", entity.getAccessMethod());
        map.put("mcp_url", entity.getMcpUrl());
        map.put("mcp_ip", entity.getMcpIp());
        map.put("mcp_port", entity.getMcpPort());
        map.put("healthy", entity.isHealthy());
        map.put("region_id", entity.getRegionId());
        map.put("os", entity.getOs());
        map.put("order_id", entity.getOrderId());
        return map;
    }

    /** 内部状态机 -> 华为 ShowProgress 状态码映射：就绪=0、失败=-1、其余排队中=1 */
    private int huaweiStatus(CloudPhoneInstance entity) {
        return switch (entity.getStatus()) {
            case InstanceStatus.NORMAL -> 0;
            case InstanceStatus.FAILED -> -1;
            default -> 1;
        };
    }

    /** 必填参数校验，缺失抛 400 KOOPHONE.API.1000（华为参数错误码） */
    private void require(Map<String, Object> request, String field) {
        if (request.get(field) == null) {
            throw new ApiException(400, "KOOPHONE.API.1000", "missing required parameter: " + field);
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
