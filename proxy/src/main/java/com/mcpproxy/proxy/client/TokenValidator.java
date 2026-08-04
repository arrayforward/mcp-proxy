package com.mcpproxy.proxy.client;

public interface TokenValidator {

    ValidationResult validate(String token);

    record ValidationResult(boolean valid, String uid, String instanceId, String reason) {
    }
}
