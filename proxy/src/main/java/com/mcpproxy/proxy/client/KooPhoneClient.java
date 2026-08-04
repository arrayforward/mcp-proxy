package com.mcpproxy.proxy.client;

public interface KooPhoneClient {

    String createOrderId();

    String newInstanceId();

    AccessInfo fetchAccessInfo(String instanceId);

    record AccessInfo(String ip, int mcpPort) {
    }
}
