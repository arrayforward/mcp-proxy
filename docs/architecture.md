# 架构文档（Architecture）

> 版本：v1.1 ｜ 更新：2026-08-04

## 1. 总体架构（ASCII）

```
                用户 Agent（MCP 客户端 / LLM）
                        │
        ┌───────────────┼──────────────────────────────┐
        │ 10s token     │ Bearer JWT                   │ JSON-RPC
        │ 订阅/退订/准备 │ /mcp/{instanceId} /sse /ws   │
        ▼               ▼                              ▼
┌───────────────────────────────────────────────────────────────────┐
│  gateway:8080                                                      │
│  ┌───────────────┬─────────────────┬──────────────────────────┐    │
│  │InstanceCtrl   │AuthCtrl         │McpProxyCtrl              │    │
│  │(华为报文兼容)  │login/exchange   │HTTP+SSE+WebSocket 代理    │    │
│  └───────┬───────┴───────┬─────────┴────────────┬─────────────┘    │
│          │               │                      │                   │
│          ▼               ▼                      ▼                   │
│  KooPhoneClient   TokenValidator      JwtAuthFilter + JwtService    │
│  Mock/华为REST    Mock/远程            HS256 30min uid+instanceId   │
│          │               │                      │                   │
│          └───────────────┼──────────────────────┘                   │
│                          ▼                                          │
│                  InstanceService（业务编排）                          │
│                    │ 读写                       │ 转发                │
│                    ▼                            ▼                    │
│              InstanceRepository          McpBackendClient           │
│              (Spring Data JPA)           (RestClient/WebClient)     │
└──────────────────┬─────────────────────────────┬────────────────────┘
                   │ JDBC                        │ HTTP
          ┌────────▼────────┐          ┌──────────▼───────────┐
          │ MySQL 8.x       │          │ mcp-mock:9091        │
          │ t_cloud_phone_  │          │ (模拟云机内 MCP)     │
          │ instance        │          │ /mcp /sse /message   │
          │ (注册表+访问方式)│          └──────────────────────┘
          └─────────────────┘
          ┌──────────────────────────────────┐
          │ auth-validator-mock:9092         │
          │ /api/token/issue 10s token       │
          │ /api/validate/token 校验         │
          └──────────────────────────────────┘
```

## 2. 模块职责

| 组件 | 职责 |
|---|---|
| `InstanceController` | 代理华为 KooPhone 实例接口：create / delete / prepare / prepare-progress，报文与华为一致 |
| `AuthController` | 登录（10s token → 30min JWT）、续期（旧 JWT → 新 JWT） |
| `McpProxyController` | 三种传输 MCP 代理入口，校验 JWT 与路径 instanceId |
| `JwtService` | HS256 签发/验签，claims：uid、instanceId、exp(30min) |
| `JwtAuthFilter` | Security 过滤器：解析 Bearer JWT 注入 SecurityContext |
| `InstanceService` | 业务编排：订阅落库、准备、进度、退订、就绪门控查询 |
| `InstanceRepository` | Spring Data JPA：`t_cloud_phone_instance` 表 CRUD |
| `KooPhoneClient` | 华为实例 API 抽象；Mock 默认、华为 REST 预留 |
| `TokenValidator` | 外部校验抽象；Remote → mock 校验服务 |
| `McpBackendClient` | 云机 MCP 转发抽象；HTTP 实现 → mcp-mock / 云机 |

## 3. 认证与鉴权链路（ASCII）

```
Agent                 gateway                auth-validator-mock
  │ 1.获取10s临时token   │                      │
  │─────────────────────▶─────────────────────▶│
  │◀─────────────────────token──────────────────│
  │ 2.POST /api/auth/login {token}              │
  │─────────────────────▶                       │
  │                     │ 3./api/validate/token │
  │                     │──────────────────────▶│
  │                     │◀──── {valid,uid,instanceId} ────│
  │◀──── 4.30min JWT(uid+instanceId) ───────────│
  │ 5.POST /mcp/{instanceId} + Bearer JWT       │
  │─────────────────────▶                       │
  │                     │ 6.验签+过期+instanceId比对 │
  │                     │   +归属校验+就绪门控     │
  │                     │ 7.转发 JSON-RPC → mcp-mock/云机 │
```

## 4. MCP 代理转发决策链（ASCII）

```
Agent POST /mcp/{instanceId}
   │
   ▼
JwtAuthFilter：无token/签名无效/过期 ──▶ 401
   │ 通过
   ▼
路径 instanceId == jwt.instanceId？ ── 否 ──▶ 403
   │ 是
   ▼
注册表(MySQL) instanceId.uid == jwt.uid？ ── 否 ──▶ 403
   │ 是
   ▼
实例状态 == NORMAL？ ── 否 ──▶ 409 未就绪
   │ 是
   ▼
McpBackendClient 转发 → mcp-mock/云机 MCP ──▶ 透传响应
```

## 5. 传输层设计

| 传输 | 网关入口 | 后端对接 | 会话 |
|---|---|---|---|
| streamable-http | `POST /mcp/{id}` | 同步 POST 后端 `/mcp` | 无状态 |
| SSE | `GET /mcp/{id}/sse` + `POST /mcp/{id}/message` | 后端 `/sse` + `/message` | sessionId 映射 + endpoint 重写 |
| WebSocket | `/ws/mcp/{id}?token=` | 文本帧桥接为后端 HTTP POST | 每连接一链路 |

SSE 会话时序（ASCII）：

```
Agent                 gateway(SSE代理)            mcp-mock(云机)
  │ GET /mcp/{id}/sse   │                          │
  │────────────────────▶│ GET /sse                 │
  │                     │─────────────────────────▶│
  │                     │◀─ event:endpoint ────────│
  │                     │   /message?sessionId=abc │
  │◀─ event:endpoint ───│                          │
  │   /mcp/{id}/message?sessionId=abc（重写）       │
  │ POST /message?sessionId=abc + JWT              │
  │────────────────────▶│ POST /message?sessionId  │
  │                     │─────────────────────────▶│
  │                     │◀─ 202 ──────────────────│
  │                     │◀─ event:message ────────│
  │◀── event:message 透传回推 ─────────────────────│
```

## 6. 数据访问架构（ASCII）

```
Controller/Service
     │
     ▼
InstanceService（事务边界 @Transactional）
     │
     ├──▶ InstanceRepository（JPA 接口）
     │         └──▶ EntityManager
     │               └──▶ Hibernate
     │                     └──▶ JDBC 驱动（mysql-connector-j / H2）
     │
     └──▶ KooPhoneClient（Mock：直接改状态；华为：REST 同步到华为云）
```

| 环境 | driver | url |
|---|---|---|
| 生产/本地 | `com.mysql.cj.jdbc.Driver` | `jdbc:mysql://${JDBC_HOST}:3306/${JDBC_DB}` |
| 测试/E2E | H2 | `jdbc:h2:mem:mcpproxy;MODE=MySQL` |

## 7. 部署拓扑（生产目标形态，ASCII）

```
 Agent/LLM ──HTTPS──▶ LB ──▶ gateway 实例1 ──┐
                          └──▶ gateway 实例2 ──┼──▶ MySQL（共享注册表）
                                              ├──▶ 华为云 KooPhone API
                                              └──▶ 云手机1/2 ... mcp_mobile_use
```
