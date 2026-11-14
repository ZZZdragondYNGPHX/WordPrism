package app.wordprism.codex

import app.wordprism.sdk.*
import dev.wordprism.contract.Command
import dev.wordprism.contract.Query
import dev.wordprism.contract.View
import dev.wordprism.contract.Contract
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import dev.wordprism.contract.Id as CId
import dev.wordprism.contract.Feature as CFeature
import dev.wordprism.contract.Ability as CAbility
import dev.wordprism.contract.Operation as COperation
import dev.wordprism.contract.OperationStatus as CStatus
import dev.wordprism.contract.Metric as CMetric

private fun CMetric.metric(id: String, label: String, note: String? = null) = RatioMetric(id, label, Ratio(successes, opportunities, lowSample),
    if (observed) "observed" else "predicted", if (observed) "实际作答" else "模型预测", note)
class CodexOverview(private val b: CodexBridge) : OverviewApi {
    override fun observeOverview(window: OverviewWindow): Flow<LearningOverview> = b.live {
        val from = if (window.days == 0) 0 else System.currentTimeMillis() - window.days * 86_400_000L
        val s = (b.view(Query.Overview(from)) as View.Snapshot).snapshot; val d = s.details
        val labels = mapOf(CAbility.RECOGNITION to "辨析识别", CAbility.RECALL to "主动回忆", CAbility.SPELLING to "拼写", CAbility.READING to "阅读理解", CAbility.EXPRESSION to "表达（客观题）")
        val abilities = s.byAbility.filterKeys { it != CAbility.EXPRESSION }.map { (ability, metric) -> metric.metric(ability.name.lowercase(), labels.getValue(ability), "未使用提示且题目无争议的作答") }.toMutableList()
        abilities += RatioMetric("expression_ai", "表达用法", Ratio(d.expressionUsage.successes, d.expressionUsage.opportunities, d.expressionUsage.lowSample),
            "ai_review", "AI 评价", "目标词用法评价，不直接等同记忆评分")
        LearningOverview(s.id.value, s.schemaVersion, "v${s.metricVersion}", s.asOfMs, window, s.zoneId,
            "覆盖 ${s.answers} 次作答、${s.distinctTargets} 个目标；用时来源：" + if (s.timeSource == "recorded_intervals") "实际计时间隔" else "包含旧版会话累计用时",
            LibraryStats(s.words, s.groups, d.addedWords, d.acceptedRecommendations, d.undoneOperations, d.ungroupedWords),
            EffortStats(s.activeDays, s.answers, s.distinctTargets, d.newTargets, (s.studyMs / 60_000).toInt(), d.budgetMinutes,
                d.daily.map { DayEffort(LocalDay(it.date), (it.studyMs / 60_000).toInt(), (it.plannedMs / 60_000).toInt()) }, (s.waitingMs / 60_000).toInt(), d.interruptedSessions),
            listOf(s.delayedRecall.metric("delayed_recall", "延迟回忆", "距上次有效复习至少 24 小时的独立回忆与拼写"),
                s.newContext.metric("new_context", "新语境表现", "此前未做过的 AI 语境；不计看过答案或使用提示的作答")), abilities,
            d.confusions.map { ConfusionPair(it.expected, it.selected, it.count, it.opportunities, it.trend, "趋势证据不足", it.lowSample, it.groupId?.value?.let(::GroupId)) },
            LoadStats(s.dueTargets, d.overdueTargets, d.forecast.map { DayForecast(LocalDay(it.date), it.targets, (it.estimatedMs / 60_000).toInt()) },
                "按当前记忆安排与各题型近期有效用时估计；样本不足时采用初始估计，不含未来新增词或尚未发生的作答"),
            listOf(CountMetric("争议作答", s.disputedAnswers), CountMetric("使用提示或看过答案", s.hintedAnswers), CountMetric("跳过", s.skippedAnswers), CountMetric("收到的 AI 反馈", s.aiFeedbackCount)),
            d.usage.map { UsageRow(it.feature.toSdk(), featureLabel(it.feature), it.presetName, UsageFigure(it.requests, it.knownInputTokens, it.knownOutputTokens), it.unknownRequests > 0) })
    }
    override suspend fun queryConfusionEvidence(from: String, to: String, limit: Int): SdkResult<List<EvidenceItem>> = b.ask(Query.ConfusionEvidence(from, to, limit)) { view ->
        (view as? View.Evidence)?.let { v -> v.answers.map { a ->
            val activity = v.activities.firstOrNull { it.id == a.activityId && it.version == a.activityVersion }
            EvidenceItem(a.atMs, activity?.prompt.orEmpty(), a.input.selectedChoiceId?.let { choice -> activity?.choices?.firstOrNull { it.id == choice }?.text } ?: a.input.text,
                activity?.acceptedAnswers?.joinToString(" / ").orEmpty(), activity?.kind?.let(::kindLabel).orEmpty(), a.input.hintUsed || a.input.answerRevealed)
        } }
    }
}

