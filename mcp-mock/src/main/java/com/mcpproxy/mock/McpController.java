package com.mcpproxy.mock;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class McpController {

    private final McpMockService mcpMockService;

    public McpController(McpMockService mcpMockService) {
        this.mcpMockService = mcpMockService;
    }

    @PostMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> mcp(@RequestBody JsonNode body) {
        Map<String, Object> response = mcpMockService.handle(body);
        if (response == null) {
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok(response);
    }
}
