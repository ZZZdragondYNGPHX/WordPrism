# 后端与 Android 集成验证

记录日期：2026-11-04 · 产品：`v0.4` · 执行环境：Windows，独立 API 35 x86_64 Android 模拟器。

Claude 的 Compose 前端通过 `sdk-codex` 接入真实本机核心。test.6 增加词源、助记、英英释义与词族候选，并接入同一 AI 分析、练习、任务和撤销体系。本轮按用户授权执行真实 API 完整流程，修复可选字段 `null`、题面遗漏、已有词接纳撤销和重开后的任务排序问题。正式 Android APK 已完成实际添加、讲解重试、离线阅读及包含新内容的完整备份恢复；相关测试、debug/release 构建、Lint、契约及 APK 检查通过。下文区分真实服务、合成响应、模拟器及未验证范围，保留早期版本的历史证据。

## 测试与构建

| 检查 | 实际结果 | 覆盖 |
| --- | --- | --- |
| `:core-engine:test` | 96 tests，0 failures / errors / skipped | 原有业务/HTTP/学习/恢复回归；新增 17 个讲解测试，含字段边界、并发、迟到结果、AI 子任务、保留后续作答的撤销、词族接纳、旧数据与备份 |
| `:platform-jvm:test` | 4 tests，全部通过 | 真实 SQLite 持久化、恢复及数据版本 |
| `:memory-core:test` | 1 test，通过 | 真实 Rust JNI 调度/拟合/评估边界 |
| `android :sdk-codex:test` | 23 tests，全部通过 | 8 个门面测试；15 个真实 `CoreEngine + SQLite + Rust JNI` 集成测试，含讲解、词族接纳/撤销、六题型完整题面与重开后的最新任务 |
| `android :app:testDebugUnitTest` | 6 tests，全部通过 | 模型拉取重复点击、取消、旧响应、离开页面、凭证清理和明确重试 |
| `ModelPickerTest` | 2 tests，通过 | 实际 Compose：60 项合成列表中的搜索和准确选择、无匹配结果、关闭保留手动输入 |
| `AndroidCoreTest` | 5 tests，通过 | Room、JNI 调度与本机拟合、Keystore、Room 1→2、应用平台与 WorkManager |
| `ExplanationAndroidTest` | 1 test，本轮通过 | 真 Room / Keystore / JNI 上的讲解、词族接纳/撤销、重开与备份恢复；模型为脚本响应 |
| `ProcessRestartProbeTest` | 写入、真实 force-stop、读取两段均通过 | 跨新 PID 保留已提交状态，不以重建对象代替进程终止 |
| test.6 真实 AI 流程探针 | 完整流程 1 test 通过；失败已诊断、修复后续跑 | Windows 正式 `CoreEngine / TaskRunner / OpenAiCompatibleProvider`；17 次真实生成请求及一次模型目录，包含失败尝试，用量见下文 |
| test.6 正式 APK 的真实 API | 4 次生成请求：连接、添加、讲解截断及同任务重试成功 | API 35 x86_64 模拟器直连 OpenAI；原义项不改写、用量保留、强停重开不重发 |
| 后端 Maven 发布 | 五模块发布成功 | `core-contract / memory-core / core-engine / platform-jvm / platform-android` |
| Android 构建 | `assembleDebug / assembleRelease` 通过 | 可安装 debug 与 unsigned release；release 不包含模拟业务类 |
| `lintDebug` | 0 errors，3 warnings | 目标 API、低 API 忽略 localeConfig、依赖 catalog 提醒 |
| 契约校验脚本 | 12 schemas、40 messages、8 个参考包模块通过 | 同源 JSON Schema、31 个响应、7 个命令请求及 2 个模型目录请求夹具、三个正式包 |
| 最终 APK 校验脚本 | debug / release 均通过 | 两个 ABI 的 6 个原生库、ELF/ZIP 16 KiB 对齐、249 个组件许可、源码附件、字体摘要 |

当前常规 JVM 测试集共 130 个。本轮执行核心 96 个、SQLite 平台 4 个、前端门面及集成 23 个、页面状态 6 个，共 129 个；未修改的 `memory-core` 1 个沿用此前结果。完整核心结果保存在 `backend/build/reports/test6-core-full/`；最后的已有词复用修复又执行 `ExplanationTest / AiWorkflowTest / PackageTest`，均通过。发现一个测试因表达式函数推断为非 `Unit` 而未被 JUnit 发现后，统一声明返回 `Unit`，确认 17 个讲解测试全部实际执行。真实 API 探针单独计数，不属于常规离线测试集，最终通过的 XML 保存于 `backend/build/reports/live-learning/passed-test-results/`。

Android 平台原有 5 个 instrumentation 测试及独立进程探针在早期集成执行，模型选择器 2 个 Compose 仪器测试在 test.3 执行，本轮未重复。本轮新增讲解平台测试，并在正式 APK 操作真实预设、添加、讲解、重试、离线接纳/撤销、force-stop、SAF 完整备份与恢复。test.5 的输入法 composition 和读取超时修复、test.4 的泛型编译修复属于已有结果。没有因失败停在实验阶段；本次实际问题均完成修复和相关复测。

