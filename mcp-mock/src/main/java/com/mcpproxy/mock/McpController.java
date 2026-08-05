package com.mcpproxy.mock;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * mock 云机的 streamable-http 入口（POST /mcp）。
 *
 * <p>功能：与 mcp_mobile_use 的 streamable-http 传输对齐——单端点收 JSON-RPC，
 * 普通请求同步返回 application/json，通知类返回 202 空响应。
 *
 * @author hubin
 * @since 2026-08-04
 */
@RestController
public class McpController {

    private final McpMockService mcpMockService;

    public McpController(McpMockService mcpMockService) {
        this.mcpMockService = mcpMockService;
    }

    /**
     * 处理 streamable-http JSON-RPC 请求。
     *
     * <p>伪代码：handle(body) -> null（通知）: 202；否则: 200 + JSON 响应。
     */
    @PostMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> mcp(@RequestBody JsonNode body) {
        Map<String, Object> response = mcpMockService.handle(body);
        if (response == null) {
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok(response);
    }
}
