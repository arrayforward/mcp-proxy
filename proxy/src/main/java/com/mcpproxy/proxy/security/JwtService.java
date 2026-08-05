package com.mcpproxy.proxy.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private static final long TTL_MILLIS = 30L * 60L * 1000L;

    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public JwtService(@Value("${security.jwt.private-key:}") String privateKeyMaterial,
                      @Value("${security.jwt.public-key:}") String publicKeyMaterial) {
        try {
            if (privateKeyMaterial == null || privateKeyMaterial.isBlank()) {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair keyPair = generator.generateKeyPair();
                this.privateKey = keyPair.getPrivate();
                this.publicKey = keyPair.getPublic();
                log.warn("security.jwt.private-key not configured, generated ephemeral dev keypair; "
                        + "cloud phone mcp-servers cannot verify tokens until the public key is distributed");
            } else {
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                this.privateKey = keyFactory.generatePrivate(
                        new PKCS8EncodedKeySpec(decodeKeyMaterial(privateKeyMaterial)));
                if (publicKeyMaterial != null && !publicKeyMaterial.isBlank()) {
                    this.publicKey = keyFactory.generatePublic(
                            new X509EncodedKeySpec(decodeKeyMaterial(publicKeyMaterial)));
                } else {
                    RSAPrivateCrtKey crtKey = (RSAPrivateCrtKey) this.privateKey;
                    this.publicKey = keyFactory.generatePublic(
                            new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to load RSA key material", e);
        }
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
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token).getPayload();
    }

    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /** PEM 编码的公钥（BEGIN PUBLIC KEY），供云机 mcp_mobile_use 落盘配置 */
    public String publicKeyPem() {
        return "-----BEGIN PUBLIC KEY-----\n" +
                Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(publicKey.getEncoded()) +
                "\n-----END PUBLIC KEY-----\n";
    }

    /** JWKS（RFC 7517）形式的公钥，供标准 JWKS 客户端拉取 */
    public Map<String, Object> publicKeyJwk() {
        RSAPublicKey rsa = (RSAPublicKey) publicKey;
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        jwk.put("kid", "mcp-proxy-rs256-1");
        jwk.put("n", Base64.getUrlEncoder().withoutPadding().encodeToString(rsa.getModulus().toByteArray()));
        jwk.put("e", Base64.getUrlEncoder().withoutPadding().encodeToString(rsa.getPublicExponent().toByteArray()));
        return jwk;
    }

    public long expiresInSeconds() {
        return TTL_MILLIS / 1000L;
    }

    private static byte[] decodeKeyMaterial(String material) {
        String cleaned = material
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(cleaned);
    }
}
