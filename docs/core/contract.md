# 本地后端契约

契约版本：`1`。

业务类型定义在 [WordPrism.kt](../../core/core-contract/src/main/kotlin/dev/wordprism/contract/WordPrism.kt)、[Models.kt](../../core/core-contract/src/main/kotlin/dev/wordprism/contract/Models.kt)、[Projections.kt](../../core/core-contract/src/main/kotlin/dev/wordprism/contract/Projections.kt)、[Explanations.kt](../../core/core-contract/src/main/kotlin/dev/wordprism/contract/Explanations.kt)、[ApiProtocols.kt](../../core/core-contract/src/main/kotlin/dev/wordprism/contract/ApiProtocols.kt) 和 [ModelCatalog.kt](../../core/core-contract/src/main/kotlin/dev/wordprism/contract/ModelCatalog.kt)。命令、查询、错误、实体、派生事实、讲解、协议和模型目录均从这里定义；[JSON schema](schemas) 与 [样例](fixtures) 由同源序列化描述生成。

Compose 前端通过 `sdk/adapter` 调用已发布的本地 Maven 构件。`sdk/contract` 是界面门面，适配器负责格式、标签和类型映射；存储、判分、归组、调度和统计集中在 `core/`。具体对应见[前端映射](../frontend/sdk-mapping.md)，构建见 [running.md](running.md)。

## 学习模式与托管执行

