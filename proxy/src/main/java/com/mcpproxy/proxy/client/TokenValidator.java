package com.mcpproxy.proxy.client;

/**
 * 临时 token 校验抽象（统一认证服务客户端）。
 *
 * <p>功能：登录与实例管理 API 共用——把 10s 临时 token 换成可信的 uid + instanceId。
 * 默认实现 {@link RemoteTokenValidator} 远程调用 auth-validator-mock（:9092）。
 *
 * @author hubin
 */
public interface TokenValidator {

    /**
     * 校验临时 token。
     *
     * @param token 10s 临时 token（tmp.uid.instanceId.ts）
     * @return 校验结果（valid + uid/instanceId；失败带 reason）
     */
    ValidationResult validate(String token);

    /**
     * 校验结果值对象。
     *
     * @param valid      是否有效（格式正确且未过 10s 时效）
     * @param uid        token 绑定用户（valid=true 时非空）
     * @param instanceId token 绑定实例
     * @param reason     失败原因：malformed / expired / validator-unreachable
     */
    record ValidationResult(boolean valid, String uid, String instanceId, String reason) {
    }
}
