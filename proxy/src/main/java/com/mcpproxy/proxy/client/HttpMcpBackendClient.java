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

@Component
public class HttpMcpBackendClient implements McpBackendClient {

    private final RestClient restClient = RestClient.create();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    public String forwardPost(String backendBaseUrl, String jsonRpcBody) {
        try {
            return restClient.post()
                    .uri(backendBaseUrl + "/mcp")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(jsonRpcBody)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new BackendException("cloud phone MCP unreachable: " + backendBaseUrl, e);
        }
    }

    @Override
    public void forwardMessage(String backendBaseUrl, String sessionId, String jsonRpcBody) {
        try {
            restClient.post()
                    .uri(backendBaseUrl + "/message?sessionId=" + sessionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonRpcBody)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            throw new BackendException("cloud phone MCP unreachable: " + backendBaseUrl, e);
        }
    }

    @Override
    public void proxySse(String backendBaseUrl, String instanceId, SseEmitter emitter) {
        executor.submit(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(backendBaseUrl + "/sse")).GET().build();
                HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
                String event = null;
                StringBuilder data = new StringBuilder();
                try (Stream<String> lines = response.body()) {
                    for (String line : (Iterable<String>) lines::iterator) {
                        if (line.isEmpty()) {
                            if (event != null) {
                                String payload = data.toString();
                                if ("endpoint".equals(event)) {
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
