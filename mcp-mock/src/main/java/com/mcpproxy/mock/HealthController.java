package com.mcpproxy.mock;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * mock 云机判活接口（GET /healthz）。
 *
 * <p>功能：proxy 的就绪门控与 30s 定时探活都调用本端点；返回 200 + {"status":"UP"}。
 * 按 security.md §7 约定，healthz 免鉴权（JwtVerifyFilter 明确放行）。
 *
 * @author hubin
 * @since 2026-08-04
 */
@RestController
public class HealthController {

    /** 判活：固定返回 UP（真实云机可在此上报 MCP 引擎/ADB 通道状态） */
    @GetMapping("/healthz")
    public Map<String, Object> healthz() {
        return Map.of("status", "UP");
    }
}
