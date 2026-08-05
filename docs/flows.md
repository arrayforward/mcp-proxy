# 关键流程文档（Key Flows）

> 版本：v1.1 ｜ 更新：2026-08-04
> 重点：**登录流程**（10s 临时 token → 校验 → 30min JWT）

## 1. 端到端主流程（ASCII 时序）

```
 Agent               validator:9092       gateway:8080          MySQL            mcp-mock:9091
  │  1.issue 10s token    │                    │                  │                  │
  │──────────────────────▶│                    │                  │                  │
  │◀─── tmp token ────────│                    │                  │                  │
  │  2.create 订阅(x-auth-token)                │                  │                  │
  │───────────────────────────────────────────▶│                  │                  │
  │                       │  3.validate token  │                  │                  │
  │                       │◀──────────────────▶│                  │                  │
  │                       │                    │ 4.INSERT 实例    │                  │
  │                       │                    │─────────────────▶│                  │
  │◀──────── instanceId ───────────────────────│                  │                  │
  │  5.prepare 准备                             │                  │                  │
  │───────────────────────────────────────────▶│ 6.UPDATE PREPARING│                  │
  │◀──────── 受理 ─────────────────────────────│─────────────────▶│                  │
  │ 7.轮询 prepare-progress（循环到 status==0） │                  │                  │
  │───────────────────────────────────────────▶│ 8.读状态         │                  │
  │◀──── {status,waitingCount} ────────────────│─────────────────▶│                  │
  │  9.login 登录（10s token）                   │                  │                  │
  │───────────────────────────────────────────▶│                  │                  │
  │                       │ 10.validate         │                  │                  │
  │                       │◀──────────────────▶│                  │                  │
  │◀── 11.30min JWT(uid+instanceId) ───────────│                  │                  │
  │ 12.POST /mcp/{instanceId} + Bearer JWT     │                  │                  │
  │───────────────────────────────────────────▶│ 13.验签/比对/门控 │                  │
  │                       │                    │ 14.转发 JSON-RPC  │                  │
  │                       │                    │──────────────────────────────────▶│
  │◀── 16.透传响应 ────────────────────────────│◀─────────15.响应 ─────────────────│
  │ 17.exchange 旧JWT续期                       │                  │                  │
  │───────────────────────────────────────────▶│                  │                  │
  │◀── 18.新30min JWT ─────────────────────────│                  │                  │
  │ 19.delete 退订                              │                  │                  │
  │───────────────────────────────────────────▶│ 20.UPDATE DELETED│                  │
  │◀── OK ─────────────────────────────────────│─────────────────▶│                  │
```

## 2. 登录流程（重点）

### 2.1 为什么需要两层令牌

```
 临时 token（10s）                 访问 JWT（30min）
 ───────────────                  ──────────────
 外部校验服务签发                  网关 JwtService 签发
 证明 Agent 身份合法               供 MCP 代理高频调用
 与 uid 绑定，防重放               内含 instanceId → 单机隔离
```

### 2.2 登录时序（ASCII）

```
 Agent                        gateway                    auth-validator-mock
  │ POST /api/auth/login       │                              │
  │ {token:"tmp.uid.iid.ts"}   │                              │
  │───────────────────────────▶│ 本地快速检查: now-ts<=10s     │
  │                            │ (不满足 → 直接 401)           │
  │                            │ POST /api/validate/token     │
  │                            │─────────────────────────────▶│
  │                            │◀── {valid:true,uid,iid} ─────│
  │                            │ 或 {valid:false,reason}      │
  │  有效:                      │                              │
  │  签发 JWT: HS256 exp=now+30min claims(uid,instanceId)     │
  │◀──── 200 {accessToken, expiresIn:1800, uid, instanceId} ──│
  │  无效:                      │                              │
  │◀──── 401 {error:"invalid token"}                          │
```

### 2.3 登录状态图（ASCII）

```
 ┌────────────┐   获取临时token   ┌──────────────┐   10s内登录   ┌──────────────┐
 │    [*]     │────────────────▶ │ 携带token登录  │────────────▶ │ 网关远程校验  │
 └────────────┘                   └──────────────┘              └──────┬───────┘
                                        │                             │
                                        │ 超过10s                      │
                                        ▼                             │
                                  过期丢弃[*]                    valid=true / false
                                                                      │
                                              ┌───────────────────────┴────────┐
                                              ▼                               ▼
                                    签发JWT(HS256,uid,         401 invalid
                                    instanceId,exp=30min)      token [*]
                                              │
                                              ▼
                                        200 accessToken [*]
```

### 2.4 校验服务内部逻辑（Mock）（ASCII）

```
 /api/validate/token
        │
        ▼
  格式 = tmp.uid.instanceId.ts ? ── 否 ──▶ {valid:false, reason:malformed}
        │ 是
        ▼
  now - ts <= 10s ? ───────────── 否 ──▶ {valid:false, reason:expired}
        │ 是
        ▼
  {valid:true, uid, instanceId, expiresAt}
```

## 3. 令牌隔离（Token 只能访问对应云机）

