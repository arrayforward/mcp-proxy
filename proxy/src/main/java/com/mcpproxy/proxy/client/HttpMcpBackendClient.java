package com.mcpproxy.proxy.client;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * 云机 MCP 后端客户端的 HTTP 实现。
 *
 * <p>功能：proxy 与云手机内 mcp-server（mcp_mobile_use）之间的所有出站调用都经过本类：
 * streamable-http 同步转发、SSE 会话桥接、/message 提交、healthz 判活。
 *
 * <p>开发思路：
 * <ul>
 *   <li>同步请求用 Spring RestClient（阻塞式、API 简洁），SSE 流用 JDK HttpClient
 *       （BodyHandlers.ofLines 可逐行消费长连接）；</li>
 *   <li>每次调用都把<b>用户 JWT</b> 放进 Authorization 头带给云机，云机用预置公钥验签（见 security.md）；</li>
 *   <li>调用失败统一包装成 BackendException，由 ApiExceptionHandler 映射为 502；</li>
 *   <li>SSE 桥接在独立线程池跑，避免阻塞 Tomcat 请求线程。</li>
 * </ul>
 *
 * @author hubin
 * @since 2026-08-04
 */
@Component
public class HttpMcpBackendClient implements McpBackendClient {

    /** 同步 HTTP 调用（forwardPost / forwardMessage / healthCheck） */
    private final RestClient restClient = RestClient.create();
    /** SSE 长连接消费（需要逐行读流，RestClient 不适合） */
    private final HttpClient httpClient = HttpClient.newHttpClient();
    /** SSE 桥接线程池：每条 SSE 会话一个任务， cached 池按需伸缩 */
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 转发 streamable-http JSON-RPC 请求到云机。
     *
     * <p>API：{@code POST {backendBaseUrl}/mcp}，请求体原样透传，同步返回响应体。
     *
     * <p>伪代码：
     * <pre>
     *   POST backend/mcp  + Authorization: Bearer userJwt + body
     *   成功 -> 返回响应字符串（通知类后端返回 202 无 body -> null）
     *   失败 -> 抛 BackendException（上层 502）
     * </pre>
     *
     * @param backendBaseUrl 云机 MCP 地址（http://ip:port，来自 RouteService）
     * @param jsonRpcBody    Agent 发来的 JSON-RPC 原文
     * @param userJwt        proxy 签发的用户 JWT（RS256），云机公钥验签
     * @return 云机响应原文；通知类消息返回 null
     */
    @Override
    public String forwardPost(String backendBaseUrl, String jsonRpcBody, String userJwt) {
        try {
            return restClient.post()
                    .uri(backendBaseUrl + "/mcp")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + userJwt)   // 携带用户 JWT，云机验签
                    .body(jsonRpcBody)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new BackendException("cloud phone MCP unreachable: " + backendBaseUrl, e);
        }
    }

    /**
     * 转发 SSE 会话内的 JSON-RPC 消息到云机 /message 端点。
     *
     * <p>API：{@code POST {backendBaseUrl}/message?sessionId=xxx}，后端固定回 202，响应经 SSE 通道回推。
     *
     * <p>伪代码：POST backend/message?sessionId + JWT + body -> 忽略响应体（202），异常 -> BackendException。
     *
     * @param sessionId 云机 SSE 会话 ID（由云机 endpoint 事件下发，proxy 原样透传）
     */
    @Override
    public void forwardMessage(String backendBaseUrl, String sessionId, String jsonRpcBody, String userJwt) {
        try {
            restClient.post()
                    .uri(backendBaseUrl + "/message?sessionId=" + sessionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + userJwt)
                    .body(jsonRpcBody)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            throw new BackendException("cloud phone MCP unreachable: " + backendBaseUrl, e);
        }
    }

    /**
     * 调用云机 healthz 判活（就绪门控与 30s 定时探活共用）。
     *
     * <p>API：{@code GET {backendBaseUrl}/healthz}，2xx 判活，其余（含超时/连接拒绝）判死。
     *
     * <p>伪代码：try GET /healthz -> is2xx；catch 任何异常 -> false。
     *
     * @return true=云机 MCP 存活
     */
    @Override
    public boolean healthCheck(String backendBaseUrl) {
        try {
            return restClient.get()
                    .uri(backendBaseUrl + "/healthz")
                    .retrieve()
                    .toBodilessEntity()
                    .getStatusCode()
                    .is2xxSuccessful();
        } catch (Exception e) {
            return false;   // 判活接口语义：任何异常都视为不健康，而不是抛错
        }
    }

    /**
     * 桥接云机 SSE 长连接到 Agent 侧 SseEmitter。
     *
     * <p>API：异步消费 {@code GET {backendBaseUrl}/sse}，把事件逐条写入 emitter。
     * 关键点：云机下发的 {@code endpoint} 事件 data 是相对路径 {@code /message?sessionId=xxx}，
     * 必须重写为 proxy 入口 {@code /mcp/{instanceId}/message?sessionId=xxx}，Agent 才会把后续
     * 消息 POST 回 proxy 而不是直连云机。
     *
     * <p>伪代码：
     * <pre>
     *   线程池提交:
     *     GET backend/sse（带 JWT）按行读流
     *     loop 每行:
     *       空行        -> 一个事件结束：emit(event, data)；endpoint 事件先重写 data 前缀
     *       "event: x"  -> 记录事件名
     *       "data: y"   -> 追加到 data 缓冲（多行 data 用 \n 拼接）
     *     流结束 -> emitter.complete()；异常 -> emitter.completeWithError()
     * </pre>
     *
     * @param emitter proxy 返回给 Agent 的 SSE 发射器（桥接写目标）
     */
    @Override
    public void proxySse(String backendBaseUrl, String instanceId, SseEmitter emitter, String userJwt) {
        executor.submit(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(backendBaseUrl + "/sse"))
                        .header("Authorization", "Bearer " + userJwt)
                        .GET().build();
                HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
                String event = null;                 // 当前事件名（SSE 协议：event: 行）
                StringBuilder data = new StringBuilder(); // 当前事件数据缓冲（SSE 允许多行 data:）
                try (Stream<String> lines = response.body()) {
                    for (String line : (Iterable<String>) lines::iterator) {
                        if (line.isEmpty()) {
                            // 空行 = 事件边界，派发一个完整事件
                            if (event != null) {
                                String payload = data.toString();
                                if ("endpoint".equals(event)) {
                                    // 关键：endpoint 重写，把云机内部路径换成 proxy 对外路径
                                    payload = "/mcp/" + instanceId + payload;
                                }
                                emitter.send(SseEmitter.event().name(event).data(payload));
                            }
                            event = null;
                            data.setLength(0);
                        } else if (line.startsWith("event:")) {
                            event = line.substring(6).trim();
                        } else if (line.startsWith("data:")) {
                            if (data.length() > 0) {
                                data.append('\n');
                            }
                            data.append(line.substring(5).trim());
                        }
                    }
                }
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
    }
}
