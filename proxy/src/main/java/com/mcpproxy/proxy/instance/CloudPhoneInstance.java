package com.mcpproxy.proxy.instance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "t_cloud_phone_instance")
public class CloudPhoneInstance {

    @Id
    @Column(name = "instance_id", length = 32)
    private String instanceId;

    @Column(name = "uid", length = 64, nullable = false)
    private String uid;

    @Column(name = "instance_name", length = 64)
    private String instanceName;

    @Column(name = "order_id", length = 64)
    private String orderId;

    @Column(name = "status", nullable = false)
    private int status;

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

    @Column(name = "access_method", length = 16)
    private String accessMethod;

    @Column(name = "mcp_url", length = 256)
    private String mcpUrl;

    @Column(name = "backend_url", length = 256)
    private String backendUrl;

    @Column(name = "backend_token", length = 256)
    private String backendToken;

    @Column(name = "mcp_ip", length = 64)
    private String mcpIp;

    @Column(name = "mcp_port")
    private Integer mcpPort;

    @Column(name = "healthy", nullable = false)
    private boolean healthy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
