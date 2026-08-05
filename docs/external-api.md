# 外部依赖接口设计文档（External APIs）

> 版本：v1.2 ｜ 更新：2026-08-04
> 用途：本文档定义 mcp-proxy 依赖的**外部（非 MCP）接口**契约，用于向对应系统/项目提需求。
> 当前实现：仓库内 `auth-validator-mock`（:9092）与 `proxy` 内置 `MockKooPhoneClient` 为 Mock 实现，字段与真实系统对齐后可无缝切换。

## 1. 依赖总览

| # | 外部系统 | 接口 | 调用方 | 当前 Mock 实现 |
|---|---|---|---|---|
| E1 | 统一认证/校验服务 | 签发临时 token | Agent / 客户端 | `auth-validator-mock:9092` |
| E2 | 统一认证/校验服务 | 校验临时 token | proxy | `auth-validator-mock:9092` |
| E3 | 云手机控制面（KooPhone） | 实例订购类（华为兼容，由 proxy 代理） | proxy | `MockKooPhoneClient`（进程内） |
| E4 | 云手机控制面（KooPhone） | **按 instanceId 查询云机 IP + MCP 端口** | proxy | `MockKooPhoneClient#fetchAccessInfo` |
| E5 | 云手机 MCP（mcp_mobile_use） | `GET /healthz` 判活 | proxy（就绪时 + 活跃期每 30s） | `mcp-mock:9091` |

## 1.1 云手机 ≈ Sandbox（阿里云 AgentBay 模型映射）

订阅/退订语义参照阿里云 AgentBay Mobile Use 的 sandbox 模型
（`https://help.aliyun.com/zh/agentbay/developer-reference/mobile-use-andriod-14`）：

| AgentBay sandbox 工具 | 本项目对应接口 | 语义 |
|---|---|---|
| `create_sandbox` | `POST /api/v1/instances/create`（CreateInstance） | 创建云手机沙箱，返回 instanceId（≈ sandbox_id） |
| `get_sandbox_url` | `POST /api/v1/instances/access-info` | 获取云机访问地址（ip + mcp_port，即 MCP 运行时 URL） |
| `kill_sandbox` | `POST /api/v1/instances/delete`（DeleteInstance） | 退订，释放云手机资源 |
| `system_screenshot` | MCP `take_screenshot` 工具 | 截图（经 proxy 转发至云机 MCP） |
| `shell` | MCP `adb_shell` 工具 | 云机通用 shell（经 proxy 转发） |

云机内 MCP（`mcp_mobile_use`）同时暴露 AgentBay 兼容工具集（click / input_text / send_key /
get_all_ui_elements / get_clickable_ui_elements / get_installed_apps / start_app / stop_app_by_cmd），
其中 `get_all_ui_elements` 与 `get_clickable_ui_elements` 在云机内**基于 `adb_shell` 执行
`uiautomator dump` 拉取 UI 层级 XML 后解析实现**（后者在解析结果上过滤 `clickable=true`）。

---

## 2. E1 签发临时 token

| 项 | 内容 |
|---|---|
| 方法/路径 | `POST /api/token/issue` |
| 提供方 | 统一认证服务（真实）；`auth-validator-mock`（Mock） |
| 鉴权 | 无（内网/网关侧调用约束由部署保证） |

请求体字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `uid` | string | 是 | 用户唯一标识 |
| `instanceId` | string | 是 | 目标云手机实例 ID |

响应体字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `token` | string | 临时 token，格式 `tmp.<uid>.<instanceId>.<签发毫秒时间戳>` |
| `expiresIn` | number | 有效期（秒），固定 `10` |

示例：

```json
// 请求
{ "uid": "user-10001", "instanceId": "Ab3xYz9p" }
// 响应
{ "token": "tmp.user-10001.Ab3xYz9p.1722700000000", "expiresIn": 10 }
```

## 3. E2 校验临时 token

| 项 | 内容 |
|---|---|
| 方法/路径 | `POST /api/validate/token` |
| 提供方 | 统一认证服务（真实）；`auth-validator-mock`（Mock） |
| 鉴权 | 无 |

请求体字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `token` | string | 是 | E1 签发的临时 token |

响应体字段（校验通过）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `valid` | boolean | `true` |
| `uid` | string | token 绑定的用户 |
| `instanceId` | string | token 绑定的实例 |
| `expiresAt` | number | 过期时间点（毫秒时间戳） |

响应体字段（校验失败）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `valid` | boolean | `false` |
| `reason` | string | 失败原因：`malformed`（格式非法）/ `expired`（超过 10 秒） |

校验规则（真实系统需遵守）：

| 规则 | 说明 |
|---|---|
| 格式 | 4 段以 `.` 分隔，首段固定 `tmp` |
| 时效 | `now - 签发时间戳 <= 10s`，超时视为 `expired` |
| 绑定 | 必须返回 token 签发时绑定的 `uid` / `instanceId`，proxy 据此签发 30min JWT |

## 4. E3 实例订购类接口（华为 KooPhone 兼容）

proxy 以**透传代理 + 本地编排**方式实现以下接口，报文与华为 KooPhone API 对齐
（参考 `https://support.huaweicloud.com/api-koophone/kp_01_0001.html`），真实环境由 proxy 内
`KooPhoneClient` 的华为 REST 实现替换 Mock。