运行命令与依赖版本集中维护在[构建与接入](running.md)。测试报告、APK、模拟器截图和临时数据库位于已忽略的 `build/`，不提交个人数据或构建缓存。

## 关键一致性场景

核心与真实适配器集成测试覆盖：

- 同键重放、同键不同内容冲突，以及换请求键重复提交同一题；真实作答与调度只生效一次。
- SQLite 关闭重开后的会话、presentation、反馈和草稿；开放反馈绑定答案版本，旧版本结果不进入当前表达用法统计。
- 多次结束学习日、多个会话、重启、离线延迟和跨时区情况下的每日分析去重；手动与自动分析复用相同证据。
- 取消 AI 添加、关闭自动分析和撤销父任务后，迟到响应仍可记用量，但不能写入已失效的内容或策略。
- 实际应用策略/内容变更、用户能力锁、30 分钟默认预算、无可用阅读材料时顺延；不足样本不强行拟合。
- 撤销保留之后的真实作答并重算调度；后续明确编辑产生冲突时不覆盖用户改动。
- 正式内容包重导去重、完整备份恢复、未知可选模块保留、未知必需模块拒绝、非法引用/摘要/ZIP 路径拒绝、旧版本迁移。
- 完整恢复保留身份及历史，关闭自动调用，清除凭证引用，将未完成任务置为 `RESTORED_PENDING`；预览后继续学习会使旧预览失效。
- 文件流导出/恢复的幂等，原始表单版本重放，成员顺序，区分“未找到”与存储故障，故障后的原订阅重试。

模型业务使用明确的脚本替身，允许有意模拟取消后的迟到返回。HTTP 测试使用真实 OkHttp 适配器连接本地 `MockWebServer`，检查两种协议的路径、请求字段、JSON mode、返回用量、限流、截断、非法 JSON、跳转和传输校验；它没有连接真实 LLM 供应商。

Responses 新增覆盖：推理项在前、多段文本、SDK 便利属性不作为原始输出、拒绝/失败/未完成状态、畸形 token 数、真实 HTTP 取消、校验失败清理凭证；任务执行覆盖离线排队、同键重放、提交后修改预设仍用原协议快照，以及用户配置的 Responses → Chat Completions 备用重试。完整备份测试加入两种协议，确认恢复预设、绑定、未完成任务快照，保留作答和策略审计并清除凭证。

模型目录新增覆盖：未保存连接无需名称或模型；两个协议的 GET / Bearer / 路径前缀；ID 去重排序、空列表、畸形数据、鉴权/限流/不支持列表、重定向不跟随、2 MiB / 10,000 项上限、真实 HTTP 取消/超时/中途断流。核心测试验证同地址规范化、密钥仅在本次使用、错误不是 `STORAGE`、预设/凭证变化后的迟到结果、故意不响应取消的替身，以及查询前后数据库完全一致。SDK 与页面测试验证离线不调用核心、重复点击只发一次、改配置后旧失败不覆盖新结果、离开前尚未调度的请求也清理密钥；重开后可用已保存凭证重新查询。

思维程度及调用修复新增覆盖：七档在两协议中的准确字段、未指定时省略、Chat 推理 token 上限、配置修改不改变排队请求、备用协议采用备用预设档位；完整备份保留各预设版本和任务档位，缺字段的旧数据采用默认值。HTTP 测试覆盖粘贴完整端点、两个协议切换、模型目录根路径、JSON 输入要求，以及认证/权限/参数错误的脱敏诊断。任意服务商 message、code、type、param 回显密钥的合成场景不会泄漏原文。

升级自检发现新增默认字段改变旧 `SavePreset` 摘要，回归先以 test.3 的实际命令格式和摘要复现 `IDEMPOTENCY_CONFLICT`。修复为省略默认值后，旧键重放保持数据库不变，同键更改显式档位仍拒绝；相关测试和完整核心回归通过。

test.5 新增两个真实 HTTP 回归先在旧实现失败，修复后通过。慢响应测试并行覆盖 Chat、Responses、模型目录的响应头及正文六条路径：延迟 11 秒、预算 15 秒，均成功，防止隐含 10 秒默认值回归。总预算测试分别等待响应头 3 秒、正文 3 秒，预算 5 秒，必须失败；即使允许自动重试也不重发未知结果。保存新版预设后明确重试采用 `MAX / 8000 / 180 秒`，重试排队后再编辑不会改变已捕获版本，旧用量保留且同键不增加请求。SDK 集成进一步覆盖超时替身、保存、SQLite 重开、重试、再重开的全过程。

## 真实 API 诊断与验证

### test.6：词源与助记及完整 AI 流程

