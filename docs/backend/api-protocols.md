# LLM 协议接入

产品：`v0.4` · 本地契约版本：`1` · 更新：2026-11-04。

已实现 Chat Completions 兼容接口和原生 OpenAI Responses 接口。两者由手机中的同一个 `OpenAiCompatibleProvider` 发起 HTTPS 请求，共用持久任务、功能绑定、凭证、额度、取消和用量记录。接口与数据定义见[本地后端契约](contract.md)，调用授权规则见[API 预设方案](../plan/api-presets.md)。

## 选择与配置

在“设置 → API 预设”创建或编辑预设，通过“接口协议”选择协议；填写供应商的地址和密钥后，可点击“拉取模型”，搜索并选择返回的模型，也可手动填写模型标识。选择思维程度并保存后，再为所需功能绑定。保存只做本地检查；“测试连接”使用已保存的预设发起一次真实推理请求。

| 协议 ID | 界面名称 | `baseUrl` 示例 | 实际请求 |
| --- | --- | --- | --- |
| `openai_chat_completions` | OpenAI Chat Completions | `https://api.openai.com/v1` | `POST /v1/chat/completions` |
| `openai_responses` | OpenAI Responses | `https://api.openai.com/v1` | `POST /v1/responses` |

服务地址可填写 API 根路径（如 `https://api.openai.com/v1`）或完整端点。核心统一去掉末尾的 `/responses`、`/chat/completions` 或 `/models`，再按操作追加路径，避免出现 `/responses/responses`。自定义路径前缀和服务域名保持不变；不会自动改到其他服务商。已有预设和任务快照在发送时采用同一规范化，不改写历史配置。切换协议保留已填写的服务地址。模型须支持所选协议与文本 JSON 对象输出；应用没有预设固定模型名单。

协议 ID 与支持目录由 [`ApiProtocols.kt`](../../backend/core-contract/src/main/kotlin/dev/wordprism/contract/ApiProtocols.kt) 统一维护。`Query.Protocols` 返回完整目录，预设校验和生成样例复用同一来源。前端的选项、说明以及已保存预设的协议名称均读取该目录。

输出上限和超时可以分别留空。[保存样例](fixtures/save-unlimited-preset.request.json)中 `maxOutputTokens: null` 表示请求不发送输出额度参数，由服务商及模型的默认值和限制决定；这不是无限模型上下文或无限计费额度。`timeoutSeconds: null` 关闭应用的 HTTP 连接、读写和整次调用计时器，模型目录也使用该设置；网络、服务商或 Android 后台调度仍可能中断任务，用户仍可取消。有限输出额度接受正整数；有限超时接受 5–2,147,483 秒（底层 HTTP 客户端支持的数值范围）。历史数值预设保持原值；留空后的新配置需要保存，重试时捕获保存后的版本。请求 256 KiB、响应 2 MiB 的数据大小限制仍生效。

## 思维程度

唯一类型是核心 `ReasoningEffort`，字段为 `ApiPreset.reasoningEffort`。`Query.Protocols → ProtocolInfo.reasoningEfforts` 提供适配器支持发送的档位，前端只映射显示标签。该目录不代表当前模型支持全部档位，也不从 `/models` 的 ID 猜测能力。

| 选择 | 本地契约值 | 请求值 |
| --- | --- | --- |
| 默认（由模型决定） | `null`，旧数据缺失字段时也取此值 | 省略参数 |
| 关闭 | `NONE` | `none` |
| 极低 | `MINIMAL` | `minimal` |
| 低 / 中 / 高 | `LOW / MEDIUM / HIGH` | `low / medium / high` |
| 很高 / 最高 | `XHIGH / MAX` | `xhigh / max` |

Responses 使用 `reasoning: { effort: ... }`；Chat Completions 使用 `reasoning_effort`。Chat 显式配置档位时将输出上限映射到包含推理 tokens 的 `max_completion_tokens`，未指定时保留旧预设的 `max_tokens`。输出上限与超时不会因提高档位而被静默扩大；较高档位可能增加时长、用量或触发截断。服务商不支持的参数明确失败，不自动降档或重发付费请求。

修改后须保存，再点击“测试连接”。新任务使用保存后的预设版本，排队或在途任务保留原快照；用量同样保留当次思维程度。完整备份保留当前值和历史版本，恢复不会自动启用调用。选择默认会真实清除显式配置，`preserveAdvancedSettings` 只保留 `enabled/jsonMode/temperature`，不会覆盖提交的思维程度。

