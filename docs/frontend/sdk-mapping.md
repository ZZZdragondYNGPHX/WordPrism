# 前端门面与本地核心的对应

`app.wordprism.sdk` 是界面门面，`android/sdk-codex` 负责类型、标签与调用映射。判分、调度、预算和统计由 `dev.wordprism.contract` 定义的核心提供，适配器依赖 `backend/build/repository` 中的构件。接口入口见[核心契约](../backend/contract.md)。

`SdkProvider` 在应用进程中异步打开 `AndroidWordPrism`，包装成 `CodexSdk`，默认 `isMock = false`。Room、Keystore、联网监听和 WorkManager 由平台核心负责。`sdk-mock` 只保留为 debug 开发依赖，release 中没有模拟业务类。构建步骤见[构建与接入](../backend/running.md)。

## 运行约定

- 核心为 `execute(Request)`、`query(Query)`、`observe(): Flow<Long>`。门面收到修订号后重新查询，页面取消订阅不会取消持久任务。
- `Reply / Fault` 映射为 `SdkResult / SdkError`。读取失败通过 `observeErrors()` 暴露；`retryReads()` 在原订阅中重试。只有 `NOT_FOUND` 映射为可选实体不存在，存储失败单独返回错误。
- UI 请求键、实体版本和 presentation ID 贯穿重试。表单提交保留最初版本，重试使用原始命令。核心同时约束同展示重复作答。
- `previewPackage / exportPackage` 使用流；平台文件入口通过 SAF / `ContentResolver` 打开。凭证使用 `setCredential(CharArray)` 的专用接口，用后清零，不进入序列化命令或备份。
- 有效学习时间由页面可见性、等待状态和心跳上报，累计及预算判定在核心。完整备份恢复后的任务、凭证和自动调用策略也由核心决定。

## 对应关系

| 前端门面 | 核心入口 | 当前行为 |
| --- | --- | --- |
| `library` | `LibrarySummary`、`GetGroup`、`EditWord`、`RenameGroup`、`EditMemberships` | 词库筛选与分页、义项及关系原子编辑、稳定排序；学习状态、对齐字形和差异区间来自核心 |
| `intake` | `SubmitIntake`、`ResolveSpelling`、`ResolveIntake`、`GetOperation` | 拼写与归组选择持久化；返回新增、复用及归组的结构化效果 |
| 推荐 | `Recommendations`、`PreviewCall`、`RequestRecommendations`、`AcceptRecommendations` | 查看不调用 API；选中后按实际需要决定是否调用，统一取消与撤销 |
| 单词讲解 | `GetExplanation`、`ExplainSense`、`PreviewCall(EXPLANATION)` | 分段映射构词、词源、助记、英英释义、例句和用法；当前/过期状态、候选及已接纳词族、来源、任务与撤销均来自核心，见[接口](../backend/explanations.md) |
| `study` | `StartSession`、`NextActivity`、`SubmitAnswer`、`GetPresentation`、`GetFeedback` | 理解步骤与各题型映射；提交身份、反馈与进度可在新进程恢复 |
| 提示、修订、争议 | `RequestHint`、`BeginAnswerRevision`、`ReviseAnswer`、`MarkDifficulty`、`DisputeActivity` | 不把提示或理解步骤记为独立回忆；开放反馈绑定原答案版本 |
| 学习日与计划 | `GetLearningDay`、`SetTimer`、`FinishSession`、`FinishLearningDay` | 计划目标数、新词数、预算和自动分析状态直接使用核心投影；暂停与结束当天分开 |
| `overview` | `Overview`、`ConfusionEvidence` | 用户与 AI 共享事实；观察、预测、表达评价、低样本和投入序列均由核心定义 |
| `adaptation` | `Settings`、`SetSettings`、`AnalyzeLearning`、`PolicyChanges`、`OperationEffects`、`Capabilities` | 读取实际生效策略及内容变化；手动、自动、锁定和撤销共享业务规则 |
| `presets` | `Protocols`、`Presets`、`SavePreset`、`DeletePreset`、`BindPreset`、`UnbindPreset`、`CallPolicy`、`SetCallPolicy`、`TestConnection`、`GetUsage` | 协议目录与名称、版本化编辑、默认/独立/备用绑定、调用控制、测试状态及真实用量；不读取完整密钥 |
| `presets.listModels` | `listModels(ModelCatalogRequest, CharArray?) → View.Models` | 未保存表单或同地址的已保存凭证；门面只映射模型 ID/所有者，页面管理取消、重试与搜索选择，规则和样例见[模型目录](../backend/api-protocols.md#拉取模型) |
| 预设思维程度 | `ApiPreset.reasoningEffort`、`ProtocolInfo.reasoningEfforts` | 界面门面使用核心枚举名及显示标签；默认值 `null` 与显式 `NONE` 分开，保存、请求映射与兼容行为见[思维程度](../backend/api-protocols.md#思维程度) |
| `dataTransfer` | `GetPackagePreview`、流式预览/导出、`CommitImport`、`RestoreBackup` | 正式 `.wordprism` 容器；预览绑定资料库修订；同目的地导出收据去重，恢复可重试 |
| `operations` | `Operations`、`GetOperation`、`CancelOperation`、`RetryOperation`、`UndoOperation` | 显示任务状态与当前可用动作 |
| 答案草稿 | `SaveDraft`、`GetDraft` | 持久版本化草稿；AI 添加的纯界面草稿继续使用前端 DataStore |

`operations.retry` 捕获原预设当前保存的版本；页面应展示返回的 `presetVersion`。旧尝试的来源和用量保留，失败后修改配置、保存、重试的同源样例见[API 接入](../backend/api-protocols.md#失败取消与用量)。

## 双模式映射

`AdaptationApi.observeManagedLearning()` 对应 `Query.ManagedLearning`，`setLearningMode()` 对应带版本检查的 `SetLearningMode`。托管首页复用同一个 `TodayViewModel`、日计划和学习入口；模式、任务计数和配置错误由核心返回。`LearnWord.support / Contrast.support` 映射当前有效的讲解分段，进入作答后不显示。完整行为见[托管契约](../backend/contract.md#学习模式与托管执行)，最新执行结果见[验证记录](../validation.md)。

`StudyApi.observeTutorReviewSession()` 提供最近可总结会话，`reviewWithTutor()` 显式提交 `TutorSession(RECAP)`；结束页与首页仅在点击按钮时调用。每日分析显示实际任务错误、重试及对应预设编辑入口。

`StudyApi.observeTutor(sessionId?)` 映射 `Query.Tutoring`；`askTutor()` 使用当前展示及已提交反馈的真实答案版本调用 `TutorSession(QUESTION)`。`StudyViewModel` 保留请求身份并只在持久提交成功后清空相同草稿；模式关闭、取消、重试和配置状态沿用业务操作。学习页显示当前题目或答案版本的助教内容，未作答的评分题不展示开场讲解。托管首页使用真实日计划和最近小结。

`TutorSheet` 使用原生 Material 底部面板，区分用户气泡、助教正文和理解追问；输入采用 `TextFieldValue` 保留 IME 状态，明确发送时提交可见文本，等待期间仍可编辑。48 dp 发送/关闭触点、窄屏提示横向滚动及键盘下紧凑标题复用现有主题。界面参考见[资料 R61–R63](../research/sources.md#r61)。

验证范围见[验证状态](../validation.md)。
