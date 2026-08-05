package com.mcpproxy.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * mock 云机的 SSE 传输入口（/sse + /message），与 mcp_mobile_use SSE 协议对齐。
 *
 * <p>协议流程：
 * <pre>
 *   GET /sse           -> 立即下发 event:endpoint, data:/message?sessionId=<32hex>
 *   POST /message?sid  -> 202；处理结果经 SSE 通道 event:message 回推
 * </pre>
 *
 * <p>开发思路：sessions 用 ConcurrentHashMap 维护 sessionId -> emitter；
 * emitter 的 completion/timeout/error 三个回调都清理 map，防泄漏。
 *
 * @author hubin
 * @since 2026-08-04
 */
@RestController
public class SseController {

    /** sessionId -> 该会话的 SSE 发射器 */
    private final Map<String, SseEmitter> sessions = new ConcurrentHashMap<>();
    private final McpMockService mcpMockService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SseController(McpMockService mcpMockService) {
        this.mcpMockService = mcpMockService;
    }

    /**
     * 建立 SSE 会话。
     *
     * <p>伪代码：生成 32hex sessionId -> 存 sessions -> 注册清理回调 ->
     * 立即发 endpoint 事件 -> 返回 emitter（长连接保持）。
     */
    @GetMapping("/sse")
    public SseEmitter sse() throws IOException {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        SseEmitter emitter = new SseEmitter(0L);
        sessions.put(sessionId, emitter);
        emitter.onCompletion(() -> sessions.remove(sessionId));
        emitter.onTimeout(() -> sessions.remove(sessionId));
        emitter.onError(e -> sessions.remove(sessionId));
        emitter.send(SseEmitter.event().name("endpoint").data("/message?sessionId=" + sessionId));
        return emitter;
    }

    /**
     * 提交会话消息：处理后经 SSE message 事件回推，HTTP 侧固定 202。
     *
     * <p>伪代码：handle(body) -> 非 null 且会话存在: emitter.send(message, json)；return 202。
     */
    @PostMapping("/message")
    public ResponseEntity<Void> message(@RequestParam String sessionId, @RequestBody JsonNode body) throws IOException {
        Map<String, Object> response = mcpMockService.handle(body);
        if (response != null) {
            SseEmitter emitter = sessions.get(sessionId);
            if (emitter != null) {
                emitter.send(SseEmitter.event().name("message").data(objectMapper.writeValueAsString(response)));
            }
        }
        return ResponseEntity.accepted().build();
    }
}
