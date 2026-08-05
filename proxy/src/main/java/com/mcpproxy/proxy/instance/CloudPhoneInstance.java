package com.mcpproxy.proxy.instance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 云手机实例实体（对应表 t_cloud_phone_instance）。
 *
 * <p>功能：实例注册表 + 访问方式 + 健康状态的持久化载体，网关重启后路由与状态不丢（ADR-2）。
 *
 * <p>开发思路：
 * <ul>
 *   <li>字段与 design.md §3.1 表结构一一对应，列名统一下划线；</li>
 *   <li>created_at/updated_at 由 JPA 生命周期回调维护，不依赖数据库默认值，H2/MySQL 行为一致；</li>
 *   <li>mcp_ip/mcp_port/healthy 是 v1.2+ 扩展：E4 获取落库 + healthz 探活回写；</li>
 *   <li>status 用 int 存枚举值（InstanceStatus），与华为状态码解耦，响应时由 Service 映射。</li>
 * </ul>
 *
 * @author hubin
 * @since 2026-08-04
 */
@Entity
@Table(name = "t_cloud_phone_instance")
public class CloudPhoneInstance {

    /** 实例 ID（主键，订阅时生成，MCP URL 的一部分） */
    @Id
    @Column(name = "instance_id", length = 32)
    private String instanceId;

    /** 归属用户（鉴权归属校验依据） */
    @Column(name = "uid", length = 64, nullable = false)
    private String uid;

    @Column(name = "instance_name", length = 64)
    private String instanceName;

    /** 华为下单返回的订单号 */
    @Column(name = "order_id", length = 64)
    private String orderId;

    /** 状态机：0=CREATED 1=PREPARING 2=NORMAL 3=FAILED 4=DELETED（见 InstanceStatus） */
    @Column(name = "status", nullable = false)
    private int status;

    /** 排队计数：prepare 置 3，每次 progress 轮询 -1，归零就绪 */
    @Column(name = "waiting_count", nullable = false)
    private int waitingCount;

    @Column(name = "os", length = 16)
    private String os;

    @Column(name = "instance_sku_id", length = 64)
    private String instanceSkuId;

    @Column(name = "band_sku_id", length = 64)
    private String bandSkuId;

    @Column(name = "band_size")
    private Double bandSize;

    @Column(name = "region_id", length = 64)
    private String regionId;

    @Column(name = "network", length = 32)
    private String network;

    /** 访问方式：streamable-http / sse / websocket */
    @Column(name = "access_method", length = 16)
    private String accessMethod;

    /** 回给 Agent 的网关入口 URL（非云机真实地址） */
    @Column(name = "mcp_url", length = 256)
    private String mcpUrl;

    /** 云机内 MCP 地址：http://{mcp_ip}:{mcp_port} */
    @Column(name = "backend_url", length = 256)
    private String backendUrl;

    @Column(name = "backend_token", length = 256)
    private String backendToken;

    /** 云手机 IP（E4 fetchAccessInfo 获取后落库） */
    @Column(name = "mcp_ip", length = 64)
    private String mcpIp;

    /** 云机 MCP 端口（同上） */
    @Column(name = "mcp_port")
    private Integer mcpPort;

    /** 健康标记：就绪判活写入；活跃期 30s 探活更新（HealthCheckService） */
    @Column(name = "healthy", nullable = false)
    private boolean healthy;

    /** 失败原因：healthz-failed / timeout（FAILED 时由 InstanceService/SandboxService 写入） */
    @Column(name = "status_reason", length = 32)
    private String statusReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 插入前初始化两个时间戳 */
    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    /** 更新前刷新 updated_at */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getInstanceName() {
        return instanceName;
    }

    public void setInstanceName(String instanceName) {
        this.instanceName = instanceName;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public int getWaitingCount() {
        return waitingCount;
    }

    public void setWaitingCount(int waitingCount) {
        this.waitingCount = waitingCount;
    }

    public String getOs() {
        return os;
    }

    public void setOs(String os) {
        this.os = os;
    }

    public String getInstanceSkuId() {
        return instanceSkuId;
    }

    public void setInstanceSkuId(String instanceSkuId) {
        this.instanceSkuId = instanceSkuId;
    }

    public String getBandSkuId() {
        return bandSkuId;
    }

    public void setBandSkuId(String bandSkuId) {
        this.bandSkuId = bandSkuId;
    }

    public Double getBandSize() {
        return bandSize;
    }

    public void setBandSize(Double bandSize) {
        this.bandSize = bandSize;
    }

    public String getRegionId() {
        return regionId;
    }

    public void setRegionId(String regionId) {
        this.regionId = regionId;
    }

    public String getNetwork() {
        return network;
    }

    public void setNetwork(String network) {
        this.network = network;
    }

    public String getAccessMethod() {
        return accessMethod;
    }

    public void setAccessMethod(String accessMethod) {
        this.accessMethod = accessMethod;
    }

    public String getMcpUrl() {
        return mcpUrl;
    }

    public void setMcpUrl(String mcpUrl) {
        this.mcpUrl = mcpUrl;
    }

    public String getBackendUrl() {
        return backendUrl;
    }

    public void setBackendUrl(String backendUrl) {
        this.backendUrl = backendUrl;
    }

    public String getBackendToken() {
        return backendToken;
    }

    public void setBackendToken(String backendToken) {
        this.backendToken = backendToken;
    }

    public String getMcpIp() {
        return mcpIp;
    }

    public void setMcpIp(String mcpIp) {
        this.mcpIp = mcpIp;
    }

    public Integer getMcpPort() {
        return mcpPort;
    }

    public void setMcpPort(Integer mcpPort) {
        this.mcpPort = mcpPort;
    }

    public boolean isHealthy() {
        return healthy;
    }

    public void setHealthy(boolean healthy) {
        this.healthy = healthy;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public void setStatusReason(String statusReason) {
        this.statusReason = statusReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
