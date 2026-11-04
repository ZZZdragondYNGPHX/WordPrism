# 后端与 Android 集成验证

记录日期：2026-11-03 · 产品：`v0.4` · 执行环境：Windows，独立 API 35 x86_64 Android 模拟器。

Claude 的 Compose 前端已经通过 `sdk-codex` 接入真实本机核心，并增加原生 Responses 协议。当前相关 JVM 测试、debug/release 构建、Lint、契约样例及 APK 检查均通过。下文分别记录真实代码执行、模型替身、Android 模拟器和未验证范围；不将模拟模型响应作为真实供应商或长期学习效果证据。

## 测试与构建

| 检查 | 实际结果 | 覆盖 |
| --- | --- | --- |
| `:core-engine:test` | 56 tests，0 failures / errors / skipped | 业务一致性、AI 任务、每日分析、学习历史、策略与撤销、包格式、Chat Completions / Responses HTTP 适配及跨协议任务 |
| `:platform-jvm:test` | 4 tests，全部通过 | 真实 SQLite 持久化、恢复及数据版本 |
| `:memory-core:test` | 1 test，通过 | 真实 Rust JNI 调度/拟合/评估边界 |
| `android :sdk-codex:test` | 15 tests，全部通过 | 6 个门面测试；9 个真实 `CoreEngine + SQLite + Rust JNI` 集成测试，包含 Responses 标签、功能绑定、离线任务和重开 |
| `AndroidCoreTest` | 5 tests，通过 | Room、JNI 调度与本机拟合、Keystore、Room 1→2、应用平台与 WorkManager |
| `ProcessRestartProbeTest` | 写入、真实 force-stop、读取两段均通过 | 跨新 PID 保留已提交状态，不以重建对象代替进程终止 |
| 后端 Maven 发布 | 五模块发布成功 | `core-contract / memory-core / core-engine / platform-jvm / platform-android` |
| Android 构建 | `assembleDebug / assembleRelease` 通过 | 可安装 debug 与 unsigned release；release 不包含模拟业务类 |
| `lintDebug` | 0 errors，3 warnings | 目标 API、低 API 忽略 localeConfig、依赖 catalog 提醒 |
| 契约校验脚本 | 11 schemas、24 messages、7 个参考包模块通过 | 同源 JSON Schema、22 个响应与 2 个请求夹具、两个正式包 |
| 最终 APK 校验脚本 | debug / release 均通过 | 两个 ABI 的 6 个原生库、ELF/ZIP 16 KiB 对齐、249 个组件许可、源码附件、字体摘要 |

当前 JVM 测试集共 76 个，结果来自各模块 `build/test-results/test/TEST-*.xml`。本轮 Responses 扩展执行了核心 56 个、SQLite 平台 4 个、前端门面及集成 15 个，共 75 个；未修改的 `memory-core` 1 个沿用此前通过的结果。Android 平台的 5 个 instrumentation 测试及独立进程探针在前一轮集成执行，本轮未重复。本轮另执行了应用 debug/release 构建、Lint、契约和 APK 检查，并在实际模拟器安装新版后验证协议配置、冷启动与数据保留。

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

早期 [Claude 前端验证](../frontend/validation-2026-11-03.md)记录的是模拟后端与模拟包格式，保留其历史含义，不用于证明本次真实存储或模型调用。

## 证据边界与限制

- **没有真实 LLM API 调用证据**，本次未提供或使用真实凭证；供应商的兼容差异、模型质量、用量与账单仍需用户配置后验证。
- **没有物理手机或 arm64 运行证据**。arm64 库已构建并检查，但模拟器为 x86_64；minSdk 声明也不是 API 26 实机验证。
- 没有长期学习效果、真实个人参数收益、目标规模性能或功耗证据。资料库的 10,000 词 / 100,000 作答限制是保护边界，不是已经达成的性能指标；当前事务仍读取、解码资料库快照。
- 内容包冲突明确拒绝，需修正来源后重新导入；没有逐项交互合并。批量任务暂不提供分数进度。
- Android 仍受系统后台调度约束；计时心跳丢失后的未确认尾段不补算。文件提供方与本机数据库不能跨系统原子提交，具体导出重试语义见[契约](contract.md)。
- 未执行 Compose 自动化仪器测试、中文输入法输入、TalkBack 朗读、横屏或平板验证；保留前端已有的无障碍与动效实现。
- Debug APK 可安装；release 尚未签名或上架。GitHub 源码推送不等同于应用发行。
