# LLM 协议接入

产品：`v0.4` · 本地契约版本：`1` · 更新：2026-11-03。

已实现 Chat Completions 兼容接口和原生 OpenAI Responses 接口。两者由手机中的同一个 `OpenAiCompatibleProvider` 发起 HTTPS 请求，共用持久任务、功能绑定、凭证、额度、取消和用量记录。接口与数据定义见[本地后端契约](contract.md)，调用授权规则见[API 预设方案](../plan/api-presets.md)。

## 选择与配置

在“设置 → API 预设”创建或编辑预设，通过“接口协议”选择协议；填写供应商的 API 根地址、模型标识和密钥，再为所需功能绑定。保存只做本地检查；“测试连接”会向所选地址发起一次真实请求。

| 协议 ID | 界面名称 | `baseUrl` 示例 | 实际请求 |
| --- | --- | --- | --- |
| `openai_chat_completions` | OpenAI Chat Completions | `https://api.openai.com/v1` | `POST /v1/chat/completions` |
| `openai_responses` | OpenAI Responses | `https://api.openai.com/v1` | `POST /v1/responses` |

服务地址填写根路径，不含末尾的 `/responses` 或 `/chat/completions`；也支持供应商自己的路径前缀。切换协议保留已填写的服务地址。模型须支持所选协议与文本 JSON 对象输出；应用没有预设固定模型名单。

协议 ID 与支持目录由 [`ApiProtocols.kt`](../../backend/core-contract/src/main/kotlin/dev/wordprism/contract/ApiProtocols.kt) 统一维护。`Query.Protocols` 返回完整目录，预设校验和生成样例复用同一来源。前端的选项、说明以及已保存预设的协议名称均读取该目录。

## 请求与返回映射

| 本地字段/内容 | Chat Completions | 原生 Responses |
| --- | --- | --- |
| 任务指令 | `messages` 中 `system` 消息 | `instructions` |
| 业务 JSON 上下文 | `messages` 中 `user.content` 字符串 | `input` 字符串 |
| 输出上限 | `max_tokens` | `max_output_tokens`，包含可见输出及推理 tokens |
| JSON mode | `response_format.type = json_object` | `text.format.type = json_object` |
| 可选采样参数 | 配置后发送 `temperature` | 配置后发送 `temperature` |
| 文本提取 | `choices[0].message.content` | 遍历 `output` 中已完成的 assistant message，拼接其 `content` 中的 `output_text.text` |
| token 用量 | `usage.prompt_tokens / completion_tokens` | `usage.input_tokens / output_tokens` |

Responses 每次发送 `store: false`、`stream: false`。当前任务使用独立请求，不保存远端会话 ID，也不依赖 `previous_response_id`。原始 HTTP 不提供 SDK 的 `output_text` 便利属性，因此不会把该属性或第一个推理项当成模型答案。模型文本先验证为 JSON 对象，再按对应业务类型校验，全部有效后才提交本地变更。

`ApiPreset.jsonMode` 默认为 `true`；关闭时省略相应格式参数。`temperature` 默认为 `null`，不发送模型可能不支持的采样参数。当前 Compose 表单保留这些高级字段，配置入口仍以本地契约为准。没有新增依赖或供应商 SDK。

## 失败、取消与用量

| 服务商结果 | 本地错误/处理 |
| --- | --- |
| HTTP 401 / 403 | `PROVIDER_AUTH` |
| HTTP 429 或 Responses `failed` + `rate_limit_exceeded` | `PROVIDER_RATE_LIMIT`，可按用户设置重试 |
| HTTP 5xx 或 Responses `failed` + `server_error` | `PROVIDER_UNAVAILABLE`，可按用户设置重试 |
| Responses `failed` + `invalid_prompt` | `VALIDATION`，提示检查输入或模型配置 |
| Responses `incomplete` + `max_output_tokens / max_messages` | `TRUNCATED_RESPONSE`；不把部分文本写入词库 |
| 内容过滤、拒绝、空文本、畸形输出或无效 JSON 对象 | `INVALID_RESPONSE` |
| Responses `queued / in_progress` | `OUTCOME_UNKNOWN`；同步请求没有取得最终结果，不自动重发 |
| Responses `cancelled` | `CANCELLED`；保留本地任务和失败用量 |
| 超时 / 连接中断 | `PROVIDER_TIMEOUT / OUTCOME_UNKNOWN`，须明确选择重试 |

每次尝试保留实际协议、预设版本、模型与已知用量。`output_tokens` 已包含 reasoning tokens，不再叠加子项；缺失、负数或畸形 token 数保留未知。即使生成被截断、拒绝或失败，服务商已提供的有效用量仍写入任务记录。取消或关闭后的结果是否可提交继续由核心的持久状态及版本校验决定。

限流和服务不可用只按用户已有重试次数及备用预设执行，可以在两个协议之间切换；超时和未知结果不会触发这类自动切换。HTTP 重定向不转发凭证，OkHttp 不自行重试。请求上限 256 KiB，响应上限 2 MiB。凭证数组在发送后以及校验失败等退出路径均清零。

## 契约与数据兼容

- [协议目录样例](fixtures/api-protocols.json)
- [保存 Responses 预设的请求样例](fixtures/save-responses-preset.request.json)
- [预设与功能绑定响应样例](fixtures/responses-preset.json)

这些样例由 `contract-fixtures` 从正式类型生成，没有真实密钥，模型名称是占位符。`ApiPreset.protocol` 延续可序列化字符串，默认值保持 `openai_chat_completions`；已有预设不会被改为 Responses。本次不改变契约、数据库或包模块的版本。完整备份保留两种协议、绑定及任务中的预设快照，恢复后依旧清除凭证、关闭自动调用，并等待显式恢复未完成任务。

## 依据与验证边界

按 OpenAI 官方的 [Responses 迁移指南](https://developers.openai.com/api/docs/guides/migrate-to-responses?api-mode=responses)、[文本输出指南](https://developers.openai.com/api/docs/guides/text)及 [Create response API 参考](https://developers.openai.com/api/reference/resources/responses/methods/create.md)核对请求字段、输出项、状态和用量含义。

验证使用真实 OkHttp 与本地 `MockWebServer`，覆盖原生 HTTP 格式、错误及取消，并经实际任务执行器验证离线排队、同键重放、预设快照和跨协议备用重试。SQLite/前端门面测试验证重开、标签与绑定，完整备份测试验证两种协议恢复。具体执行结果见[验证记录](validation.md)。这些是合成 HTTP / 模型数据，尚未使用真实供应商 API 或物理手机验证。
