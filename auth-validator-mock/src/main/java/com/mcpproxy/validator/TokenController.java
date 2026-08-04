package com.mcpproxy.validator;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class TokenController {

    private static final long TOKEN_TTL_MILLIS = 10_000L;

    @PostMapping("/api/token/issue")
    public Map<String, Object> issue(@RequestBody Map<String, String> body) {
        String uid = body.getOrDefault("uid", "anonymous");
        String instanceId = body.getOrDefault("instanceId", "");
        long now = System.currentTimeMillis();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", "tmp." + uid + "." + instanceId + "." + now);
        resp.put("expiresIn", TOKEN_TTL_MILLIS / 1000L);
        return resp;
    }

    @PostMapping("/api/validate/token")
    public Map<String, Object> validate(@RequestBody Map<String, String> body) {
        String token = body.getOrDefault("token", "");
        Map<String, Object> resp = new LinkedHashMap<>();
        String[] parts = token.split("\\.");
        if (parts.length != 4 || !"tmp".equals(parts[0])) {
            resp.put("valid", false);
            resp.put("reason", "malformed");
            return resp;
        }
        long issuedAt;
        try {
            issuedAt = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            resp.put("valid", false);
            resp.put("reason", "malformed");
            return resp;
        }
        long now = System.currentTimeMillis();
        if (now - issuedAt > TOKEN_TTL_MILLIS) {
            resp.put("valid", false);
            resp.put("reason", "expired");
            return resp;
        }
        resp.put("valid", true);
        resp.put("uid", parts[1]);
        resp.put("instanceId", parts[2]);
        resp.put("expiresAt", issuedAt + TOKEN_TTL_MILLIS);
        return resp;
    }
}