同源样例：[保存高思维程度](fixtures/save-reasoning-preset.request.json)、[返回的预设](fixtures/reasoning-preset.json)、[恢复模型默认](fixtures/reset-reasoning-preset.request.json)。不携带密钥或真实学习资料。

## 拉取模型

唯一类型为 [`ModelCatalogRequest / ApiModel`](../../backend/core-contract/src/main/kotlin/dev/wordprism/contract/ModelCatalog.kt)，入口为挂起方法 `WordPrism.listModels(request, secret: CharArray? = null)`，成功返回 `View.Models`。`JsonBridge.listModels(json, secret)` 使用同一类型。此操作与不联网的 `query` 分开，支持只有地址、协议和超时的新表单，无需先保存预设、名称或填写模型。

两个协议都向服务根地址发送一次 `GET /models`，使用 Bearer 凭证。解析 `data[].id` 和可选 `owned_by`，按 ID 去重排序；不按名称猜测模型支持的协议、文本或 JSON 能力。最多接收 2 MiB / 10,000 项，超限明确失败，不返回被静默截断的目录。目录只保留在当前页面，不写入词库、任务、备份、连接测试状态或推理 token 用量，也不消耗应用内的生成请求额度。

凭证参数不在可序列化请求中。传入数组时使用本次密钥，并在所有退出路径清零；不传时使用 `credentialPreset: Ref` 指定的已保存凭证，校验预设版本及规范化后的同一服务根地址。修改服务地址后须输入该地址的密钥；切换 Chat / Responses 且地址不变可复用原凭证。查询期间预设被编辑/删除或凭证被替换/移除时，旧结果返回 `VERSION_CONFLICT`。读取保存的密钥不会将明文返回给 UI。

UI 门面为 `PresetsApi.listModels(ModelCatalogDraft, secret)`。点击才发请求，同一页面仅允许一个在途请求；取消协程中止 HTTP。改地址、协议、超时、密钥或离开页面会取消并丢弃迟到结果，进程重启后不会重发。断网显示 `OFFLINE`；联网后由用户重试。选择模型只填写表单，需点击“保存”才修改预设，关闭列表保留手动输入。

| 目录结果 | 处理 |
| --- | --- |
| HTTP 401 / 403 | `PROVIDER_AUTH`，检查凭证 |
| HTTP 404 / 405 | `PROVIDER_UNAVAILABLE`，提示该服务没有列表接口，可手动填写 |
| HTTP 429 / 5xx | `PROVIDER_RATE_LIMIT / PROVIDER_UNAVAILABLE`，可手动重试 |
| 超时或连接中断 | `PROVIDER_TIMEOUT / PROVIDER_UNAVAILABLE`，可手动重试；元数据 GET 不标成推理结果未知 |
| 无效 JSON、缺少 `data` 数组或非法 ID | `INVALID_RESPONSE` |
| 空 `data` | 成功的空列表，提示手动填写 |

目录请求不执行自动重试、备用预设或重定向。示例：[新表单请求](fixtures/unsaved-models.catalog-request.json)、[复用凭证请求](fixtures/saved-models.catalog-request.json)、[模型列表](fixtures/models.json)、[空列表](fixtures/empty-models.json)、[列表不可用](fixtures/models-unavailable.json)。示例没有密钥，模型 ID 与所有者是合成值。

## 请求与返回映射

| 本地字段/内容 | Chat Completions | 原生 Responses |
| --- | --- | --- |
| 任务指令 | `messages` 中 `system` 消息 | `instructions` |
| 业务 JSON 上下文 | `messages` 中 `user.content` 字符串 | `input` 字符串，JSON 模式时前置明确的 JSON 输出指令 |
| 输出上限 | 未指定思维程度时为 `max_tokens`；显式配置时为 `max_completion_tokens` | `max_output_tokens`，包含可见输出及推理 tokens |
| 可选思维程度 | `reasoning_effort` | `reasoning.effort` |
| JSON mode | `response_format.type = json_object` | `text.format.type = json_object` |
| 可选采样参数 | 配置后发送 `temperature` | 配置后发送 `temperature` |
| 文本提取 | `choices[0].message.content` | 遍历 `output` 中已完成的 assistant message，拼接其 `content` 中的 `output_text.text` |
| token 用量 | `usage.prompt_tokens / completion_tokens` | `usage.input_tokens / output_tokens` |

