package com.mcpproxy.proxy.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class RemoteTokenValidator implements TokenValidator {

    private final RestClient restClient;

    public RemoteTokenValidator(@Value("${koophone.validator-url:http://localhost:9092}") String validatorUrl) {
        this.restClient = RestClient.create(validatorUrl);
    }

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
