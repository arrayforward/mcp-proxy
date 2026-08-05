package com.mcpproxy.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpMockServiceTest {

    private final McpMockService service = new McpMockService();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void initializeReturnsServerInfo() throws Exception {
        var request = mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\"}}");
        Map<String, Object> response = service.handle(request);
        assertEquals("2.0", response.get("jsonrpc"));
        assertEquals(1, response.get("id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        @SuppressWarnings("unchecked")
        Map<String, Object> serverInfo = (Map<String, Object>) result.get("serverInfo");
        assertEquals("mcp-mock", serverInfo.get("name"));
    }

    @Test
    void toolsListReturnsAllTools() throws Exception {
        var request = mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        Map<String, Object> response = service.handle(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        assertEquals(26, tools.size());
        assertTrue(tools.stream().anyMatch(t -> "create_sandbox".equals(t.get("name"))));
        assertTrue(tools.stream().anyMatch(t -> "kill_sandbox".equals(t.get("name"))));
        assertTrue(tools.stream().anyMatch(t -> "adb_shell".equals(t.get("name"))));
    }

    @Test
    void sandboxLifecycleCalls() throws Exception {
        var create = mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"create_sandbox\",\"arguments\":{}}}");
        Map<String, Object> resp = service.handle(create);
        assertNotNull(resp.get("result"));

        var getUrl = mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"get_sandbox_url\",\"arguments\":{\"sandbox_id\":\"sandbox-mock-0001\"}}}");
        assertNotNull(service.handle(getUrl).get("result"));

        var kill = mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"kill_sandbox\",\"arguments\":{\"sandbox_id\":\"sandbox-mock-0001\"}}}");
        assertNotNull(service.handle(kill).get("result"));
    }

    @Test
    void adbShellCall() throws Exception {
        var request = mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":{\"name\":\"adb_shell\",\"arguments\":{\"command\":\"getprop ro.build.version.release\"}}}");
        Map<String, Object> resp = service.handle(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        assertNotNull(result);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertTrue(((String) content.get(0).get("text")).contains("getprop ro.build.version.release"));
    }

    @Test
    void toolsCallReturnsMockContent() throws Exception {
        var request = mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"tap\",\"arguments\":{\"x\":1,\"y\":2}}}");
        Map<String, Object> response = service.handle(request);
        assertNotNull(response.get("result"));
    }

    @Test
    void unknownMethodReturnsError() throws Exception {
        var request = mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"no/such\"}");
        Map<String, Object> response = service.handle(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertEquals(-32601, error.get("code"));
    }

    @Test
    void notificationReturnsNull() throws Exception {
        var request = mapper.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertNull(service.handle(request));
    }
}
