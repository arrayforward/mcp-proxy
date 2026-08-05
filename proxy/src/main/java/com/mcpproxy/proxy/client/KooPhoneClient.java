package com.mcpproxy.proxy.client;

/**
 * 云手机控制面（华为 KooPhone）客户端抽象。
 *
 * <p>功能：屏蔽"真实华为 REST"与"Mock 实现"的差异，业务层只面向本接口编程（ADR-5）。
 * 默认实现 {@link MockKooPhoneClient}；对接真实华为时新增 RestClient 实现并替换 Bean 即可。
 *
 * @author hubin
 */
public interface KooPhoneClient {

    /**
     * 生成订单号（CreateInstance 响应的一部分）。
     *
     * @return 订单号，如 CS20260804A1B2C3D4
     */
    String createOrderId();

    /**
     * 生成实例 ID（全局唯一，MCP URL 的一部分）。
     *
     * @return 8 位字母数字实例 ID
     */
    String newInstanceId();

    /**
     * E4 接口：按 instanceId 查询云机访问信息（IP + MCP 端口）。
     *
     * <p>调用时机：实例就绪时、或缓存与 MySQL 均未命中时；获取成功后由调用方落库（见 external-api.md §5）。
     *
     * @param instanceId 云手机实例 ID
     * @return 云机 IP 与 MCP 端口
     */
    AccessInfo fetchAccessInfo(String instanceId);

    /** 云机访问信息值对象（E4 接口响应） */
    record AccessInfo(String ip, int mcpPort) {
    }
}
