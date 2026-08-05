# API 文档

> 版本：v1.3 ｜ 更新：2026-08-05
> Base URL：`http://localhost:8080`（proxy）、`http://localhost:9091`（mcp-mock）、`http://localhost:9092`（auth-validator-mock）
> 在线调试：启动 proxy 后访问 Swagger UI `http://localhost:8080/swagger-ui.html`

## 1. 概述

| 分类 | 端点 | 鉴权 |
|---|---|---|
| Sandbox（Agent 实例入口） | `/api/v1/sandbox/create`、`/status`、`/list`、`/kill` | `Authorization: Bearer <30min JWT>` |
| 认证 | `/api/auth/login`、`/api/auth/exchange` | 无 |
| MCP 代理 | `/mcp/{id}`、`/mcp/{id}/sse`、`/mcp/{id}/message`、`/ws/mcp/{id}` | `Authorization: Bearer <JWT>` |
| Mock 校验服务 | `/api/token/issue`、`/api/validate/token` | 无 |
| Mock MCP 服务 | `/mcp`、`/sse`、`/message`、`/healthz` | RS256 公钥验签（healthz 豁免） |

通用错误码（华为兼容）：

| error_code | HTTP | 含义 |
|---|---|---|
| `0` | 200 | 成功 |
| `KOOPHONE.API.1000` | 400 | 请求参数错误 |
| `KOOPHONE.API.1001` | 401/403 | token 无效 / 无实例权限 |
| `KOOPHONE.API.4001` | 404 | 实例不存在（含已退订） |
| `KOOPHONE.API.5002` | 409 | 实例未就绪 / 创建等待中超时 |
| `KOOPHONE.API.9999` | 409/502 | 内部错误 / 云机不可达 |

---

## 2. Sandbox API（Agent 唯一可见的实例入口）

> 设计约束：华为风格实例管理接口（CreateInstance / BatchPrepareInstances / ShowProgress /
> DeleteInstance / access-info）是 proxy 与云控制面之间的**内部 mock 接口，不对 Agent 暴露**。
> Agent 只能调用以下四个 sandbox 接口（对齐阿里云 AgentBay sandbox 语义）。
> 鉴权统一为 **30min Bearer JWT**（由 `/api/auth/login` 签发），JWT 的 uid 即沙箱归属。

### 2.1 create_sandbox（异步受理）

真实华为云手机开通约 1~5 分钟，故 create 为**异步**：内部完成 CreateInstance + BatchPrepareInstances
后立即返回，并启动后台看守线程轮询 ShowProgress（默认每 3s，最长 900s，均可配置）。
请求体与华为 CreateInstance 相同。

```
POST /api/v1/sandbox/create
Authorization: Bearer <30min JWT>
Content-Type: application/json
```

请求体字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `os` | string | 是 | 镜像版本：AOSP11 / AOSP14 |
| `instanceSkuId` | string | 是 | 实例规格，如 `kp.professional.2xlarge.128g.2` |
| `regionId` | string | 是 | 区域，如 `cn-north-7` |
| `bandSkuId` | string | 否 | 带宽规格 |
| `bandSize` | number | 否 | 带宽大小（M） |
| `count` | int | 否 | 数量，默认 1（当前返回首台） |
| `instanceNamePrefix` | string | 否 | 名称前缀，默认 `koophone` |
| `network` | string | 否 | 网络线路，如 `EIP` |
| `chargeParam` | object | 否 | 计费参数（透传华为） |

请求示例：

```json
{
  "os": "AOSP14",
  "instanceSkuId": "kp.professional.2xlarge.128g.2",
  "bandSkuId": "kp.bandwidth",
  "regionId": "cn-north-7",
  "instanceNamePrefix": "koophone",
  "bandSize": 4.0,
  "count": 1,
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

错误：缺必填参数 → 400 `KOOPHONE.API.1000`；JWT 缺失/无效/过期 → 401。

### 2.2 sandbox_status（轮询初始化进度）

纯读接口（Redis 滚动缓存优先，未命中回源 MySQL）。建议轮询间隔 ≥ 3 秒。

```
POST /api/v1/sandbox/status
Authorization: Bearer <30min JWT>
Content-Type: application/json
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

| sandbox_status | 含义 | 后续动作 |
|---|---|---|
| `initializing` | 初始化中（带 waiting_count） | 继续轮询 |
| `ready` | 就绪（healthz 判活通过） | 用 mcp_url 发起 MCP 请求 |
| `failed` | 初始化失败（healthz 未通过） | kill 后重新 create |
| `timeout` | 创建超时（看守线程 900s 未就绪） | kill 后重新 create |

