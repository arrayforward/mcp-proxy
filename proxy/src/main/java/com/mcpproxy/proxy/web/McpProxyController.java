package com.mcpproxy.proxy.web;

import com.mcpproxy.proxy.client.McpBackendClient;
import com.mcpproxy.proxy.health.ActivityTracker;
import com.mcpproxy.proxy.instance.CloudPhoneInstance;
import com.mcpproxy.proxy.instance.InstanceStatus;
import com.mcpproxy.proxy.route.RouteInfo;
import com.mcpproxy.proxy.route.RouteService;
import com.mcpproxy.proxy.security.AuthUser;
import com.mcpproxy.proxy.service.InstanceService;
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

    @PostMapping(value = "/mcp/{instanceId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> mcp(@PathVariable String instanceId,
                                      @RequestBody String body,
                                      Authentication authentication) {
        checkAccess(instanceId, authentication);
        activityTracker.recordRequest(instanceId);
        RouteInfo route = routeService.resolveRoute(instanceId);
        String response = backendClient.forwardPost(route.backendBaseUrl(), body);
        if (response == null) {
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response);
    }

    @GetMapping("/mcp/{instanceId}/sse")
    public SseEmitter sse(@PathVariable String instanceId, Authentication authentication) {
        checkAccess(instanceId, authentication);
        activityTracker.connectionOpened(instanceId);
        RouteInfo route = routeService.resolveRoute(instanceId);
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> activityTracker.connectionClosed(instanceId));
        emitter.onTimeout(() -> activityTracker.connectionClosed(instanceId));
        emitter.onError(e -> activityTracker.connectionClosed(instanceId));
        backendClient.proxySse(route.backendBaseUrl(), instanceId, emitter);
        return emitter;
    }

    @PostMapping("/mcp/{instanceId}/message")
    public ResponseEntity<Void> message(@PathVariable String instanceId,
                                        @RequestParam String sessionId,
                                        @RequestBody String body,
                                        Authentication authentication) {
        checkAccess(instanceId, authentication);
        activityTracker.recordRequest(instanceId);
        RouteInfo route = routeService.resolveRoute(instanceId);
        backendClient.forwardMessage(route.backendBaseUrl(), sessionId, body);
        return ResponseEntity.accepted().build();
    }

    private void checkAccess(String instanceId, Authentication authentication) {
        AuthUser user = (AuthUser) authentication.getPrincipal();
        if (!user.instanceId().equals(instanceId)) {
            throw new ApiException(403, "KOOPHONE.API.1001", "token does not match instance: " + instanceId);
        }
        CloudPhoneInstance entity = instanceService.requireOwner(user.uid(), instanceId);
        if (entity.getStatus() != InstanceStatus.NORMAL) {
            throw new ApiException(409, "KOOPHONE.API.5002", "instance not ready: " + instanceId);
        }
    }
}
