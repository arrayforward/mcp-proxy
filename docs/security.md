# 安全文档（Security）

> 版本：v1.3 ｜ 更新：2026-08-05
> 范围：mcp-proxy 与云手机内 mcp-server（mcp_mobile_use）之间的 JWT 信任体系

## 1. 密钥体系（核心）

采用 **RS256（RSA-2048 + SHA-256）非对称签名**：

| 密钥 | 持有方 | 用途 | 保密级别 |
|---|---|---|---|
| RSA 私钥 | **仅 mcp-proxy** | 签发用户访问 JWT（login/exchange） | 高机密，泄露=可伪造任意用户令牌，必须轮换 |
| RSA 公钥 | **每台云手机内的 mcp-server** + mcp-proxy 自身 | 验签 JWT（签名有效性 + 过期时间） | 公开，可随镜像分发 |

私钥不出 proxy 进程；公钥写入每台云手机（mcp-server 启动参数/配置文件），
云机只认公钥验签，**无法伪造令牌**，单台云机被攻破不影响其它云机。

## 2. 令牌体系

| 令牌 | 算法 | 有效期 | 签发方 | 验签方 | 用途 |
|---|---|---|---|---|---|
| 临时 token | 自定义 `tmp.*` | 10 秒 | 统一认证服务（mock: auth-validator-mock） | 统一认证服务 | 登录、实例管理 API 的 `x-auth-token` |
| 用户访问 JWT | **RS256** | 30 分钟 | mcp-proxy（私钥签发） | mcp-proxy + **云机 mcp-server（公钥验签）** | Agent→proxy；proxy→云机 转发携带 |
| healthz | 无 | — | — | — | 判活接口不鉴权（见 §6 风险说明） |

## 3. 转发鉴权流程

```
 Agent ──Bearer JWT(RS256,uid+instanceId)──▶ mcp-proxy
      proxy 验签（公钥）+ instanceId 比对 + 归属校验 + 就绪门控
      │
      ▼  转发时原样携带同一个用户 JWT
 云机 mcp-server ──公钥验签──▶ 有效且未过期：放行 / 否则 401
```

- proxy → 云机的**所有** MCP 请求（`POST /mcp`、`GET /sse`、`POST /message`、WS 桥接）都在
  `Authorization: Bearer <用户JWT>` 头中携带 proxy 签发的用户 JWT；
- 云机 mcp-server 必须验签：签名有效 + `exp` 未过期，否则返回 **401**；
- 云机 mcp-server 可选校验 `instanceId` claim 与自身一致（防串机，见 §5 强化建议）。

## 4. 密钥生成与配置

生成密钥对（任选其一）：

```bash
# OpenSSL
openssl genrsa -out jwt-private.pem 2048
openssl rsa -in jwt-private.pem -pubout -out jwt-public.pem
```

配置项：

| 服务 | 配置项 | 环境变量 | 说明 |
|---|---|---|---|
| proxy | `security.jwt.private-key` | `JWT_PRIVATE_KEY` | PKCS#8 私钥（PEM 全文或单行 Base64） |
| proxy | `security.jwt.public-key` | `JWT_PUBLIC_KEY` | X.509 公钥；缺省时由私钥推导 |
| 云机 mcp-server | `mcp.auth.public-key` | `MCP_JWT_PUBLIC_KEY` | X.509 公钥（对应 mcp_mobile_use 的 `--auth-jwt-public-key`） |

> proxy 未配置私钥时会生成临时开发密钥对并打印告警——**仅限本地开发**，生产必须配置固定私钥，
> 否则重启后所有已签发 JWT 失效且云机公钥无法对应。

## 5. 公钥分发到云手机（重要）

每台云手机的 mcp-server 启动时必须持有同一份公钥，分发方式按优先级：

| 方式 | 说明 | 适用 |
|---|---|---|
| ① 镜像预置 | 公钥 baked 进云手机镜像（如 `/data/local/tmp/mcp-public.pem`），mcp-server 启动加载 | 生产首选：零运行时依赖 |
| ② 准备期下发 | proxy 在 BatchPrepareInstances / 就绪判活阶段通过云机控制面通道写入 | 镜像不可改时 |
| ③ 配置中心 | 云机启动时从内网配置服务拉取公钥 | 需要轮换公钥时 |

**约束**：公钥与 proxy 私钥必须成对；更换私钥前必须先完成所有云机公钥更新，否则存量实例全部 401。

## 6. 密钥轮换

| 步骤 | 操作 |
|---|---|
| 1 | 生成新密钥对；新公钥随新镜像/配置下发（旧公钥保留，mcp-server 支持多公钥验签更佳） |
| 2 | 全部云机持有新公钥后，proxy 切换新私钥签发 |
| 3 | 旧 JWT 最长 30 分钟自然过期后，下线旧公钥 |

## 7. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 私钥泄露 | 可伪造任意用户/实例令牌 | 私钥仅存 proxy（KMS/环境变量注入，不入库不入日志）；泄露即按 §6 紧急轮换 |
| 云机被 root | 攻击者拿到公钥伪造不了令牌，但可关闭验签 | 云机 mcp-server 验签逻辑固化在镜像；配合健康检查与审计 |
| JWT 被截获重放 | 30 分钟内可冒用该实例 | 短有效期；HTTPS 传输；JWT 绑定 instanceId，越机访问被 proxy 403 |
| healthz 未鉴权 | 可被探测云机存活 | 仅返回 `{"status":"UP"}` 无敏感信息；如需收敛可加网络 ACL |
| 临时 token 伪造 | mock 校验服务无防重放 | 真实认证服务需签名 + 一次性消费 |

## 8. 验签规则（云机 mcp-server 必须实现）

| 规则 | 失败响应 |
|---|---|
| `Authorization: Bearer <jwt>` 头存在 | 缺失 → 401 |
| RS256 签名用预置公钥验签通过 | 失败 → 401 |
| `exp` 未过期 | 过期 → 401 |
| （可选）`instanceId` claim == 本机实例 ID | 不一致 → 403 |
| `/healthz` 免验签 | — |
