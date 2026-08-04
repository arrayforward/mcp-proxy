package com.mcpproxy.proxy.client;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface McpBackendClient {

    String forwardPost(String backendBaseUrl, String jsonRpcBody);

    void forwardMessage(String backendBaseUrl, String sessionId, String jsonRpcBody);

    void proxySse(String backendBaseUrl, String instanceId, SseEmitter emitter);
}
