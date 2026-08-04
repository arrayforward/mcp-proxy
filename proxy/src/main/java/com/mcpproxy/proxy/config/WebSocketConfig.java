package com.mcpproxy.proxy.config;

import com.mcpproxy.proxy.client.McpBackendClient;
import com.mcpproxy.proxy.route.RouteService;
import com.mcpproxy.proxy.security.JwtService;
import com.mcpproxy.proxy.service.InstanceService;
import com.mcpproxy.proxy.web.McpWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final JwtService jwtService;
    private final InstanceService instanceService;
    private final RouteService routeService;
    private final McpBackendClient backendClient;

    public WebSocketConfig(JwtService jwtService,
                           InstanceService instanceService,
                           RouteService routeService,
                           McpBackendClient backendClient) {
        this.jwtService = jwtService;
        this.instanceService = instanceService;
        this.routeService = routeService;
        this.backendClient = backendClient;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new McpWebSocketHandler(jwtService, instanceService, routeService, backendClient),
                        "/ws/mcp/{instanceId}")
                .setAllowedOrigins("*");
    }
}
