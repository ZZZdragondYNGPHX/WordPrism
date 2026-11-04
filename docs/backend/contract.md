# 本地后端契约

契约版本：`1` · 产品：`v0.4` · 实现与联调：2026-11-03。

唯一业务类型权威是 [WordPrism.kt](../../backend/core-contract/src/main/kotlin/dev/wordprism/contract/WordPrism.kt)、[Models.kt](../../backend/core-contract/src/main/kotlin/dev/wordprism/contract/Models.kt)、[Projections.kt](../../backend/core-contract/src/main/kotlin/dev/wordprism/contract/Projections.kt)、[ApiProtocols.kt](../../backend/core-contract/src/main/kotlin/dev/wordprism/contract/ApiProtocols.kt) 和 [ModelCatalog.kt](../../backend/core-contract/src/main/kotlin/dev/wordprism/contract/ModelCatalog.kt)。命令、查询、错误、实体、派生事实、协议和模型目录均从这里定义；[JSON schema](schemas/) 与 [样例](fixtures/) 由同源序列化描述生成。

Claude 的 Compose 前端现通过 `android/sdk-codex` 调用已发布的本地 Maven 构件。`android/sdk-contract` 是界面门面，适配器负责格式、标签和类型映射；存储、判分、归组、调度和统计集中在 `backend/`。具体对应见[前端映射](../frontend/codex-contract-mapping.md)，构建见 [running.md](running.md)。

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
| 学习 | `StartSession`、`NextActivity`、`SubmitAnswer`、`RequestHint`、`BeginAnswerRevision`、`ReviseAnswer`、`MarkDifficulty`、`DisputeActivity`；可恢复的题目展示及反馈 |
| 计划与学习日 | `GetLearningDay`、`SetTimer`、`FinishSession`、`FinishLearningDay`；预算状态、独立目标数、去重的新词数、已完成目标、自动分析身份 |
| 总览与证据 | `Overview`、`Snapshot`、`Evidence`、`ConfusionEvidence`；观察与预测、延迟回忆、新语境、表达评价、低样本、按日投入、负担与用量 |
| 自动调整 | `Capabilities`、`AnalyzeLearning`、`ApplyPolicy`、`FitMemory`、`EvaluateParameters`、`PolicyChanges`、`OperationEffects`；实际策略与内容变更、依据、可撤销性 |
| API | `Protocols`、`Presets`、`SavePreset`、`DeletePreset`、`BindPreset`、`UnbindPreset`、`CallPolicy`、`SetCallPolicy`、`TestConnection`、`GetUsage`、独立 `listModels`；版本、默认/独立/备用绑定、模型目录及真实调用记录 |
| 文件与任务 | 流式 `previewPackage` / `exportPackage`；`CommitImport`、`RestoreBackup`、`GetPackagePreview`；任务查询、取消、重试和撤销 |

具体签名、默认值与完整操作集合以类型源为准。派生状态不由前端自行猜测：例如计划的 `newCount` 是新学习**目标**数量，`DaySummary.plannedNewWords` 才是去重的新词数量；接触基本义后可显示“学习中”，但不会凭空产生 FSRS 复习或独立回忆证据。

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

每日分析由学习日及证据序号去重；暂停会话不等于结束当天。关闭自动分析会使在途自动结果失去提交资格，手动动作依据本次授权执行。调整只能使用能力目录内的动作，并遵守用户锁定、预算与调用设置；不能编辑真实作答、凭证或自动接纳额外推荐词。

## 学习、反馈与撤销

作答带 `currentPresentationId` 与题目 `Ref`。同一展示以另一请求键重复提交仍只记一次；内容不同则冲突。`SessionState.feedback` 与 `GetPresentation` 保留已经提交的对应版本，页面退出、重开或强制停止后可恢复反馈，再由 `NextActivity` 明确前进。

`FAMILIARITY` 和 `CONTRAST` 是理解步骤；曝光、提示、跳过和争议单独保存。提交前 `ActivityCard` 不暴露标准答案。开放表达先保存原文，AI 反馈绑定 `answerId + version`，旧版本反馈不套用到新答案。AI 用法分类为 `good`、`misused`、`acceptable_unnatural`、`equally_valid`、`uncertain`；未知结果明确失败，表达汇总由核心计算，与记忆证据分开。

计时由 `SetTimer(STUDY / WAITING / BACKGROUND / PAUSED)` 驱动。Compose 当前每 15 秒报告前台状态；单段超过 90 秒的未确认时长不继续累计，冷启动不补算消失后的尾段。阅读不以点击密度估算。

撤销是补偿操作。撤销新增内容时归档仍被历史引用的实体；撤销策略后使用包含后续作答的真实历史重算调度，不删除后来的答案。若用户后续明确编辑造成版本冲突，则明确失败。`OperationEffects` 返回实际内容、义项、关系或绑定的前后值，和 `PolicyChanges` 一起供前端解释 AI 做了什么。

## 文件与凭证

正式 `.wordprism` 是 ZIP 容器，格式版本 `1`，六个已知模块版本 `2`；详见[参考包](packages/README.md)。内容包追加导入，完整备份替换恢复。预览在暂存数据上核对摘要、版本、依赖与引用，再返回绑定资料库修订的 token；修订变化要求重新预览。内容冲突目前明确拒绝，需在来源修正后重导。

完整恢复在事务中替换资料库，保留包内身份与历史，关闭自动调用、移除设备凭证引用并将未完成任务置为 `RESTORED_PENDING`。未知可选模块/字段保留但不执行；未知必需模块、过新的已知模块或非法策略拒绝恢复。Room 数据库 schema 为 `2`，包含 `1 → 2` 迁移。

Android 文件经 SAF/`ContentResolver` 打开流，不要求磁盘路径。`ExportIdentity(key, destinationSha256)` 为同一目的、词范围和目的 URI 提供导出收据去重；适配器延迟打开文件，先完成 flush/close，再登记成功。同键已完成导出不再截断文件。文件提供方与数据库无法跨系统原子提交：若进程在写完文件、登记收据前终止，显式重试可能重写文件。

凭证经 `setCredential(CharArray)` / `deleteCredential`，不进入可序列化请求或备份；输入数组用后清零。设备使用 Keystore AES-GCM，密文与 Room 数据在 `noBackupFilesDir`，完整恢复后须重新填写凭证。界面提供明文输入时，平台文本对象本身不能保证即时擦除，后端不保留可读密钥。

实际执行过的验证及证据边界见 [validation.md](validation.md)。契约夹具是合成状态，不能作为真实模型、真机或长期学习效果证据。
