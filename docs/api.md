# API 文档

> 版本：v1.1 ｜ 更新：2026-08-04
> Base URL：`http://localhost:8080`（gateway）、`http://localhost:9091`（mcp-mock）、`http://localhost:9092`（auth-validator-mock）

## 1. 概述

| 分类 | 端点 | 鉴权 |
|---|---|---|
| Sandbox（Agent 实例入口） | `/api/v1/sandbox/create`、`/status`、`/kill` | 请求头 `x-auth-token`（10s 临时 token） |
| 认证 | `/api/auth/login`、`/api/auth/exchange` | 无 |
| MCP 代理 | `/mcp/{instanceId}`、`/sse`、`/ws` | `Authorization: Bearer <JWT>` |
| Mock 校验服务 | `/api/token/issue`、`/api/validate/token` | 无 |
| Mock MCP 服务 | `/mcp`、`/sse`、`/message` | 可选静态 token |

通用错误码（华为兼容）：

| error_code | 含义 |
|---|---|
| `0` | 成功 |
| `KOOPHONE.API.1000` | 请求参数错误 |
| `KOOPHONE.API.1001` | 没有实例权限 |
| `KOOPHONE.API.4001` | 实例不存在 |
| `KOOPHONE.API.5002` | 实例未就绪（网关新增） |
| `KOOPHONE.API.9999` | 服务内部错误 |

---

## 2. Sandbox API（Agent 唯一可见的实例入口）

> 设计约束：华为风格实例管理接口（CreateInstance / BatchPrepareInstances / ShowProgress /
> DeleteInstance / access-info）是 proxy 与云控制面之间的**内部 mock 接口，不对 Agent 暴露**。
> Agent 只能调用以下三个 sandbox 接口（对齐阿里云 AgentBay sandbox 语义）。

### 2.1 create_sandbox（异步受理）

真实华为云手机开通约 1~5 分钟，故 create 为**异步**：内部完成 CreateInstance + BatchPrepareInstances
后立即返回，并启动后台看守线程轮询 ShowProgress（默认每 3s，最长 900s，均可配置）。
请求体与华为 CreateInstance 相同。

```
POST /api/v1/sandbox/create
x-auth-token: <10s临时token>
Content-Type: application/json
```

请求体：

```json
{
  "os": "AOSP14",
  "instanceSkuId": "kp.professional.2xlarge.128g.2",
  "bandSkuId": "kp.bandwidth",
  "regionId": "cn-north-7",
  "instanceNamePrefix": "koophone",
  "bandSize": 4.0,
  "count": 1,
  "chargeParam": { "chargingMode": 1, "periodType": 2, "periodNum": 1, "isAutoPay": 1, "isAutoRenew": 1 },
  "network": "EIP"
}
```

响应（200，立即返回）：

```json
{
  "data": {
    "sandbox_id": "Ab3xYz9p",
    "instance_name": "koophone-00001",
    "sandbox_status": "initializing"
  },
  "error_code": "0",
  "error_msg": "OK"
}
```

> `sandbox_id` 即 MCP URL 的一部分：`POST /mcp/{sandbox_id}`（ready 之后才可用）。
> 创建过程的状态全部持久化到 MySQL，并镜像到 Redis 滚动缓存（30min，读命中续期）。

### 2.2 sandbox_status（轮询初始化进度）

```
POST /api/v1/sandbox/status
x-auth-token: <10s临时token>
```

请求体：`{ "sandbox_id": "Ab3xYz9p" }`

响应（initializing）：

```json
{ "data": { "sandbox_id": "Ab3xYz9p", "sandbox_status": "initializing", "waiting_count": 2 },
  "error_code": "0", "error_msg": "OK" }
```

响应（ready，可发起 MCP 请求）：

```json
{
  "data": {
    "sandbox_id": "Ab3xYz9p",
    "sandbox_status": "ready",
    "healthy": true,
    "mcp_url": "http://localhost:8080/mcp/Ab3xYz9p",
    "mcp_ip": "10.0.0.23",
    "mcp_port": 9091
  },
  "error_code": "0",
  "error_msg": "OK"
}
```

响应（failed / timeout）：

```json
{ "data": { "sandbox_id": "Ab3xYz9p", "sandbox_status": "timeout" }, "error_code": "0", "error_msg": "OK" }
```

| sandbox_status | 含义 |
|---|---|
| `initializing` | 初始化中（含 waiting_count，继续轮询） |
| `ready` | 就绪（healthz 判活通过，带 MCP 访问信息） |
| `failed` | 初始化失败（healthz 判活未通过） |
| `timeout` | 创建超时（后台看守线程超过 900s 未就绪） |

### 2.3 kill_sandbox（退订释放）

```
POST /api/v1/sandbox/kill
x-auth-token: <10s临时token>
```

请求体：`{ "sandbox_id": "Ab3xYz9p" }`

响应（200）：`{ "data": null, "error_code": "0", "error_msg": "OK" }`