Responses 每次发送 `store: false`、`stream: false`。当前任务使用独立请求，不保存远端会话 ID，也不依赖 `previous_response_id`。原始 HTTP 不提供 SDK 的 `output_text` 便利属性，因此不会把该属性或第一个推理项当成模型答案。模型文本先验证为 JSON 对象，再按对应业务类型校验，全部有效后才提交本地变更。

Responses JSON 模式的输入检查要求 `input` 消息包含 JSON 指令；只在 `instructions` 中声明会被真实服务以 HTTP 400 拒绝。适配器在 `input` 的业务数据之前加一条固定 JSON 输出指令，业务结构和主指令保持完整。此行为由用户临时 key 的真实调用定位，不能以本地合成响应的通过替代验证。

`ApiPreset.jsonMode` 默认为 `true`；关闭时省略相应格式参数。`temperature` 默认为 `null`，不发送模型可能不支持的采样参数。当前 Compose 表单保留这些高级字段，配置入口仍以本地契约为准。没有新增依赖或供应商 SDK。

## 失败、取消与用量

| 服务商结果 | 本地错误/处理 |
| --- | --- |
| HTTP 400 / 422 | `VALIDATION`，检查模型与请求参数 |
| HTTP 401 / 403 | `PROVIDER_AUTH`，分别显示认证失败 / 访问权限或地区限制与状态码 |
| HTTP 404 | `PROVIDER_UNAVAILABLE`，检查端点、协议与模型 |
| HTTP 429 或 Responses `failed` + `rate_limit_exceeded` | `PROVIDER_RATE_LIMIT`，可按用户设置重试 |
| HTTP 5xx 或 Responses `failed` + `server_error` | `PROVIDER_UNAVAILABLE`，可按用户设置重试 |
| Responses `failed` + `invalid_prompt` | `VALIDATION`，提示检查输入或模型配置 |
| Responses `incomplete` + `max_output_tokens / max_messages` | `TRUNCATED_RESPONSE`；不把部分文本写入词库 |
| 内容过滤、拒绝、空文本、畸形输出或无效 JSON 对象 | `INVALID_RESPONSE` |
| Responses `queued / in_progress` | `OUTCOME_UNKNOWN`；同步请求没有取得最终结果，不自动重发 |
| Responses `cancelled` | `CANCELLED`；保留本地任务和失败用量 |
| 超时 / 连接中断 | `PROVIDER_TIMEOUT / OUTCOME_UNKNOWN`，须明确选择重试 |

HTTP 错误保留状态码，以及核心白名单内的参数名、服务商 code/type；为 JSON 输入缺指令和思维程度不支持提供具体处理说明。服务商任意原始 message 不保存或回显，避免错误响应回显密钥或学习内容。未识别的内容使用稳定的本地错误。

`timeoutSeconds` 同时配置连接、读取、写入及整次 HTTP 调用的超时；空值显式禁用这四项应用计时器。不能只设置 `callTimeout`：OkHttp 4.12.0 的各 I/O 阶段另有默认 10 秒上限，会让设置为 180 秒的推理提前失败。有限超时提示包含本次生效的秒数；模型目录复用相同实现。慢响应回归覆盖超过 10 秒仍成功，以及多个阶段合计超出总预算时失败。

用户明确点击 `RetryOperation` 时，捕获原预设当前已保存的版本；因此修改超时、模型或思维程度后，应先保存，再重试原任务。过去尝试的 `Usage.preset` 与用量不改写。重试已排队后再编辑预设，也不会改动这次已经捕获的配置。同键重放不会重新捕获或额外发请求。超时或结果未知仍须手动重试，自动重试只适用于下述允许的错误。样例：[失败任务](fixtures/failed-operation.json)、[手动重试请求](fixtures/retry-operation.request.json)、[采用已保存新版的排队任务](fixtures/retry-uses-saved-preset.json)。

每次尝试保留实际协议、预设版本、模型与已知用量。`output_tokens` 已包含 reasoning tokens，不再叠加子项；缺失、负数或畸形 token 数保留未知。即使生成被截断、拒绝或失败，服务商已提供的有效用量仍写入任务记录。取消或关闭后的结果是否可提交继续由核心的持久状态及版本校验决定。