按用户最新授权继续使用仍有效的临时 key。目标保持 OpenAI Responses / `gpt-6-luna`。宿主用正式核心和适配器发请求；业务时钟固定以复现学习日与幂等，HTTP 调用真实联网。测试输入为公开单词与合成作答，不使用个人学习资料。已完成的阶段从本地 checkpoint 续跑，不为重跑断言重复付费。

| 宿主功能 | 真实结果 | 输入 / 输出 tokens |
| --- | --- | --- |
| 模型目录 | 130 个模型，含 `gpt-6-luna`；完整端点规范化正确 | 不产生生成用量 |
| 连接 | 成功 | 115 / 52 |
| 添加 `hospital / hospitable` | 成功，保存词条与组关系 | 274 / 1014 |
| 首次讲解 | `MAX / 8000 / 180 秒`，约 75.5 秒后截断，未保存半成品 | 1073 / 8000 |
| 收紧讲解篇幅后重测 | 成功保存版本化讲解与词族，约 37.7 秒 | 991 / 4004 |
| 持续添加 `hospice` | 成功加入已有组 | 915 / 676 |
| 组内推荐 | 成功，候选尚未自动接纳 | 1133 / 625 |
| CHOICE / RECALL / SPELLING | 三种实际生成均通过解析、写入和查询 | 分别 1110 / 1080、1110 / 1220、1127 / 717 |
| PASSAGE / WRITING / REWRITE | 三种实际生成均通过解析、写入和查询 | 分别 1127 / 6591、1127 / 1011、1127 / 2469 |
| 表达反馈 | 对一条合成开放作答返回有效引用与反馈 | 727 / 270 |
| 日终分析 / 手动分析 | 两次均成功，证据不足时保留当前策略，无子动作 | 分别 6638 / 4982、6703 / 7807 |
| 重生成讲解的首次响应 | HTTP 成功，类型解析因可选备注 `null` 拒绝 | 2298 / 7773 |
| 修复默认值归一化后明确重试 | 成功，约 59.1 秒；随后撤销恢复原讲解 | 2298 / 6319 |

宿主共 17 次真实生成请求，29,893 输入 / 54,610 输出 tokens，包含截断与业务校验失败尝试。模型目录另计一次 GET。候选本地接纳、重复提交、撤销、离线排队取消、核心重开、备份恢复与重复唤醒均额外验证，没有因此新增生成请求。六种练习实际携带当前讲解；日终与手动分析读取同一事实口径。两次分析只有一条合成开放作答，模型选择不调整，不能据此声称真实模型的自动调参或自动讲解动作已验证；这些写入、关闭和迟到分支由脚本回归覆盖。

| 正式 Android APK 的本轮请求 | 真实结果 / 耗时 | 输入 / 输出 tokens |
| --- | --- | --- |
| 连接，`MAX / 8000 / 180 秒` | 成功，3338 ms | 115 / 48 |
| 添加 `affect / effect`，相同配置 | 成功，17,840 ms | 1782 / 1609 |
| `affect` 讲解，离线排队和强停后联网执行 | 75,447 ms，`TRUNCATED_RESPONSE` | 1045 / 8000 |
| 保存 `MAX / 16384 / 180 秒` 后重试同一任务 | 成功，91,547 ms，`attempt = 2`，采用预设第 4 版 | 1045 / 11,315 |

设备本轮共 4 次生成请求，3987 输入 / 20,972 输出 tokens。Room 中共 5 条用量，其中一条来自 test.5，不重复计入本轮。宿主加设备合计 21 次生成请求，33,880 输入 / 75,582 输出 tokens；这是响应报告的总数，包含失败，没有核对账单。`output_tokens` 已包含推理，不重复相加。

这次实测发现并修复了以下问题：

- 讲解过长时更易耗尽输出额度：收紧生成目标为约 500–800 汉字及简短英文；部分词在 `MAX / 8000` 仍会截断，提示明确告知调整并保存后重试。相同设备任务在 16,384 上限成功，不以连接成功承诺业务预算一定足够。
- 服务商把可选备注/列表输出为 `null`：根据正式序列化类型的默认值归一化；必填正文、未知字段、非法枚举仍拒绝，旧数据和本地命令的解析不放宽。
- 真实 RECALL / SPELLING 的问题或上下文落在独立字段时，门面只显示 `prompt`：六题型统一映射完整题面，作答前不泄漏标准答案，新增真 SQLite 集成回归。
- 接纳已有词只改变推荐状态时没有撤销入口：接纳仍记录可撤销操作；已有单义项词复用原内容，避免 AI 换一种释义表达后新增义项。
- Room 重开后按 Map 末项选任务，旧取消任务遮住最新失败：按创建/更新时间选最新任务，核心及真 SQLite 重开回归通过；取消待联网任务时同步清除旧错误。

宿主证据位于 `backend/core-engine/build/reports/live-learning/`：初次失败 `first-attempt-steps.json`、分段请求/响应/HTTP 元数据 `run-*/`、最终 `checkpoint.json` 与 `phase.txt = 5`。它们和一次性探针位于已忽略目录，不提交真实服务响应或凭证。宿主测试在 `finally` 清除凭证与临时环境；设备恢复后凭证引用为 0、加密凭证目录为空，默认预设及临时绑定也已清除。本轮真实调用已结束，可撤销服务端测试 key。

