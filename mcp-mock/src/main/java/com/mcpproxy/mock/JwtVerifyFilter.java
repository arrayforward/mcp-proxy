package com.mcpproxy.mock;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Component
public class JwtVerifyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtVerifyFilter.class);

    private final PublicKey publicKey;

    public JwtVerifyFilter(@Value("${mcp.auth.public-key:}") String publicKeyMaterial) {
        PublicKey key = null;
        if (publicKeyMaterial != null && !publicKeyMaterial.isBlank()) {
            try {
                String cleaned = publicKeyMaterial
                        .replaceAll("-----BEGIN [A-Z ]+-----", "")
                        .replaceAll("-----END [A-Z ]+-----", "")
                        .replaceAll("\\s", "");
                key = KeyFactory.getInstance("RSA").generatePublic(
                        new X509EncodedKeySpec(Base64.getDecoder().decode(cleaned)));
                log.info("JWT verification enabled (RS256 public key loaded)");
            } catch (Exception e) {
                throw new IllegalStateException("failed to load mcp.auth.public-key", e);
            }
        } else {
            log.warn("mcp.auth.public-key not configured, JWT verification DISABLED (dev mode)");
        }
        this.publicKey = key;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/healthz") || publicKey == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "missing bearer token");
            return;
        }
        try {
            Claims claims = Jwts.parser().verifyWith(publicKey).build()
                    .parseSignedClaims(header.substring(7)).getPayload();
            request.setAttribute("jwt.uid", claims.get("uid", String.class));
            request.setAttribute("jwt.instanceId", claims.get("instanceId", String.class));
        } catch (JwtException | IllegalArgumentException e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "invalid token");
            return;
        }
        chain.doFilter(request, response);
    }
}
