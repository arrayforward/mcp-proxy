package com.mcpproxy.proxy.web;

import com.mcpproxy.proxy.client.McpBackendClient;
import com.mcpproxy.proxy.health.ActivityTracker;
import com.mcpproxy.proxy.instance.CloudPhoneInstance;
import com.mcpproxy.proxy.instance.InstanceStatus;
import com.mcpproxy.proxy.route.RouteInfo;
import com.mcpproxy.proxy.route.RouteService;
import com.mcpproxy.proxy.security.AuthUser;
import com.mcpproxy.proxy.service.InstanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * MCP 代理控制器：streamable-http 与 SSE 两种传输的 Agent 入口（WS 在 McpWebSocketHandler）。
 *
 * <p>功能：把 Agent 的 JSON-RPC 请求经「决策链」校验后透传到云机 mcp-server。
 *
 * <p>决策链（architecture.md §4，顺序敏感）：
 * <pre>
 *   401  无有效 JWT（SecurityConfig 拦截，进不了本类）
 *   403  jwt.instanceId != 路径 instanceId，或实例不属于 jwt.uid
 *   404  实例不存在 / 已退订
 *   409  实例未就绪（status != NORMAL）
 *   通过 -> RouteService 解析云机地址 -> 携带用户 JWT 转发
 * </pre>
 *
 * <p>开发思路：每次请求都 recordRequest / 连接开关都记 connectionOpened-Closed，
 * 喂给 ActivityTracker，驱动 30s 探活只打活跃实例。
 *
 * @author hubin
 * @since 2026-08-04
 */
@Tag(name = "MCP 代理", description = "MCP 三传输代理入口（Bearer JWT 鉴权 + 决策链）")
@RestController
public class McpProxyController {

    private final InstanceService instanceService;
    private final RouteService routeService;
    private final McpBackendClient backendClient;
    private final ActivityTracker activityTracker;

    public McpProxyController(InstanceService instanceService,
                              RouteService routeService,
                              McpBackendClient backendClient,
                              ActivityTracker activityTracker) {
        this.instanceService = instanceService;
        this.routeService = routeService;
        this.backendClient = backendClient;
        this.activityTracker = activityTracker;
    }

    /**
     * streamable-http 转发入口。
     *
     * <p>API：{@code POST /mcp/{instanceId}}，JSON-RPC 原文透传，同步返回云机响应；
     * 通知类消息（云机回 202 无 body）返回 202。
     *
     * <p>伪代码：决策链 -> 记录活跃 -> 解析路由 -> forwardPost（带 JWT）-> 透传响应。
     */
    @Operation(summary = "MCP streamable-http 代理", security = @SecurityRequirement(name = "bearer-jwt"))
    @PostMapping(value = "/mcp/{instanceId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> mcp(@PathVariable String instanceId,
                                      @RequestBody String body,
                                      Authentication authentication) {
        AuthUser user = checkAccess(instanceId, authentication);
        activityTracker.recordRequest(instanceId);
        RouteInfo route = routeService.resolveRoute(instanceId);
        String response = backendClient.forwardPost(route.backendBaseUrl(), body, user.token());
        if (response == null) {
            return ResponseEntity.accepted().build();   // 通知类：202 空响应
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response);
    }

    /**
     * SSE 会话建立入口。
     *
     * <p>API：{@code GET /mcp/{instanceId}/sse}，返回 SseEmitter 长连接；
     * 云机 endpoint 事件的路径会被重写为本 proxy 的 /mcp/{id}/message（见 HttpMcpBackendClient）。
     *
     * <p>伪代码：决策链 -> connectionOpened -> 解析路由 -> 桥接云机 /sse -> emitter 的三个
     * 结束回调都 connectionClosed（防计数泄漏）。
     */
    @Operation(summary = "MCP SSE 会话建立", security = @SecurityRequirement(name = "bearer-jwt"))
    @GetMapping("/mcp/{instanceId}/sse")
    public SseEmitter sse(@PathVariable String instanceId, Authentication authentication) {
        AuthUser user = checkAccess(instanceId, authentication);
        activityTracker.connectionOpened(instanceId);
        RouteInfo route = routeService.resolveRoute(instanceId);
        SseEmitter emitter = new SseEmitter(0L);   // 0L = 永不超时，由云机侧保活
        emitter.onCompletion(() -> activityTracker.connectionClosed(instanceId));
        emitter.onTimeout(() -> activityTracker.connectionClosed(instanceId));
        emitter.onError(e -> activityTracker.connectionClosed(instanceId));
        backendClient.proxySse(route.backendBaseUrl(), instanceId, emitter, user.token());
        return emitter;
    }

    /**
     * SSE 消息提交入口。
     *
     * <p>API：{@code POST /mcp/{instanceId}/message?sessionId=xxx}，202；
     * 云机处理结果经 SSE 通道 message 事件回推给 Agent。
     */
    @Operation(summary = "MCP SSE 消息提交", security = @SecurityRequirement(name = "bearer-jwt"))
    @PostMapping("/mcp/{instanceId}/message")
    public ResponseEntity<Void> message(@PathVariable String instanceId,
                                        @RequestParam String sessionId,
                                        @RequestBody String body,
                                        Authentication authentication) {
        AuthUser user = checkAccess(instanceId, authentication);
        activityTracker.recordRequest(instanceId);
        RouteInfo route = routeService.resolveRoute(instanceId);
        backendClient.forwardMessage(route.backendBaseUrl(), sessionId, body, user.token());
        return ResponseEntity.accepted().build();
    }

    /**
     * 决策链校验（403/404/409），通过后返回 AuthUser（含转发用原始 token）。
     *
     * <p>伪代码：
     * <pre>
     *   jwt.instanceId != 路径 id -> 403（令牌与目标实例不匹配）
     *   requireOwner（不存在/已退订 404、非本人 403）
     *   status != NORMAL -> 409 KOOPHONE.API.5002（未就绪门控，ADR-4）
     * </pre>
     */
    private AuthUser checkAccess(String instanceId, Authentication authentication) {
        AuthUser user = (AuthUser) authentication.getPrincipal();
        if (!user.instanceId().equals(instanceId)) {
            throw new ApiException(403, "KOOPHONE.API.1001", "token does not match instance: " + instanceId);
        }
        CloudPhoneInstance entity = instanceService.requireOwner(user.uid(), instanceId);
        if (entity.getStatus() != InstanceStatus.NORMAL) {
            throw new ApiException(409, "KOOPHONE.API.5002", "instance not ready: " + instanceId);
        }
        return user;
    }
}
