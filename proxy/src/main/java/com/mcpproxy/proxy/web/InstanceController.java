package com.mcpproxy.proxy.web;

import com.mcpproxy.proxy.client.TokenValidator;
import com.mcpproxy.proxy.route.RouteInfo;
import com.mcpproxy.proxy.route.RouteService;
import com.mcpproxy.proxy.service.InstanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实例管理 API（华为 KooPhone 兼容报文）。
 *
 * <p>功能：订阅 create / 查询 list / 退订 delete / 批量准备 prepare / 准备进度 prepare-progress /
 * 访问信息 access-info（E4 新增）。
 *
 * <p>开发思路：
 * <ul>
 *   <li>所有端点都要求 {@code x-auth-token} 头（10s 临时 token），统一由 {@link #authUid}
 *       远程校验后取 uid 作为归属；</li>
 *   <li>响应一律包成华为风格 {@code {data, error_code, error_msg}}；</li>
 *   <li>业务编排全部下沉 InstanceService，本类只做鉴权 + 参数搬运 + 缓存失效。</li>
 * </ul>
 *
 * @author hubin
 * @since 2026-08-04
 */
@Tag(name = "实例管理", description = "华为 KooPhone 兼容的实例生命周期 API（x-auth-token 鉴权）")
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

    /**
     * 订阅实例（CreateInstance）。
     *
     * <p>伪代码：校验 token 取 uid -> InstanceService.create 落库 -> 返回 {orderId, instanceInfos}。
     */
    @Operation(summary = "订阅实例（CreateInstance）", security = @SecurityRequirement(name = "x-auth-token"))
    @PostMapping("/create")
    public Map<String, Object> create(@RequestHeader(value = "x-auth-token", required = false) String token,
                                      @RequestBody Map<String, Object> body) {
        return ok(instanceService.create(authUid(token), body));
    }

    /** 查询实例列表（含访问方式与健康状态）；instance_ids 省略则查全部 */
    @Operation(summary = "查询实例（ListInstances）", security = @SecurityRequirement(name = "x-auth-token"))
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

    /**
     * 退订实例（DeleteInstance，对应 AgentBay kill_sandbox）。
     *
     * <p>伪代码：逐个置 DELETED -> 同时 evict Redis 路由缓存（防已退订实例继续被路由）。
     */
    @Operation(summary = "退订实例（DeleteInstance）", security = @SecurityRequirement(name = "x-auth-token"))
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

    /** 实例批量准备（BatchPrepareInstances）：CREATED/FAILED -> PREPARING 排队 */
    @Operation(summary = "批量准备（BatchPrepareInstances）", security = @SecurityRequirement(name = "x-auth-token"))
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

    /** 准备进度（ShowProgress）：Agent 轮询直到 status==0；就绪时自动判活标记 */
    @Operation(summary = "准备进度（ShowProgress）", security = @SecurityRequirement(name = "x-auth-token"))
    @PostMapping("/prepare-progress")
    public Map<String, Object> prepareProgress(@RequestHeader(value = "x-auth-token", required = false) String token,
                                               @RequestBody Map<String, Object> body) {
        String uid = authUid(token);
        return ok(instanceService.progress(uid, (String) body.get("instance_id")));
    }

    /**
     * 查询云机访问信息（access-info，E4 新增；对应 AgentBay get_sandbox_url）。
     *
     * <p>伪代码：requireOwner 校验归属 -> RouteService 三级解析（Redis->MySQL->E4）-> 返回 ip/port。
     */
    @Operation(summary = "云机访问信息（IP + MCP 端口）", security = @SecurityRequirement(name = "x-auth-token"))
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

    /**
     * x-auth-token 统一校验：缺失/无效 -> 401 KOOPHONE.API.1001；有效 -> 返回 uid。
     */
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

    /** 华为风格成功响应包装：{data, error_code:"0", error_msg:"OK"} */
    private Map<String, Object> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("error_code", "0");
        body.put("error_msg", "OK");
        return body;
    }
}
