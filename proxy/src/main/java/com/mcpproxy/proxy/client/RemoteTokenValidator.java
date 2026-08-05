package com.mcpproxy.proxy.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * TokenValidator 的远程实现。
 *
 * <p>功能：调用统一认证服务（mock: auth-validator-mock）的 {@code POST /api/validate/token}。
 *
 * <p>开发思路：fail-safe——校验服务不可达时返回 valid=false（reason=validator-unreachable），
 * 让上层统一按 401 处理，而不是把内部异常抛给 Agent。
 *
 * @author hubin
 */
@Component
public class RemoteTokenValidator implements TokenValidator {

    private final RestClient restClient;

    public RemoteTokenValidator(@Value("${koophone.validator-url:http://localhost:9092}") String validatorUrl) {
        this.restClient = RestClient.create(validatorUrl);
    }

    /**
     * 远程校验 token。
     *
     * <p>伪代码：
     * <pre>
     *   POST {validator}/api/validate/token {token}
     *   响应 valid==true -> (true, uid, instanceId, null)
     *   响应 valid==false/空响应/异常 -> (false, null, null, reason)
     * </pre>
     */
    @Override
    @SuppressWarnings("unchecked")
    public ValidationResult validate(String token) {
        try {
            Map<String, Object> resp = restClient.post()
                    .uri("/api/validate/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("token", token))
                    .retrieve()
                    .body(Map.class);
            if (resp == null) {
                return new ValidationResult(false, null, null, "empty-response");
            }
            boolean valid = Boolean.TRUE.equals(resp.get("valid"));
            return new ValidationResult(valid,
                    (String) resp.get("uid"),
                    (String) resp.get("instanceId"),
                    (String) resp.get("reason"));
        } catch (Exception e) {
            return new ValidationResult(false, null, null, "validator-unreachable");
        }
    }
}
