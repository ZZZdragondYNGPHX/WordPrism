# 前端门面与本地核心的对应

日期：2026-11-03 · 契约：`backend/core-contract`，`Contract.VERSION = 1` · 状态：全部业务门面已接入真实核心。

唯一业务类型权威是 `dev.wordprism.contract`，入口见[本地后端契约](../backend/contract.md)。Claude 起草的 `app.wordprism.sdk` 保留为界面门面；`android/sdk-codex` 只负责类型、标签与调用映射，不计算第二套判分、调度、预算或统计。它依赖发布到 `backend/build/repository` 的构件，不编译后端源码副本。

`SdkProvider` 在应用进程中异步打开 `AndroidWordPrism`，包装成 `CodexSdk`，默认 `isMock = false`。Room、Keystore、联网监听和 WorkManager 由平台核心负责。`sdk-mock` 只保留为 debug 开发依赖，release 中没有模拟业务类。构建步骤见[构建与接入](../backend/running.md)。

## 运行约定

- 核心为 `execute(Request)`、`query(Query)`、`observe(): Flow<Long>`。门面收到修订号后重新查询，页面取消订阅不会取消持久任务。
- `Reply / Fault` 映射为 `SdkResult / SdkError`。读取失败通过 `observeErrors()` 暴露；`retryReads()` 在原订阅中重试。只有 `NOT_FOUND` 映射为可选实体不存在，存储失败不冒充空库或已删除。
- UI 请求键、实体版本和 presentation ID 贯穿重试。表单提交保留最初版本，不能为同一个请求键重新查询版本后构造另一条编辑。核心同时约束同展示重复作答。
- `previewPackage / exportPackage` 使用流；平台文件入口通过 SAF / `ContentResolver` 打开。凭证使用 `setCredential(CharArray)` 的专用接口，用后清零，不进入序列化命令或备份。
- 有效学习时间由页面可见性、等待状态和心跳上报，累计及预算判定在核心。完整备份恢复后的任务、凭证和自动调用策略也由核心决定。

## 对应关系

