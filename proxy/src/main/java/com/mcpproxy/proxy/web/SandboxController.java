package com.mcpproxy.proxy.web;

import com.mcpproxy.proxy.client.TokenValidator;
import com.mcpproxy.proxy.route.RouteService;
import com.mcpproxy.proxy.service.SandboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sandbox API（Agent 唯一可见的实例入口）。
 *
 * <p>功能：对 Agent 暴露 create_sandbox / kill_sandbox 两个一键接口（对齐阿里云 AgentBay
 * sandbox 语义），内部包装华为云实例全生命周期（创建 → 准备 → 轮询 → 就绪判活 / 退订）。
 *
 * <p>设计约束（重要）：/api/v1/instances/* 的华为风格实例管理接口是 proxy 与云控制面之间的
 * <b>内部接口，不对 Agent 暴露</b>；Agent 只能看到本类的 sandbox 接口（以及 /api/auth、/mcp）。
 *
 * @author hubin
 * @since 2026-08-05
 */
@Tag(name = "Sandbox", description = "Agent 一键沙箱接口（包装华为实例全生命周期；实例管理接口不对外暴露）")
@RestController
@RequestMapping("/api/v1/sandbox")
public class SandboxController {

    private final SandboxService sandboxService;
    private final RouteService routeService;
    private final TokenValidator tokenValidator;

    public SandboxController(SandboxService sandboxService,
                             RouteService routeService,
                             TokenValidator tokenValidator) {
        this.sandboxService = sandboxService;
        this.routeService = routeService;
        this.tokenValidator = tokenValidator;
    }

    /**
     * create_sandbox：异步受理沙箱开通（华为云手机真实开通约 1~5 分钟，不同步等待）。
     *
     * <p>伪代码：校验 token -> create + prepare -> 立即返回
     * {sandbox_id, sandbox_status:"initializing"}；进度由 sandbox_status 轮询。
     */
    @Operation(summary = "create_sandbox：异步受理沙箱创建", security = @SecurityRequirement(name = "x-auth-token"))
    @PostMapping("/create")
    public Map<String, Object> createSandbox(@RequestHeader(value = "x-auth-token", required = false) String token,
                                             @RequestBody Map<String, Object> body) {
        return ok(sandboxService.createSandbox(authUid(token), body));
    }

    /**
     * sandbox_status：Agent 每隔几秒轮询初始化进度。
     *
     * <p>伪代码：推进一次内部准备进度 -> 映射语义状态：
     * initializing（带 waiting_count）/ ready（带 mcp_url/mcp_ip/mcp_port/healthy）/ failed。
     */
    @Operation(summary = "sandbox_status：轮询沙箱初始化进度", security = @SecurityRequirement(name = "x-auth-token"))
    @PostMapping("/status")
    public Map<String, Object> sandboxStatus(@RequestHeader(value = "x-auth-token", required = false) String token,
                                             @RequestBody Map<String, Object> body) {
        return ok(sandboxService.sandboxStatus(authUid(token), (String) body.get("sandbox_id")));
    }

    /**
     * kill_sandbox：关闭并释放沙箱（包装退订 DeleteInstance）。
     *
     * <p>伪代码：校验 token -> delete（置 DELETED）-> evict 路由缓存 -> OK。
     */
    @Operation(summary = "kill_sandbox：关闭并释放云手机沙箱", security = @SecurityRequirement(name = "x-auth-token"))
    @PostMapping("/kill")
    public Map<String, Object> killSandbox(@RequestHeader(value = "x-auth-token", required = false) String token,
                                           @RequestBody Map<String, Object> body) {
        String uid = authUid(token);
        String sandboxId = (String) body.get("sandbox_id");
        sandboxService.killSandbox(uid, sandboxId);
        routeService.evict(sandboxId);
        return ok(null);
    }

    /** x-auth-token 统一校验（与实例管理内部接口同一认证体系） */
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

    /** 成功响应包装：{data, error_code:"0", error_msg:"OK"} */
    private Map<String, Object> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("error_code", "0");
        body.put("error_msg", "OK");
        return body;
    }
}
