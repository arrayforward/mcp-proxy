package com.mcpproxy.proxy.web;

import com.mcpproxy.proxy.client.TokenValidator;
import com.mcpproxy.proxy.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证 API：两层令牌体系的入口。
 *
 * <p>功能：
 * <ul>
 *   <li>{@code /login}：10s 临时 token -> 30min RS256 JWT（绑定 uid + instanceId）；</li>
 *   <li>{@code /exchange}：旧 JWT（未过期）-> 同 uid/instanceId 的新 30min JWT（续期）。</li>
 * </ul>
 *
 * <p>开发思路：login 走远程校验服务拿可信身份；exchange 只靠本地验签（ADR-6），
 * 旧 JWT 过期即 401，逼迫客户端重新走 10s token 登录，防止无限续期。
 *
 * @author hubin
 * @since 2026-08-04
 */
@Tag(name = "认证", description = "10s token 换 30min JWT / JWT 续期")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final TokenValidator tokenValidator;
    private final JwtService jwtService;

    public AuthController(TokenValidator tokenValidator, JwtService jwtService) {
        this.tokenValidator = tokenValidator;
        this.jwtService = jwtService;
    }

    /**
     * 登录：临时 token 换访问 JWT。
     *
     * <p>伪代码：
     * <pre>
     *   validate(token) -> 无效: 401 {error:"invalid token"}
     *                   -> 有效: 用 uid+instanceId 签发 RS256 JWT，返回标准 token 响应
     * </pre>
     */
    @Operation(summary = "登录：10s 临时 token 换 30min JWT")
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        TokenValidator.ValidationResult result = tokenValidator.validate(body.get("token"));
        if (!result.valid()) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid token"));
        }
        return ResponseEntity.ok(tokenBody(result.uid(), result.instanceId()));
    }

    /**
     * 续期：旧 JWT 换新 JWT（uid/instanceId 不变，有效期重计 30min）。
     *
     * <p>伪代码：parse(accessToken) -> 验签失败/过期 401；成功 -> 按原 claims 重新签发。
     */
    @Operation(summary = "续期：旧 JWT 换新 30min JWT")
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

    /** 标准令牌响应：accessToken + tokenType + expiresIn + uid + instanceId */
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