`TRUNCATED_RESPONSE` 明确提示未保存不完整内容，并说明可提高输出上限或降低思维程度，保存后再重试。连接测试的小请求成功，不代表讲解、短文或分析也能在同一预算内完成。test.6 的真实讲解在 `MAX / 8000` 下仍出现截断；同一任务保存 `MAX / 16384 / 180 秒` 后重试成功，实际输出 11,315 tokens。该配置是已通过的样本，不保证所有内容都能在额度内完成；应用不隐式改变用户配置或拆分付费请求。完整实测见[验证记录](validation.md#test6词源与助记及完整-ai-流程)。

限流和服务不可用只按用户已有重试次数及备用预设执行，可以在两个协议之间切换；超时和未知结果不会触发这类自动切换。HTTP 重定向不转发凭证，OkHttp 不自行重试。请求上限 256 KiB，响应上限 2 MiB。凭证数组在发送后以及校验失败等退出路径均清零。

## 契约与数据兼容

- [协议目录样例](fixtures/api-protocols.json)
- [保存 Responses 预设的请求样例](fixtures/save-responses-preset.request.json)
- [预设与功能绑定响应样例](fixtures/responses-preset.json)

这些样例由 `contract-fixtures` 从正式类型生成，没有真实密钥，模型名称是占位符。`ApiPreset.protocol` 延续可序列化字符串，默认值保持 `openai_chat_completions`；已有预设不会被改为 Responses。模型目录新增同源请求 schema 与 `View.Models`，仍为契约版本 1，客户端与核心一起构建；数据库和包模块版本不变。完整备份保留两种协议、绑定及任务中的预设快照，恢复后依旧清除凭证、关闭自动调用，并等待显式恢复未完成任务。

新增 `reasoningEffort` 为可选字段；旧 Room / SQLite 行、任务与 `.wordprism` 备份缺少字段时采用 `null`，无需迁移数据库结构。默认 `null` 在序列化时省略，保留旧 `SavePreset` 命令的摘要与幂等身份；非空档位参与摘要，同键改变档位仍返回 `IDEMPOTENCY_CONFLICT`。保存命令中省略或显式传 `null` 均表示恢复供应商默认，不表示保留原档位。历史记录、默认预设的请求行为保持兼容；客户端与核心仍共同构建。新枚举值对旧版客户端不承诺可执行，备份恢复规则继续由专属格式维护。

## 依据与验证边界

按 OpenAI 官方的 [Responses 迁移指南](https://developers.openai.com/api/docs/guides/migrate-to-responses?api-mode=responses)、[文本输出指南](https://developers.openai.com/api/docs/guides/text)及 [Create response API 参考](https://developers.openai.com/api/reference/resources/responses/methods/create.md)核对请求字段、输出项、状态和用量含义。

模型目录按官方 [List models API 参考](https://developers.openai.com/api/reference/resources/models/methods/list.md)核对 GET 路径、鉴权与 `data` 字段。供应商未实现此接口时，保留手动输入，不内置一份可能过时的模型名单。

思维程度及模型差异依据官方 [Reasoning models](https://developers.openai.com/api/docs/guides/reasoning)、[Create chat completion](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)与 Create response 参考。JSON 输入要求见 [Structured outputs / JSON mode](https://developers.openai.com/api/docs/guides/structured-outputs#json-mode)，HTTP 400 的实际响应将问题定位到 `input`。

超时行为已核对所采用版本的 [OkHttp 4.12.0 源码](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/OkHttpClient.kt)，未增加依赖。按用户截图的 `gpt-6-luna / MAX / 8000 / 180 秒` 配置，修复前的完整 AI 添加在约 11.4 秒超时，修复后约 12.5 秒成功并写入两个词与一个组；具体宿主和设备证据见[验证记录](validation.md)。

回归使用真实 OkHttp 与本地 `MockWebServer`，覆盖 HTTP 格式、错误与取消；任务执行器验证离线排队、同键重放、预设快照和跨协议备用重试，SQLite 与完整备份验证配置兼容。另按用户授权使用临时 key，对 OpenAI `gpt-6-luna` 真实验证 Responses、Chat 低档连接及模型目录。test.6 扩展到真实添加、持续归组、词族与组推荐、讲解、六种题型、表达反馈、日终和手动分析；正式 Android APK 也完成真实添加、讲解重试与持久化。分析样本证据不足时保留策略，没有产生自动调整动作；自动变更与迟到结果的验证使用脚本响应。具体用量、失败与修复见[验证记录](validation.md)，这些证据不代表物理手机、其他模型或长期学习质量已验证。