### 2.3 list_sandbox（列出我的全部沙箱）

```
POST /api/v1/sandbox/list
Authorization: Bearer <30min JWT>
Content-Type: application/json
```

请求体：`{}`（无参数，按 JWT 的 uid 过滤）

响应（200）：

```json
{
  "data": {
    "sandboxes": [
      {
        "sandbox_id": "Ab3xYz9p",
        "instance_name": "koophone-00001",
        "sandbox_status": "ready",
        "healthy": true,
        "mcp_url": "http://localhost:8080/mcp/Ab3xYz9p",
        "mcp_ip": "10.0.0.23",
        "mcp_port": 9091
      },
      {
        "sandbox_id": "Xy7Qwe2k",
        "instance_name": "koophone-00002",
        "sandbox_status": "initializing",
        "waiting_count": 3
      },
      {
        "sandbox_id": "Mn4Zxc8v",
        "instance_name": "koophone-00003",
        "sandbox_status": "failed",
        "status_reason": "healthz-failed"
      }
    ]
  },
  "error_code": "0",
  "error_msg": "OK"
}
```

> 典型用法：Agent 先 list 一屏看清自己的沙箱，再对某一个 `sandbox_status` 轮询或 `kill_sandbox` 杀死。

### 2.4 kill_sandbox（退订释放）

```
POST /api/v1/sandbox/kill
Authorization: Bearer <30min JWT>
Content-Type: application/json
```

请求体：`{ "sandbox_id": "Ab3xYz9p" }`

响应（200）：`{ "data": null, "error_code": "0", "error_msg": "OK" }`

> 逻辑删除（status=DELETED），同时清除 Redis 路由缓存与实例状态缓存。
> 已退订沙箱再访问 MCP → 404。

---

## 3. 认证 API（proxy）

### 3.1 登录（Login）：10s token → 30min JWT

```
POST /api/auth/login
Content-Type: application/json
```

请求体：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `token` | string | 是 | 10s 临时 token（统一认证服务签发） |

响应（200）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `accessToken` | string | RS256 JWT，claims 含 uid + instanceId |
| `tokenType` | string | 固定 `Bearer` |
| `expiresIn` | int | 固定 1800（秒） |
| `uid` | string | 用户 ID |
| `instanceId` | string | token 绑定的实例 ID |

```json
{
  "accessToken": "eyJhbGciOiJSUzI1NiJ9...",
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
旧 JWT 签名无效或已过期 → 401（需重新走 10s token 登录，防无限续期）。

---

## 4. MCP 代理 API（proxy）

三种传输共用同一条决策链（顺序敏感）：

| 顺序 | 校验 | 失败响应 |
|---|---|---|
| 1 | JWT 验签 + 过期检查（SecurityConfig 拦截） | 401 |
| 2 | jwt.instanceId == 路径 instanceId | 403 `KOOPHONE.API.1001` |
| 3 | 实例归属：MySQL[instanceId].uid == jwt.uid | 403 / 404 `KOOPHONE.API.1001/4001` |
| 4 | 实例状态 == NORMAL | 409 `KOOPHONE.API.5002` |
| 5 | 转发云机（携带同一用户 JWT） | 云机不可达 → 502 |

### 4.1 streamable-http

```
POST /mcp/{instanceId}
Authorization: Bearer <JWT>
Content-Type: application/json
Accept: application/json, text/event-stream
```

#### 4.1.1 initialize

请求：

```json
{ "jsonrpc": "2.0", "id": 1, "method": "initialize",
  "params": { "protocolVersion": "2025-03-26", "capabilities": {},
              "clientInfo": { "name": "demo-agent", "version": "1.0" } } }
```

响应（200，透传云机）：

```json
{ "jsonrpc": "2.0", "id": 1,
  "result": { "protocolVersion": "2025-03-26", "capabilities": { "tools": {} },
              "serverInfo": { "name": "mcp-mock", "version": "1.0" } } }
```

#### 4.1.2 initialized 通知（无响应体）

```json
{ "jsonrpc": "2.0", "method": "notifications/initialized" }
```

响应：`202 Accepted`（空 body）。

#### 4.1.3 ping

请求：`{ "jsonrpc": "2.0", "id": 2, "method": "ping" }`
响应：`{ "jsonrpc": "2.0", "id": 2, "result": {} }`

#### 4.1.4 tools/list（26 个工具）

请求：`{ "jsonrpc": "2.0", "id": 3, "method": "tools/list" }`

响应（节选，完整 schema 见 §6）：

```json
{ "jsonrpc": "2.0", "id": 3,
  "result": { "tools": [
    { "name": "tap", "description": "Tap screen at coordinates",
      "inputSchema": { "type": "object",
        "properties": { "x": { "type": "integer" }, "y": { "type": "integer" } },
        "required": ["x", "y"] } },
    { "name": "adb_shell", "description": "Execute a standard adb shell command ...",
      "inputSchema": { "type": "object",
        "properties": { "command": { "type": "string" },
                        "timeout_ms": { "type": "integer" } },
        "required": ["command"] } }
  ] } }