class CodexAdaptation(private val b: CodexBridge) : AdaptationApi {
    override fun observeManagedLearning(): Flow<ManagedLearning> = b.live {
        val state = (b.view(Query.ManagedLearning) as View.ManagedLearning).state
        ManagedLearning(LearningMode(state.mode.name.lowercase()), state.presetId?.value, state.configurationFault?.toSdk(),
            state.pendingTasks, state.attentionTasks, state.summary)
    }
    override suspend fun setLearningMode(mode: LearningMode, request: ClientRequestId): SdkResult<Unit> = safely {
        val coreMode = when (mode) {
            LearningMode.Standard -> dev.wordprism.contract.LearningMode.STANDARD
            LearningMode.AiManaged -> dev.wordprism.contract.LearningMode.AI_MANAGED
            else -> return@safely invalid<Unit>("未知学习模式")
        }
        val settings = (b.view(Query.Settings) as View.Settings).settings
        b.exec(request, Command.SetLearningMode(coreMode, settings.version)) { Unit }
    }
    private suspend fun settings(): AdaptationSettings {
        val v = b.view(Query.Settings) as View.Settings
        val ops = (b.view(Query.Operations(200)) as View.Operations).operations.filter { it.feature == CFeature.LEARNING_ANALYSIS }
        val last = ops.maxByOrNull { it.updatedAtMs }
        val snap = last?.snapshotId?.let { (b.optionalView(Query.Snapshot(it)) as? View.Snapshot)?.snapshot }
        return AdaptationSettings(if (v.settings.automaticAnalysis || v.settings.learningMode == dev.wordprism.contract.LearningMode.AI_MANAGED) AnalysisMode.DailyAfterDayEnd else AnalysisMode.Off, v.settings.budgetMinutes, IntRangeDto(1, 241),
            (b.view(Query.PreviewCall(CFeature.LEARNING_ANALYSIS)) as View.Preview).preview.toSdk(b.online.value),
            last?.let { AnalysisRun(OperationId(it.id.value), if (it.automatic) "auto" else "manual", it.triggerDayId?.let { id -> (b.optionalView(Query.GetLearningDay(id)) as? View.Day)?.day?.date?.let(::LocalDay) },
                it.updatedAtMs.takeIf { _ -> it.status == CStatus.SUCCEEDED }, snap?.asOfMs, it.toSdk().status, it.summary.ifBlank { null }) },
            ops.firstOrNull { it.toSdk().status.isActive }?.id?.value?.let(::OperationId))
    }
    override fun observeSettings(): Flow<AdaptationSettings> = b.live { settings() }.combine(b.online) { value, online ->
        value.copy(requirement = value.requirement.let { if (!online && it.readiness == ApiReadiness.Ready) it.copy(readiness = ApiReadiness.QueuedOffline) else it })
    }
    private suspend fun editSettings(update: (dev.wordprism.contract.UserSettings) -> dev.wordprism.contract.UserSettings): SdkResult<AdaptationSettings> = safely {
        val s = (b.view(Query.Settings) as View.Settings).settings
        val result = b.exec(requestId(), Command.SetSettings(update(s), s.version)) { it }
        if (result is SdkResult.Err) result else SdkResult.Ok(settings())
    }
    override suspend fun setMode(mode: AnalysisMode): SdkResult<AdaptationSettings> {
        if (mode != AnalysisMode.Off && mode != AnalysisMode.DailyAfterDayEnd) return invalid<AdaptationSettings>("未知自动分析模式")
        return editSettings { it.copy(automaticAnalysis = mode == AnalysisMode.DailyAfterDayEnd) }
    }
    override suspend fun setDailyBudget(minutes: Int) = editSettings { it.copy(budgetMinutes = minutes) }
    override suspend fun analyzeNow(forceReanalyze: Boolean, request: ClientRequestId) = b.exec(request, Command.AnalyzeLearning(forceReanalyze)) { (it as? View.OperationResult)?.operation?.id?.value?.let(::OperationId) }
    override fun observeChangeSets(): Flow<List<ChangeSet>> = b.live {
        val changes = b.view(Query.PolicyChanges) as View.PolicyChanges
        val ops = (b.view(Query.Operations(200)) as View.Operations).operations
        val capabilities = (b.view(Query.Capabilities) as View.Capabilities).capabilities.associateBy { it.id }
        val records = changes.changes.groupBy { it.operationId }
        val analysis = ops.filter { it.feature == CFeature.LEARNING_ANALYSIS && it.status in setOf(CStatus.SUCCEEDED, CStatus.FAILED, CStatus.SUPERSEDED) }
        (records.keys + analysis.map { it.id }).distinct().mapNotNull { id ->
            val op = ops.firstOrNull { it.id == id } ?: (b.optionalView(Query.GetOperation(id)) as? View.OperationResult)?.operation ?: return@mapNotNull null
            val list = records[id].orEmpty(); val snapshotId = op.snapshotId ?: list.firstOrNull()?.snapshotId
            val snapshot = snapshotId?.let { (b.optionalView(Query.Snapshot(it)) as? View.Snapshot)?.snapshot }
            val contentChanges = (b.view(Query.OperationEffects(op.id)) as View.OperationEffects).effects
            val params = list.flatMap { change ->
                val before = Contract.json.encodeToJsonElement(change.before).jsonObject; val after = Contract.json.encodeToJsonElement(change.after).jsonObject
                before.keys.filter { it != "version" && before[it] != after[it] }.map { field ->
                    val cap = capabilities[field]
                    ParamChange(field, cap?.description ?: field, formatValue(field, before[field]!!), formatValue(field, after[field]!!), "学习策略", cap?.affectsSchedule == true)
                }
            } + contentChanges.map { change -> ParamChange(change.capabilityId, change.label, formatContent(change.before), formatContent(change.after), "词库与业务内容", change.affectsSchedule) }
            val undone = op.undoneBy != null || list.any { it.undoneBy != null }
            val undoable = op.undoable && !undone && (list.isEmpty() || list.all { it.id in changes.undoableIds })
            ChangeSet(ChangeSetId(op.id.value), OperationId(op.id.value), if (op.automatic) "auto" else if (op.actor == dev.wordprism.contract.Actor.USER) "manual" else "ai",
                list.firstOrNull()?.atMs ?: op.updatedAtMs, snapshotId?.value.orEmpty(), snapshot?.asOfMs ?: op.createdAtMs,
                list.firstOrNull()?.reason ?: op.summary.ifBlank { op.fault?.message ?: "分析未产生策略改动" }, list.map { it.reason }.distinct(), params,
                when { undone -> ChangeStatus.Undone; op.status == CStatus.FAILED -> ChangeStatus.Failed; op.status == CStatus.SUPERSEDED -> ChangeStatus.Superseded; params.isEmpty() && !op.undoable -> ChangeStatus.NoChange; else -> ChangeStatus.Applied },
                if (contentChanges.isEmpty()) "下次会话起使用新策略；已有会话保留原版本" else "内容修改已生效；新策略从下次会话起使用", UndoAvailability(undoable, if (undoable) "保留之后的作答并重算记忆安排" else "后续明确修改或当前状态限制撤销"))
        }.sortedByDescending { it.createdAt }
    }
    private fun formatContent(value: JsonElement?): String = when (value) {
        null, JsonNull -> "未设置"
        is JsonPrimitive -> value.content
        is JsonArray -> value.joinToString("；") { formatContent(it) }.ifBlank { "无" }
        else -> value.toString()
    }
    private fun formatValue(field: String, value: JsonElement): String = when {
        field == "weights" && value is JsonArray -> if (value.isEmpty()) "默认 FSRS-6 参数" else "个人参数（${value.size} 项）"
        field == "mix" && value is JsonObject -> value.entries.joinToString("，") { (key, weight) -> "${runCatching { kindLabel(dev.wordprism.contract.ActivityKind.valueOf(key)) }.getOrDefault(key)} $weight" }
        value is JsonArray -> "${value.size} 个目标"
        value is JsonPrimitive -> when (value.content) { "on_request" -> "按需提示"; "after_attempt" -> "先独立作答"; "simple" -> "简单"; "intermediate" -> "中等"; "advanced" -> "较难"; else -> value.content }
        else -> value.toString()
    }
    override suspend fun undoChangeSet(id: ChangeSetId, request: ClientRequestId) = CodexOperations(b).undo(OperationId(id.value), request)
    override fun observeCapabilities(): Flow<List<Capability>> = b.live {
        (b.view(Query.Capabilities) as View.Capabilities).capabilities.map { c ->
            val current = when {
                c.valueType != "action" -> formatValue(c.id, c.current)
                c.userLocked -> "已锁定"
                c.id in setOf("generate_activity", "explain_sense") -> "由 AI 分析后决定补充"
                else -> "AI 可按需执行"
            }
            val range = c.constraint.takeIf { c.valueType in setOf("integer", "number") && c.id != "plannedMinutes" }?.replace("..", "–")
            Capability(c.id, c.description, c.description, current, range, c.affectsSchedule, c.userLocked, true)
        }
    }
    override suspend fun setCapabilityLock(capabilityId: String, locked: Boolean): SdkResult<Unit> = safely {
        val s = (b.view(Query.Settings) as View.Settings).settings
        b.exec(requestId(), Command.SetSettings(s.copy(
            lockedCapabilities = if (locked) s.lockedCapabilities + capabilityId else s.lockedCapabilities - capabilityId,
            automaticGeneration = if (capabilityId == "explain_sense") !locked else s.automaticGeneration), s.version)) { Unit }
    }
}
