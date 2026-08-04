package com.mcpproxy.proxy.web;

import com.mcpproxy.proxy.client.TokenValidator;
import com.mcpproxy.proxy.route.RouteInfo;
import com.mcpproxy.proxy.route.RouteService;
import com.mcpproxy.proxy.service.InstanceService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/instances")
public class InstanceController {

    private final InstanceService instanceService;
    private final RouteService routeService;
    private final TokenValidator tokenValidator;

    public InstanceController(InstanceService instanceService,
                              RouteService routeService,
                              TokenValidator tokenValidator) {
        this.instanceService = instanceService;
        this.routeService = routeService;
        this.tokenValidator = tokenValidator;
    }

    @PostMapping("/create")
    public Map<String, Object> create(@RequestHeader(value = "x-auth-token", required = false) String token,
                                      @RequestBody Map<String, Object> body) {
        return ok(instanceService.create(authUid(token), body));
    }

    @PostMapping("/list")
    @SuppressWarnings("unchecked")
    public Map<String, Object> list(@RequestHeader(value = "x-auth-token", required = false) String token,
                                    @RequestBody Map<String, Object> body) {
        String uid = authUid(token);
        List<String> instanceIds = body.get("instance_ids") instanceof List<?> ids
                ? (List<String>) ids
                : List.of();
        return ok(Map.of("instance_list", instanceService.list(uid, instanceIds)));
    }

    @PostMapping("/delete")
    @SuppressWarnings("unchecked")
    public Map<String, Object> delete(@RequestHeader(value = "x-auth-token", required = false) String token,
                                      @RequestBody Map<String, Object> body) {
        String uid = authUid(token);
        List<String> instanceIds = body.get("instanceIdList") instanceof List<?> ids
                ? (List<String>) ids
                : List.of();
        instanceService.delete(uid, instanceIds);
        instanceIds.forEach(routeService::evict);
        return ok(null);
    }

    @PostMapping("/prepare")
    @SuppressWarnings("unchecked")
    public Map<String, Object> prepare(@RequestHeader(value = "x-auth-token", required = false) String token,
                                       @RequestBody Map<String, Object> body) {
        String uid = authUid(token);
        List<String> instanceIds = body.get("instance_ids") instanceof List<?> ids
                ? (List<String>) ids
                : List.of();
        return ok(Map.of("status_list", instanceService.prepare(uid, instanceIds)));
    }

    @PostMapping("/prepare-progress")
    public Map<String, Object> prepareProgress(@RequestHeader(value = "x-auth-token", required = false) String token,
                                               @RequestBody Map<String, Object> body) {
        String uid = authUid(token);
        return ok(instanceService.progress(uid, (String) body.get("instance_id")));
    }

    @PostMapping("/access-info")
    public Map<String, Object> accessInfo(@RequestHeader(value = "x-auth-token", required = false) String token,
                                          @RequestBody Map<String, Object> body) {
        String uid = authUid(token);
        String instanceId = (String) body.get("instance_id");
        instanceService.requireOwner(uid, instanceId);
        RouteInfo route = routeService.resolveRoute(instanceId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("instance_id", route.instanceId());
        data.put("mcp_ip", route.ip());
        data.put("mcp_port", route.mcpPort());
        return ok(data);
    }

    private String authUid(String token) {
        if (token == null || token.isBlank()) {
            throw new ApiException(401, "KOOPHONE.API.1001", "missing x-auth-token");
        }
        TokenValidator.ValidationResult result = tokenValidator.validate(token);
        if (!result.valid()) {
            throw new ApiException(401, "KOOPHONE.API.1001", "invalid x-auth-token: " + result.reason());
        }
        return result.uid();
    }

    private Map<String, Object> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("error_code", "0");
        body.put("error_msg", "OK");
        return body;
    }
}
