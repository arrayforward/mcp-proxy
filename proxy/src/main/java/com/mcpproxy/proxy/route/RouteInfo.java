package com.mcpproxy.proxy.route;

public record RouteInfo(String instanceId, String ip, int mcpPort) {

    public String backendBaseUrl() {
        return "http://" + ip + ":" + mcpPort;
    }
}
