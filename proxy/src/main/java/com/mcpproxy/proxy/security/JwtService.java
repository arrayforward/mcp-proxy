package com.mcpproxy.proxy.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private static final long TTL_MILLIS = 30L * 60L * 1000L;

    private final SecretKey key;

    public JwtService(@Value("${security.jwt.secret:mcp-proxy-dev-secret-key-0123456789abcdef}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String issue(String uid, String instanceId) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(uid)
                .claim("uid", uid)
                .claim("instanceId", instanceId)
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date(now))
                .expiration(new Date(now + TTL_MILLIS))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public long expiresInSeconds() {
        return TTL_MILLIS / 1000L;
    }
}
