package app.wordprism.mock

import app.wordprism.sdk.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate

internal class MockInsights(private val c: MockCore) : OverviewApi, AdaptationApi {

    init { c.handlers[OperationKind.Analysis] = ::runAnalysis }

    // ================================================================== overview (local only)

    override fun observeOverview(window: OverviewWindow): Flow<LearningOverview> = c.db.map { d -> overview(d, window) }.distinctUntilChanged()

    internal fun overview(d: MockDb, window: OverviewWindow): LearningOverview {
        val h = d.history
        val today = LocalDate.parse(c.today())
        val span = if (window.days == 0) h.dailyMinutes.size else minOf(window.days, h.dailyMinutes.size)
        val todayMin = ((d.days[c.today()]?.effectiveSeconds ?: 0) / 60).toInt()
        val daily = (span - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            val minutes = if (back == 0) todayMin else h.dailyMinutes[h.dailyMinutes.size - back]
            DayEffort(LocalDay(date.toString()), minutes, d.settings.budgetMinutes)
        }
        val live = d.answers
        val liveScored = live.filter { it.correct != null }
        fun ratio(base: Pair<Int, Int>, add: Pair<Int, Int> = 0 to 0, scale: Double = span / 14.0): Ratio {
            val s = (base.first * scale).toInt() + add.first; val o = (base.second * scale).toInt() + add.second
            return Ratio(s, o, o < 12)
        }
        val liveOk = liveScored.count { it.correct == true && !it.hinted } to liveScored.count { !it.hinted }
        val confusions = h.confusions.map { (from, to, co) ->
            val extra = live.count { it.target == from && it.chosen == to }
            val r = ratio(co, extra to live.count { it.target == from })
            ConfusionPair(from, to, r.successes, r.opportunities,
                if (from == "adopt") "flat" else "improving", if (from == "adopt") "没有明显变化" else "比前一周少",
                r.lowSample, d.groups.values.firstOrNull { g -> g.memberIds.contains("w-$from") }?.id?.let(::GroupId))
        }
        return LearningOverview(
            snapshotId = "snap-${d.answers.size}-${d.changeSets.size}", schemaVersion = 1, metricsVersion = "metrics-0.1-mock",
            asOf = c.clock(), window = window, timezone = c.zone.id,
            coverage = "含模拟的 14 天历史与本机 ${live.size} 次作答",
            library = LibraryStats(d.words.size, d.groups.size, d.words.values.count { it.createdAt > c.clock() - span * 86_400_000L },
                d.operations.values.count { it.kind == OperationKind.AcceptRecommendation && it.status == OperationStatus.Succeeded },
                d.operations.values.count { it.status == OperationStatus.Undone },
                d.words.values.count { w -> d.groups.values.none { w.id in it.memberIds } }),
            effort = EffortStats(daily.count { it.effectiveMinutes > 0 }, (h.answers * span / 14) + live.size, d.words.size,
                (span * 0.9).toInt(), daily.sumOf { it.effectiveMinutes }, d.settings.budgetMinutes, daily, span / 2, 2),
            memory = listOf(
                RatioMetric("unaided", "无提示答对", ratio(h.unaidedSuccess, liveOk), "observed", "实际作答"),
                RatioMetric("delayed", "间隔 3 天以上仍答对", ratio(h.delayedSuccess), "observed", "实际作答", "间隔后的表现更能说明记住了"),
                RatioMetric("hinted", "用过提示后答对", ratio(h.hinted), "observed", "实际作答", "不计入无提示成功"),
                RatioMetric("predicted", "算法预测的明日保持率", Ratio(88, 100, false), "predicted", "算法预测", "预测值，不是实际结果"),
            ),
            abilities = listOf(
                RatioMetric("recognition", "辨认与语境选词", ratio(h.recognition), "observed", "客观判分"),
                RatioMetric("recall", "无选项回忆", ratio(h.recall), "observed", "客观判分"),
                RatioMetric("reading", "短文理解", ratio(h.reading), "observed", "客观判分"),
                RatioMetric("production", "造句用词", ratio(h.production), "ai_review", "AI 评价", "AI 评价仅作参考"),
            ),
            confusions = confusions,
            load = LoadStats(d.words.values.count { it.status == StudyStatus.Due.value }, 0,
                (1..7).map { DayForecast(LocalDay(today.plusDays(it.toLong()).toString()), listOf(9, 6, 11, 4, 8, 12, 5)[it - 1], listOf(14, 10, 17, 7, 12, 19, 8)[it - 1]) },
                "按当前参数估计，未包含新学"),
            contentQuality = listOf(CountMetric("归组被你纠正", 1), CountMetric("题目被标记有问题", d.disputes), CountMetric("生成失败", d.operations.values.count { it.status == OperationStatus.Failed })),
            apiUsage = usage(d),
        )
    }

