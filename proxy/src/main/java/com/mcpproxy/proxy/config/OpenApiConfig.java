package com.mcpproxy.proxy.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc / Swagger UI 配置。
 *
 * <p>功能：自动生成 OpenAPI 3 文档并在 {@code /swagger-ui.html} 提供可视化调试页面。
 *
 * <p>开发思路：
 * <ul>
 *   <li>声明两套安全方案并注册为全局 SecurityRequirement，Swagger UI 右上角 "Authorize"
 *       一次填写即可试调所有接口；</li>
 *   <li>实际鉴权仍由 JwtAuthFilter / InstanceController 自行完成，这里只是文档声明；</li>
 *   <li>SecurityConfig 只拦截 /mcp/**，/v3/api-docs 与 /swagger-ui 天然放行，无需额外配置。</li>
 * </ul>
 *
 * @author hubin
 * @since 2026-08-05
 */
@Configuration
public class OpenApiConfig {

    /**
     * 构建全局 OpenAPI 元信息。
     *
     * <p>伪代码：info(标题/版本/描述) + components(两个 securityScheme) + 全局 security(两者可选其一)。
     *
     * @return OpenAPI 根对象，springdoc 序列化为 /v3/api-docs
     */
    @Bean
    public OpenAPI mcpProxyOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("mcp-proxy API")
                        .version("v1.3")
                        .description("云手机 MCP 代理网关：实例生命周期（华为 KooPhone 兼容）+ 认证 + MCP 三传输代理。"
                                + "详见 docs/ 目录 design/api/security/external-api 文档。"))
                .components(new Components()
                        // 实例管理 API 用：10s 临时 token，华为风格自定义头
                        .addSecuritySchemes("x-auth-token", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("x-auth-token")
                                .description("10 秒临时 token，由统一认证服务签发（mock: POST :9092/api/token/issue）"))
                        // MCP 代理 API 用：proxy 签发的 30min RS256 JWT
                        .addSecuritySchemes("bearer-jwt", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("30 分钟访问 JWT，由 POST /api/auth/login 签发")))
                .addSecurityItem(new SecurityRequirement().addList("x-auth-token"))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }
}