### test.4：Responses HTTP 400

用户提供手机截图与临时测试 key 后，先按原版请求向 `https://api.openai.com/v1/responses` 调用 `gpt-6-luna`，真实返回 HTTP 400：`invalid_request_error`、`param = input`，指出 `input` 必须含有 JSON 指令才能使用 `text.format = json_object`。此前只有 `instructions` 包含该指令；这是本次已复现的请求格式问题，不能归因于密钥。该失败响应未提供 token 用量。

修复后通过正式任务执行器创建连接测试，不使用脚本响应，结果如下：

| 请求 | 结果 | 输入 / 输出 tokens |
| --- | --- | --- |
| Responses，模型默认思维程度 | `SUCCEEDED`，返回有效 `{"ok":true}` | 115 / 42 |
| Responses，`LOW` | `SUCCEEDED`，返回有效 `{"ok":true}` | 116 / 30 |
| Chat Completions，`LOW` | `SUCCEEDED`，返回有效 `{"ok":true}` | 105 / 11 |
| GET `/models` | 130 个模型，包含 `gpt-6-luna` | 无生成 token 用量 |

三个成功生成请求共 336 输入 / 83 输出 tokens；输出统计包含供应商报告的推理用量，不重复叠加。测试将完整 `/v1/responses` 地址交给正式适配器，验证正确规范化和跨协议路径。临时 key 仅经进程环境和内存凭证传入，不写入源码、固定测试夹具、设备或 APK；测试完成后已告知用户可撤销，无后续真实调用。

这是 Windows 宿主上的 OpenAI 网络证据，内容仅为连接测试；不能代替 Android 手机网络、所有档位、其他供应商或实际 AI 学习业务质量验证。回归中的模拟服务器单独覆盖失败、取消和状态恢复。

### test.5：连接成功但实际添加超时

