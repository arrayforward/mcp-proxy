package com.mcpproxy.proxy;

import com.mcpproxy.proxy.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService("", "");

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
    void tokenVerifiableWithDistributedPublicKey() throws Exception {
        String token = jwtService.issue("user-10001", "Ab3xYz9p");
        byte[] publicKeyBytes = Base64.getDecoder().decode(jwtService.publicKeyBase64());
        java.security.PublicKey publicKey = java.security.KeyFactory.getInstance("RSA")
                .generatePublic(new java.security.spec.X509EncodedKeySpec(publicKeyBytes));
        Claims claims = Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token).getPayload();
        assertEquals("Ab3xYz9p", claims.get("instanceId", String.class));
    }

    @Test
    void configuredKeyPairRoundTrip() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String privateKey = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        JwtService configured = new JwtService(privateKey, publicKey);
        Claims claims = configured.parse(configured.issue("u1", "i1"));
        assertEquals("u1", claims.get("uid", String.class));
    }

    @Test
    void differentKeyPairRejected() {
        JwtService other = new JwtService("", "");
        String token = other.issue("user-10001", "Ab3xYz9p");
        assertThrows(JwtException.class, () -> jwtService.parse(token));
    }

    @Test
    void tamperedTokenRejected() {
        String token = jwtService.issue("user-10001", "Ab3xYz9p");
        assertThrows(JwtException.class, () -> jwtService.parse(token + "tampered"));
    }
}
