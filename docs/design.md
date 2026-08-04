# 设计文档（Design）

> 版本：v1.2 ｜ 更新：2026-08-04
> 项目：mcp-proxy —— 云手机 MCP 代理网关微服务（模块名：proxy / mcp-mock / auth-validator-mock / e2e-tests）
> v1.2 变更：gateway 模块更名 proxy；新增 `mcp_ip`/`mcp_port` 字段与 E4 访问信息接口；新增 Redis 路由缓存（30min 滑动过期）

## 1. 背景与目标

在云手机（华为云 KooPhone）业务场景中，用户的 Agent（如 Claude、opencode 等 MCP 客户端）需要操作属于自己的云手机实例。云手机内运行着 `mcp_mobile_use`（MCP 服务，见 `D:\agent\mcp\mcp_mobile_use`），提供 tap / swipe / screenshot 等 12 个操控工具。

本服务（mcp-proxy）作为**统一网关**，解决三个核心问题：

1. **多租户多实例路由**：多个用户、多台云手机，Agent 通过 `instanceId` 唯一寻址自己的云机；
2. **安全令牌体系**：外部 10 秒短令牌校验通过后，签发 30 分钟 JWT（携带 `uid` 与 `instanceId`），JWT 只能访问对应的那台云机；
3. **协议透传**：对云机内 MCP 服务做 streamable-http / SSE / WebSocket 三种传输的透明代理转发。

同时网关代理华为云 KooPhone 的**实例订购类接口**（订阅 / 退订 / 准备 / 准备进度），
使 Agent 完成"订阅 → 准备 → 轮询就绪 → MCP 操控 → 退订"的完整生命周期。

## 2. 总体架构（ASCII）

```
                 ┌──────────────────────────────────────────────────────────┐
                 │                    用户 Agent（MCP 客户端）                 │
                 └──────┬──────────────────────┬───────────────────┬─────────┘
                        │ 10s token            │ Bearer JWT        │ JSON-RPC
                        │ 实例管理 API          │ MCP 代理          │ (HTTP/SSE/WS)
                        ▼                      ▼                   ▼
        ┌───────────────────────────────────────────────────────────────────┐
        │                   gateway（mcp-proxy）:8080                        │
        │  ┌──────────────┐  ┌─────────────┐  ┌──────────────────────────┐  │
        │  │InstanceCtrl  │  │AuthCtrl     │  │McpProxyCtrl              │  │
        │  │订阅/退订/准备 │  │login/exchange│  │/mcp/{id}  /sse  /ws      │  │
        │  └──────┬───────┘  └─────┬───────┘  └───────────┬──────────────┘  │
        │         │                │                      │                 │
        │  ┌──────▼────────┐┌──────▼──────┐  ┌────────────▼─────────────┐    │
        │  │KooPhoneClient ││TokenValidator│  │JwtAuthFilter+JwtService   │    │
        │  │Mock/华为REST  ││Mock/远程     │  │HS256 30min uid+instanceId │    │
        │  └──────┬────────┘└──────┬──────┘  └────────────┬─────────────┘    │
        │         │                │                      │                   │
        │  ┌──────▼────────────────▼──────────────────────▼──────┐           │
        │  │        InstanceService（业务编排）                     │           │
        │  └──────┬───────────────────────────────────────┬───────┘           │
        │         │ 读写                                  │ 转发               │
        │  ┌──────▼────────┐                ┌─────────────▼──────────────┐    │
        │  │InstanceRepo   │                │McpBackendClient            │    │
        │  │(JPA)          │                │HTTP→mcp-mock/云机          │    │
        │  └──────┬────────┘                └─────────────┬──────────────┘    │
        └─────────┼───────────────────────────────────────┼───────────────────┘
                  │ JDBC                                  │ HTTP
        ┌─────────▼──────────┐                 ┌──────────▼──────────┐
        │ MySQL 8.x          │                 │ mcp-mock:9091       │
        │ t_cloud_phone_instance │              │ (模拟云机 MCP)      │
        │ (注册表+访问方式)    │                 └─────────────────────┘
        └────────────────────┘
        ┌────────────────────┐
        │ auth-validator-mock:9092 │  ← 10s token 签发/校验（远程调用）
        └────────────────────┘
```

## 3. 数据持久化（MySQL）

**核心设计变更（v1.1）**：实例注册信息、云机访问方式、云手机信息全部持久化到 MySQL，
网关重启后实例路由与状态不丢失（此前为内存注册表）。

### 3.1 表结构 `t_cloud_phone_instance`

| 字段 | 类型 | 说明 |
|---|---|---|
| `instance_id` | varchar(32) PK | 实例 ID（订阅生成，URL 的一部分） |
| `uid` | varchar(64) | 实例归属用户 |
| `instance_name` | varchar(64) | 实例名称 |
| `order_id` | varchar(64) | 华为下单返回订单号 |
| `status` | tinyint | 实例状态：0=CREATED 1=PREPARING 2=NORMAL 3=FAILED 4=DELETED |
| `waiting_count` | int | 排队计数（轮询递减，归零就绪） |
| `os` | varchar(16) | 镜像版本（AOSP11/AOSP14） |
| `instance_sku_id` | varchar(64) | 实例商品规格 |
| `band_sku_id` | varchar(64) | 带宽规格 |
| `band_size` | double | 带宽大小(M) |
| `region_id` | varchar(64) | 区域 |
| `network` | varchar(32) | 网络线路 |
| `spec_pool_id` | varchar(64) | 规格池 |
| `node_group_id` | varchar(64) | 节点组 |
| `access_method` | varchar(16) | 访问方式：streamable-http / sse / websocket |
| `mcp_url` | varchar(256) | 云机 MCP 代理 URL（网关入口，回给 Agent） |
| `backend_url` | varchar(256) | 云机内 MCP 服务地址（`http://<mcp_ip>:<mcp_port>`） |
| `backend_token` | varchar(256) | 转发到云机的静态令牌 |
| `mcp_ip` | varchar(64) | 云手机 IP（E4 接口获取后落库） |
| `mcp_port` | int | 云机内 MCP 服务端口（E4 接口获取后落库） |
| `created_at` | datetime | 创建时间 |
| `updated_at` | datetime | 更新时间 |

