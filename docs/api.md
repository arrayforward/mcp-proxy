# API 文档

> 版本：v1.1 ｜ 更新：2026-08-04
> Base URL：`http://localhost:8080`（gateway）、`http://localhost:9091`（mcp-mock）、`http://localhost:9092`（auth-validator-mock）

## 1. 概述

| 分类 | 端点 | 鉴权 |
|---|---|---|
| 实例管理（华为兼容） | `/api/v1/instances/*` | 请求头 `x-auth-token`（10s 临时 token） |
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

## 2. 实例管理 API（gateway）

### 2.1 订阅实例（CreateInstance）

镜像华为 `POST /v1/instances/create`。

```
POST /api/v1/instances/create
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

响应（200）：

```json
{
  "data": {
    "orderId": "CS20260804A1B2C3D4",
    "instanceInfos": [
      { "instanceId": "Ab3xYz9p", "instanceName": "koophone-00001" }
    ]
  },
  "error_code": "0",
  "error_msg": "OK"
}
```

> `instanceId` 即 MCP URL 的一部分：`POST /mcp/{instanceId}`。
> 订阅成功后实例信息与**访问方式**（access_method / mcp_url / backend_url / backend_token）持久化到 MySQL `t_cloud_phone_instance`。

### 2.2 查询实例信息（ListInstances，含访问方式）

```
POST /api/v1/instances/list
x-auth-token: <10s临时token>
```

请求体：`{ "user_id": "user-10001", "instance_ids": ["Ab3xYz9p"] }`（`instance_ids` 可省略，省略查全部）

响应（200）：

```json
{
  "data": {
    "instance_list": [
      {
        "instance_id": "Ab3xYz9p",
        "instance_name": "koophone-00001",
        "status": 2,
        "access_method": "streamable-http",
        "mcp_url": "http://localhost:8080/mcp/Ab3xYz9p",
        "mcp_ip": "10.0.0.23",
        "mcp_port": 9091,
        "region_id": "cn-north-7",
        "os": "AOSP14"
      }
    ]
  },
  "error_code": "0",
  "error_msg": "OK"
}
```

### 2.3 退订实例（DeleteInstance）

```
POST /api/v1/instances/delete
x-auth-token: <10s临时token>
```

请求体：`{ "instanceIdList": ["Ab3xYz9p"] }`

响应（200）：`{ "data": null, "error_code": "0", "error_msg": "OK" }`

### 2.4 实例批量准备（BatchPrepareInstances）

```
POST /api/v1/instances/prepare
x-auth-token: <10s临时token>
```

请求体：`{ "user_id": "user-10001", "instance_ids": ["Ab3xYz9p"] }`

响应（200）：

```json
{
  "data": { "status_list": [ { "instance_id": "Ab3xYz9p", "status": 1 } ] },
  "error_code": "0",
  "error_msg": "ok"
}
```

### 2.5 实例准备进度（ShowProgress）

```
POST /api/v1/instances/prepare-progress
x-auth-token: <10s临时token>
```

请求体：`{ "user_id": "user-10001", "instance_id": "Ab3xYz9p" }`

响应（200）：

```json
{
  "data": { "status": 1, "waitingCount": 2 },
  "error_code": "0",
  "error_msg": "OK"
}
```

状态：`0` 正常（就绪）、`1` 排队中、`2` 还原中/离线、`3` 备份中、`-1` 处理失败。
**Agent 循环轮询直到 `status == 0` 才可发起 MCP 请求。**
就绪时 proxy 自动调用 E4 `fetchAccessInfo(instanceId)` 获取云机 IP/端口并落库（见 external-api.md）。

### 2.6 查询云机访问信息（access-info）

```
POST /api/v1/instances/access-info
x-auth-token: <10s临时token>
```

请求体：`{ "instance_id": "Ab3xYz9p" }`

响应（200）：

```json
{
  "data": { "instance_id": "Ab3xYz9p", "mcp_ip": "10.0.0.23", "mcp_port": 9091 },
  "error_code": "0",
  "error_msg": "OK"
}
```

> 解析顺序：Redis 缓存（命中即续期 30min）→ MySQL → E4 接口获取并落库。

---

## 3. 认证 API（gateway）

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

## 4. MCP 代理 API（gateway）

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

tools/list 返回 12 个工具（与 `mcp_mobile_use` 对齐）：
`tap`、`swipe`、`take_screenshot`、`text_input`、`back`、`home`、`menu`、
`launch_app`、`close_app`、`list_apps`、`autoinstall_app`、`terminate`
