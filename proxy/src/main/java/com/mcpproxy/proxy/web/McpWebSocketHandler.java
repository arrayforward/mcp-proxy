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

/**
 * MCP WebSocket 代理处理器。
 *
 * <p>功能：{@code /ws/mcp/{instanceId}?token=<JWT>} 的第三种传输入口——
 * 把 WS 文本帧桥接为云机的 streamable-http POST，响应再以文本帧回推（每连接一链路）。
 *
 * <p>开发思路：
 * <ul>
 *   <li>WS 握手走 SecurityConfig 的 permitAll，鉴权全部在 afterConnectionEstablished 内做：
 *       验 JWT + 路径 instanceId 比对 + 归属 + 就绪门控，任一失败关闭连接
 *       （WS 握手不方便返回 JSON 错误体，POLICY_VIOLATION 即可）；</li>
 *   <li>握手成功时把 backendBaseUrl / instanceId / userJwt 存进 session attributes，
 *       后续每帧直接用，避免重复查库；</li>
 *   <li>连接开/关接 ActivityTracker，纳入 30s 探活范围。</li>
 * </ul>
 *
 * @author hubin
 * @since 2026-08-04
 */
public class McpWebSocketHandler extends TextWebSocketHandler {

    /** session attribute keys：握手成功后缓存的转发上下文 */
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

    /**
     * 握手完成后的鉴权与转发上下文初始化。
     *
     * <p>伪代码：
     * <pre>
     *   token = query 参数 token；instanceId = path 最后一段
     *   验签 -> instanceId 比对 -> requireOwner -> NORMAL 门控
     *   全过 -> 解析路由，attributes 存 backendBaseUrl/instanceId/userJwt，connectionOpened
     *   任一失败 -> close(POLICY_VIOLATION)
     * </pre>
     */
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

    /** 连接关闭：扣减长连接计数（探活范围随之收缩） */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String instanceId = (String) session.getAttributes().get(ATTR_INSTANCE_ID);
        if (instanceId != null) {
            activityTracker.connectionClosed(instanceId);
        }
    }

    /**
     * 文本帧桥接：帧内容（JSON-RPC）-> 云机 POST /mcp -> 响应作为文本帧回推。
     *
     * <p>伪代码：recordRequest 刷新活跃 -> forwardPost 带 JWT -> 非空响应回写；
     * 云机不可达 -> 回写 {"error":"backend unavailable"} 而不是断连。
     */
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