```sql
CREATE TABLE t_cloud_phone_instance (
  instance_id    VARCHAR(32)  PRIMARY KEY,
  uid            VARCHAR(64)  NOT NULL,
  instance_name  VARCHAR(64),
  order_id       VARCHAR(64),
  status         TINYINT      NOT NULL DEFAULT 0,
  waiting_count  INT          NOT NULL DEFAULT 0,
  os             VARCHAR(16),
  instance_sku_id VARCHAR(64),
  band_sku_id    VARCHAR(64),
  band_size      DOUBLE,
  region_id      VARCHAR(64),
  network        VARCHAR(32),
  spec_pool_id   VARCHAR(64),
  node_group_id  VARCHAR(64),
  access_method  VARCHAR(16),
  mcp_url        VARCHAR(256),
  backend_url    VARCHAR(256),
  backend_token  VARCHAR(256),
  mcp_ip         VARCHAR(64),
  mcp_port       INT,
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_uid (uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 3.2 访问方式（AccessInfo）

创建云机成功后，将"云机访问方式"（`access_method` + `mcp_url` 网关入口 + `backend_url` 云机地址 +
`backend_token`）随实例信息一并落库；Agent 可查询实例信息获得自己的访问 URL。

### 3.3 路由缓存（Redis，30min 滑动过期）

实例 → 云机地址（`mcp_ip:mcp_port`）的转发路由经 Redis 缓存加速：

| 项 | 内容 |
|---|---|
| Key | `mcp:route:{instanceId}` |
| Value | `<mcp_ip>:<mcp_port>` |
| TTL | 30 分钟，**每次命中续期**（滑动过期） |
| 未命中顺序 | Redis → MySQL（有则回填缓存）→ E4 `fetchAccessInfo`（获取后落 MySQL + 写缓存） |
| 失效 | 退订实例时主动 evict |

### 3.4 连接配置

| 环境 | 数据源 |
|---|---|
| 本地/生产/E2E | MySQL 8.x（`JDBC_URL` / `JDBC_USER` / `JDBC_PASSWORD` 环境变量覆盖；本地默认 `jdbc:mysql://localhost:3306/mcpproxy`，root/root） |
| Redis | `REDIS_HOST` / `REDIS_PORT`（默认 localhost:6379） |

## 4. JWT 载荷（HS256，有效期 30 分钟）

```json
{
  "sub": "user-10001",
  "uid": "user-10001",
  "instanceId": "Ab3xYz9p",
  "iat": 1722700000,
  "exp": 1722701800,
  "jti": "uuid"
}
```

## 5. 令牌体系

| 令牌 | 有效期 | 签发方 | 用途 |
|---|---|---|---|
| 临时 token | 10 秒 | 外部校验服务（Mock：`/api/token/issue`） | 登录、实例管理 API 的 `x-auth-token` |
| 访问 JWT | 30 分钟 | 网关 `JwtService` | MCP 代理请求 `Authorization: Bearer <jwt>` |
| 后端静态 token | 长期 | 配置/落库 | 网关 → 云机 MCP 转发 |

## 6. 设计决策（ADR）

| # | 决策 |
|---|---|
| ADR-1 | Maven 多模块：gateway / mcp-mock / auth-validator-mock / e2e-tests，`mvn verify` 一键构建测试 |
| ADR-2 | 实例注册表落 MySQL（JPA），测试用 H2 MySQL 兼容模式；不再用纯内存 Map |
| ADR-3 | JWT 同时承载 `uid` + `instanceId`，双重校验（路径一致 + 归属一致），不满足返回 403 |
| ADR-4 | 就绪门控：`status != NORMAL` 拒绝 MCP 转发（409 KOOPHONE.API.5002） |
| ADR-5 | 外部依赖接口化：`TokenValidator` / `KooPhoneClient` / `McpBackendClient`，Mock 默认、可切真实 |
| ADR-6 | exchange 仅凭旧 JWT（有效且未过期）续签同 uid/instanceId 的新 30min JWT |
| ADR-7 | E4 访问信息（ip/port）获取后先落 MySQL，再写 Redis 30min 滑动缓存；转发只读 `RouteService` |
| ADR-8 | 模块命名 proxy（非 gateway）；三个应用同 JVM 测试时用 profile 配置文件隔离（application-proxy/mock/validator.yml） |

## 7. 实例状态机（ASCII）

```
                    订阅 create
        ┌────────────────────────────┐
        ▼                            │
    [*] ──▶ CREATED ──prepare──▶ PREPARING
                                  │    ▲
                    progress 轮询 │    │ waitingCount--（未归零）
                                  ▼    │
                                NORMAL ◀── waitingCount==0
                                  │  ▲
                                  │  │ -1 处理失败
                                  ▼  │
                                FAILED
                                  │
        CREATED/PREPARING/NORMAL/FAILED ──delete──▶ DELETED ──▶ [*]
```
