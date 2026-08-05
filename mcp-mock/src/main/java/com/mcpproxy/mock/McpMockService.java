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
                JsonNode args = request.path("params").path("arguments");
                response.put("result", Map.of(
                        "content", List.of(Map.of("type", "text", "text", toolCallResult(name, args))),
                        "isError", false));
            }
            default -> response.put("error", Map.of("code", -32601, "message", "Method not found: " + method));
        }
        return response;
    }

    private List<Map<String, Object>> tools() {
        List<Map<String, Object>> tools = new java.util.ArrayList<>(List.of(
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
                tool("terminate", "Terminate the MCP server", schema(Map.of(), List.of())),
                tool("adb_shell", "Execute a standard adb shell command on the device and return its output. "
                        + "Generic low-level interface aligned with 'adb shell <command>'; use specialized tools "
                        + "(tap, swipe, take_screenshot, ...) for common operations. WARNING: executes arbitrary "
                        + "commands, keep the MCP endpoint protected (auth) when exposed to untrusted clients", schema(Map.of(
                        "command", Map.of("type", "string", "description", "The shell command to execute on the device, e.g. \"ls -l /sdcard\" or \"getprop ro.build.version.release\""),
                        "timeout_ms", Map.of("type", "integer", "description", "Command timeout in milliseconds, default 30000")), List.of("command")))));
        tools.addAll(sandboxTools());
        return tools;
    }

    private List<Map<String, Object>> sandboxTools() {
        return List.of(
                tool("create_sandbox", "Create a new AgentBay sandbox and return its ID", schema(Map.of(), List.of())),
                tool("get_sandbox_url", "Get the Wuying MCP runtime URL; single-use, expires after use", schema(Map.of(
                        "sandbox_id", Map.of("type", "string", "description", "The sandbox ID from create_sandbox")), List.of("sandbox_id"))),
                tool("kill_sandbox", "Release sandbox resources after task completion", schema(Map.of(
                        "sandbox_id", Map.of("type", "string", "description", "The sandbox ID from create_sandbox")), List.of("sandbox_id"))),
                tool("system_screenshot", "Capture full screen and return a shareable URL (expires in 64 minutes)", schema(Map.of(
                        "sandbox_id", Map.of("type", "string")), List.of("sandbox_id"))),
                tool("shell", "Execute a shell command on Android with timeout", schema(Map.of(
                        "sandbox_id", Map.of("type", "string"),
                        "command", Map.of("type", "string", "description", "client input command"),
                        "timeout_ms", Map.of("type", "integer", "default", 1000)), List.of("sandbox_id", "command", "timeout_ms"))),
                tool("click", "Click on the screen at specific coordinates", schema(Map.of(
                        "x", Map.of("type", "integer", "description", "X coordinate"),
                        "y", Map.of("type", "integer", "description", "Y coordinate"),
                        "button", Map.of("type", "string", "description", "left/middle/right, default left")), List.of("x", "y", "button"))),
                tool("send_key", "Send a key. Android: 3:HOME,4:BACK,24:VOLUME_UP,25:VOLUME_DOWN,26:POWER,82:MENU", schema(Map.of(
                        "key", Map.of("type", "integer", "description", "client send key")), List.of("key"))),
                tool("get_all_ui_elements", "Get all UI elements from the device with timeout", schema(Map.of(
                        "timeout_ms", Map.of("type", "integer", "default", 1000)), List.of("timeout_ms"))),
                tool("get_clickable_ui_elements", "Get all clickable UI elements within timeout", schema(Map.of(
                        "timeout_ms", Map.of("type", "integer", "default", 1000)), List.of("timeout_ms"))),
                tool("get_installed_apps", "Retrieve installed applications with optional filters", schema(Map.of(
                        "desktop", Map.of("type", "boolean", "default", false),
                        "ignore_system_app", Map.of("type", "boolean", "default", true),
                        "start_menu", Map.of("type", "boolean", "default", true)), List.of())),
                tool("start_app", "Start an application using the provided command and optional work directory", schema(Map.of(
                        "start_cmd", Map.of("type", "string", "description", "e.g. monkey -p <package> -c android.intent.category.LAUNCHER 1"),
                        "activity", Map.of("type", "string"),
                        "work_directory", Map.of("type", "string", "default", "")), List.of("start_cmd"))),
                tool("stop_app_by_cmd", "Terminate an application using the provided stop command", schema(Map.of(
                        "stop_cmd", Map.of("type", "string")), List.of("stop_cmd"))),
                tool("input_text", "Input text", schema(Map.of(
                        "text", Map.of("type", "string", "description", "client input text")), List.of("text"))));
    }

    private String toolCallResult(String name, JsonNode args) {
        return switch (name) {
            case "create_sandbox" -> "{\"sandbox_id\":\"sandbox-mock-0001\"}";
            case "get_sandbox_url" -> "{\"url\":\"http://127.0.0.1:9091/mcp?ticket=single-use-mock\"}";
            case "kill_sandbox" -> "{\"released\":true}";
            case "system_screenshot" -> "{\"url\":\"http://127.0.0.1:9091/shots/mock.png\",\"expires_in\":3840}";
            case "shell" -> "{\"exit_code\":0,\"output\":\"mock shell ok: " + args.path("command").asText("") + "\"}";
            case "adb_shell" -> "{\"command\":\"" + args.path("command").asText("") + "\",\"exit_code\":0,\"timed_out\":false,\"stdout\":\"mock adb_shell ok\",\"stderr\":\"\"}";
            case "get_installed_apps" -> "{\"apps\":[{\"name\":\"MockApp\",\"start_cmd\":\"monkey -p com.mock.app -c android.intent.category.LAUNCHER 1\"}]}";
            case "start_app" -> "{\"processes\":[{\"name\":\"com.mock.app\",\"pid\":12345}]}";
            case "get_all_ui_elements" -> "{\"source\":\"adb_shell:uiautomator dump /sdcard/window.xml\",\"elements\":["
                    + "{\"text\":\"\",\"resource_id\":\"com.android.systemui:id/status_bar\",\"class\":\"android.widget.FrameLayout\",\"package\":\"com.android.systemui\",\"bounds\":[0,0,1080,96],\"clickable\":false,\"enabled\":true},"
                    + "{\"text\":\"设置\",\"resource_id\":\"com.android.settings:id/title\",\"class\":\"android.widget.TextView\",\"package\":\"com.android.settings\",\"bounds\":[48,200,1032,320],\"clickable\":false,\"enabled\":true},"
                    + "{\"text\":\"确定\",\"resource_id\":\"android:id/button1\",\"class\":\"android.widget.Button\",\"package\":\"com.android.settings\",\"bounds\":[800,1700,1032,1820],\"clickable\":true,\"enabled\":true},"
                    + "{\"text\":\"取消\",\"resource_id\":\"android:id/button2\",\"class\":\"android.widget.Button\",\"package\":\"com.android.settings\",\"bounds\":[560,1700,792,1820],\"clickable\":true,\"enabled\":true}]}";
            case "get_clickable_ui_elements" -> "{\"source\":\"adb_shell:uiautomator dump /sdcard/window.xml\",\"elements\":["
                    + "{\"text\":\"确定\",\"resource_id\":\"android:id/button1\",\"class\":\"android.widget.Button\",\"package\":\"com.android.settings\",\"bounds\":[800,1700,1032,1820],\"clickable\":true,\"enabled\":true},"
                    + "{\"text\":\"取消\",\"resource_id\":\"android:id/button2\",\"class\":\"android.widget.Button\",\"package\":\"com.android.settings\",\"bounds\":[560,1700,792,1820],\"clickable\":true,\"enabled\":true}]}";
            default -> "mock " + name + " ok";
        };
    }

    private Map<String, Object> tool(String name, String description, Map<String, Object> inputSchema) {
        return Map.of("name", name, "description", description, "inputSchema", inputSchema);
    }

    private Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required);
    }
}
