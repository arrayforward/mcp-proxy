package com.mcpproxy.proxy.web;

import com.mcpproxy.proxy.client.BackendException;
import com.mcpproxy.proxy.client.McpBackendClient;
import com.mcpproxy.proxy.health.ActivityTracker;
import com.mcpproxy.proxy.instance.CloudPhoneInstance;
import com.mcpproxy.proxy.instance.InstanceStatus;
import com.mcpproxy.proxy.route.RouteInfo;
import com.mcpproxy.proxy.route.RouteService;
import com.mcpproxy.proxy.security.JwtService;
import com.mcpproxy.proxy.service.InstanceService;
import io.jsonwebtoken.Claims;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

public class McpWebSocketHandler extends TextWebSocketHandler {

    private static final String ATTR_BACKEND_BASE_URL = "backendBaseUrl";

    private static final String ATTR_INSTANCE_ID = "instanceId";
    private static final String ATTR_USER_JWT = "userJwt";

    private final JwtService jwtService;
    private final InstanceService instanceService;
    private final RouteService routeService;
    private final McpBackendClient backendClient;
    private final ActivityTracker activityTracker;

    public McpWebSocketHandler(JwtService jwtService,
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

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        try {
            URI uri = session.getUri();
            String token = UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst("token");
            String path = uri.getPath();
            String instanceId = path.substring(path.lastIndexOf('/') + 1);
            Claims claims = jwtService.parse(token);
            String uid = claims.get("uid", String.class);
            String jwtInstanceId = claims.get("instanceId", String.class);
            if (!instanceId.equals(jwtInstanceId)) {
                throw new IllegalArgumentException("instanceId mismatch");
            }
            CloudPhoneInstance entity = instanceService.requireOwner(uid, instanceId);
            if (entity.getStatus() != InstanceStatus.NORMAL) {
                throw new IllegalStateException("instance not ready");
            }
            RouteInfo route = routeService.resolveRoute(instanceId);
            session.getAttributes().put(ATTR_BACKEND_BASE_URL, route.backendBaseUrl());
            session.getAttributes().put(ATTR_INSTANCE_ID, instanceId);
            session.getAttributes().put(ATTR_USER_JWT, token);
            activityTracker.connectionOpened(instanceId);
        } catch (Exception e) {
            session.close(CloseStatus.POLICY_VIOLATION);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String instanceId = (String) session.getAttributes().get(ATTR_INSTANCE_ID);
        if (instanceId != null) {
            activityTracker.connectionClosed(instanceId);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String backendBaseUrl = (String) session.getAttributes().get(ATTR_BACKEND_BASE_URL);
        String instanceId = (String) session.getAttributes().get(ATTR_INSTANCE_ID);
        if (instanceId != null) {
            activityTracker.recordRequest(instanceId);
        }
        try {
            String userJwt = (String) session.getAttributes().get(ATTR_USER_JWT);
            String response = backendClient.forwardPost(backendBaseUrl, message.getPayload(), userJwt);
            if (response != null && session.isOpen()) {
                session.sendMessage(new TextMessage(response));
            }
        } catch (BackendException e) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage("{\"error\":\"backend unavailable\"}"));
            }
        }
    }
}
