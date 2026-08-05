package com.mcpproxy.validator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * auth-validator-mock 应用入口（模拟统一认证/校验服务，默认 :9092）。
 *
 * <p>功能：签发与校验 10s 临时 token（external-api.md E1/E2）。
 *
 * <p>开发思路：与 mcp-mock 一样排除 Security/JPA/Redis 自动装配，
 * 避免与 proxy 同 JVM 跑 e2e 时被波及。
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
public class ValidatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ValidatorApplication.class, args);
    }
}
