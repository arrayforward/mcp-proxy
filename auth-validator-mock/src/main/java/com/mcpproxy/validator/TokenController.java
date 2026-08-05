package com.mcpproxy.validator;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 临时 token 签发/校验（模拟统一认证服务，external-api.md E1/E2）。
 *
 * <p>功能：
 * <ul>
 *   <li>{@code /api/token/issue}：按 uid + instanceId 签发 10s 临时 token；</li>
 *   <li>{@code /api/validate/token}：校验格式与时效，返回绑定的 uid/instanceId。</li>
 * </ul>
 *
 * <p>token 格式：{@code tmp.<uid>.<instanceId>.<签发毫秒时间戳>}。
 * 校验规则：4 段、首段 tmp、now - ts <= 10s。真实认证服务需改为签名 + 一次性消费（security.md §7）。
 *
 * @author hubin
 * @since 2026-08-04
 */
@RestController
public class TokenController {

    /** 临时 token 时效：10 秒（登录窗口，超时即失效防重放） */
    private static final long TOKEN_TTL_MILLIS = 10_000L;

    /**
     * 签发临时 token。
     *
     * <p>伪代码：token = "tmp." + uid + "." + instanceId + "." + now；返回 {token, expiresIn:10}。
     */
    @PostMapping("/api/token/issue")
    public Map<String, Object> issue(@RequestBody Map<String, String> body) {
        String uid = body.getOrDefault("uid", "anonymous");
        String instanceId = body.getOrDefault("instanceId", "");
        long now = System.currentTimeMillis();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", "tmp." + uid + "." + instanceId + "." + now);
        resp.put("expiresIn", TOKEN_TTL_MILLIS / 1000L);
        return resp;
    }

    /**
     * 校验临时 token。
     *
     * <p>伪代码：
     * <pre>
     *   按 . 切成 4 段且首段为 tmp，否则 -> {valid:false, reason:malformed}
     *   ts 解析失败 -> malformed
     *   now - ts > 10s -> {valid:false, reason:expired}
     *   否则 -> {valid:true, uid, instanceId, expiresAt}
     * </pre>
     */
    @PostMapping("/api/validate/token")
    public Map<String, Object> validate(@RequestBody Map<String, String> body) {
        String token = body.getOrDefault("token", "");
        Map<String, Object> resp = new LinkedHashMap<>();
        String[] parts = token.split("\\.");
        if (parts.length != 4 || !"tmp".equals(parts[0])) {
            resp.put("valid", false);
            resp.put("reason", "malformed");
            return resp;
        }
        long issuedAt;
        try {
            issuedAt = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            resp.put("valid", false);
            resp.put("reason", "malformed");
            return resp;
        }
        long now = System.currentTimeMillis();
        if (now - issuedAt > TOKEN_TTL_MILLIS) {
            resp.put("valid", false);
            resp.put("reason", "expired");
            return resp;
        }
        resp.put("valid", true);
        resp.put("uid", parts[1]);
        resp.put("instanceId", parts[2]);
        resp.put("expiresAt", issuedAt + TOKEN_TTL_MILLIS);
        return resp;
    }
}
