package com.mcpproxy.proxy.web;

import com.mcpproxy.proxy.route.RouteService;
import com.mcpproxy.proxy.security.AuthUser;
import com.mcpproxy.proxy.service.SandboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sandbox API（Agent 唯一可见的实例入口）。
 *
 * <p>功能：对 Agent 暴露 create_sandbox / sandbox_status / kill_sandbox / list_sandbox
 * 四个接口（对齐阿里云 AgentBay sandbox 语义），内部包装华为云实例全生命周期。
 *
 * <p>鉴权（v1.3 调整）：使用 <b>30min Bearer JWT</b>（/api/auth/login 签发），
 * 而不是 10s 临时 token——sandbox 是会话级操作（创建要 1~5 分钟、还要轮询状态、随时 kill），
 * 10s token 无法覆盖整个使用周期。JWT 中的 uid 即沙箱归属；
 * instanceId claim 仅用于 /mcp/** 的单机绑定，sandbox 接口是用户级操作，不校验该 claim。
 *
 * <p>设计约束：/api/v1/instances/* 的华为风格实例管理接口是 proxy 与云控制面之间的
 * <b>内部 mock 接口，不对 Agent 暴露</b>。
 *
 * @author hubin
 * @since 2026-08-05
 */
@Tag(name = "Sandbox", description = "Agent 一键沙箱接口（Bearer JWT 鉴权；实例管理接口不对外暴露）")
@RestController
@RequestMapping("/api/v1/sandbox")
public class SandboxController {

    private final SandboxService sandboxService;
    private final RouteService routeService;

    public SandboxController(SandboxService sandboxService, RouteService routeService) {
        this.sandboxService = sandboxService;
        this.routeService = routeService;
    }

    /**
     * create_sandbox：异步受理沙箱开通（真实华为开通约 1~5 分钟）。
     *
     * <p>伪代码：JWT 取 uid -> create + prepare + 启动后台看守线程 ->
     * 立即返回 {sandbox_id, sandbox_status:"initializing"}。
     */
    @Operation(summary = "create_sandbox：异步受理沙箱创建", security = @SecurityRequirement(name = "bearer-jwt"))
    @PostMapping("/create")
    public Map<String, Object> createSandbox(Authentication authentication,
                                             @RequestBody Map<String, Object> body) {
        return ok(sandboxService.createSandbox(authUid(authentication), body));
    }

    /**
     * sandbox_status：轮询初始化进度（纯读，Redis 滚动缓存优先）。
     *
     * <p>返回 initializing（带 waiting_count）/ ready（带 mcp 访问信息）/ failed / timeout。
     */
    @Operation(summary = "sandbox_status：轮询沙箱初始化进度", security = @SecurityRequirement(name = "bearer-jwt"))
    @PostMapping("/status")
    public Map<String, Object> sandboxStatus(Authentication authentication,
                                             @RequestBody Map<String, Object> body) {
        return ok(sandboxService.sandboxStatus(authUid(authentication), (String) body.get("sandbox_id")));
    }

    /**
     * list_sandbox：列出当前用户的全部沙箱（语义化状态视图）。
     *
     * <p>用途：Agent 一屏看清自己有哪些沙箱，再决定对某一个轮询状态或杀死。
     */
    @Operation(summary = "list_sandbox：列出当前用户全部沙箱", security = @SecurityRequirement(name = "bearer-jwt"))
    @PostMapping("/list")
    public Map<String, Object> listSandboxes(Authentication authentication,
                                             @RequestBody(required = false) Map<String, Object> body) {
        return ok(sandboxService.listSandboxes(authUid(authentication)));
    }

    /**
     * kill_sandbox：关闭并释放沙箱（包装退订 DeleteInstance）。
     *
     * <p>伪代码：置 DELETED -> evict 路由缓存 -> OK。
     */
    @Operation(summary = "kill_sandbox：关闭并释放沙箱", security = @SecurityRequirement(name = "bearer-jwt"))
    @PostMapping("/kill")
    public Map<String, Object> killSandbox(Authentication authentication,
                                           @RequestBody Map<String, Object> body) {
        String uid = authUid(authentication);
        String sandboxId = (String) body.get("sandbox_id");
        sandboxService.killSandbox(uid, sandboxId);
        routeService.evict(sandboxId);
        return ok(null);
    }

    /**
     * 从 SecurityContext 取已认证用户 uid（JwtAuthFilter 已验签注入 AuthUser）。
     * instanceId claim 不在此校验（sandbox 是用户级操作，非单机绑定）。
     */
    private String authUid(Authentication authentication) {
        return ((AuthUser) authentication.getPrincipal()).uid();
    }

    /** 成功响应包装：{data, error_code:"0", error_msg:"OK"} */
    private Map<String, Object> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("error_code", "0");
        body.put("error_msg", "OK");
        return body;
    }
}
