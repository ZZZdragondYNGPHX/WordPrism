# 后端与 Android 集成验证

记录日期：2026-11-04 · 产品：`v0.4` · 执行环境：Windows，独立 API 35 x86_64 Android 模拟器。

Claude 的 Compose 前端已经通过 `sdk-codex` 接入真实本机核心，支持原生 Responses、模型目录与思维程度。本轮修复用户实际遇到的 HTTP 400，并通过 OpenAI 真实连接验证、相关 JVM 回归、debug/release 构建、Lint、契约及 APK 检查。下文分别记录真实服务、合成响应、Android 模拟器和未验证范围。

## 测试与构建

| 检查 | 实际结果 | 覆盖 |
| --- | --- | --- |
| `:core-engine:test` | 77 tests，0 failures / errors / skipped | 业务、学习历史、每日分析与撤销；两协议 HTTP、模型目录、思维程度、端点规范化、JSON 输入、旧请求身份、旧行和备份兼容 |
| `:platform-jvm:test` | 4 tests，全部通过 | 真实 SQLite 持久化、恢复及数据版本 |
| `:memory-core:test` | 1 test，通过 | 真实 Rust JNI 调度/拟合/评估边界 |
| `android :sdk-codex:test` | 18 tests，全部通过 | 8 个门面测试；10 个真实 `CoreEngine + SQLite + Rust JNI` 集成测试，新增思维程度映射、版本、离线快照、清除及重开验证 |
| `android :app:testDebugUnitTest` | 6 tests，全部通过 | 模型拉取重复点击、取消、旧响应、离开页面、凭证清理和明确重试 |
| `ModelPickerTest` | 2 tests，通过 | 实际 Compose：60 项合成列表中的搜索和准确选择、无匹配结果、关闭保留手动输入 |
| `AndroidCoreTest` | 5 tests，通过 | Room、JNI 调度与本机拟合、Keystore、Room 1→2、应用平台与 WorkManager |
| `ProcessRestartProbeTest` | 写入、真实 force-stop、读取两段均通过 | 跨新 PID 保留已提交状态，不以重建对象代替进程终止 |
| 一次性真实 API 探针 | 1 test，通过；3 个生成请求、1 个模型目录请求 | Windows 宿主上的正式 `CoreEngine / TaskRunner / OpenAiCompatibleProvider`；具体协议和用量见下文 |
| 后端 Maven 发布 | 五模块发布成功 | `core-contract / memory-core / core-engine / platform-jvm / platform-android` |
| Android 构建 | `assembleDebug / assembleRelease` 通过 | 可安装 debug 与 unsigned release；release 不包含模拟业务类 |
| `lintDebug` | 0 errors，3 warnings | 目标 API、低 API 忽略 localeConfig、依赖 catalog 提醒 |
| 契约校验脚本 | 12 schemas、32 messages、7 个参考包模块通过 | 同源 JSON Schema、26 个响应、4 个命令请求及 2 个模型目录请求夹具、两个正式包 |
| 最终 APK 校验脚本 | debug / release 均通过 | 两个 ABI 的 6 个原生库、ELF/ZIP 16 KiB 对齐、249 个组件许可、源码附件、字体摘要 |

当前常规 JVM 测试集共 106 个。本轮实际执行核心 77 个、SQLite 平台 4 个、前端门面及集成 18 个、页面状态 6 个，共 105 个；未修改的 `memory-core` 1 个沿用此前结果。真实 API 探针单独执行、单独计数，不属于常规离线测试集。标准 XML 位于各模块的 `build/test-results/`；真实调用报告单独保存在 `backend/build/reports/live-api-test4/`。

Android 平台的 5 个 instrumentation 测试及独立进程探针在早期集成执行，模型选择器的 2 个 Compose 仪器测试在 test.3 执行，本轮未重复。本轮操作实际 APK 的思维程度表单，覆盖升级、选择、放弃编辑、保存、真实 force-stop 和原数据比对。Android 首次构建暴露一个门面泛型返回值无法推断的问题；显式指定 `invalid<ApiPreset>` 后，SDK、页面测试、两个 APK 构建及 Lint 相关复测全部通过。

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

## 真实 API 诊断与验证

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

早期 [Claude 前端验证](../frontend/validation-2026-11-03.md)记录的是模拟后端与模拟包格式，保留其历史含义，不用于证明本次真实存储或模型调用。

## 证据边界与限制

- **真实 API 范围有限**：仅 OpenAI `gpt-6-luna` 的宿主连接测试和模型目录；未验证其他供应商、所有思维档位、真实 AI 添加/评分/分析的质量，或手机上的成功 API 调用。用量为服务商响应数字，没有核对账单。
- **没有物理手机或 arm64 运行证据**。arm64 库已构建并检查，但模拟器为 x86_64；minSdk 声明也不是 API 26 实机验证。
- 没有长期学习效果、真实个人参数收益、目标规模性能或功耗证据。资料库的 10,000 词 / 100,000 作答限制是保护边界，不是已经达成的性能指标；当前事务仍读取、解码资料库快照。
- 内容包冲突明确拒绝，需修正来源后重新导入；没有逐项交互合并。批量任务暂不提供分数进度。
- Android 仍受系统后台调度约束；计时心跳丢失后的未确认尾段不补算。文件提供方与本机数据库不能跨系统原子提交，具体导出重试语义见[契约](contract.md)。
- 既有 Compose 仪器测试覆盖模型选择器；本轮思维程度通过 adb/UIAutomator 操作真实 APK 验证。未验证中文输入法输入、TalkBack 朗读、横屏或平板，未进行真机延迟或性能测量。保留前端已有的无障碍与动效实现。
- Debug APK 可安装；release 尚未签名或上架。GitHub 源码推送不等同于应用发行。