    internal fun usage(d: MockDb): List<UsageRow> = d.operations.values.filter { it.feature != null && it.usage != null }
        .groupBy { it.feature!! to it.presetName }
        .map { (k, ops) ->
            val unknown = ops.any { it.usage?.outputTokens == null }
            UsageRow(k.first, c.featureLabel(k.first), k.second,
                UsageFigure(ops.sumOf { it.usage!!.requests }, ops.sumOf { it.usage!!.inputTokens ?: 0 }, ops.sumOf { it.usage!!.outputTokens ?: 0 }, null), unknown)
        }.sortedByDescending { it.usage.requests }

    override suspend fun queryConfusionEvidence(from: String, to: String, limit: Int): SdkResult<List<EvidenceItem>> {
        val d = c.db.value
        val live = d.answers.filter { it.target == from && it.chosen == to }.map { EvidenceItem(it.at, it.prompt, to, from, it.activityKind, it.hinted) }
        val seeded = (1..3).map { i -> EvidenceItem(c.clock() - i * 86_400_000L * 2, "语境选词", to, from, "语境选词", false) }
        return SdkResult.Ok((live + seeded).take(limit))
    }

    // ================================================================== adaptation

    override fun observeSettings() = combine(c.db, c.online) { d, o ->
        val last = (d.settings.activeAnalysisOpId ?: d.settings.lastAnalysisOpId)?.let { d.operations[it] }
        AdaptationSettings(
            AnalysisMode(d.settings.mode), d.settings.budgetMinutes, IntRangeDto(10, 61),
            c.requirement(d, FeatureId.LearningAnalysis, online = o),
            last?.let { AnalysisRun(it.id, if (it.title.startsWith("每日")) "auto" else "manual", null, it.updatedAt, it.createdAt, it.status, it.resultSummary ?: it.error?.message) },
            d.settings.activeAnalysisOpId?.takeIf { d.operations[it]?.status?.isActive == true }?.let(::OperationId),
        )
    }.distinctUntilChanged()

    override suspend fun setMode(mode: AnalysisMode): SdkResult<AdaptationSettings> {
        var cancel: String? = null
        c.mutate { d ->
            val active = d.settings.activeAnalysisOpId?.let { d.operations[it] }
            // Turning auto off cancels the queued/running *automatic* task; late results won't apply (commit checks status).
            if (mode == AnalysisMode.Off && active != null && active.status.isActive && active.title.startsWith("每日")) cancel = active.id.value
            d.copy(settings = d.settings.copy(mode = mode.value))
        }
        cancel?.let { c.cancel(OperationId(it)) }
        return SdkResult.Ok(observeSettings().first())
    }

    override suspend fun setDailyBudget(minutes: Int): SdkResult<AdaptationSettings> {
        if (minutes !in 10..60) return SdkResult.Err(SdkError(ErrorCode.Validation, "预算范围是 10 到 60 分钟", false))
        c.mutate { it.copy(settings = it.settings.copy(budgetMinutes = minutes)) }
        return SdkResult.Ok(observeSettings().first())
    }

