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

/**
 * JWT 验签过滤器（模拟云机 mcp-server 的验签行为，对应 mcp_mobile_use --auth-jwt-public-key）。
 *
 * <p>功能：除 /healthz 外的所有请求，必须用预置 RSA 公钥验签 proxy 签发的 RS256 JWT，
 * 失败返回 401（security.md §8 验签规则）。
 *
 * <p>开发思路：
 * <ul>
 *   <li>公钥经 mcp.auth.public-key（MCP_JWT_PUBLIC_KEY）注入，对应"公钥分发到每台云机"；</li>
 *   <li>未配置公钥时放行全部请求并 WARN——本地开发模式，生产必须配置；</li>
 *   <li>验签通过把 uid/instanceId 存 request attribute，模拟云机按身份隔离的语境。</li>
 * </ul>
 *
 * @author hubin
 * @since 2026-08-05
 */
@Component
public class JwtVerifyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtVerifyFilter.class);

    /** 验签公钥；null 表示 dev 模式（不验签） */
    private final PublicKey publicKey;

    /**
     * 构造：加载 X.509 公钥（支持 PEM 全文或单行 Base64）；为空则 dev 模式。
     */
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

    /** healthz 免验签；dev 模式（无公钥）全部放行 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/healthz") || publicKey == null;
    }

    /**
     * 验签主逻辑。
     *
     * <p>伪代码：
     * <pre>
     *   无 Bearer 头 -> 401 missing bearer token
     *   公钥验签 + exp 检查 -> 失败: 401 invalid token
     *   通过 -> uid/instanceId 存 request attribute -> 放行
     * </pre>
     */
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
