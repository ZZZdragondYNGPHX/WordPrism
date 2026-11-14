# 类型化讲解、词源与助记

实现入口为核心 `Command.ExplainSense(Ref)` / `Query.GetExplanation(senseId)`，继续使用 `Feature.EXPLANATION`。唯一业务类型见 [`Explanations.kt`](../../backend/core-contract/src/main/kotlin/dev/wordprism/contract/Explanations.kt)。这里维护接口行为，产品规则见[词源与助记](../design/ai-intake.md#词源与助记)。

`Sense.explanation` 保存 `SenseExplanation`：内容版本、依据的义项版本、`ExplanationContent`、推荐 ID 和 `Provenance`。内容包含 `explanation`、可选 `englishDefinition`、`wordParts`、`etymology`、`mnemonic`、`usageNotes`、`examples`。词源与助记分开，生成内容不宣称词典已核验。

查询返回 `View.Explanation`，同时提供当前义项 `Ref`、`stale`、仍可选择的 `recommendations`、最近一次讲解 `operation`，以及包括已接纳词在内的完整 `family`。已有词的 `libraryWordId` 是查询投影，可打开词条；接纳后不会失去关联词的阅读入口。查看不调用 API；生成调用使用已保存预设和现有任务/用量/重试规则。

## 与 AI 学习流程共用的事实

- 讲解请求包含当前义项、同组相关义项、已有讲解，以及最近最多 8 次实际作答及其对应题目版本。没有记录时不声称掌握程度。
- `GenerateActivity` 的 `studyExplanation` 使用当前有效的讲解，在新语境中练习词义、搭配和区别。旧版内容不参与出题，助记故事不能成为词源事实或判分依据。
- `AnalyzeLearning` 的 `explanations` 摘要包含可用性、过期状态、来源、内容版本和任务状态。AI 可通过同一 `explain_sense` 能力为薄弱义项补充内容；读取或生成讲解不算掌握。
- 自动讲解是分析任务的可追溯子任务，可在 AI 调整中通过 `explain_sense` 能力锁控制，并遵循[调用规则](../design/api-presets.md#调用由用户控制)。取消或撤销父任务后，迟到结果不能安装讲解或词族候选；供应商已报告的用量仍保留。旧数据中的 `automaticGeneration` 保留为自动讲解的已有偏好，解锁该能力时启用。

生成目标是约 500–800 个汉字和简短英文组成的学习卡片，包含两个短例句及最多三个优先候选。校验上限留出兼容空间：正文总计不超过 24,000 字符，构词最多 8 项，候选最多 6 项。字段可为空，缺乏可靠词源时允许省略，不以字形相似杜撰词根。服务商实际生成仍受用户所设 token 和超时上限约束，不会隐式降低思维程度、增加额度或拆成多个付费请求。

模型输出中的可选空备注/列表按类型声明的默认值归一化，例如 `uncertainty: null` 对应空备注。归一化由序列化描述驱动，只处理有默认值的非必填字段；必填正文、未知字段、非法枚举及数据范围仍严格校验。本地命令、数据库和包格式不放宽解析规则。截断响应不保存半成品，明确提示调整额度或思维程度后由用户重试。

## 写入、并发与撤销

讲解成功后更新义项及词条版本，只补充 `Sense.explanation`；原释义、可接受答案和用户已有例句保留。来源记录操作、预设 ID/版本、模型和生成时间。用户随后修改义项，查询会标记旧讲解过期，直到重新生成；旧内容仍可离线阅读。

同一请求键返回原收据；同一义项版本、同一发起者的在途请求即使换键也合并。最近任务按创建时间、更新时间选择，不依赖数据库行或 Map 的加载顺序，进程重开后仍显示最新任务及其重试入口。事务提交前再次检查义项版本与任务状态，修改义项、取消或关闭授权后的结果不能覆盖新内容。已完成的重复读取或任务唤醒不再次调用 API。

词族候选不自动入库，也不强制加入易混组。`AcceptRecommendations` 使用同一调用预览：内容齐全或可复用已有义项时本地加入；缺少内容时由用户明确点击后调用 API。已有词只有一个义项时优先复用原内容，不因 AI 改写释义而增加重复义项；多义项词不任意选取其中一个。按拼写、目标组和所属讲解义项复用已有候选 ID；重新生成若引用同一候选，保留它原有内容和来源，不伪装成新候选。已撤销、过期或不再引用的候选不能迟到加入。

接纳已有词也可撤销：撤销接纳状态，不删除原词和真实历史。撤销讲解沿用核心补偿记录；若重生成前已有讲解，则恢复旧内容并使其版本对应当前义项。撤销自动分析时只撤销实际属于它的子任务。之后用户自行接纳的词、作答与调度保留；后续明确编辑冲突时返回 `VERSION_CONFLICT`。

## Android 门面

`LibraryApi.observeExplanation / explanationRequirement / requestExplanation` 对应查询、调用预览和命令。无组的 `acceptRequirement / acceptRecommendations` 重载共用已有接纳能力。`ExplanationDetail` 仅提供显示分段、来源、任务、候选和完整词族，不解析供应商输出或重写规则。

沿用现有 Compose 字体、间距、按钮和 `OperationLine`。词组内的“词源与助记”、待整理词的“详解”进入单词页；支持多义项、文本选择复制、长文滚动、生成/更新、取消、重试、撤销及已加入词跳转。`SavedStateHandle` 保存义项选择、候选选择、请求键和接纳任务。操作收据持久化在核心，离开页面不会取消任务。

生成练习的 `prompt / passage / question / sourceSentence` 会完整映射进已有题面槽位，避免真实模型把中文释义或填空上下文放在独立字段时，回忆/拼写页只显示一行指令。该映射不向作答前页面提供标准答案或判分说明。

## 兼容与专属格式

新字段是可选扩展，默认值不改变旧义项的序列化，`ExplainSense` 的旧命令身份保持。旧 `explanation:<senseId>` 草稿由查询映射为历史讲解，保留原文；完整备份仍保留旧撤销记录。

完整备份保留讲解、候选、用量、任务、撤销关系及真实学习历史。内容包携带所选词的讲解和它引用的词族内容，清除个人接纳记录和词库状态，不夹带 API 配置、凭证或个人作答。导入校验所有讲解版本和候选引用；完整恢复后的未完成任务仍须重新授权。

同源示例包括 [空讲解](fixtures/explanation-empty.json)、[保存成功](fixtures/explanation-saved.json)、[过期](fixtures/explanation-stale.json)、[取消](fixtures/explanation-cancelled.json)、[生成命令](fixtures/explain-sense.request.json)、[接纳词族](fixtures/accept-word-family.request.json)。[hospital 参考包](packages/example-explanation.wordprism)提供人工编写的讲解内容。测试覆盖见[验证状态](../validation.md)。