    internal fun dailyStatus(d: MockDb, day: MDay): DailyAnalysisStatus {
        val mode = AnalysisMode(d.settings.mode)
        val op = day.autoAnalysisOpId?.let { d.operations[it] }
        return when {
            op != null -> when (op.status) {
                OperationStatus.Queued -> DailyAnalysisStatus(mode, "queued", "今日分析已排队", op.id)
                OperationStatus.WaitingNetwork -> DailyAnalysisStatus(mode, "waiting_network", "今日分析等待联网", op.id)
                OperationStatus.Running -> DailyAnalysisStatus(mode, "running", "正在分析今天的学习", op.id)
                OperationStatus.Succeeded -> DailyAnalysisStatus(mode, "done", op.resultSummary ?: "今日分析已完成", op.id)
                OperationStatus.Cancelled -> DailyAnalysisStatus(mode, "disabled", "今日分析已取消", op.id)
                OperationStatus.Merged -> DailyAnalysisStatus(mode, "done", "已并入较新的分析", op.id)
                else -> DailyAnalysisStatus(mode, "failed", op.error?.message ?: "今日分析失败", op.id)
            }
            mode == AnalysisMode.Off -> DailyAnalysisStatus(mode, "disabled", "每日自动分析已关闭")
            day.status == DayStatus.Ended.value || day.status == DayStatus.PlanCompleted.value ->
                DailyAnalysisStatus(mode, "skipped_no_new_data", "没有新的学习数据，今天不分析")
            else -> DailyAnalysisStatus(mode, "will_run_after_day_end", "结束今天后自动分析一次")
        }
    }

    /** Day-end event: at most one automatic analysis per learning day, only with new evidence. */
    internal suspend fun onDayEnded(date: String) {
        val opId = c.tx { d ->
            val day = d.days[date] ?: return@tx d to null
            if (day.autoAnalysisOpId != null || d.settings.mode == AnalysisMode.Off.value) return@tx d to null
            if (d.answers.size <= d.lastAnalysisCoveredAnswers) return@tx d to null
            val req = c.requirement(d, FeatureId.LearningAnalysis)
            var op = c.newOperation(d, OperationKind.Analysis, "每日分析 $date", FeatureId.LearningAnalysis)
            c.blockingError(req)?.let { op = op.copy(status = OperationStatus.Failed, error = it, actions = emptySet()) }
            // Merge an older still-pending analysis (e.g. stuck offline across days) into this one.
            var n = d
            d.settings.activeAnalysisOpId?.let { old -> if (d.operations[old]?.status?.isActive == true) n = with(c) { n.updateOp(old) { it.copy(status = OperationStatus.Merged, actions = emptySet()) } } }
            n = with(c) { n.withOp(op) }.copy(
                days = n.days + (date to day.copy(autoAnalysisOpId = op.id.value)),
                settings = n.settings.copy(activeAnalysisOpId = op.id.value),
            )
            n to op.id.value.takeIf { op.status.isActive }
        }
        opId?.let { c.launch(it) }
    }

    override suspend fun analyzeNow(forceReanalyze: Boolean, request: ClientRequestId): SdkResult<OperationId> {
        val r = c.tx { d ->
            c.existing(d, request)?.let { return@tx d to SdkResult.Ok(OperationId(it)) }
            d.settings.activeAnalysisOpId?.let { if (d.operations[it]?.status?.isActive == true) return@tx d to SdkResult.Ok(OperationId(it)) }
            val last = d.settings.lastAnalysisOpId
            if (!forceReanalyze && last != null && d.answers.size <= d.lastAnalysisCoveredAnswers)
                return@tx d to SdkResult.Ok(OperationId(last)) // identical snapshot: reuse
            c.blockingError(c.requirement(d, FeatureId.LearningAnalysis))?.let { return@tx d to SdkResult.Err(it) }
            val op = c.newOperation(d, OperationKind.Analysis, "手动分析", FeatureId.LearningAnalysis)
            with(c) { d.withOp(op).remember(request, op.id.value) }.let { it.copy(settings = it.settings.copy(activeAnalysisOpId = op.id.value)) } to SdkResult.Ok(op.id)
        }
        r.onOk { c.launch(it.value) }
        return r
    }