| 前端门面 | 核心入口 | 当前行为 |
| --- | --- | --- |
| `library` | `LibrarySummary`、`GetGroup`、`EditWord`、`RenameGroup`、`EditMemberships` | 词库筛选与分页、义项及关系原子编辑、稳定排序；学习状态、对齐字形和差异区间来自核心 |
| `intake` | `SubmitIntake`、`ResolveSpelling`、`ResolveIntake`、`GetOperation` | 拼写与归组选择持久化；返回新增、复用及归组的结构化效果 |
| 推荐 | `Recommendations`、`PreviewCall`、`RequestRecommendations`、`AcceptRecommendations` | 查看不调用 API；选中后按实际需要决定是否调用，统一取消与撤销 |
| `study` | `StartSession`、`NextActivity`、`SubmitAnswer`、`GetPresentation`、`GetFeedback` | 理解步骤与各题型映射；提交身份、反馈与进度可在新进程恢复 |
| 提示、修订、争议 | `RequestHint`、`BeginAnswerRevision`、`ReviseAnswer`、`MarkDifficulty`、`DisputeActivity` | 不把提示或理解步骤记为独立回忆；开放反馈绑定原答案版本 |
| 学习日与计划 | `GetLearningDay`、`SetTimer`、`FinishSession`、`FinishLearningDay` | 计划目标数、新词数、预算和自动分析状态直接使用核心投影；暂停与结束当天分开 |
| `overview` | `Overview`、`ConfusionEvidence` | 用户与 AI 共享事实；观察、预测、表达评价、低样本和投入序列均由核心定义 |
| `adaptation` | `Settings`、`SetSettings`、`AnalyzeLearning`、`PolicyChanges`、`OperationEffects`、`Capabilities` | 读取实际生效策略及内容变化；手动、自动、锁定和撤销共享业务规则 |
| `presets` | `Protocols`、`Presets`、`SavePreset`、`DeletePreset`、`BindPreset`、`UnbindPreset`、`CallPolicy`、`SetCallPolicy`、`TestConnection`、`GetUsage` | 协议目录与名称、版本化编辑、默认/独立/备用绑定、调用控制、测试状态及真实用量；不读取完整密钥 |
| `presets.listModels` | `listModels(ModelCatalogRequest, CharArray?) → View.Models` | 未保存表单或同地址的已保存凭证；门面只映射模型 ID/所有者，页面管理取消、重试与搜索选择，规则和样例见[模型目录](../backend/api-protocols.md#拉取模型) |
| 预设思维程度 | `ApiPreset.reasoningEffort`、`ProtocolInfo.reasoningEfforts` | 界面门面使用核心枚举名及显示标签；默认值 `null` 与显式 `NONE` 分开，保存、请求映射与兼容行为见[思维程度](../backend/api-protocols.md#思维程度) |
| `dataTransfer` | `GetPackagePreview`、流式预览/导出、`CommitImport`、`RestoreBackup` | 正式 `.wordprism` 容器；预览绑定资料库修订；同目的地导出收据去重，恢复可重试 |
| `operations` | `Operations`、`GetOperation`、`CancelOperation`、`RetryOperation`、`UndoOperation` | 显示真实终态与动作，不根据页面动画猜测任务结果 |
| 答案草稿 | `SaveDraft`、`GetDraft` | 持久版本化草稿；AI 添加的纯界面草稿继续使用前端 DataStore |

## 原 G1–G14 的处理结果

此表保留前端联调问题的编号，当前完整字段仍以核心类型为准。

| 编号 | 处理结果 |
| --- | --- |
| G1 理解步骤 | 已提供 `FAMILIARITY / CONTRAST`，保留用户观察，不伪造 FSRS 复习 |
| G2 添加效果 | 已提供 `IntakeEffect`，区分内容、关系与推荐效果 |
| G3 拼写与归组 | 已提供持久拼写选择、已有组多选、单独保存及新组选择 |
| G4 字形对齐 | `LibrarySummary / GetGroup` 提供核心计算的对齐与差异区间 |
| G5 学习汇总 | 核心提供词/组学习状态；曝光可进入“学习中”，与独立记忆证据分开 |
| G6 预算 | `View.Day.summary` 与 `SessionState` 提供预算状态和提示资格 |
| G7 总览 | 快照提供按日序列、低样本、预测负担、表达用法评价及来源口径 |
| G8 结构化题面 | 卡片包含短文、问题、目标词、改写源句等；提交前不泄露标准答案 |
| G9 调用控制 | 已实现默认/备用预设、用户重试上限、每日请求上限与自动生成控制 |
| G10 解除绑定/测试 | 已实现 `UnbindPreset`，预设视图包含测试状态 |
| G11 进度 | 核心当前未提供可靠的分数进度，门面返回不确定进度；不伪造百分比 |
| G12 未知值 | 进程内客户端与核心共同构建；JSON 桥对未知命令、字段、枚举明确失败。数据包有独立兼容规则 |
| G13 文案 | `Fault.message / Operation.summary` 为可展示的中文；稳定代码用于流程判断，标签在门面映射 |
| G14 编辑版本 | 保留表单原版本与请求内容，重放同一命令；后续并发修改返回冲突 |

早期 B 系列需求保留在[契约需求](contract-requirements.md)，其中模拟实现的行为不是业务权威。例如撤销有历史引用的内容采用归档，不能照搬模拟后端的删除规则。当前异步表达反馈可在对应会话或任务中查询；门面没有另外新增待评价列表。手动分析复用已有任务身份，没有额外的 `reused` 布尔字段。

Chat Completions 与原生 Responses 通过相同预设页面配置。`observeAdapters()` 和已保存预设的 `adapterLabel` 均来自核心 `Query.Protocols`，不在门面写死名称；切换协议保留用户填写的服务地址。详细 ID 和样例见[LLM 协议接入](../backend/api-protocols.md)。

## 验证

`sdk-codex` 已执行 18 个 JVM 测试：8 个门面映射测试，以及 10 个使用真实 `CoreEngine + SQLite + Rust JNI` 的集成测试。后者覆盖 SQLite 重开后的作答幂等、理解步骤、原子编辑/撤销、调用额度、正式备份流、统一总览与保留后续学习的撤销、持久拼写选择、关闭自动分析后的迟到结果，以及 Responses 协议标签、独立绑定、离线任务重开和模型目录选择保存。模型拉取的页面状态另有 6 个单元测试，模型选择器另有 2 个实际 Android Compose 仪器测试；使用合成目录，不代表真实供应商列表。

实际 Android 应用也已在 API 35 x86_64 模拟器完成正式包导入、学习、强停恢复、总览、SAF 导出和完整替换恢复。详细记录与最终 APK 检查见[后端与集成验证](../backend/validation.md)。[Claude 的早期验证](validation-2026-11-03.md)保留为模拟前端阶段记录，不能作为真实 API 或核心验证。
