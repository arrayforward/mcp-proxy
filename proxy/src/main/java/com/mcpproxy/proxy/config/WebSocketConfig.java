package com.mcpproxy.proxy.config;

import com.mcpproxy.proxy.client.McpBackendClient;
import com.mcpproxy.proxy.health.ActivityTracker;
import com.mcpproxy.proxy.route.RouteService;
import com.mcpproxy.proxy.security.JwtService;
import com.mcpproxy.proxy.service.InstanceService;
import com.mcpproxy.proxy.web.McpWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置：注册 MCP 的 WS 代理入口。
 *
 * <p>功能：把 {@code /ws/mcp/{instanceId}} 映射到 McpWebSocketHandler。
 *
 * <p>开发思路：WS 握手走 SecurityConfig 的 permitAll（不在 /mcp/** 规则内），
 * 鉴权完全在 Handler 的 afterConnectionEstablished 里做（验 JWT + 比对实例 + 就绪门控），
 * 失败直接关闭连接——因为 WS 握手响应无法方便地返回 JSON 错误体。
 *
 * @author hubin
 * @since 2026-08-04
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final JwtService jwtService;
    private final InstanceService instanceService;
    private final RouteService routeService;
    private final McpBackendClient backendClient;
    private final ActivityTracker activityTracker;

    public WebSocketConfig(JwtService jwtService,
                           InstanceService instanceService,
                           RouteService routeService,
                           McpBackendClient backendClient,
                           ActivityTracker activityTracker) {
        this.jwtService = jwtService;
        this.instanceService = instanceService;
        this.routeService = routeService;
        this.backendClient = backendClient;
        this.activityTracker = activityTracker;
    }

    /** 注册 WS 端点；allowedOrigins=* 因为鉴权靠 token 而非 Origin */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new McpWebSocketHandler(jwtService, instanceService, routeService, backendClient, activityTracker),
                        "/ws/mcp/{instanceId}")
                .setAllowedOrigins("*");
    }
}