| 接口 | proxy 入口 | 说明 |
|---|---|---|
| CreateInstance | `POST /api/v1/instances/create` | 订阅实例，返回 orderId + instanceId 列表 |
| ListInstances | `POST /api/v1/instances/list` | 查询实例（含访问方式、mcp_ip/mcp_port） |
| DeleteInstance | `POST /api/v1/instances/delete` | 退订实例（逻辑删除） |
| BatchPrepareInstances | `POST /api/v1/instances/prepare` | 批量准备，进入排队 |
| ShowProgress | `POST /api/v1/instances/prepare-progress` | 准备进度轮询，waitingCount 递减至 0 就绪 |

通用响应包裹：

| 字段 | 类型 | 说明 |
|---|---|---|
| `data` | object/null | 业务数据 |
| `error_code` | string | `0` 成功；其余见错误码表 |
| `error_msg` | string | 描述 |

错误码：

| error_code | 含义 |
|---|---|
| `KOOPHONE.API.1000` | 请求参数错误 |
| `KOOPHONE.API.1001` | 无实例权限 / token 无效 |
| `KOOPHONE.API.4001` | 实例不存在 |
| `KOOPHONE.API.5002` | 实例未就绪（proxy 新增） |
| `KOOPHONE.API.9999` | 服务内部错误 |

ShowProgress 状态码（data.status）：

| status | 含义 |
|---|---|
| `0` | 正常（就绪，可发起 MCP 请求） |
| `1` | 排队中 |
| `2` | 还原中/离线 |
| `3` | 备份中 |
| `-1` | 处理失败 |

## 5. E4 按 instanceId 查询云机访问信息（本版新增，重点需求）

| 项 | 内容 |
|---|---|
| 接口名 | `fetchAccessInfo(instanceId)` |
| 提供方 | 云手机控制面（真实）；`MockKooPhoneClient`（Mock，返回配置化 `127.0.0.1:9091`） |
| 触发时机 | ① 实例轮询就绪（prepare-progress 归零）时；② 路由缓存与 MySQL 均未命中时 |
| 消费方 | proxy `RouteService` |

请求：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `instanceId` | string | 是 | 云手机实例 ID |

响应：

| 字段 | 类型 | 说明 |
|---|---|---|
| `ip` | string | 云手机 IP 地址（云机内 MCP 服务所在地址） |
| `mcpPort` | number | 云机内 MCP 服务端口 |

落库与缓存约定（proxy 侧行为，真实系统仅需保证接口可用）：

| 步骤 | 行为 |
|---|---|
| 1 | 调用成功后将 `mcp_ip` / `mcp_port` 持久化到 MySQL `t_cloud_phone_instance` |
| 2 | 同步写入 Redis 缓存 `mcp:route:{instanceId}`，TTL 30 分钟 |
| 3 | 每次 MCP 转发命中缓存即**续期** 30 分钟（滑动过期） |
| 4 | 缓存未命中 → 查 MySQL → 仍无 → 回调本接口获取并重复步骤 1-2 |

## 5.1 E5 云机 MCP 判活接口（healthz）

| 项 | 内容 |
|---|---|
| 方法/路径 | `GET /healthz` |
| 提供方 | 云机内 MCP 服务（mcp_mobile_use，真实）；`mcp-mock`（Mock） |
| 成功 | HTTP 200，body `{ "status": "UP" }` |
| 失败 | 非 2xx 或连接超时即判死 |

proxy 调用时机：

| 时机 | 频率 | 判死后行为 |
|---|---|---|
| 实例就绪（prepare-progress 归零）时 | 一次 | 判死 → 实例置 FAILED |
| 用户持有 MCP 长连接（SSE/WS），或最近一次 MCP 请求后 3 分钟内 | 每 30 秒 | 更新 `t_cloud_phone_instance.healthy` |

## 6. 真实系统对接要求汇总（提需求用）

| # | 需求 | 约束 |
|---|---|---|
| R1 | 提供 E1/E2 两个 token 接口 | 字段、10s 时效、错误 reason 枚举与本文档一致 |
| R2 | 提供 E4 访问信息查询接口 | 入参 instanceId，出参 ip + mcpPort；实例就绪后必须可查 |
| R3 | E3 接口保持华为兼容报文 | error_code 体系一致，便于 proxy 无缝切换真实后端 |
| R4 | 接口可用性 | E4 在 prepare-progress 返回 status=0 之前允许 404/重试，就绪后必须稳定返回 |
| R5 | 云机 MCP 提供 `GET /healthz` | 2xx 判活；响应时间 < 3s；免鉴权 |
| R6 | 云机 MCP 提供 `adb_shell` 工具 | 参数 `command`（必填）/`timeout_ms`（默认 30000）；UI 元素类工具（get_all/get_clickable_ui_elements）基于 `adb_shell` + `uiautomator dump` 实现 |
| R7 | 云机 mcp-server 预置 RSA 公钥并验签 | RS256 验签 proxy 签发的用户 JWT（`/mcp`、`/sse`、`/message` 全部要求 `Authorization: Bearer`）；无 token/验签失败/过期 → 401；公钥分发与轮换见 docs/security.md |
