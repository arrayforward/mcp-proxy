package com.mcpproxy.proxy.service;

import com.mcpproxy.proxy.client.KooPhoneClient;
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

@Service
public class InstanceService {

    private static final int PREPARE_WAITING_COUNT = 3;

    private final InstanceRepository repository;
    private final KooPhoneClient kooPhoneClient;
    private final String proxyBaseUrl;

    public InstanceService(InstanceRepository repository,
                           KooPhoneClient kooPhoneClient,
                           @Value("${mcp.proxy-base-url:http://localhost:8080}") String proxyBaseUrl) {
        this.repository = repository;
        this.kooPhoneClient = kooPhoneClient;
        this.proxyBaseUrl = proxyBaseUrl;
    }

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
            entity.setMcpUrl(proxyBaseUrl + "/mcp/" + entity.getInstanceId());
            entity.setBackendToken(UUID.randomUUID().toString());
            repository.save(entity);
            instanceInfos.add(Map.of(
                    "instanceId", entity.getInstanceId(),
                    "instanceName", entity.getInstanceName()));
        }
        return Map.of("orderId", orderId, "instanceInfos", instanceInfos);
    }

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

    @Transactional
    public void delete(String uid, List<String> instanceIds) {
        for (String instanceId : instanceIds) {
            CloudPhoneInstance entity = requireOwner(uid, instanceId);
            entity.setStatus(InstanceStatus.DELETED);
            repository.save(entity);
        }
    }

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

    @Transactional
    public Map<String, Object> progress(String uid, String instanceId) {
        CloudPhoneInstance entity = requireOwner(uid, instanceId);
        if (entity.getStatus() == InstanceStatus.PREPARING) {
            int remaining = entity.getWaitingCount() - 1;
            entity.setWaitingCount(Math.max(remaining, 0));
            if (remaining <= 0) {
                entity.setStatus(InstanceStatus.NORMAL);
                KooPhoneClient.AccessInfo accessInfo = kooPhoneClient.fetchAccessInfo(instanceId);
                entity.setMcpIp(accessInfo.ip());
                entity.setMcpPort(accessInfo.mcpPort());
                entity.setBackendUrl("http://" + accessInfo.ip() + ":" + accessInfo.mcpPort());
            }
            repository.save(entity);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", huaweiStatus(entity));
        result.put("waitingCount", entity.getWaitingCount());
        return result;
    }

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

    private Map<String, Object> toMap(CloudPhoneInstance entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("instance_id", entity.getInstanceId());
        map.put("instance_name", entity.getInstanceName());
        map.put("status", entity.getStatus());
        map.put("access_method", entity.getAccessMethod());
        map.put("mcp_url", entity.getMcpUrl());
        map.put("mcp_ip", entity.getMcpIp());
        map.put("mcp_port", entity.getMcpPort());
        map.put("region_id", entity.getRegionId());
        map.put("os", entity.getOs());
        map.put("order_id", entity.getOrderId());
        return map;
    }

    private int huaweiStatus(CloudPhoneInstance entity) {
        return switch (entity.getStatus()) {
            case InstanceStatus.NORMAL -> 0;
            case InstanceStatus.FAILED -> -1;
            default -> 1;
        };
    }

    private void require(Map<String, Object> request, String field) {
        if (request.get(field) == null) {
            throw new ApiException(400, "KOOPHONE.API.1000", "missing required parameter: " + field);
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
