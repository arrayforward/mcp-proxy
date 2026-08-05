package com.mcpproxy.mock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * mcp-mock 应用入口（模拟云手机内 mcp-server，默认 :9091）。
 *
 * <p>功能：对齐 mcp_mobile_use 协议（/mcp、/sse、/message、/healthz），
 * 提供 26 个 mock 工具，供 proxy 转发与 e2e 测试。
 *
 * <p>开发思路：excludeName 排除 Security/JPA/Redis 自动装配——本模块与 proxy 同 JVM
 * 跑 e2e 时，classpath 上存在这些 starter，不排除会把 mock 应用也加上鉴权/数据源。
 *
 * @author hubin
 * @since 2026-08-04
 */
@SpringBootApplication(excludeName = {
        "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
        "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration",
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
public class MockMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockMcpApplication.class, args);
    }
}
