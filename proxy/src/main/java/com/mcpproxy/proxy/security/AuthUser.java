package com.mcpproxy.proxy.security;

/**
 * 已认证用户主体（SecurityContext 中的 principal）。
 *
 * <p>token 字段保存原始 JWT：proxy 转发请求到云机 mcp-server 时原样携带，
 * 云机用预置公钥二次验签（见 docs/security.md §3）。
 *
 * @param uid        用户 ID（JWT claim uid）
 * @param instanceId 绑定实例 ID（JWT claim instanceId，用于路径比对）
 * @param token      原始 JWT 字符串（转发用）
 *
 * @author hubin
 * @since 2026-08-04
 */
public record AuthUser(String uid, String instanceId, String token) {
}
