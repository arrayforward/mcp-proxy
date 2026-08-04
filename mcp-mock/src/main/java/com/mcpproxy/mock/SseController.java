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

@RestController
public class SseController {

    private final Map<String, SseEmitter> sessions = new ConcurrentHashMap<>();
    private final McpMockService mcpMockService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SseController(McpMockService mcpMockService) {
        this.mcpMockService = mcpMockService;
    }

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
