package com.mcpproxy.proxy.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器（Spring Security 链路）。
 *
 * <p>功能：把 Bearer JWT（或 ?token= 查询参数，供 SSE/WS 无法设头的场景）解析成
 * {@link AuthUser} 注入 SecurityContext，供 /mcp/** 的 authenticated() 门控使用。
 *
 * <p>开发思路：
 * <ul>
 *   <li>宽容解析：token 缺失/非法时不直接 401，而是"不注入身份"——
 *       是否 401 由 SecurityConfig 的授权规则统一决定（/mcp/** 无身份 -> 401）；</li>
 *   <li>AuthUser 里同时携带原始 token，转发到云机时原样透传（云机二次验签）。</li>
 * </ul>
 *
 * @author hubin
 * @since 2026-08-04
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /**
     * 每个请求执行一次的身份解析。
     *
     * <p>伪代码：
     * <pre>
     *   token = Authorization: Bearer 头，缺省再取 ?token= 参数
     *   token 非空:
     *     try 验签解析 -> AuthUser(uid, instanceId, token) 注入 SecurityContext
     *     catch       -> 忽略（视为匿名，交给授权规则处理）
     *   chain.doFilter
     * </pre>
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = null;
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7);
        } else {
            // SSE 的 EventSource / WS 握手不方便设头，允许查询参数携带
            token = request.getParameter("token");
        }
        if (token != null && !token.isBlank()) {
            try {
                Claims claims = jwtService.parse(token);
                AuthUser user = new AuthUser(
                        claims.get("uid", String.class),
                        claims.get("instanceId", String.class),
                        token);
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(user, null,
                                List.of(new SimpleGrantedAuthority("ROLE_USER")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException ignored) {
                // 故意吞掉：未注入身份，/mcp/** 会被授权层拦成 401
            }
        }
        chain.doFilter(request, response);
    }
}
