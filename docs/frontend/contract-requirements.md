# 前端对本地 SDK 契约的需求（起草记录）

日期：2026-11-03 · 提出方：Claude（前端） · 接收方：Codex（本地核心）

开始实现时还没有任何后端契约，因此前端按 [前后端边界](../plan/frontend-backend-boundary.md) 的逻辑操作起草了 `android/sdk-contract`（包名 `app.wordprism.sdk`，版本 `0.1.0-draft`），并用 `android/sdk-mock` 实现它。这份草案**不是**权威契约：Codex 可以直接接手，也可以提供自己的类型并实现一个 `WordPrismSdk` 适配器。下面列出前端依赖的约定和尚未解决的问题。

> 当前实现：全部门面已经通过 `android/sdk-codex` 接入真实核心。业务类型唯一权威为 [backend/core-contract](../backend/contract.md)，当前对应、已解决项及限制见 [Codex 契约对应](codex-contract-mapping.md)。下文保留前端起草阶段的需求与模拟约定，不能把其中“尚未解决”或模拟删除行为当作当前事实；真实撤销必须保留被后续历史引用的实体。

## A. 前端已经依赖的约定

1. **入口**：`WordPrismSdk` 暴露 `library / intake / study / overview / adaptation / presets / dataTransfer / operations` 与 `observeConnectivity()`；`isMock`、`implementationLabel` 用于设置页显示当前后端。
2. **读写分离**：读取都是 `Flow`（可重复订阅、立即给出当前值）；写入是挂起函数，返回 `SdkResult.Ok / Err`，不抛业务异常。
3. **幂等**：所有用户发起的写操作都带 `ClientRequestId`。前端在拿到确认前把同一个 ID 存在 `SavedStateHandle`，重复点击、超时重试、进程重启都会复用。需要后端保证：同一 ID 返回同一结果或同一任务。
4. **开放枚举**：状态、错误码、题型、关系类型等都用字符串值类型。前端对未知值有兜底显示；未知题型 `ActivityView.Unknown` 提供跳过。
5. **错误**：`SdkError(code, message, retryable, fieldErrors)`。`message` 直接显示给用户（中文）；`fieldErrors` 按请求字段名定位到输入框。
6. **任务**：`Operation` 带真实状态（`queued / waiting_network / running / needs_input / succeeded / failed / cancelled / undone / merged`）、可执行动作（取消 / 重试 / 撤销 / 补充选择）、只有确知时才给的进度、所用预设与版本、已知用量。前端只按这些字段显示，不从动画推断进度。
7. **取消与迟到结果**：取消返回真实终态；如果保存已先完成，返回 `NotCancellable`，前端显示已完成结果及撤销入口。
8. **撤销**：统一走 `operations.undo(operationId)`，返回摘要和“保留了什么”（例如之后的作答）。AI 调整走 `adaptation.undoChangeSet`。
9. **调用前说明**：`ApiRequirement`（是否需要 API、功能、预设名、就绪状态与说明）。前端在每个会调用 API 的按钮前显示。
10. **棱镜条数据**：`GroupMember.formHighlights`（不同字母区间）与 `alignedForm`（对齐后的字形）由后端给出，前端只负责绘制。
11. **预算与日结束**：`LearningDay.budgetState`、`SessionState.budgetPromptPending`、`DailyAnalysisStatus` 都由后端判定；前端只呈现并回传用户选择（`answerBudgetPrompt`、`finishLearningDay`）。
12. **凭证**：`PresetDraft.credential` 只在写入时出现（`null` 保持、空串删除）；读取只返回 `CredentialState(present, maskedHint)`。

## B. 联调前需要 Codex 决定或补充的内容

| # | 缺口 | 为什么前端需要 | 建议 |
| --- | --- | --- | --- |
| B1 | **有效学习时间的信号** | 预算按“有效学习时间”累计，切后台、等待生成要单列；目前没有接口让后端知道题目何时可见、页面何时进入后台 | 增加 `study.reportPresence(session, activity, visible)` 或等价事件；前端可以在生命周期 `ON_RESUME / ON_PAUSE` 时调用。模拟后端暂用“显示到提交的间隔，封顶 90 秒” |
| B2 | 作答反馈需带回答案原文 | 进程重启后，开放题界面要显示已提交的句子（真机验证时发现） | 已在草案中加入 `Feedback.answerText`；正式契约请保留 |
| B3 | 前进到下一题后的异步反馈 | 造句反馈完成时用户可能已在下一题 | 提供 `study.observePendingReviews()` 或在会话结束页列出；目前前端引导到“任务记录” |
| B4 | 手动分析“复用上次结果”的明确标识 | 现在前端只能比对返回的任务 ID 是否等于上次 | `analyzeNow` 返回 `{ operationId, reused: Boolean }` |
| B5 | 加入推荐词的调用说明 | 加入推荐词可能需要 `explanation` 功能的预设，与“寻找推荐”不同 | 增加 `library.acceptRequirement(groupId, ids): ApiRequirement` |
| B6 | 导入预览的生命周期 | 模拟后端的预览只在内存，进程重启后提交会失败（会提示重新选择文件） | 说明预览是否持久、多久过期；或改为提交时重新校验 |
| B7 | 词库分页 | 批量导入后可能上千个词 | `LibraryQuery` 增加分页游标或改为 `PagingSource` |
| B8 | 字形对齐算法 | 模拟实现只做右侧补齐，`linear / liner` 这类插入差异对不齐 | 后端用编辑距离对齐并给出 `alignedForm`（可含占位） |
| B9 | 文案归属 | 当前后端直接返回中文标题、说明、错误文案；界面自有的状态标签由前端映射 | 确认这一分工；如需多语言，后端改为返回代码与参数 |
| B10 | 每日分析 `state` 取值 | 目前是字符串约定（`will_run_after_day_end`、`waiting_network` 等） | 正式列出取值，或改为值类型常量 |
| B11 | 调整记录的撤销顺序 | 模拟实现只允许撤销最新一次有效调整 | 明确是否允许撤销更早的调整，以及冲突时返回什么 |
| B12 | 推荐词撤销后的候选 | 撤销加入后，候选是否回到推荐列表 | 明确规则 |
| B13 | 联网状态来源 | 模拟后端由应用把系统网络状态喂给它；真实核心应自己观察 | 正式实现不需要 `setDeviceOnline` |
| B14 | 文件访问 | 前端只传 SAF 的 `content://` URI 与显示名、大小 | 后端用 ContentResolver 读写，不假设文件路径 |
| B15 | 词组标题随成员变化 | `linen` 加入后标题仍是 `linear / liner` | 后端决定是否自动更新标题、用户改过名后是否保留 |

## C. 联调时请一起覆盖的场景

模拟后端的 JVM 测试（`android/sdk-mock/src/test`）已固定以下行为，真实核心应满足同样的契约：

- 同一请求 ID 重复提交得到同一任务；同一组词再次输入复用已有词组。
- 生成完成前取消，不写入任何内容。
- 撤销 AI 添加会删除新建词组；第二次撤销被拒绝。
- 歧义输入进入 `needs_input`，选择后完成。
- 同一题重复提交只计一次；学习日结束两次只产生一次自动分析。
- 自动分析离线排队时关闭开关：任务取消，迟到结果不改设置。
- 状态在“重启”（重新创建 SDK）后保留，可继续未完成的会话。