    private suspend fun runAnalysis(opId: String) {
        if (!c.aiStep(opId, UsageFigure(1, 5200, 900))) return
        c.commit(opId) { d ->
            val s = d.settings
            val op = d.operations.getValue(opId)
            val trigger = if (op.title.startsWith("每日")) "auto" else "manual"
            // Auto task re-checks the switch before applying (closing while running must not apply late results).
            if (trigger == "auto" && s.mode == AnalysisMode.Off.value) {
                return@commit with(c) { d.updateOp(opId) { it.copy(status = OperationStatus.Cancelled, actions = emptySet(), resultSummary = "自动分析已关闭，结果未应用") } } to Unit
            }
            val snap = overview(d, OverviewWindow.Week)
            val changes = buildList {
                if ("plan.new_per_day" !in d.locks && s.newPerDay > 3) add(ParamChange("plan.new_per_day", "每日新学上限", "${s.newPerDay}", "${s.newPerDay - 1}", "明日计划", false))
                if ("mix.reading" !in d.locks && s.readingShare < 0.3f) add(ParamChange("mix.reading", "短文理解占比", pct(s.readingShare), pct(s.readingShare + 0.05f), "下一次学习", false))
                if ("plan.focus" !in d.locks && "liner → linear" !in s.focus && d.words.containsKey("w-liner")) add(ParamChange("plan.focus", "重点辨析", s.focus.joinToString().ifEmpty { "无" }, (s.focus + "liner → linear").joinToString(), "明日计划", false))
                if ("schedule.retention" !in d.locks && s.targetRetention < 0.92f && d.answers.size > 6) add(ParamChange("schedule.retention", "目标保持率", pct(s.targetRetention), pct(s.targetRetention + 0.01f), "之后的复习逐步使用", true))
            }
            val cs = ChangeSet(
                ChangeSetId(c.newId("cs")), OperationId(opId), trigger, c.clock(), snap.snapshotId, snap.asOf,
                if (changes.isEmpty()) "保持当前设置" else "调整 ${changes.size} 项：" + changes.joinToString("、") { it.label },
                listOf(
                    "adopt 被误选为 adapt：${snap.confusions.firstOrNull()?.let { "${it.count} 次（${it.opportunities} 次机会）" } ?: "暂无"}",
                    "近 7 天无提示答对 ${snap.memory.first().ratio.let { "${it.successes}/${it.opportunities}" }}，短文理解样本较少",
                    "今天实际用时 ${(d.days[c.today()]?.effectiveSeconds ?: 0) / 60} 分钟，预算 ${s.budgetMinutes} 分钟",
                ),
                changes, if (changes.isEmpty()) ChangeStatus.NoChange else ChangeStatus.Applied,
                if (changes.any { it.affectsSchedule }) "明日计划；保持率对之后的复习逐步生效" else "下一次学习会话",
                UndoAvailability(changes.isNotEmpty(), if (changes.isNotEmpty()) "撤销会恢复原设置，之后完成的作答都会保留" else null),
            )
            val ns = changes.fold(s) { acc, ch -> when (ch.capabilityId) {
                "plan.new_per_day" -> acc.copy(newPerDay = ch.after.toInt())
                "mix.reading" -> acc.copy(readingShare = acc.readingShare + 0.05f)
                "plan.focus" -> acc.copy(focus = acc.focus + "liner → linear")
                "schedule.retention" -> acc.copy(targetRetention = acc.targetRetention + 0.01f)
                else -> acc
            } }.let { if (changes.isEmpty()) it else it.copy(policyVersion = it.policyVersion + 1, planVersion = it.planVersion + 1, adjustedBy = "AI 调整：${cs.summary}") }
            // Older applied change sets can no longer be undone independently.
            val older = d.changeSets.map { if (it.status == ChangeStatus.Applied && changes.isNotEmpty()) it.copy(undo = UndoAvailability(false, "已有较新的调整，请先撤销较新的那次")) else it }
            with(c) {
                d.copy(settings = ns.copy(lastAnalysisOpId = opId, activeAnalysisOpId = null), changeSets = listOf(cs) + older, lastAnalysisCoveredAnswers = d.answers.size)
                    .updateOp(opId) { it.copy(status = OperationStatus.Succeeded, actions = emptySet(), resultSummary = cs.summary) }
            } to Unit
        }
    }

    private fun pct(f: Float) = "${(f * 100 + 0.5f).toInt()}%"