```

#### 4.1.5 tools/call

请求（以 tap 为例）：

```json
{ "jsonrpc": "2.0", "id": 4, "method": "tools/call",
  "params": { "name": "tap", "arguments": { "x": 100, "y": 200 } } }
```

响应（云机执行结果透传）：

```json
{ "jsonrpc": "2.0", "id": 4,
  "result": { "content": [ { "type": "text", "text": "mock tap ok" } ], "isError": false } }
```

adb_shell 示例：

```json
// 请求
{ "jsonrpc": "2.0", "id": 5, "method": "tools/call",
  "params": { "name": "adb_shell",
              "arguments": { "command": "getprop ro.build.version.release", "timeout_ms": 30000 } } }
// 响应
{ "jsonrpc": "2.0", "id": 5,
  "result": { "content": [ { "type": "text",
    "text": "{\"command\":\"getprop ...\",\"exit_code\":0,\"timed_out\":false,\"stdout\":\"...\",\"stderr\":\"\"}" } ],
    "isError": false } }
```

#### 4.1.6 错误响应（HTTP 状态）

| HTTP | 场景 | body |
|---|---|---|
| 401 | 无 token / 签名无效 / 已过期 | 空（Security 层） |
| 403 | jwt.instanceId ≠ 路径 instanceId，或归属 uid 不一致 | `{data:null, error_code:"KOOPHONE.API.1001", ...}` |
| 404 | 实例不存在（含已退订） | `{data:null, error_code:"KOOPHONE.API.4001", ...}` |
| 409 | 实例未就绪 | `{data:null, error_code:"KOOPHONE.API.5002", ...}` |
| 502 | 后端云机 MCP 不可达 | `{data:null, error_code:"KOOPHONE.API.9999", ...}` |

### 4.2 SSE 会话

#### 4.2.1 建立会话

```
GET /mcp/{instanceId}/sse
Authorization: Bearer <JWT>        （也可用 ?token=<JWT>，EventSource 无法设头时）
```

响应：`Content-Type: text/event-stream`，首帧为**重写后的** endpoint：

```
event: endpoint
data: /mcp/{instanceId}/message?sessionId=<32位hex>
```

> 云机下发的原始 endpoint 是 `/message?sessionId=xxx`，proxy 重写为自身入口路径，
> Agent 后续消息 POST 回 proxy 而非直连云机。

#### 4.2.2 提交消息

```
POST /mcp/{instanceId}/message?sessionId=<32位hex>
Authorization: Bearer <JWT>
Content-Type: application/json
```

请求体：任意 JSON-RPC（同 4.1）。
响应：`202 Accepted`（空 body）；云机处理结果经 SSE 通道回推：

```
event: message
data: {"jsonrpc":"2.0","id":11,"result":{...}}
```

#### 4.2.3 会话保活与关闭

- 云机侧空闲 15s 发 `: keepalive` 注释保活（透传）；
- Agent 断开 → proxy 关闭到云机的 SSE 流，连接计数 -1（探活范围收缩）。

### 4.3 WebSocket

```
WS /ws/mcp/{instanceId}?token=<JWT>
```

- 握手时验签 + instanceId 比对 + 归属 + 就绪门控，任一失败 → 关闭（POLICY_VIOLATION）；
- 连接后：客户端发**文本帧**（JSON-RPC），proxy 桥接为云机 `POST /mcp`，响应以文本帧回推；
- 通知类消息（云机无响应体）不回推；
- 云机不可达时回推 `{"error":"backend unavailable"}`，连接保持。

客户端示例（JS）：

```js
const ws = new WebSocket("ws://localhost:8080/ws/mcp/Ab3xYz9p?token=" + jwt);
ws.onopen = () => ws.send(JSON.stringify({ jsonrpc: "2.0", id: 21, method: "ping" }));
ws.onmessage = (e) => console.log(JSON.parse(e.data)); // { jsonrpc:"2.0", id:21, result:{} }
```

---

## 5. Mock 校验服务（auth-validator-mock，:9092）

### 5.1 签发 10s 临时 token

```
POST /api/token/issue
Content-Type: application/json
```

请求体：`{ "uid": "user-10001", "instanceId": "Ab3xYz9p" }`

响应：`{ "token": "tmp.user-10001.Ab3xYz9p.1722700000000", "expiresIn": 10 }`

### 5.2 校验 token

```
POST /api/validate/token
Content-Type: application/json
```

请求体：`{ "token": "tmp.user-10001.Ab3xYz9p.<ts>" }`

响应（有效）：

```json
{ "valid": true, "uid": "user-10001", "instanceId": "Ab3xYz9p", "expiresAt": 1722700010000 }
```

响应（无效）：

```json
{ "valid": false, "reason": "expired" }
```

| reason | 含义 |
|---|---|
| `malformed` | 格式非法（非 4 段 / 首段非 tmp / 时间戳非数字） |
| `expired` | 超过 10 秒时效 |

---

## 6. Mock MCP 服务（mcp-mock，:9091）

| 端点 | 鉴权 | 说明 |
|---|---|---|
| `POST /mcp` | RS256 公钥验签 | streamable-http：initialize / ping / tools/list / tools/call / notifications |
| `GET /sse` | RS256 公钥验签 | SSE 会话建立，下发 endpoint |
| `POST /message?sessionId=` | RS256 公钥验签 | 提交消息，202，SSE 回推 |
| `GET /healthz` | 豁免 | 判活：200 `{ "status": "UP" }` |

> 未配置 `MCP_JWT_PUBLIC_KEY` 时为 dev 模式，不验签（启动 WARN）。

### 6.1 工具清单（26 个）

#### mcp_mobile_use 组（13 个，与 C++ 实现对齐）

| 工具 | 必填参数 | 可选参数 | 说明 |
|---|---|---|---|
| `tap` | x, y | — | 点击坐标 |
| `swipe` | start_x, start_y, end_x, end_y | duration_ms | 滑动 |
| `take_screenshot` | — | — | 截图 |
| `text_input` | text | — | 输入文本 |
| `back` / `home` / `menu` | — | — | 按键 |
| `launch_app` | package_name | — | 启动应用 |
| `close_app` | package_name | — | 关闭应用 |
| `list_apps` | — | — | 已装应用列表 |
| `autoinstall_app` | url | — | 下载安装 |
| `terminate` | — | — | 终止 MCP 服务 |
| `adb_shell` | command | timeout_ms（默认 30000） | 通用 adb shell（低层接口，任意命令） |

#### AgentBay sandbox 组（13 个，对齐阿里云 AgentBay Mobile Use）

| 工具 | 必填参数 | 说明 |
|---|---|---|
| `create_sandbox` | — | 创建沙箱返回 ID |
| `get_sandbox_url` | sandbox_id | 取 MCP 运行时 URL（单次有效） |
| `kill_sandbox` | sandbox_id | 释放沙箱 |
| `system_screenshot` | sandbox_id | 全屏截图返回 URL（64 分钟过期） |
| `shell` | sandbox_id, command, timeout_ms | Android shell |
| `click` | x, y, button | 点击（button: left/middle/right） |
| `input_text` | text | 输入文本 |
| `send_key` | key | 按键（3:HOME 4:BACK 24:VOL_UP 25:VOL_DOWN 26:POWER 82:MENU） |
| `get_all_ui_elements` | timeout_ms | 全部 UI 元素（基于 adb_shell + uiautomator dump 实现） |
| `get_clickable_ui_elements` | timeout_ms | 可点击子集（clickable=true 过滤） |
| `get_installed_apps` | —（desktop/ignore_system_app/start_menu 可选） | 已装应用 |
| `start_app` | start_cmd | 按命令启动（monkey -p 语法） |
| `stop_app_by_cmd` | stop_cmd | 按命令停止 |

### 6.2 tools/call 返回结构

统一为 `{ "content": [ { "type": "text", "text": "<结果>" } ], "isError": false }`，
其中 sandbox 生命周期 / shell / UI 元素类工具的 `text` 为结构化 JSON 字符串：

| 工具 | text 内容示例 |
|---|---|
| `create_sandbox` | `{"sandbox_id":"sandbox-mock-0001"}` |
| `get_sandbox_url` | `{"url":"http://.../mcp?ticket=..."}` |
| `kill_sandbox` | `{"released":true}` |
| `shell` | `{"exit_code":0,"output":"..."}` |
| `adb_shell` | `{"command":"...","exit_code":0,"timed_out":false,"stdout":"...","stderr":""}` |
| `get_all_ui_elements` | `{"source":"adb_shell:uiautomator dump ...","elements":[{text,resource_id,class,package,bounds,clickable,enabled}]}` |
| `get_clickable_ui_elements` | 同上，仅 clickable=true 元素 |
