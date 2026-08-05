package com.mcpproxy.proxy.client;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 云机 MCP 后端客户端抽象。
 *
 * <p>功能：定义 proxy -> 云机 mcp-server 的全部出站操作（三种传输 + 判活）。
 * 当前唯一实现 {@link HttpMcpBackendClient}；抽象出来便于单测 Mock 与将来换 WebClient 响应式实现。
 *
 * @author hubin
 */
public interface McpBackendClient {

    /**
     * streamable-http 转发：同步 POST JSON-RPC 到云机 /mcp。
     *
     * @param backendBaseUrl 云机地址（http://ip:port）
     * @param jsonRpcBody    JSON-RPC 原文
     * @param userJwt        用户 JWT（云机公钥验签）
     * @return 云机响应原文；通知类为 null
     */
    String forwardPost(String backendBaseUrl, String jsonRpcBody, String userJwt);

    /** SSE 消息提交：POST 到云机 /message?sessionId=，响应经 SSE 回推 */
    void forwardMessage(String backendBaseUrl, String sessionId, String jsonRpcBody, String userJwt);

    /** SSE 桥接：消费云机 /sse 流，重写 endpoint 后写入 emitter */
    void proxySse(String backendBaseUrl, String instanceId, SseEmitter emitter, String userJwt);

    /** 判活：GET 云机 /healthz，2xx 为活 */
    boolean healthCheck(String backendBaseUrl);
}
