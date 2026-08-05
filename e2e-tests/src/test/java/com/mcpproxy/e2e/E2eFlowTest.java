package com.mcpproxy.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpproxy.mock.MockMcpApplication;
import com.mcpproxy.proxy.ProxyApplication;
import com.mcpproxy.validator.ValidatorApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端全流程测试：真实本地 MySQL 8.0 + Redis 5.0 上跑通完整生命周期。
 *
 * <p>覆盖路径（按 @Order 顺序）：
 * <pre>
 *   1. 订阅 create -> 准备 prepare -> 轮询 prepare-progress 直到就绪
 *   2. access-info 取 ip/port 并断言落 MySQL（含 healthy 标记）
 *   3. 登录 login -> streamable-http MCP（initialize/tools/list 26 个/tools/call）
 *      + healthz 探活 + sandbox/adb_shell 工具透传 + 云机无 JWT 拒绝（401）
 *   4. exchange 续期
 *   5. SSE 会话（endpoint 重写 + message 回推）
 *   6. WebSocket 桥接
 *   7-8. 越权 403 / 无 token 401
 *   9. 退订 delete -> 再访问 404
 * </pre>
 *
 * <p>开发思路：三个 Spring 应用同 JVM 启动（随机端口 + profile 隔离配置），
 * 动态生成 RSA-2048 密钥对分别注入 proxy（私钥）与 mcp-mock（公钥），
 * 还原"proxy 签发、云机验签"的生产形态。
 *
 * @author hubin
 * @since 2026-08-04
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class E2eFlowTest {

    private static final String UID = "user-10001";

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient http = RestClient.create();

    private ConfigurableApplicationContext validatorApp;
    private ConfigurableApplicationContext mockApp;
    private ConfigurableApplicationContext proxyApp;

    private String validatorBase;
    private String proxyBase;

    private String instanceId;
    private String jwt;

    private String mockBase;

    @BeforeAll
    void startAll() throws Exception {
        java.security.KeyPairGenerator keyGen = java.security.KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        java.security.KeyPair keyPair = keyGen.generateKeyPair();
        String privateKeyB64 = java.util.Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        String publicKeyB64 = java.util.Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        validatorApp = new SpringApplicationBuilder(ValidatorApplication.class)
                .run("--spring.profiles.active=validator", "--server.port=0");
        mockApp = new SpringApplicationBuilder(MockMcpApplication.class)
                .run("--spring.profiles.active=mock", "--server.port=0",
                        "--mcp.auth.public-key=" + publicKeyB64);
        int mockPort = portOf(mockApp);
        mockBase = "http://localhost:" + mockPort;
        proxyApp = new SpringApplicationBuilder(ProxyApplication.class)
                .run(
                        "--spring.profiles.active=proxy",
                        "--server.port=0",
                        "--spring.datasource.url=jdbc:mysql://localhost:3306/mcpproxy?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true",
                        "--spring.datasource.username=root",
                        "--spring.datasource.password=root",
                        "--spring.data.redis.host=localhost",
                        "--spring.data.redis.port=6379",
                        "--koophone.validator-url=http://localhost:" + portOf(validatorApp),
                        "--koophone.mock.mcp-port=" + mockPort,
                        "--koophone.mock.phone-ip=127.0.0.1",
                        "--security.jwt.private-key=" + privateKeyB64);
        validatorBase = "http://localhost:" + portOf(validatorApp);
        proxyBase = "http://localhost:" + portOf(proxyApp);
    }

    @AfterAll
    void stopAll() {
        if (proxyApp != null) proxyApp.close();
        if (mockApp != null) mockApp.close();
        if (validatorApp != null) validatorApp.close();
    }

    private int portOf(ConfigurableApplicationContext ctx) {
        return ((WebServerApplicationContext) ctx).getWebServer().getPort();
    }

    private String issueToken(String uid, String instanceId) {
        Map<?, ?> resp = http.post().uri(validatorBase + "/api/token/issue")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("uid", uid, "instanceId", instanceId))
                .retrieve().body(Map.class);
        return (String) resp.get("token");
    }

    @Test
    @Order(1)
    @SuppressWarnings("unchecked")
    void createPrepareAndWaitReady() {
        String token = issueToken(UID, "pending");
        Map<?, ?> createResp = http.post().uri(proxyBase + "/api/v1/instances/create")
                .header("x-auth-token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("os", "AOSP14", "instanceSkuId", "kp.professional.2xlarge.128g.2",
                        "bandSkuId", "kp.bandwidth", "regionId", "cn-north-7",
                        "instanceNamePrefix", "koophone", "bandSize", 4.0, "count", 1, "network", "EIP"))
                .retrieve().body(Map.class);
        assertEquals("0", createResp.get("error_code"));
        Map<String, Object> data = (Map<String, Object>) createResp.get("data");
        List<Map<String, Object>> infos = (List<Map<String, Object>>) data.get("instanceInfos");
        instanceId = (String) infos.get(0).get("instanceId");
        assertNotNull(instanceId);

        String prepareToken = issueToken(UID, instanceId);
        Map<?, ?> prepareResp = http.post().uri(proxyBase + "/api/v1/instances/prepare")
                .header("x-auth-token", prepareToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("user_id", UID, "instance_ids", List.of(instanceId)))
                .retrieve().body(Map.class);
        assertEquals("0", prepareResp.get("error_code"));

        int status = -1;
        for (int i = 0; i < 10 && status != 0; i++) {
            String progressToken = issueToken(UID, instanceId);
            Map<?, ?> progressResp = http.post().uri(proxyBase + "/api/v1/instances/prepare-progress")
                    .header("x-auth-token", progressToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("user_id", UID, "instance_id", instanceId))
                    .retrieve().body(Map.class);
            Map<String, Object> progressData = (Map<String, Object>) progressResp.get("data");
            status = ((Number) progressData.get("status")).intValue();
        }
        assertEquals(0, status);
    }

    @Test
    @Order(2)
    @SuppressWarnings("unchecked")
    void accessInfoPersistedToMysql() {
        String token = issueToken(UID, instanceId);
        Map<?, ?> resp = http.post().uri(proxyBase + "/api/v1/instances/access-info")
                .header("x-auth-token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("instance_id", instanceId))
                .retrieve().body(Map.class);
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertEquals("127.0.0.1", data.get("mcp_ip"));
        assertNotNull(data.get("mcp_port"));

        Map<?, ?> listResp = http.post().uri(proxyBase + "/api/v1/instances/list")
                .header("x-auth-token", issueToken(UID, instanceId))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("user_id", UID, "instance_ids", List.of(instanceId)))
                .retrieve().body(Map.class);
        Map<String, Object> listData = (Map<String, Object>) listResp.get("data");
        List<Map<String, Object>> list = (List<Map<String, Object>>) listData.get("instance_list");
        assertEquals("127.0.0.1", list.get(0).get("mcp_ip"));
        assertEquals(Boolean.TRUE, list.get(0).get("healthy"));
    }

    @Test
    @Order(3)
    void healthzCheckKeepsInstanceAlive() {
        var healthCheckService = proxyApp.getBean(com.mcpproxy.proxy.health.HealthCheckService.class);
        var activityTracker = proxyApp.getBean(com.mcpproxy.proxy.health.ActivityTracker.class);
        activityTracker.recordRequest(instanceId);
        assertTrue(activityTracker.isActive(instanceId));
        assertTrue(healthCheckService.checkOne(instanceId), "healthz should pass against mcp-mock");
    }

    @Test
    @Order(3)
    @SuppressWarnings("unchecked")
    void sandboxToolsForwardedThroughProxy() throws Exception {
        loginIfNeeded();
        Map<?, ?> createResp = http.post().uri(proxyBase + "/mcp/" + instanceId)
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"jsonrpc\":\"2.0\",\"id\":41,\"method\":\"tools/call\",\"params\":{\"name\":\"create_sandbox\",\"arguments\":{}}}")
                .retrieve().body(Map.class);
        String text = mapper.writeValueAsString(createResp);
        assertTrue(text.contains("sandbox-mock-0001"), "create_sandbox result: " + text);

        Map<?, ?> adbResp = http.post().uri(proxyBase + "/mcp/" + instanceId)
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"tools/call\",\"params\":{\"name\":\"adb_shell\",\"arguments\":{\"command\":\"ls /sdcard\"}}}")
                .retrieve().body(Map.class);
        assertTrue(mapper.writeValueAsString(adbResp).contains("ls /sdcard"), "adb_shell result forwarded");
    }

    @Test
    @Order(3)
    void cloudPhoneMcpRejectsRequestWithoutJwt() {
        var resp = http.post().uri(mockBase + "/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"jsonrpc\":\"2.0\",\"id\":51,\"method\":\"ping\"}")
                .retrieve()
                .onStatus(s -> s.value() == 401, (req, res) -> { })
                .toBodilessEntity();
        assertEquals(401, resp.getStatusCode().value(), "cloud phone mcp-server must reject requests without JWT");
    }

    private void loginIfNeeded() {
        if (jwt == null) {
            String token = issueToken(UID, instanceId);
            Map<?, ?> loginResp = http.post().uri(proxyBase + "/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("token", token))
                    .retrieve().body(Map.class);
            jwt = (String) loginResp.get("accessToken");
        }
    }

    @Test
    @Order(3)
    @SuppressWarnings("unchecked")
    void loginAndMcpOverHttp() {
        String token = issueToken(UID, instanceId);
        Map<?, ?> loginResp = http.post().uri(proxyBase + "/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("token", token))
                .retrieve().body(Map.class);
        jwt = (String) loginResp.get("accessToken");
        assertNotNull(jwt);
        assertEquals(1800L, ((Number) loginResp.get("expiresIn")).longValue());

        Map<?, ?> initResp = http.post().uri(proxyBase + "/mcp/" + instanceId)
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"e2e\",\"version\":\"1.0\"}}}")
                .retrieve().body(Map.class);
        Map<String, Object> result = (Map<String, Object>) initResp.get("result");
        Map<String, Object> serverInfo = (Map<String, Object>) result.get("serverInfo");
        assertEquals("mcp-mock", serverInfo.get("name"));

        Map<?, ?> toolsResp = http.post().uri(proxyBase + "/mcp/" + instanceId)
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
                .retrieve().body(Map.class);
        Map<String, Object> toolsResult = (Map<String, Object>) toolsResp.get("result");
        assertEquals(26, ((List<?>) toolsResult.get("tools")).size());

        Map<?, ?> callResp = http.post().uri(proxyBase + "/mcp/" + instanceId)
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"tap\",\"arguments\":{\"x\":100,\"y\":200}}}")
                .retrieve().body(Map.class);
        assertNotNull(callResp.get("result"));
    }

    @Test
    @Order(4)
    @SuppressWarnings("unchecked")
    void exchangeJwt() {
        Map<?, ?> resp = http.post().uri(proxyBase + "/api/auth/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("accessToken", jwt))
                .retrieve().body(Map.class);
        String newJwt = (String) resp.get("accessToken");
        assertNotNull(newJwt);
        assertEquals(UID, resp.get("uid"));
        assertEquals(instanceId, resp.get("instanceId"));
    }

    @Test
    @Order(5)
    void mcpOverSse() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest sseRequest = HttpRequest.newBuilder(URI.create(proxyBase + "/mcp/" + instanceId + "/sse"))
                .header("Authorization", "Bearer " + jwt).GET().build();
        HttpResponse<Stream<String>> sseResponse = client.send(sseRequest, HttpResponse.BodyHandlers.ofLines());
        assertEquals(200, sseResponse.statusCode());

        BlockingQueue<Map<String, String>> events = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            String event = null;
            StringBuilder data = new StringBuilder();
            try (Stream<String> lines = sseResponse.body()) {
                for (String line : (Iterable<String>) lines::iterator) {
                    if (line.isEmpty()) {
                        if (event != null) {
                            events.offer(Map.of("event", event, "data", data.toString()));
                        }
                        event = null;
                        data.setLength(0);
                    } else if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        data.append(line.substring(5).trim());
                    }
                }
            } catch (Exception ignored) {
            }
        });
        reader.setDaemon(true);
        reader.start();

        Map<String, String> endpoint = events.poll(10, TimeUnit.SECONDS);
        assertNotNull(endpoint, "should receive endpoint event");
        assertEquals("endpoint", endpoint.get("event"));
        String rewritten = endpoint.get("data");
        assertTrue(rewritten.startsWith("/mcp/" + instanceId + "/message?sessionId="), "endpoint rewritten: " + rewritten);
        String sessionId = rewritten.substring(rewritten.indexOf("sessionId=") + "sessionId=".length());

        http.post().uri(proxyBase + "/mcp/" + instanceId + "/message?sessionId=" + sessionId)
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"ping\"}")
                .retrieve().toBodilessEntity();

        Map<String, String> message = events.poll(10, TimeUnit.SECONDS);
        assertNotNull(message, "should receive message event");
        assertEquals("message", message.get("event"));
        assertTrue(message.get("data").contains("\"id\":11"), "message payload: " + message.get("data"));
    }

    @Test
    @Order(6)
    void mcpOverWebSocket() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocketSession session = client.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                received.offer(message.getPayload());
            }
        }, null, URI.create("ws://localhost:" + portOf(proxyApp)
                + "/ws/mcp/" + instanceId + "?token=" + jwt)).get(10, TimeUnit.SECONDS);

        session.sendMessage(new TextMessage("{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"ping\"}"));
        String payload = received.poll(10, TimeUnit.SECONDS);
        assertNotNull(payload, "should receive ws response");
        assertTrue(payload.contains("\"id\":21"), "ws payload: " + payload);
        session.close();
    }

    @Test
    @Order(7)
    void crossInstanceForbidden() {
        var resp = http.post().uri(proxyBase + "/mcp/OtherInst")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"jsonrpc\":\"2.0\",\"id\":31,\"method\":\"ping\"}")
                .retrieve()
                .onStatus(s -> s.value() == 403, (req, res) -> { })
                .toBodilessEntity();
        assertEquals(403, resp.getStatusCode().value());
    }

    @Test
    @Order(8)
    void noTokenUnauthorized() {
        var resp = http.post().uri(proxyBase + "/mcp/" + instanceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"jsonrpc\":\"2.0\",\"id\":32,\"method\":\"ping\"}")
                .retrieve()
                .onStatus(s -> s.value() == 401, (req, res) -> { })
                .toBodilessEntity();
        assertEquals(401, resp.getStatusCode().value());
    }

    @Test
    @Order(9)
    @SuppressWarnings("unchecked")
    void deleteInstanceThenNotFound() {
        Map<?, ?> deleteResp = http.post().uri(proxyBase + "/api/v1/instances/delete")
                .header("x-auth-token", issueToken(UID, instanceId))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("instanceIdList", List.of(instanceId)))
                .retrieve().body(Map.class);
        assertEquals("0", deleteResp.get("error_code"));

        var resp = http.post().uri(proxyBase + "/mcp/" + instanceId)
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"jsonrpc\":\"2.0\",\"id\":33,\"method\":\"ping\"}")
                .retrieve()
                .onStatus(s -> s.value() == 404, (req, res) -> { })
                .toBodilessEntity();
        assertEquals(404, resp.getStatusCode().value());
    }
}
