package com.mcpproxy.proxy.route;

/**
 * 路由信息值对象：instanceId 对应的云机 MCP 地址。
 *
 * <p>用 record 保证不可变；{@link #backendBaseUrl()} 把 ip+port 拼成转发用的 base URL，
 * 转发层（HttpMcpBackendClient）只认这个形式。
 *
 * @author hubin
 * @since 2026-08-04
 */
public record RouteInfo(String instanceId, String ip, int mcpPort) {

    /** 云机 MCP base 地址，如 http://10.0.0.23:9091 */
    public String backendBaseUrl() {
        return "http://" + ip + ":" + mcpPort;
    }
}