`UserSettings.learningMode` 默认 `STANDARD`，旧存档无需迁移表结构；`SetLearningMode(mode, expectedVersion)` 使用版本检查切换，`Query.ManagedLearning` 返回当前模式、配置错误、默认预设、待处理与异常任务数及摘要。界面通过 `AdaptationApi.observeManagedLearning / setLearningMode` 访问，详细产品规则仅在[手机学习方案](../design/mobile-learning.md#ai-托管模式)维护。

`ManagedLearning.kt` 在命令提交、凭证保存、恢复和任务完成/失败后收敛待办。沿用持久 `Operation / JobData`、现有 provider、用量与撤销；任务以 epoch、义项版本及学习日构成持久去重键。托管有效分析/生成权限与普通设置分开计算，不覆盖原偏好。切换模式递增自动化 epoch，旧自动任务及迟到结果不再应用。

`Operation.managed` 标记来源；自动讲解/练习作为添加操作的子任务，复合撤销保留之后作答。`SenseIntroduction.support` 只携带托管辨析阶段的当前版本讲解，不向测试卡片透露答案。设备内部凭证引用用于检测已拒绝请求是否可恢复，导出时清除；完整恢复关闭托管。契约样例包括 `managed-learning.json` 与 `set-learning-mode.request.json`。

## 调用、身份与错误

- `execute(Request(key, command))`：持久幂等提交。同键同内容返回原动作及当前可观察结果；同键不同内容返回 `IDEMPOTENCY_CONFLICT`。实体编辑带 `Ref(id, version)`，重试须保留原始输入及版本，不能重新读取新版本拼成另一条命令。
- `query(Query)`：不发起模型请求。`GetLearningDay()` 在需要时创建并持久化当天及计划，其余查询读取已有事实或计算投影。
- `listModels(ModelCatalogRequest, CharArray?)`：用户明确发起的网络元数据读取，返回 `View.Models`；可取消、不创建持久任务。凭证、重试、迟到结果及样例见[拉取模型](api-protocols.md#拉取模型)。
- `observe(): Flow<Long>`：通知已持久化修订，收到后重查所需视图。停止订阅不取消业务任务。
- `Reply` 恰有 `value` 或 `fault`。`Fault.code` 与 `retryable` 用于流程判断，中文 `message` 可直接展示；不得从文案推断状态。存储错误为 `STORAGE`；提交结果不确定时保留同一请求身份重试。
- 所有绝对时间为 UTC 毫秒；学习日保存 IANA 时区与起止时刻，跨日会话的用时按实际日期分段。稳定 ID 不随标题、拼写或内容修改而变。

JSON 使用 `type` 判别、枚举原名和字符串 ID。进程内 Kotlin 客户端与核心共同构建；JSON 桥目前对未知命令、字段和枚举明确失败，不承诺跨版本宽松解码。数据包的未知可选内容保留机制是单独的兼容层。

## 已接入的业务接口

| 范围 | 核心入口与返回事实 |
| --- | --- |
| 词库 | `Library`、`LibrarySummary`、`GetWord`、`GetGroup`；原子 `EditWord`、`RenameGroup`、`EditMemberships`；稳定成员顺序、字形对齐与差异区间、核心计算的学习状态 |
| AI 添加 | `SubmitIntake`、`ResolveSpelling`、`ResolveIntake`；持久拼写选择、候选组多选、单独保存或新组、结构化 `IntakeEffect` |
| 推荐 | `Recommendations`、`PreviewCall`、`RequestRecommendations`、`AcceptRecommendations`；额外词只有被选中后加入，有完整内容时可不调用模型 |
| 讲解与词源 | `ExplainSense`、`GetExplanation`；版本化构词、词源、助记、英英释义、用法、例句与词族，供用户及 AI 共用，见[讲解接口](explanations.md) |
| 学习 | `StartSession`、`NextActivity`、`SubmitAnswer`、`RequestHint`、`BeginAnswerRevision`、`ReviseAnswer`、`MarkDifficulty`、`DisputeActivity`；可恢复的题目展示及反馈 |
| 计划与学习日 | `GetLearningDay`、`SetTimer`、`FinishSession`、`FinishLearningDay`；预算状态、独立目标数、去重的新词数、已完成目标、自动分析身份 |
| 总览与证据 | `Overview`、`Snapshot`、`Evidence`、`ConfusionEvidence`；观察与预测、延迟回忆、新语境、表达评价、低样本、按日投入、负担与用量 |
| 自动调整 | `Capabilities`、`AnalyzeLearning`、`ApplyPolicy`、`FitMemory`、`EvaluateParameters`、`PolicyChanges`、`OperationEffects`；实际策略与内容变更、依据、可撤销性 |
| API | `Protocols`、`Presets`、`SavePreset`、`DeletePreset`、`BindPreset`、`UnbindPreset`、`CallPolicy`、`SetCallPolicy`、`TestConnection`、`GetUsage`、独立 `listModels`；版本、默认/独立/备用绑定、模型目录及真实调用记录 |
| 文件与任务 | 流式 `previewPackage` / `exportPackage`；`CommitImport`、`RestoreBackup`、`GetPackagePreview`；任务查询、取消、重试和撤销 |

具体签名、默认值与完整操作集合以类型源为准。派生状态不由前端自行猜测：例如计划的 `newCount` 是新学习**目标**数量，`DaySummary.plannedNewWords` 才是去重的新词数量；接触基本义后可显示“学习中”，但不会凭空产生 FSRS 复习或独立回忆证据。

有待学内容时，日计划可超过策略的 `plannedMinutes`，但仍受用户当日剩余预算、每日新学/复习上限和启用题型约束；优先复习，再安排新学。`LearningPlan` 分别提供 `deferredReviewCount`（已有记忆排程的到期目标）、`deferredNewWordCount`（待学新词去重数）和 `unavailableTargetCount`（等待可用内容的目标）；这些口径可能重叠，不可相加解释为逾期词数。未启用题型不计入待安排量。`plannerVersion` 用于更新当前日的旧缓存计划，原计划及历史作答保留。

计划用时与七日负荷预测共用后端 `durationEstimates`：从有效 `STUDY` 区间统计每个目标的讲解、作答及反馈用时，排除等待、后台、跳过及尚未结束的当前题。每种能力取最近 60 天内最多 30 个样本的中位数；不足 3 个时结合个人总体速度和冷启动先验，达到 3 个后不再以初始秒数为下限。旧备份没有目标计时归属时，以实际学习区间和提交边界近似重建。`estimateSource` 区分初始、混合与个人估计，`estimateSamples` 给出本次采用的记录数；UI 明示样本不足，估计不等于实际耗时。

当天暂停的会话按原题续接；前一天暂停或冷启动恢复的队列不再替代新一天的计划，开始学习时归档旧会话。已在跨午夜继续作答的活动会话可保留当前上下文，时间和作答仍按发生日记录。Android 的学习日与计划订阅在可观察期间每 30 秒刷新，使跨天更新不依赖额外写入。

`Protocols` 提供 Chat Completions 与原生 Responses 的稳定 ID、名称和说明；预设选择、调用与用量共用上述接口。协议请求映射、错误语义和兼容样例统一维护在[LLM 协议接入](api-protocols.md)。

`ApiPreset.reasoningEffort: ReasoningEffort?` 默认 `null`，目录字段 `ProtocolInfo.reasoningEfforts` 提供可选值；表示适配器可发送的档位，不承诺模型均支持。旧行和备份缺字段时保持默认，任务和用量保留当次配置。见[思维程度及同源样例](api-protocols.md#思维程度)。

## 任务与迟到结果

| 状态 | 含义 |
| --- | --- |
| `QUEUED` / `WAITING_NETWORK` | 已持久化，等待执行或联网 |
| `NEEDS_CONFIGURATION` | 等待可用预设、凭证或配置；显式重试后重新校验 |
| `RUNNING` | 当前尝试已领取执行权；提交仍需核对授权、版本及 lease |
| `NEEDS_INPUT` | 等待拼写或归组选择；选择本身不新增模型调用 |
| `SUCCEEDED` / `FAILED` / `CANCELLED` | 已完成、失败或取消；动作可用性由当前操作事实决定 |
| `SUPERSEDED` | 已被覆盖或没有新证据需要再分析 |
| `RESTORED_PENDING` | 来自完整恢复，未取得当前设备的执行授权 |

取消在业务提交前生效。若事务已提交，返回已完成状态及实际撤销入口。供应商仍可能返回迟到数据或记费：用量继续记入尝试记录，已取消任务不得写入内容或策略。进程中断的在途网络请求标为 `OUTCOME_UNKNOWN`，不会静默重发。

明确执行 `RetryOperation` 会捕获同一预设最新保存的配置，保留各次尝试的历史版本与用量；运行中或已排队的请求不随预设编辑变化。具体超时、重试规则与前后状态样例见[LLM 协议接入](api-protocols.md#失败取消与用量)。

每日分析由学习日及证据序号去重；暂停会话不等于结束当天。关闭自动分析会使在途自动结果失去提交资格，手动动作依据本次授权执行。分析 AI 可使用 `generate_activity` 把补题作为子任务，原有题目不被替换；能力锁、预设和用户指定的总请求上限仍然适用。旧 `automaticRequestsPerDay`、`preGenerationMaxPerDay` 和 `preGenerateLimit` 保留用于读取历史数据，不再限制调用或作为 AI 可调整项目。调整只能使用能力目录内的动作；不能编辑真实作答、凭证或自动接纳额外推荐词。调用规则见[API 方案](../design/api-presets.md#调用由用户控制)。

## 随身助教

`Feature.TUTORING` 复用现有持久任务、预设、实际用量与取消机制。`TutorSession(sessionId, moment, presentationId?, answer?, question?)` 的 `QUESTION / RECAP` 由用户明确提交；学习事件不再自动创建助教请求。`RECAP` 要求会话已暂停或完成，由核心选择最新有效作答，按模式周期、会话及作答版本去重。`WELCOME / COACH` 仅保留历史数据兼容，不能由用户命令新建。`Query.Tutoring(sessionId?)` 返回最近 80 条 `TutorEntry`，含操作状态、用户提问及类型化 `TutorReport`，并通过 `reviewSessionId` 提供最近可总结的结束/暂停会话。样例见 `fixtures/tutor-session.request.json` 与 `fixtures/tutoring.json`。

提交前求助须符合提示策略，并先记录辅助曝光；题目切换或已经作答后到达的旧提示拒绝应用。助教观察必须引用提示中提供且仍有效的答案版本，答案修订或题目争议使旧记忆失效。当前题面使用公开卡片，不传入评分题的标准答案字段。相关记忆、近期作答与表达反馈供后续教学、讲解、出题及分析复用，不视为确定的学习能力。

助教可在当前会话的未完成目标内提出优先顺序；提交时会话已前进则不重排。仅 `COACH` 可请求一份启用题型的针对性练习，作为原任务的子任务持久保存。遵守能力锁和用户请求额度，不修改历史作答、成绩或 FSRS 参数。切回普通模式取消待执行和在途助教任务的提交资格，保留已发生用量。报告随现有任务进入完整备份，恢复后不会自动重新请求。

## 学习、反馈与撤销

作答带 `currentPresentationId` 与题目 `Ref`。同一展示以另一请求键重复提交仍只记一次；内容不同则冲突。`SessionState.feedback` 与 `GetPresentation` 保留已经提交的对应版本，页面退出、重开或强制停止后可恢复反馈，再由 `NextActivity` 明确前进。

`FAMILIARITY` 和 `CONTRAST` 是理解步骤；曝光、提示、跳过和争议单独保存。评分题提交前的 `ActivityCard` 不暴露标准答案；理解步骤可以通过 `introduction` 提供本词拼写、词性、基本义和已有例句。开放表达先保存原文，AI 反馈绑定 `answerId + version`，旧版本反馈不套用到新答案。新反馈包含整体评价、目标词与搭配、语法与结构、自然度与语气、意图表达四个分项，以及 AI 优化句和改动说明；分项引用须是原句的精确片段。`GetOperationFeedback` 按已完成任务返回对应的原始答案版本与反馈，供前进后的任务记录查看，旧反馈没有新增字段时仍可读取。完整字段见[合成反馈样例](fixtures/writing-feedback.json)。

Android 使用 `StartSession(includeIntroductions=true)`：不认识的义项先进入理解步骤，孤立词也适用。引导模式下首次答错的目标进入 `Session.reinforcementTargets`，原队列结束后最多巩固一次；已有例句可遮住目标词生成不同题面，没有其他语境时退回释义回忆。巩固展示持久化为 `answerRevealed`，不重复更新 FSRS，完成记录保存于 `completedReinforcements`。预算仍可暂停队列，重开按原状态继续；不启用引导模式的直接核心调用保持原来的队列行为。

`AnswerResult.correctChoiceIds` 返回全部可接受选项，旧单值 `correctChoiceId` 保留；反馈恢复使用原题版本。错选反馈可带 `Feedback.confusionNote`，引用实际选择义项与目标义项。UI 的“辅助练习”状态与此次客观对错分别展示，不因练习不计调度而隐藏纠错，也不把另一个正确选项标红。

AI 另保留目标词用法分类 `good`、`misused`、`acceptable_unnatural`、`equally_valid`、`uncertain`，用于表达汇总；未知分类或无效分项明确失败。AI 反馈不直接更改记忆评分，与记忆证据分开。

计时由 `SetTimer(STUDY / WAITING / BACKGROUND / PAUSED)` 驱动。Compose 当前每 15 秒报告前台状态；单段超过 90 秒的未确认时长不继续累计，冷启动不补算消失后的尾段。阅读不以点击密度估算。

撤销是补偿操作。撤销新增内容时归档仍被历史引用的实体；撤销策略后使用包含后续作答的真实历史重算调度，不删除后来的答案。若用户后续明确编辑造成版本冲突，则明确失败。`OperationEffects` 返回实际内容、义项、关系或绑定的前后值，和 `PolicyChanges` 一起供前端解释 AI 做了什么。

## 文件与凭证

正式 `.wordprism` 是 ZIP 容器，格式版本 `1`，六个已知模块版本 `2`；详见[参考包](packages/README.md)。内容包追加导入，完整备份替换恢复。预览在暂存数据上核对摘要、版本、依赖与引用，再返回绑定资料库修订的 token；修订变化要求重新预览。内容冲突目前明确拒绝，需在来源修正后重导。

完整恢复在事务中替换资料库，保留包内身份与历史，关闭自动调用、移除设备凭证引用并将未完成任务置为 `RESTORED_PENDING`。未知可选模块/字段保留但不执行；未知必需模块、过新的已知模块或非法策略拒绝恢复。Room 数据库 schema 为 `2`，包含 `1 → 2` 迁移。

Android 文件经 SAF/`ContentResolver` 打开流，不要求磁盘路径。`ExportIdentity(key, destinationSha256)` 为同一目的、词范围和目的 URI 提供导出收据去重；适配器延迟打开文件，先完成 flush/close，再登记成功。同键已完成导出不再截断文件。文件提供方与数据库无法跨系统原子提交：若进程在写完文件、登记收据前终止，显式重试可能重写文件。

凭证经 `setCredential(CharArray)` / `deleteCredential`，不进入可序列化请求或备份；输入数组用后清零。设备使用 Keystore AES-GCM，密文与 Room 数据在 `noBackupFilesDir`，完整恢复后须重新填写凭证。界面提供明文输入时，平台文本对象本身不能保证即时擦除，后端不保留可读密钥。

测试覆盖见[验证状态](../validation.md)。