```
 POST /mcp/{instanceId} + Bearer JWT
        │
        ▼
  JWT 解析（验签+过期） ── 失败 ──▶ 401
        │
        ▼
  jwt.instanceId == 路径 instanceId ? ── 否 ──▶ 403（令牌与目标实例不匹配）
        │ 是
        ▼
  MySQL[instanceId].uid == jwt.uid ? ── 否 ──▶ 403（实例不属于该用户）
        │ 是
        ▼
  实例状态 == NORMAL ? ── 否 ──▶ 409 未就绪
        │ 是
        ▼
  转发 → 云机 MCP
```

## 4. 实例生命周期（ASCII）

```
 订阅create ──▶ CREATED ──prepare──▶ PREPARING ──轮询progress(waitingCount--)──▶ NORMAL ──▶ 退订delete ──▶ DELETED
                    │                   │                                            │
                    │                   └── status=-1 ──▶ FAILED ────────────────────┼──────────────┘
                    └──────────────────────────────────────────────────────────────┘
```

| 阶段 | 状态 | MCP 代理 |
|---|---|---|
| 订阅后 | CREATED | 409 未就绪 |
| 准备中 | PREPARING（waitingCount 递减） | 409 未就绪 |
| 就绪 | NORMAL | 放行转发 |
| 失败 | FAILED | 409 |
| 退订 | DELETED（行逻辑删除） | 404 |

## 5. Token 续期（Exchange）

```
 Agent                      gateway                       JwtService
  │ POST /api/auth/exchange │                               │
  │ {accessToken: 旧JWT}     │                               │
  │────────────────────────▶│ 验签(HS256) + exp 检查         │
  │                         │ 有效: 取出 uid/instanceId      │
  │                         │──────────────────────────────▶│ 重新签发30min
  │◀──── 200 新 accessToken ─│◀─────────────────────────────│
  │ 过期/无效:               │                               │
  │◀──── 401 ───────────────│                               │
```

## 6. 异常路径汇总

| 场景 | 期望行为 |
|---|---|
| 10s token 过期后登录 | 401 invalid token |
| JWT 过期后访问 /mcp | 401 |
| 用 A 实例 JWT 访问 B 实例 | 403 |
| 访问他人实例 | 403 |
| 实例未就绪发 MCP | 409 |
| 退订后访问 | 404 |
| 云机 MCP 不可达 | 502 |
| 订阅请求体缺必填 | 400 KOOPHONE.API.1000 |
| 重复订阅 | 每台新实例重新生成 instanceId |

## 6.1 健康检查闭环（v1.2 新增）

```
  就绪判活（prepare-progress 归零时）:
    waitingCount==0 ──▶ fetchAccessInfo 落库 ──▶ GET /healthz ──▶ 活: NORMAL + healthy=true
                                                                └─▶ 死: FAILED + healthy=false

  活跃期探活（每 30s, @Scheduled）:
    遍历 status==NORMAL 的实例
        │
        ▼
    活跃?（SSE/WS 长连接数>0 或 3min 内有 MCP 请求）── 否 ──▶ 跳过
        │ 是
        ▼
    GET http://{mcp_ip}:{mcp_port}/healthz
        │ 2xx ──▶ healthy=true    │ 超时/非2xx ──▶ healthy=false
```

## 7. 路由解析流程（Redis 缓存，v1.2 新增）

```
  MCP 转发 / access-info 查询 instanceId 路由
        │
        ▼
  RouteCacheService.get (Redis mcp:route:{id})
        │ 命中 ──▶ expire 续期 30min ──▶ 返回 ip:port
        │ 未命中
        ▼
  MySQL t_cloud_phone_instance.mcp_ip/mcp_port
        │ 有值 ──▶ 回填 Redis(30min) ──▶ 返回
        │ 为空
        ▼
  KooPhoneClient.fetchAccessInfo(instanceId)  (E4, Mock: 配置化 ip/port)
        │
        ▼
  UPDATE MySQL 落库 ──▶ 写 Redis(30min) ──▶ 返回
```

## 8. 测试矩阵

| 流程 | 单元测试 | 端到端测试 |
|---|---|---|
| 登录（含 10s 过期） | AuthControllerTest | E2E 全流程 |
| JWT 签发/验签/越权 | JwtServiceTest / McpProxyServiceTest | E2E 403 断言 |
| 订阅/查询/退订/准备/进度 + MySQL 落库 | InstanceControllerTest / RepositoryTest | E2E 全流程 + 查库断言 |
| 就绪门控 | McpProxyServiceTest（409） | E2E 409 断言 |
| MCP 三种传输 | McpBackendClientTest / WS 测试 | E2E HTTP + SSE + WS |
| Mock 校验服务 | TokenControllerTest | E2E 间接覆盖 |
| Mock MCP 服务 | McpMockServiceTest | E2E 间接覆盖 |
| access-info 落库 + Redis 缓存 | —（真实 MySQL/Redis） | E2eFlowTest#accessInfoPersistedToMysql |
| JWT 签发/验签 | JwtServiceTest | E2E 全流程 |
| healthz 判活 + 活跃期探活 | — | E2eFlowTest#healthzCheckKeepsInstanceAlive |
| sandbox/adb_shell 工具转发 | McpMockServiceTest | E2eFlowTest#sandboxToolsForwardedThroughProxy |
