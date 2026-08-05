package com.mcpproxy.proxy.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * KooPhoneClient 的 Mock 实现（默认 Bean）。
 *
 * <p>功能：不依赖真实华为云，本地模拟云控制面行为。
 *
 * <p>开发思路：
 * <ul>
 *   <li>订单号/实例 ID 用 SecureRandom 生成，格式与真实一致（CS+日期+8位 / 8位实例 ID）；</li>
 *   <li>{@link #fetchAccessInfo} 返回<b>配置化</b>的 ip/port（koophone.mock.phone-ip /
 *       koophone.mock.mcp-port），默认 127.0.0.1:9091 指向 mcp-mock——这样 e2e 可以把端口
 *       换成动态端口，形成"mock 云机"闭环；</li>
 *   <li>切真实环境时把本 Bean 换成华为 REST 实现，业务层零改动。</li>
 * </ul>
 *
 * @author hubin
 */
@Component
public class MockKooPhoneClient implements KooPhoneClient {

    private static final char[] ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private final SecureRandom random = new SecureRandom();
    /** Mock 云机 IP（实际指向 mcp-mock 监听地址） */
    private final String mockPhoneIp;
    /** Mock 云机 MCP 端口 */
    private final int mockMcpPort;

    public MockKooPhoneClient(
            @Value("${koophone.mock.phone-ip:127.0.0.1}") String mockPhoneIp,
            @Value("${koophone.mock.mcp-port:9091}") int mockMcpPort) {
        this.mockPhoneIp = mockPhoneIp;
        this.mockMcpPort = mockMcpPort;
    }

    /** 订单号 = "CS" + yyyyMMdd + 8 位随机大写（贴近华为单号风格） */
    @Override
    public String createOrderId() {
        return "CS" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + randomChars(8).toUpperCase();
    }

    /** 实例 ID = 8 位随机字母数字 */
    @Override
    public String newInstanceId() {
        return randomChars(8);
    }

    /**
     * E4 Mock：忽略 instanceId，直接返回配置的云机地址。
     *
     * <p>伪代码：return (mockPhoneIp, mockMcpPort)。真实实现会按 instanceId 查华为返回真实 IP。
     */
    @Override
    public AccessInfo fetchAccessInfo(String instanceId) {
        return new AccessInfo(mockPhoneIp, mockMcpPort);
    }

    private String randomChars(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALNUM[random.nextInt(ALNUM.length)]);
        }
        return sb.toString();
    }
}
