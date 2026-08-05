package com.mcpproxy.proxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * mcp-proxy 网关应用入口。
 *
 * <p>功能：云手机 MCP 代理网关主服务（默认 :8080），包含实例生命周期 API、认证、
 * MCP 三传输代理、路由缓存与健康检查。
 *
 * <p>开发思路：{@code @EnableScheduling} 开启定时任务，驱动 HealthCheckService 的
 * 30s 探活；运行时需要 profile=proxy（application-proxy.yml）激活数据源/Redis 配置。
 *
 * @author hubin
 */
@SpringBootApplication
@EnableScheduling
public class ProxyApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProxyApplication.class, args);
    }
}
