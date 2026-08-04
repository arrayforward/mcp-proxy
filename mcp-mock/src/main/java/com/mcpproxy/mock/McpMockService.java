package com.mcpproxy.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class McpMockService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> handle(JsonNode request) {
        String method = request.path("method").asText("");
        if (method.startsWith("notifications/")) {
            return null;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", request.has("id") ? objectMapper.convertValue(request.get("id"), Object.class) : null);
        switch (method) {
            case "initialize" -> response.put("result", Map.of(
                    "protocolVersion", request.path("params").path("protocolVersion").asText("2025-03-26"),
                    "capabilities", Map.of("tools", Map.of()),
                    "serverInfo", Map.of("name", "mcp-mock", "version", "1.0")));
            case "ping" -> response.put("result", Map.of());
            case "tools/list" -> response.put("result", Map.of("tools", tools()));
            case "tools/call" -> {
                String name = request.path("params").path("name").asText();
                response.put("result", Map.of(
                        "content", List.of(Map.of("type", "text", "text", "mock " + name + " ok")),
                        "isError", false));
            }
            default -> response.put("error", Map.of("code", -32601, "message", "Method not found: " + method));
        }
        return response;
    }

    private List<Map<String, Object>> tools() {
        return List.of(
                tool("tap", "Tap screen at coordinates", schema(Map.of(
                        "x", Map.of("type", "integer", "description", "x coordinate"),
                        "y", Map.of("type", "integer", "description", "y coordinate")), List.of("x", "y"))),
                tool("swipe", "Swipe on screen", schema(Map.of(
                        "start_x", Map.of("type", "integer"),
                        "start_y", Map.of("type", "integer"),
                        "end_x", Map.of("type", "integer"),
                        "end_y", Map.of("type", "integer"),
                        "duration_ms", Map.of("type", "integer")), List.of("start_x", "start_y", "end_x", "end_y"))),
                tool("take_screenshot", "Take a screenshot of the device", schema(Map.of(), List.of())),
                tool("text_input", "Input text", schema(Map.of(
                        "text", Map.of("type", "string")), List.of("text"))),
                tool("back", "Press back key", schema(Map.of(), List.of())),
                tool("home", "Press home key", schema(Map.of(), List.of())),
                tool("menu", "Press menu key", schema(Map.of(), List.of())),
                tool("launch_app", "Launch an app by package name", schema(Map.of(
                        "package_name", Map.of("type", "string")), List.of("package_name"))),
                tool("close_app", "Close an app by package name", schema(Map.of(
                        "package_name", Map.of("type", "string")), List.of("package_name"))),
                tool("list_apps", "List installed apps", schema(Map.of(), List.of())),
                tool("autoinstall_app", "Download and install an app from url", schema(Map.of(
                        "url", Map.of("type", "string")), List.of("url"))),
                tool("terminate", "Terminate the MCP server", schema(Map.of(), List.of())));
    }

    private Map<String, Object> tool(String name, String description, Map<String, Object> inputSchema) {
        return Map.of("name", name, "description", description, "inputSchema", inputSchema);
    }

    private Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required);
    }
}