> 逻辑删除（status=DELETED），同时清除 Redis 路由缓存与实例状态缓存。

---

## 3. 认证 API（proxy）

### 3.1 登录（Login）：10s token → 30min JWT

```
POST /api/auth/login
Content-Type: application/json
```

请求体：`{ "token": "<10s临时token>" }`

响应（200）：

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 1800,
  "uid": "user-10001",
  "instanceId": "Ab3xYz9p"
}
```

错误：token 无效/过期 → 401 `{ "error": "invalid token" }`。

### 3.2 续期（Exchange）：旧 JWT 换新 JWT

```
POST /api/auth/exchange
Content-Type: application/json
```

请求体：`{ "accessToken": "<旧JWT>" }`

响应：同 3.1（新 JWT，uid/instanceId 不变，有效期重新计 30 分钟）。
旧 JWT 签名无效或已过期 → 401。

---

## 4. MCP 代理 API（proxy）

### 4.1 streamable-http

```
POST /mcp/{instanceId}
Authorization: Bearer <JWT>
Content-Type: application/json
Accept: application/json, text/event-stream
```

请求体（透传 JSON-RPC）：

```json
{ "jsonrpc": "2.0", "id": 1, "method": "initialize",
  "params": { "protocolVersion": "2025-03-26", "capabilities": {},
              "clientInfo": { "name": "demo-agent", "version": "1.0" } } }
```

响应（200，透传）：

```json
{ "jsonrpc": "2.0", "id": 1,
  "result": { "protocolVersion": "2025-03-26", "capabilities": { "tools": {} },
              "serverInfo": { "name": "mcp-mock", "version": "1.0" } } }
```

错误（HTTP 状态）：

| HTTP | 场景 |
|---|---|
| 401 | 无 token / 签名无效 / 已过期 |
| 403 | jwt.instanceId ≠ 路径 instanceId，或归属 uid 不一致 |
| 404 | 实例不存在（含已退订） |
| 409 | 实例未就绪 |
| 502 | 后端云机 MCP 不可达 |

### 4.2 SSE 会话

```
GET /mcp/{instanceId}/sse          （Authorization 头 或 ?token=<JWT>）
```
返回重写后的 endpoint：
```
event: endpoint
data: /mcp/{instanceId}/message?sessionId=<32位hex>
```
```
POST /mcp/{instanceId}/message?sessionId=<id>
Authorization: Bearer <JWT>
```
发送 JSON-RPC 消息 → 202；响应经 SSE 通道 `event: message` 回推。

### 4.3 WebSocket

```
WS /ws/mcp/{instanceId}?token=<JWT>
```
文本帧发送 JSON-RPC，服务端文本帧回推响应。

---

## 5. Mock 校验服务（auth-validator-mock，:9092）

### 5.1 签发 10s 临时 token

```
POST /api/token/issue
{ "uid": "user-10001", "instanceId": "Ab3xYz9p" }
```

响应：`{ "token": "tmp.<uid>.<instanceId>.<过期时间戳>", "expiresIn": 10 }`

### 5.2 校验 token

```
POST /api/validate/token
{ "token": "tmp.user-10001.Ab3xYz9p.<ts>" }
```

响应：`{ "valid": true, "uid": "user-10001", "instanceId": "Ab3xYz9p", "expiresAt": 1722700000 }`
无效/过期：`{ "valid": false, "reason": "expired" }`

---

## 6. Mock MCP 服务（mcp-mock，:9091）

| 端点 | 说明 |
|---|---|
| `POST /mcp` | streamable-http：initialize / ping / tools/list / tools/call / notifications |
| `GET /sse` | SSE 会话建立，下发 endpoint |
| `POST /message?sessionId=` | 提交消息，202，SSE 回推 |
| `GET /healthz` | 判活：200 `{ "status": "UP" }`（proxy 就绪判活 + 活跃期 30s 探活调用） |

tools/list 返回 26 个工具：

| 分组 | 工具 |
|---|---|
| mcp_mobile_use（13） | `tap`、`swipe`、`take_screenshot`、`text_input`、`back`、`home`、`menu`、`launch_app`、`close_app`、`list_apps`、`autoinstall_app`、`terminate`、`adb_shell` |
| AgentBay sandbox（13） | `create_sandbox`、`get_sandbox_url`、`kill_sandbox`、`system_screenshot`、`shell`、`click`、`input_text`、`send_key`、`get_all_ui_elements`、`get_clickable_ui_elements`、`get_installed_apps`、`start_app`、`stop_app_by_cmd` |

> `adb_shell`：参数 `command`（必填）、`timeout_ms`（默认 30000），云机通用 shell。
> `get_all_ui_elements` / `get_clickable_ui_elements`：云机内基于 `adb_shell` 执行
> `uiautomator dump` 拉取 UI XML 后解析返回元素列表（含 text/resource_id/class/bounds/clickable/enabled），
> 后者仅保留 `clickable=true` 的元素。
