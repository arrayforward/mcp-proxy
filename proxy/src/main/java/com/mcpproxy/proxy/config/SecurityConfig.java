package com.mcpproxy.proxy.config;

import com.mcpproxy.proxy.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置。
 *
 * <p>功能：定义网关的授权规则——只有 /mcp/**（MCP 代理三传输入口）要求认证，
 * 其余（实例管理 API、登录、swagger、WS 握手）放行但各自有独立鉴权逻辑：
 * 实例 API 靠 x-auth-token 远程校验，WS 握手在 Handler 内自验 JWT。
 *
 * <p>开发思路：
 * <ul>
 *   <li>无状态（STATELESS）+ 关 CSRF：纯 token API，无会话无表单；</li>
 *   <li>未认证访问 /mcp/** 时返回 401 而非默认 403（自定义 authenticationEntryPoint）；</li>
 *   <li>JwtAuthFilter 插在用户名密码过滤器之前，只做"身份注入"不做拒绝。</li>
 * </ul>
 *
 * @author hubin
 * @since 2026-08-04
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 构建安全过滤链。
     *
     * <p>伪代码：
     * <pre>
     *   csrf off + session off
     *   /mcp/**      -> authenticated()（由 JwtAuthFilter 注入身份）
     *   其余所有     -> permitAll（各自鉴权：x-auth-token / WS 内验签 / 公开）
     *   未认证访问受保护路径 -> 401
     * </pre>
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(registry -> registry
                        .requestMatchers("/mcp/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(handling -> handling.authenticationEntryPoint(
                        (request, response, exception) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
