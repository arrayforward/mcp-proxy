package com.mcpproxy.proxy;

import com.mcpproxy.proxy.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService("0123456789abcdef0123456789abcdef");

    @Test
    void issueAndParseRoundTrip() {
        String token = jwtService.issue("user-10001", "Ab3xYz9p");
        Claims claims = jwtService.parse(token);
        assertEquals("user-10001", claims.getSubject());
        assertEquals("user-10001", claims.get("uid", String.class));
        assertEquals("Ab3xYz9p", claims.get("instanceId", String.class));
        assertTrue(claims.getExpiration().getTime() > System.currentTimeMillis());
    }

    @Test
    void tamperedTokenRejected() {
        String token = jwtService.issue("user-10001", "Ab3xYz9p");
        assertThrows(JwtException.class, () -> jwtService.parse(token + "tampered"));
    }

    @Test
    void differentSecretRejected() {
        JwtService other = new JwtService("fedcba9876543210fedcba9876543210");
        String token = other.issue("user-10001", "Ab3xYz9p");
        assertThrows(JwtException.class, () -> jwtService.parse(token));
    }
}
