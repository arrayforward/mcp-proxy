package com.mcpproxy.proxy.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Component
public class MockKooPhoneClient implements KooPhoneClient {

    private static final char[] ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private final SecureRandom random = new SecureRandom();
    private final String mockPhoneIp;
    private final int mockMcpPort;

    public MockKooPhoneClient(
            @Value("${koophone.mock.phone-ip:127.0.0.1}") String mockPhoneIp,
            @Value("${koophone.mock.mcp-port:9091}") int mockMcpPort) {
        this.mockPhoneIp = mockPhoneIp;
        this.mockMcpPort = mockMcpPort;
    }

    @Override
    public String createOrderId() {
        return "CS" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + randomChars(8).toUpperCase();
    }

    @Override
    public String newInstanceId() {
        return randomChars(8);
    }

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
