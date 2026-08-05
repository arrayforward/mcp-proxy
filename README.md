# mcp-proxy

云手机 MCP 代理网关：让 AI Agent（Claude / opencode 等 MCP 客户端）安全地操作自己的云手机实例。

## 功能概览

- **Sandbox 一键接口**（对齐阿里云 AgentBay 语义）：`create_sandbox`（异步，后台线程轮询华为开通进度）/ `sandbox_status` / `list_sandbox` / `kill_sandbox`，华为实例管理细节不暴露给 Agent
- **双层令牌体系**：10s 临时 token → 30min RS256 JWT；proxy 持私钥签发，每台云机 mcp-server 持公钥独立验签
- **MCP 三传输透明代理**：streamable-http / SSE / WebSocket，统一决策链（401/403/404/409/502）
- **26 个云机工具**：13 个 mcp_mobile_use（含 adb_shell）+ 13 个 AgentBay sandbox 工具
- **健康闭环**：就绪 healthz 判活；活跃期（长连接或 3min 内有请求）每 30s 探活
- **持久化与缓存**：MySQL 权威存储 + Redis 双层滚动缓存（30min，读命中续期）
- **启动校准**：重启后恢复未完成的创建轮询、healthz 校准中间态实例

## 模块

| 模块 | 端口 | 说明 |
|---|---|---|
| `proxy` | 8080 | 网关主服务（sandbox API + 认证 + MCP 代理 + 健康检查） |
| `mcp-mock` | 9091 | 模拟云机 mcp-server（26 工具 + healthz + 公钥验签） |
| `auth-validator-mock` | 9092 | 模拟统一认证服务（10s token 签发/校验） |
| `e2e-tests` | — | 全流程端到端测试（真实 MySQL/Redis） |

## 快速开始

### 环境要求

- JDK 21+、Maven 3.9+、MySQL 8.0、Redis 5+

### 构建与测试

```bash
# 依赖 MySQL（建库 mcpproxy，root/root）与 Redis（localhost:6379）运行
mvn verify          # 一键构建 + 单元测试 + e2e 全流程（33 个测试）
```

### 运行

```bash
# 1. 统一认证 mock
java -jar auth-validator-mock/target/auth-validator-mock-1.0.0.jar --spring.profiles.active=validator

# 2. 云机 mock（生产为云手机内 mcp_mobile_use；MCP_JWT_PUBLIC_KEY 配公钥开启验签）
java -jar mcp-mock/target/mcp-mock-1.0.0.jar --spring.profiles.active=mock

# 3. 网关（生产必须配 JWT_PRIVATE_KEY，否则用临时开发密钥对）
java -jar proxy/target/proxy-1.0.0.jar --spring.profiles.active=proxy
```

### 典型调用流程

```bash
# 1. 获取 10s 临时 token
curl -X POST localhost:9092/api/token/issue -H "Content-Type: application/json" \
  -d '{"uid":"user-10001","instanceId":"pending"}'

# 2. 登录换 30min JWT
curl -X POST localhost:8080/api/auth/login -H "Content-Type: application/json" \
  -d '{"token":"tmp.user-10001.pending.<ts>"}'

# 3. 创建沙箱（异步，立即返回 initializing）
curl -X POST localhost:8080/api/v1/sandbox/create -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"os":"AOSP14","instanceSkuId":"kp.professional.2xlarge.128g.2","regionId":"cn-north-7"}'

# 4. 轮询状态直到 ready（建议间隔 ≥3s）
curl -X POST localhost:8080/api/v1/sandbox/status -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" -d '{"sandbox_id":"Ab3xYz9p"}'

# 5. 发起 MCP 请求（streamable-http）
curl -X POST localhost:8080/mcp/Ab3xYz9p -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"tap","arguments":{"x":100,"y":200}}}'

# 6. 用完退订
curl -X POST localhost:8080/api/v1/sandbox/kill -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" -d '{"sandbox_id":"Ab3xYz9p"}'
```

Swagger UI：`http://localhost:8080/swagger-ui.html`

## 配置项

| 配置 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `spring.datasource.url` | `JDBC_URL` | `jdbc:mysql://localhost:3306/mcpproxy...` | MySQL 连接 |
| `spring.datasource.username/password` | `JDBC_USER` / `JDBC_PASSWORD` | root / root | MySQL 账号 |
| `spring.data.redis.host/port` | `REDIS_HOST` / `REDIS_PORT` | localhost:6379 | Redis |
| `security.jwt.private-key` | `JWT_PRIVATE_KEY` | 空（临时开发密钥对） | RSA 私钥（PKCS#8），**生产必配** |
| `security.jwt.public-key` | `JWT_PUBLIC_KEY` | 空（从私钥推导） | RSA 公钥（X.509） |
| `koophone.validator-url` | `VALIDATOR_URL` | `http://localhost:9092` | 统一认证服务地址 |
| `koophone.mock.phone-ip/mcp-port` | `MOCK_PHONE_IP` / `MOCK_MCP_PORT` | 127.0.0.1:9091 | Mock 云机地址 |
| `sandbox.progress-interval-ms` | — | 3000 | 看守线程轮询间隔（最低 3s） |
| `sandbox.progress-timeout-ms` | — | 900000 | 创建总超时（15min） |
| `healthcheck.interval-ms` | — | 30000 | 探活周期 |
| `healthcheck.activity-window-ms` | — | 180000 | 请求活跃窗口（3min） |
| mcp-mock: `mcp.auth.public-key` | `MCP_JWT_PUBLIC_KEY` | 空（dev 不验签） | 云机验签公钥 |

## 文档

| 文档 | 内容 |
|---|---|
| [docs/architecture.md](docs/architecture.md) | 总体架构 + 9 张 PNG 架构/流程图 + Mermaid |
| [docs/design.md](docs/design.md) | 详细设计：表结构、状态机、缓存、ADR |
| [docs/api.md](docs/api.md) | 全部接口详细契约（sandbox/认证/MCP 三传输/mock） |
| [docs/security.md](docs/security.md) | 安全体系：RS256 密钥、公钥分发、轮换、验签规则 |
| [docs/external-api.md](docs/external-api.md) | 外部依赖接口专项设计（可对其他系统提需求） |
| [docs/flows.md](docs/flows.md) | 关键流程：登录、决策链、健康闭环、测试矩阵 |

## 测试

| 层级 | 数量 | 覆盖 |
|---|---|---|
| 单元测试 | 20 | JwtService(RS256)、McpMockService(26 工具)、TokenController、StartupReconciler、SandboxWatcher |
| E2E | 13 | 真实 MySQL/Redis 全流程：create→轮询 ready→login→MCP(HTTP/SSE/WS)→续期→越权→kill |