按用户截图保持 `https://api.openai.com/v1`、Responses、`gpt-6-luna`、`MAX`、8000 输出 tokens 和 180 秒上限，正式核心提交 `complement compliment`。核对 OkHttp 4.12.0 源码后确认，原实现只设置整次 `callTimeout`，读取阶段仍受默认 10 秒约束。另确认手动重试保留了旧任务的预设快照，导致保存更长超时后也可能继续使用旧值。修复和重试契约见[协议说明](api-protocols.md#失败取消与用量)。

| 执行位置与阶段 | 实际结果 | 耗时 | 输入 / 输出 tokens |
| --- | --- | --- | --- |
| Windows 宿主，修复前 | `FAILED / PROVIDER_TIMEOUT`，未写入词或组 | 11,423 ms | 未知，失败响应未提供 |
| Windows 宿主，修复后 | `SUCCEEDED`，新增 2 词、1 组、2 个成员关系 | 12,501 ms | 274 / 1247 |
| API 35 x86_64 模拟器，最终 APK | `SUCCEEDED`，新增 2 词、1 组、2 个成员关系 | 15,263 ms | 1084 / 1500 |
| 同一模拟器，GET `/models` | 130 个模型，搜索命中 `gpt-6-luna` | 未单独计时 | 无生成 token 用量 |

宿主使用空测试库，模拟器使用包含既有词组的库，因此上下文与用量不同。成功生成共 1358 输入 / 2747 输出 tokens；修复前超时请求的供应商实际用量仍未知，不能按零计。宿主探针还验证同键重放和再次运行已完成任务均不新增 API 请求。模拟器中只产生一条 `WORD_INTAKE` 用量，任务 `attempt = 1`，配置快照与用户截图一致。

真实响应包含两个正确拼写的词、非空释义与例句，以及对应组关系。此单个样本证明完整请求、解析和本地保存可以工作，不证明所有词条、评分、分析或长期学习质量。测试只使用公开词汇和合成旧词库。宿主 key 仅经临时进程环境传入；模拟器用正式 Keystore 保存，结束后删除加密凭证文件及引用、清除临时功能绑定，未写入源码、夹具或 APK。真实 API 测试已经结束，可撤销本轮临时 key。

## Android 应用实际流程

独立设备为 `emulator-5556`（API 35，x86_64，480×800）。以下操作由 adb / UIAutomator 驱动实际 APK，与宿主单元测试分开：

1. 安装真实核心 debug APK，从空词库冷启动；通过 SAF 选择 `example-content.wordprism`，预览并导入 3 个词及 1 个组，任务为 `SUCCEEDED`。
2. 词库显示 `linear / liner / linen`。完成基础判断与回忆/拼写，输入、提交、提示和反馈写入真实 Room。
3. 实际 `am force-stop app.wordprism` 后重开，PID 从 `3631` 变为 `3922`；4 条记录的 ID、版本、答案及 presentation 保持一致，恢复原反馈。
4. 总览显示 4 次作答；延迟回忆保持 `0 / 0`，不将理解步骤或看过提示的作答算作独立记忆证据。
5. 通过 SAF 导出约 16.76 kB 的完整备份，再选择该文件；六模块预览通过，明确执行“替换并恢复”，任务为 `SUCCEEDED`。4 条原始记录、会话和反馈身份保留，自动调用关闭。
6. 安装最终 APK，再次冷启动；保留导入及恢复后的数据，首页显示 3 个新词，原反馈可继续查看；暂停退出后，词库显示“学习中”，总览仍为 4 次作答。

操作证据位于本机 `backend/build/reports/app-ui/`：`restart-before.json`、`restart-after.json`、`backup-restored.json`、`force-stop-feedback.png`、`backup-preview-visible.png`、`backup-restored.png`、`final-apk-feedback.png`、`final-apk-library.png`、`final-apk-overview.png`。正式备份副本为 `emulator-export.wordprism`；这些是公开合成资料的本地调试产物，不随仓库发布。

本次 UI 操作发现并修复了输入法 composition 残留导致明确点击“提交”静默无效的问题，使用相同步骤重新输入并点击后成功保存。另修复了把 12 个学习目标误显示为 12 个新词的问题，现由核心提供去重的新词数。最终包检查发现依赖原先带入没有 FSRS 库的 32 位 ABI，已限制为两个完整的 64 位 ABI 并重新构建、检查。

### Responses 扩展与覆盖升级

在同一独立模拟器用 `adb install -r` 从原测试版升级至 `versionCode = 2`、`versionName = 0.4.0-test.2`，安装成功且未清除数据。通过实际预设页面输入合成配置 `Responses-local-test`、`https://example.invalid/v1`、`test-responses-model`，从 Chat Completions 切换到 Responses 后，自定义地址保留；保存成功，设置列表显示正确的协议名称。

执行真正的 `am force-stop` 并冷启动，再打开预设，协议、地址、模型均保留。升级前 PID `4633`、升级后重启 PID `5410`；脚本比对前后的 3 个词条身份/版本、4 条作答身份/版本/序号及会话字段一致。Room 副本确认保存协议为 `openai_responses`，用量记录仍为零。测试配置没有凭证，也未点击连接测试。

证据位于 `backend/build/reports/app-ui/`：`responses-upgrade-before.json`、`responses-upgrade-after.json`、`responses-protocol-options.png`、`responses-saved.png`、`responses-preset-list.png`、`responses-reopened-preset.png` 及相应 UI 树。安装后第一次 UI 树抓取遇到启动尚未完成的 `null root node`；检查新进程和 AndroidRuntime 后再次抓取成功，没有应用崩溃。此为模拟器及本地配置证据，原生 HTTP 的合成服务测试单独记录在上文。

新版可安装 APK 的 SHA-256 为 `22b84740b362afd11daab20ad5e08bb795f4c3d630541ac586d03a038ed6ad6b`，使用与前版一致的 debug 签名，签名校验通过。分发入口为 [v0.4.0-test.2 Release](https://github.com/ZZZdragondYNGPHX/WordPrism/releases/tag/v0.4.0-test.2)，附件同时提供示例内容包及 `SHA256SUMS.txt`。

### 模型目录与覆盖升级

在同一模拟器从 test.2 覆盖升级至 `versionCode = 3`、`versionName = 0.4.0-test.3`。现有 Responses 预设显示“拉取模型”，点击后在真实离线状态显示可重试错误，原有模型不变；新建表单在名称、模型均为空时同样可发起目录查询。两者均未配置真实密钥，未发起供应商调用。实际界面截图与 UI 树为 `models-existing-preset`、`models-offline`、`models-new-preset`、`models-new-offline`。

`ModelPickerTest` 在实际 APK 的 Compose 环境以合成的 60 个模型测试搜索：输入 `model-42` 显示一项并选择准确 ID；无匹配时明确提示，关闭不会覆盖已有的 `manual-model`。两个测试通过。截图 `model-picker-search.png`、`model-picker-no-match.png` 已人工查看，文本、选择控件和搜索输入可见；截图来自 Android 渲染，不是静态网页或真实供应商列表。测试替换的是组件内容，网络与正式 SDK 的测试单独记录在上文。

执行 `am force-stop` 冷启动后，比对 `models-upgrade-before.json` / `models-upgrade-after.json`，3 个词、4 条作答、1 个会话与 2 个已有业务操作的记录身份/版本及所捕获字段保持一致；PID 从 `5410` 变为 `5913`。这些证据与仪器测试输出 `models-instrumentation.txt` 位于 `backend/build/reports/app-ui/`。

交互自检采用 `microinteractions` 的 8 项诊断：可发现入口、触发状态、简单规则、即时状态反馈、反馈程度、重复使用、模式清晰、首次使用均通过，评分 10/10，无未通过项。依据为可见的拉取/取消入口、同步切换加载状态、明确错误与空列表、已有预设减少重复输入、搜索后单击选择及手动输入保留。未对端到端反馈毫秒数或 TalkBack 朗读做仪器测量。

可安装 APK 为 25,192,926 bytes，SHA-256 `a5fb822b2c6ca716ba32914c8b9e23233f8f54f5d827e8950eb8f19bbd3a9f7c`。签名证书与前版一致（SHA-256 `d362a43b64230b0258c89a5dd463de551f822540db5ecd9a71c28b7bcb6e0652`），APK v2 签名校验通过。分发入口为 [v0.4.0-test.3 Release](https://github.com/ZZZdragondYNGPHX/WordPrism/releases/tag/v0.4.0-test.3)，附件包括 APK、示例内容包及摘要文件。

### 思维程度、调用修复与覆盖升级

同一模拟器从 test.3 覆盖升级至 `versionCode = 4`、`versionName = 0.4.0-test.4`。旧预设初始显示“默认（由模型决定）”；选择高档、保存后真正强停重开仍显示“高（high）”。选择“关闭（none）”但返回不保存，再进入仍为高档。随后恢复默认、保存并再次强停，重新进入显示默认；Room 副本保留历史 `HIGH` 版本，当前值为 `null`。

自动比对 `reasoning-upgrade-before.json / reasoning-upgrade-after.json / reasoning-upgrade-final.json`，3 个词、4 条作答、1 个暂停会话和 2 个已有操作的身份、版本及捕获字段一致，PID 为 `5913 → 6409 → 6729`。模拟器一直离线、未配置 key，用量记录为零。`reasoning-effort-options.png`、`reasoning-saved-high.png`、`reasoning-reopened-high.png`、`reasoning-reopened-default.png` 与 UI 树保存于同一 `app-ui` 目录，实际查看后确认下拉选项、说明和保存反馈完整可见。

交互自检按 `microinteractions` 八项诊断：可发现入口、当前状态、可预测规则、即时反馈、反馈程度、重复使用、模式清晰、首次可理解均通过，10/10，无未通过项。沿用 Compose 原有下拉/表单控件，修改立即反映选择，保存状态可见，离开未保存不写库，重开读取已保存值。未测量端到端毫秒数或进行 TalkBack 朗读测试。

最终重打包后再次覆盖安装并冷启动，PID `6729 → 6884`；`reasoning-release-before.json / reasoning-release-after.json` 比对确认上述学习记录不变，保存的默认档位保留，用量仍为零。实际查看 `reasoning-release-preset.png`，新档位入口与地址说明正常显示。

可安装 APK 为 25,209,310 bytes，SHA-256 `b2739f5ff7d2be1e05e9b32d53c9b5214e0fd28e6748d2da6b5366018bec4cdf`。打包前发现增量 ZIP 留有约 3.8 MB 未引用空间，删除该生成 APK 后重新执行打包，体积恢复正常；最终内容、16 KiB 对齐和同一 debug 证书的 v2 签名再次验证通过。分发入口为 [v0.4.0-test.4 Release](https://github.com/ZZZdragondYNGPHX/WordPrism/releases/tag/v0.4.0-test.4)。

### AI 添加超时、明确提交与覆盖升级

在同一模拟器正常重启恢复 Wi-Fi 后，从 test.4 覆盖升级至 `versionCode = 5`、`versionName = 0.4.0-test.5`，没有清空或卸载应用。新建独立测试预设、保存截图中的参数与临时凭证，拉取到真实模型列表并搜索命中 `gpt-6-luna`；只为 AI 添加设置临时绑定，自动分析保持关闭。

实际输入 `complement compliment` 并隐藏键盘后，发现 `compliment` 仍带输入法 composition，原按钮条件静默阻止提交，数据库确认尚无添加任务。沿用学习页已有实现，在明确按钮点击时捕获可见文本、清除 composition 和焦点后提交；键盘回车仍不直接触发 API。重新构建、覆盖安装最终 APK，再次手动输入形成相同候选状态，点击一次后显示“进行中”，随后变为“词条与归组已保存”。

打开实际词组可见两个词的释义和例句。`Usage.startedAtMs / endedAtMs` 差为 15,263 ms，超过旧的 10 秒限制；正式 Room 中共 5 个词、2 个组、1 条用量，新增任务为 `SUCCEEDED`。随后真正 `am force-stop` 并冷启动，PID `3047 → 3379`；逐条比对词、义项、组、成员、作答、会话、用量及操作的完整存储记录，强停前后完全相同。启动前已有的 3 个词、4 条作答、1 个暂停会话和 2 个业务操作的身份、版本及捕获字段也保持不变。重新打开词库和词组显示同样内容，无重复生成请求。

结束后通过预设页面删除测试密钥并保存，恢复 AI 添加未绑定状态。数据库凭证引用为 0，加密凭证目录为空，用量仍为 1 条。保留公开测试词组用于本地检查。证据位于 `backend/build/reports/app-ui/`：`intake5-live-model-search.png`、`intake5-submit-stalled.png`、`intake5-retyped-before-submit.png`、`intake5-final-request-running.png`、`intake5-final-result.png`、`intake5-reopened-group.png`、`intake5-live-evidence.json`、`intake5-restart-validation.json` 与各阶段状态及 UI 树。

按 `microinteractions` 八项行为诊断，修复后的明确提交为 10/10（8/8）：入口和状态可见、规则与学习页一致、点击进入真实任务反馈、结果与撤销可追溯、草稿减少重复输入、输入法候选不再成为隐含阻挡、初次可按标签完成；无未通过的行为项。仅沿用现有 Compose 控件，无布局或动效改造。该行为自检未测量 100 ms 反馈阈值，也未验证中文输入法候选或 TalkBack 朗读。

最终 APK 为 25,209,310 bytes，SHA-256 `423e041a406028855549734f69116bd40c9a8e5d683b6933e166aca28bca60c3`。两种 APK 的内容、两个 ABI、16 KiB 对齐、许可和字体摘要检查通过，可安装 APK 的 v2 签名证书与前版一致。分发入口为 [v0.4.0-test.5 Release](https://github.com/ZZZdragondYNGPHX/WordPrism/releases/tag/v0.4.0-test.5)，附件包括 APK、示例内容包及 `SHA256SUMS.txt`。

### 词源与助记、真实讲解与完整恢复

同一独立模拟器从 test.5 覆盖升级至 `versionCode = 6`、`versionName = 0.4.0-test.6`，保留原有 5 个词、4 条作答和暂停会话。通过正式预设页重新保存临时 key，设置测试默认预设，自动分析与自动生成保持关闭。本轮设备生成请求和用量见上方真实 API 表。

实际执行的流程：

1. SAF 导入人工 `example-explanation.wordprism`，查看 hospital 分段讲解、词源与助记的不同说明。选择 hospitality 时显示“不会调用 API”；快速重复点击仅接纳一次，已加入词可打开详情，撤销后候选恢复，生成用量不增加。
2. 真实添加 `affect / effect` 后打开详解。关闭 Wi-Fi 和移动数据，提交讲解并取消，任务 `attempt = 0` 且无用量；再次离线提交，执行真正的 `am force-stop`，PID `5314 → 5764`，待联网任务及旧记录保留。恢复网络后自动执行已授权请求。
3. 8000 输出上限的讲解被截断。保存 16,384 上限后，在修复过任务排序的最终 APK 点击同一任务“重试”，采用预设第 4 版并成功；保留首次失败及两次实际用量，义项的原释义、词性、可接受答案、用户例句均未改写。
4. 检查实际英文定义、词源说明、助记、用法与例句的长文滚动。再次断网，接纳已有词 effect，再通过“查看词条”确认复用原义项和例句；撤销接纳后原词、4 条历史及调度不变，没有新增 API 请求。
5. 强停重开，PID `6224 → 6742`；逐项比对词、义项、组、关系、作答、会话、调度、操作、用量和推荐均一致。断网重新打开 affect，讲解可读且显示正确的完成任务。一次 UI 树抓取恰逢冷启动返回 `null root node`，待实际页面出现后重抓正常，无应用崩溃。
6. 离线为 effect 创建一个待联网任务，用 SAF 导出 33,594 bytes 完整备份，重新选择刚导出的文件，六模块预览通过并执行“替换并恢复”。恢复前后 9 个词行（含 1 个归档词）、9 个义项、3 个组、7 个成员关系、4 条作答及版本、1 个会话、34 个调度、3 个推荐、5 条历史用量逐项完全一致，已有完成任务和撤销关系保留。
7. 待联网任务恢复为 `RESTORED_PENDING / attempt = 0`，恢复联网后显示“备份恢复后需要重新授权任务”，未发送请求。随后取消该测试任务。恢复自动清空凭证引用及加密凭证文件，最后清除测试默认预设；独立绑定为 0，自动分析/自动生成仍关闭。再次打开 affect，可读取恢复的真实讲解。

证据保存在 `backend/build/reports/app-ui/`：`explanation6-real-max16384-success.png`、`explanation6-real-sections.png`、`explanation6-real-family-accepted-offline.json`、`explanation6-real-family-undone.json`、`explanation6-real-offline-read.json`、`explanation6-before-backup-restore.json`、`explanation6-after-backup-restore.json`、`explanation6-restored-pending-online.png`、`explanation6-final-state.json`、`explanation6-final-restored-content.png` 和自动比对结果 `explanation6-audit.json`。本轮真实库的备份为 `explanation6-device-backup.wordprism`，只保留本机调试证据，Release 上传的是人工示例包。

交互自检使用 `microinteractions` 的八项诊断，按 `round(7 / 8 × 10)` 记为 **9/10**：

| 诊断项 | 结果与依据 |
| --- | --- |
| 入口可发现 | 通过：组内“词源与助记”、待整理词“详解”，均有可见按钮 |
| 当前状态可见 | 通过：提交、排队、取消、失败、完成、撤销与过期均来自核心状态 |
| 规则可预测 | 通过：查看本地内容不调用，明确生成/接纳前显示实际调用条件 |
| 即时反馈低于 100 ms | 未测量阈值，不记通过：同步设置提交/接纳忙状态和选择状态已实现，仍需设备时序测量才能确认该指标 |
| 反馈程度合适 | 通过：页内状态和既有 snackbar，真实失败带可操作说明，无假进度 |
| 重复使用 | 通过：保存内容离线复用，候选接纳后保留查看入口，重试使用保存的新配置 |
| 无不必要模式 | 通过：当前义项、旧内容和候选选择可见，返回不隐式取消任务 |
| 初次可理解 | 通过：生成、保存、候选选择及是否调用 API 的标签明确 |

当前沿用 Compose 控件，无新增动效。未通过项的具体处理是保留同步反馈、不阻塞输入，并将端到端 100 ms 验证留作设备测量；不将静态代码或 UI 树当作时延证据。此次不新增独立评审或性能门槛。

test.6 分发入口为 [GitHub Release](https://github.com/ZZZdragondYNGPHX/WordPrism/releases/tag/v0.4.0-test.6)。可安装 APK 为 25,530,908 bytes，SHA-256 `69464fa00848dc91df2796ccfea2ae1282df209f836436088ae4f265421fc756`，v2 签名证书与前版一致，支持覆盖安装。附件包括 APK、两个人工内容示例包和 `SHA256SUMS.txt`。unsigned release 只作为构建验证产物，不提供给用户安装。

早期 [Claude 前端验证](../frontend/validation-2026-11-03.md)记录的是模拟后端与模拟包格式，保留其历史含义，不用于证明本次真实存储或模型调用。

## 日计划、用时估计与窄屏辨析修复（2026-11-05）

- `:core-engine:test` 113 项、`:platform-jvm:test` 4 项通过。新增覆盖日预算内扩展安排、跨天旧会话隔离、当天原题续接、旧计划更新、禁用题型，以及按能力计算有效用时、等待/后台排除、异常长记录、旧计时兼容和过期样本。
- `:sdk-codex:test` 26 项、`:app:testDebugUnitTest` 6 项、debug 应用和测试 APK 构建、`:app:lintDebug` 通过。新增验证没有数据库写入时仍刷新时间相关订阅，取消订阅后停止刷新。
- API 35 x86_64 模拟器中，`FocusRowTest` 在 320 dp 宽、1.5 倍字体下验证词组完整分行、说明位于下方及点击打开。使用独立测试应用恢复用户提供的备份，核对新一天首页、待学分类及开始学习后的会话/计划一致性。私有备份与临时验证脚本未纳入仓库。
- 可安装 debug APK 通过 `validate-apk.py` 的双 ABI、16 KiB 对齐、原生内核、249 项第三方声明及字体/源码附件检查。修复版分发入口为 [v0.4.0-test.8](https://github.com/ZZZdragondYNGPHX/WordPrism/releases/tag/v0.4.0-test.8)，Android 版本号递增为 8。未进行物理手机或长期用时预测准确度验证。

## 证据边界与限制

- **真实 API 范围有限**：OpenAI `gpt-6-luna` 的实际连接、目录、添加、归组、推荐、讲解、六题型、表达反馈与分析均有样本；分析只使用一条合成开放作答并实际选择不调整。实际 AI 自动动作由脚本响应覆盖，不等于真实服务主动调整已获验证。没有其他供应商、全部档位、广泛内容质量或物理手机的成功证据。用量为服务商响应数字，没有核对账单；早期超时的未知用量仍未知。
- **推理输出额度仍由用户配置约束**：`MAX / 8000` 的实际讲解出现截断；`MAX / 16384 / 180 秒` 在本轮约 92 秒成功。不承诺所有内容均成功，不自动扩大额度、降低思维档位或将一次操作拆为多次付费请求。AI 词源和助记分别标识，未经词典事实核验。
- **没有物理手机或 arm64 运行证据**。arm64 库已构建并检查，但模拟器为 x86_64；minSdk 声明也不是 API 26 实机验证。
- 没有长期学习效果、真实个人参数收益、目标规模性能或功耗证据。资料库的 10,000 词 / 100,000 作答限制是保护边界，不是已经达成的性能指标；当前事务仍读取、解码资料库快照。
- 内容包冲突明确拒绝，需修正来源后重新导入；没有逐项交互合并。批量任务暂不提供分数进度。
- Android 仍受系统后台调度约束；计时心跳丢失后的未确认尾段不补算。文件提供方与本机数据库不能跨系统原子提交，具体导出重试语义见[契约](contract.md)。
- 既有 Compose 仪器测试覆盖模型选择器；本轮真实添加/讲解、离线接纳/撤销、重开和完整恢复通过 adb/UIAutomator 操作正式 APK 验证。未验证中文输入法输入、TalkBack 朗读、横屏或平板，未进行真机延迟或性能测量。保留前端已有的无障碍与动效实现。
- Debug APK 可安装；release 尚未签名或上架。GitHub 源码推送不等同于应用发行。
