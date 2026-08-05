package com.mcpproxy.proxy.client;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface McpBackendClient {

    String forwardPost(String backendBaseUrl, String jsonRpcBody, String userJwt);

    void forwardMessage(String backendBaseUrl, String sessionId, String jsonRpcBody, String userJwt);

    void proxySse(String backendBaseUrl, String instanceId, SseEmitter emitter, String userJwt);

    boolean healthCheck(String backendBaseUrl);
}