    override fun observeChangeSets() = c.db.map { it.changeSets }.distinctUntilChanged()

    override suspend fun undoChangeSet(id: ChangeSetId, request: ClientRequestId): SdkResult<UndoOutcome> = c.tx { d ->
        val idx = d.changeSets.indexOfFirst { it.id == id }
        if (idx < 0) return@tx d to SdkResult.Err(SdkError(ErrorCode.NotFound, "调整记录不存在", false))
        val cs = d.changeSets[idx]
        if (!cs.undo.available || cs.status != ChangeStatus.Applied) return@tx d to SdkResult.Err(SdkError(ErrorCode.NotUndoable, cs.undo.note ?: "这次调整不能撤销", false))
        val s = cs.changes.fold(d.settings) { acc, ch -> when (ch.capabilityId) {
            "plan.new_per_day" -> acc.copy(newPerDay = ch.before.toInt())
            "mix.reading" -> acc.copy(readingShare = acc.readingShare - 0.05f)
            "plan.focus" -> acc.copy(focus = ch.before.split(", ").filter { it != "无" && it.isNotBlank() })
            "schedule.retention" -> acc.copy(targetRetention = acc.targetRetention - 0.01f)
            else -> acc
        } }.copy(policyVersion = d.settings.policyVersion + 1, planVersion = d.settings.planVersion + 1, adjustedBy = null)
        val answersAfter = d.answers.count { it.at > cs.createdAt }
        val undoOp = c.newOperation(d, OperationKind.Undo, "撤销调整：${cs.summary}", null, OperationStatus.Succeeded)
        val list = d.changeSets.toMutableList()
        list[idx] = cs.copy(status = ChangeStatus.Undone, undo = UndoAvailability(false, "已撤销"))
        // The next-older applied change becomes undoable again.
        list.indexOfFirst { it.status == ChangeStatus.Applied }.takeIf { it >= 0 }?.let { list[it] = list[it].copy(undo = UndoAvailability(true, "撤销后之后完成的作答会保留")) }
        val n = with(c) { d.copy(settings = s, changeSets = list).withOp(undoOp) }
        n to SdkResult.Ok(UndoOutcome(undoOp.id, "已恢复调整前的设置，计划已重新计算", listOf("调整后完成的 $answersAfter 次作答已保留，并用于重新计算记忆状态")))
    }

    override fun observeCapabilities() = c.db.map { d ->
        val s = d.settings
        listOf(
            Capability("plan.new_per_day", "每日新学上限", "AI 根据完成情况调整明日新学数量", "${s.newPerDay}", "0–15", false, "plan.new_per_day" in d.locks, true),
            Capability("plan.focus", "重点辨析", "近期反复混淆的词对优先安排", s.focus.joinToString().ifEmpty { "无" }, null, false, "plan.focus" in d.locks, true),
            Capability("mix.reading", "短文理解占比", "混合训练中 AI 短文题的比例", pct(s.readingShare), "0–40%", false, "mix.reading" in d.locks, true),
            Capability("mix.production", "造句与改写占比", "主动输出练习的比例", pct(s.productionShare), "0–30%", false, "mix.production" in d.locks, true),
            Capability("schedule.retention", "目标保持率", "记忆算法安排复习时追求的保持率", pct(s.targetRetention), "80–95%", true, "schedule.retention" in d.locks, true),
            Capability("user.recommend_click", "额外词需要你点击加入", "你明确要求的规则，AI 不能更改", "始终", null, false, true, false),
            Capability("user.budget", "每日时间预算", "由你决定，AI 只在预算内安排", "${s.budgetMinutes} 分钟", null, false, true, false),
        )
    }.distinctUntilChanged()

    override suspend fun setCapabilityLock(capabilityId: String, locked: Boolean): SdkResult<Unit> = c.tx { d ->
        if (capabilityId.startsWith("user.")) return@tx d to SdkResult.Err(SdkError(ErrorCode.Validation, "这一项始终由你决定", false))
        d.copy(locks = if (locked) d.locks + capabilityId else d.locks - capabilityId) to SdkResult.Ok(Unit)
    }
}
