package com.mcpproxy.proxy.web;

import com.mcpproxy.proxy.client.TokenValidator;
import com.mcpproxy.proxy.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final TokenValidator tokenValidator;
    private final JwtService jwtService;

    public AuthController(TokenValidator tokenValidator, JwtService jwtService) {
        this.tokenValidator = tokenValidator;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        TokenValidator.ValidationResult result = tokenValidator.validate(body.get("token"));
        if (!result.valid()) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid token"));
        }
        return ResponseEntity.ok(tokenBody(result.uid(), result.instanceId()));
    }

    @PostMapping("/exchange")
    public ResponseEntity<Map<String, Object>> exchange(@RequestBody Map<String, String> body) {
        try {
            Claims claims = jwtService.parse(body.get("accessToken"));
            return ResponseEntity.ok(tokenBody(
                    claims.get("uid", String.class),
                    claims.get("instanceId", String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid token"));
        }
    }

    @GetMapping("/public-key")
    public ResponseEntity<Map<String, Object>> publicKey() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("publicKey", jwtService.publicKeyBase64());
        body.put("pem", jwtService.publicKeyPem());
        body.put("alg", "RS256");
        return ResponseEntity.ok(body);
    }

    @GetMapping("/jwks")
    public ResponseEntity<Map<String, Object>> jwks() {
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("keys", java.util.List.of(jwtService.publicKeyJwk()));
        return ResponseEntity.ok(keys);
    }

    private Map<String, Object> tokenBody(String uid, String instanceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accessToken", jwtService.issue(uid, instanceId));
        body.put("tokenType", "Bearer");
        body.put("expiresIn", jwtService.expiresInSeconds());
        body.put("uid", uid);
        body.put("instanceId", instanceId);
        return body;
    }
}
