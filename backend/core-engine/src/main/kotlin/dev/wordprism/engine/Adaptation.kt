package dev.wordprism.engine

import dev.wordprism.contract.*
import dev.wordprism.memory.*
import kotlinx.serialization.json.*

internal fun Database.snapshot(now: Long, fromMs: Long = 0): LearningSnapshot {
    val all = answers.values.filter { it.atMs in fromMs..now }
    val valid = all.filter { it.valid && it.verdict in setOf(Verdict.CORRECT, Verdict.INCORRECT) }
    val unassisted = valid.filter { !it.input.hintUsed && !it.input.answerRevealed }
    fun metric(items: List<Answer>) = Metric(items.count { it.verdict == Verdict.CORRECT }, items.size)
    val includedSessions = sessions.values.filter { session -> days[session.dayId]?.opensAtMs?.let { it <= now && days[session.dayId]!!.closesAtMs >= fromMs } == true }
    fun recordedTime(mode: TimerMode) = timeSpans.values.filter { it.mode == mode }.sumOf { (minOf(it.toMs, now) - maxOf(it.fromMs, fromMs)).coerceAtLeast(0) } +
        includedSessions.sumOf { legacyTime(it, mode) }
    val calls = usage.values.filter { it.startedAtMs in fromMs..now }
    val confusions = valid.filter { it.confusedWith != null }.groupBy { it.targetId to it.confusedWith!! }.map { (pair, items) ->
        Confusion(pair.first, pair.second, items.size, valid.count { it.targetId == pair.first })
    }
    return LearningSnapshot(id(), asOfMs = now, fromMs = fromMs, zoneId = settings.zoneId, throughSequence = sequence, policyVersion = policy.version,
        words = words.values.count { !it.archived }, senses = senses.size, groups = groups.values.count { !it.archived },
        answers = all.size, distinctTargets = all.map { it.targetId }.distinct().size, activeDays = all.mapNotNull { answerDayId(it) }.distinct().size,
        studyMs = recordedTime(TimerMode.STUDY), waitingMs = recordedTime(TimerMode.WAITING), backgroundMs = recordedTime(TimerMode.BACKGROUND),
        dueTargets = targets.values.count { activeTarget(it) && (schedules[it.id]?.dueAtMs ?: 0) <= now },
        byAbility = Ability.entries.associateWith { ability -> metric(unassisted.filter { targets[it.targetId]?.ability == ability }) },
        delayedRecall = metric(unassisted.filter { it.delayed && targets[it.targetId]?.ability in setOf(Ability.RECALL, Ability.SPELLING) }),
        newContext = metric(unassisted.filter { it.newContext && activities[it.activityId]?.source?.kind == "ai" }), confusions = confusions,
        apiRequests = calls.size, knownInputTokens = calls.sumOf { it.inputTokens ?: 0 }, knownOutputTokens = calls.sumOf { it.outputTokens ?: 0 },
        unknownUsageRequests = calls.count { it.inputTokens == null || it.outputTokens == null },
        hintedAnswers = all.count { it.input.hintUsed || it.input.answerRevealed }, skippedAnswers = all.count { it.input.skipped },
        disputedAnswers = all.count { !it.valid || it.verdict == Verdict.DISPUTED },
        aiFeedbackCount = feedback.values.count { it.source == GradeSource.AI && answers[it.answerId]?.atMs?.let { time -> time in fromMs..now } == true },
        gradeSources = all.groupingBy { it.gradeSource }.eachCount(),
        timeSource = if (includedSessions.any { session -> TimerMode.entries.any { legacyTime(session, it) > 0 } }) "legacy_session_totals" else "recorded_intervals",
        details = snapshotDetails(now, fromMs, confusions, calls))
}

internal data class PolicySpec(val description: String, val type: String, val constraint: String, val scheduling: Boolean = false)
private val policySpecs = linkedMapOf(
    "newTargetLimit" to PolicySpec("每学习日新增目标上限", "integer", "0..100"),
    "reviewTargetLimit" to PolicySpec("每学习日复习目标上限", "integer", "0..500"),
    "sessionTargetLimit" to PolicySpec("每轮目标上限", "integer", "1..50"),
    "plannedMinutes" to PolicySpec("学习日预计时长", "integer", "1..user.budgetMinutes"),
    "retention" to PolicySpec("期望保持率", "number", "0.70..0.97", true),
    "maxIntervalDays" to PolicySpec("最大间隔天数", "integer", "1..36500", true),
    "weights" to PolicySpec("本机 FSRS-6 参数；需要同口径候选评估", "number[]", "empty defaults or 21 bounded finite values", true),
    "mix" to PolicySpec("题型相对权重，零表示不安排", "object", "known ActivityKind, 0..100; sum > 0"),
    "focusTargets" to PolicySpec("优先学习目标", "id[]", "existing target IDs"),
    "suspendedTargets" to PolicySpec("暂停的学习目标", "id[]", "existing target IDs"),
    "hintPolicy" to PolicySpec("提示策略", "string", "on_request or after_attempt"),
    "passageWords" to PolicySpec("新短文目标词数", "integer", "30..300"),
    "backgroundDifficulty" to PolicySpec("新生成语境的背景词难度", "string", "simple, intermediate, advanced"),
    "reuseAfterDays" to PolicySpec("已见 AI 题目再次使用的间隔", "integer", "0..365"),
    "preGenerateLimit" to PolicySpec("已授权预生成任务数上限", "integer", "0..10; requires user.automaticGeneration"),
)
private val actionCapabilities = listOf(
        Capability("fit_memory", "在本机拟合并评估真实学习历史", "action", JsonNull, "bounded data/time, held-out validation", affectsSchedule = true),
        Capability("update_word", "修正词条备注、标签或归档状态", "action", JsonNull, "Ref version; immutable history preserved"),
        Capability("update_sense", "修正义项和例句", "action", JsonNull, "Ref version; immutable content versions preserved"),
        Capability("change_membership", "修正归组关系", "action", JsonNull, "Ref version; undoable"),
        Capability("rename_group", "修正词组名称", "action", JsonNull, "Ref version; undoable"),
        Capability("edit_word", "原子修正备注和指定义项", "action", JsonNull, "Ref versions; immutable history preserved"),
        Capability("edit_memberships", "原子修正成员关系", "action", JsonNull, "Ref version; ambiguous senses require explicit selection"),
        Capability("dispute_activity", "标记争议题并排除受影响评分", "action", JsonNull, "Ref version; replay actual history", affectsSchedule = true),
        Capability("bind_preset", "调整未被用户锁定的功能预设", "action", JsonNull, "only unlocked feature bindings; existing enabled preset"),
        Capability("generate_activity", "在用户授权范围内生成练习", "action", JsonNull, "automaticGeneration; shared request budget"),
        Capability("explain_sense", "在用户授权范围内补充讲解", "action", JsonNull, "automaticGeneration; shared request budget"),
)
internal val aiActionTypes: Set<String> = actionCapabilities.map { it.id }.toSet()
internal val aiActionSchema by lazy { JsonSchemas.document(Command.serializer().descriptor, "Implemented AI actions", variants = aiActionTypes) }
internal fun Database.capabilities(): List<Capability> {
    val values = Contract.json.encodeToJsonElement(policy).jsonObject
    return policySpecs.map { (key, spec) -> Capability(key, spec.description, spec.type, values.getValue(key), spec.constraint,
        affectsSchedule = spec.scheduling, userLocked = key in settings.lockedCapabilities) } +
        actionCapabilities.map { capability -> capability.copy(userLocked = capability.id in settings.lockedCapabilities) }
}

internal fun Database.applyPatch(patch: PolicyPatch, expectedVersion: Long, actor: Actor, operationId: Id,
    snapshotId: Id?, now: Long, memory: MemoryKernel, evaluationId: Id? = null): PolicyChange? {
    requireBusiness(expectedVersion == policy.version, "策略已变化，请使用最新数据", ErrorCode.VERSION_CONFLICT)
    requireBusiness(patch.reason.isNotBlank() && patch.reason.length <= 4000, "变更需要简短依据")
    if (patch.values.isEmpty()) return null
    patch.values.keys.forEach { key ->
        requireBusiness(key in policySpecs, "未知可调整能力：$key", ErrorCode.UNKNOWN_CAPABILITY)
        requireBusiness(actor != Actor.AI || key !in settings.lockedCapabilities, "此参数已由用户锁定", ErrorCode.DISABLED)
    }
    patch.evidenceIds.forEach { requireBusiness(it in answers || it in snapshots || it in changes, "依据不存在") }
    val raw = Contract.json.encodeToJsonElement(policy).jsonObject
    val next = try { Contract.json.decodeFromJsonElement<TrainingPolicy>(JsonObject(raw + patch.values)).copy(version = policy.version + 1) }
        catch (_: Exception) { fail(ErrorCode.VALIDATION, "策略值类型无效") }
    validateTrainingPolicy(this, next, settings.budgetMinutes)
    requireBusiness(next.preGenerateLimit <= settings.preGenerationMaxPerDay && (next.preGenerateLimit == 0 || settings.automaticGeneration), "未启用自动生成或范围无效", ErrorCode.DISABLED)
    try { memory.validate(next.weights) } catch (_: IllegalArgumentException) { fail(ErrorCode.VALIDATION, "算法参数无效") }
    if (next.weights != policy.weights) {
        val evaluation = evaluationId?.let { evaluations[it] }.found("参数评估")
        requireBusiness(evaluation.accepted && evaluation.weights == next.weights && evaluation.throughSequence == sequence && evaluation.policyVersion == policy.version,
            "参数必须通过当前数据上的评估", ErrorCode.VERSION_CONFLICT)
    }
    if (next.copy(version = policy.version) == policy) return null
    val change = PolicyChange(id(), operationId, snapshotId, actor, policy, next, patch.reason, patch.evidenceIds, now, evaluationId = evaluationId)
    changes[change.id] = change; policy = next; policyVersions[next.version] = next
    invalidatePlans(now)
    // Existing sessions retain their exact policy. Unstarted future plans will be rebuilt on entry.
    return change
}

internal fun Database.finishDay(dayId: Id, reason: DayEndReason, now: Long): View.Day {
    val original = days[dayId].found("学习日")
    if (reason == DayEndReason.USER_FINISHED) sessions.values.filter {
        it.status == SessionStatus.ACTIVE && (now in original.opensAtMs until original.closesAtMs ||
            (it.timerAtMs?.let { at -> at in original.opensAtMs until original.closesAtMs } ?: (it.dayId == dayId)))
    }.forEach { session ->
        sessions[session.id] = tick(session.id, TimerMode.PAUSED, now).copy(status = SessionStatus.PAUSED)
    }
    var current = original
    if (original.endedAtMs == null) {
        current = original.copy(endedAtMs = now, endReason = reason, endSequence = sequence)
        days[dayId] = current
    }
    if (settings.automaticAnalysis && current.automaticOperationId == null && sequence > lastAnalyzedSequence) {
        val operation = analyze(now, true, current.id, false)
        current = current.copy(automaticOperationId = operation.id); days[dayId] = current
    }
    return dayView(current)
}
internal fun Database.dayView(day: LearningDay) = View.Day(day, day.planId?.let { plans[it] }, studyTime(day.id), day.automaticOperationId?.let { operations[it] }, daySummary(day))

internal fun Database.analyze(now: Long, automatic: Boolean, dayId: Id?, force: Boolean): Operation {
    if (!force) operations.values.filter { it.feature == Feature.LEARNING_ANALYSIS && it.throughSequence == sequence &&
        it.policyVersion == policy.version && it.status !in setOf(OperationStatus.FAILED, OperationStatus.CANCELLED, OperationStatus.SUPERSEDED, OperationStatus.RESTORED_PENDING) }
        .maxByOrNull { it.createdAtMs }?.let { return it }
    val op = queue(Command.AnalyzeLearning(force), Feature.LEARNING_ANALYSIS, now, automatic, dayId)
    if (automatic) {
        operations.values.filter { it.id != op.id && it.automatic && it.feature == Feature.LEARNING_ANALYSIS &&
            it.status in setOf(OperationStatus.QUEUED, OperationStatus.WAITING_NETWORK, OperationStatus.NEEDS_CONFIGURATION) }.forEach {
            operations[it.id] = it.copy(status = OperationStatus.SUPERSEDED, mergedInto = op.id, updatedAtMs = now)
        }
    }
    return op
}

internal data class DatedHistory(val atMs: Long, val history: History, val targetId: Id, val sequence: Long)
internal fun Database.histories(): List<DatedHistory> {
    // Keep only the newest bounded set while constructing prefixes. Never reset an old
    // target's memory by treating the middle of its history as a new first review.
    val result = java.util.PriorityQueue<DatedHistory>(compareBy { it.atMs })
    val initial = mutableMapOf<Id, DatedHistory>()
    answers.values.filter { it.valid && it.affectsSchedule && it.rating != null }.groupBy { it.targetId }.forEach { (targetId, answers) ->
        var last: Long? = null; val reviews = mutableListOf<Review>()
        answers.sortedBy { it.sequence }.take(256).forEach { answer ->
            val delta = last?.let { ((answer.atMs - it).coerceAtLeast(0) / DAY_MS).toInt() } ?: 0
            reviews += Review(rating(answer)!!.ordinal + 1, delta); last = answer.atMs
            if (delta > 0) {
                val sample = DatedHistory(answer.atMs, History(reviews.toList()), targetId, answer.sequence)
                initial.putIfAbsent(targetId, sample)
                if (result.size < 2500 || answer.atMs > result.peek().atMs) {
                    if (result.size == 2500) result.remove()
                    result += sample
                }
            }
        }
    }
    // fsrs-rs initializes stability only from first delayed-review prefixes. Retain one
    // for every selected target even when a recent-sample cap would otherwise drop it.
    return (result + result.map { initial.getValue(it.targetId) }).distinctBy { it.sequence }.sortedBy { it.atMs }
}

internal fun evaluateMemory(db: Database, candidate: List<Float>?, memory: MemoryKernel, token: Long): Evaluation {
    val all = db.histories()
    if (all.size < 16) return Evaluation(id(), db.sequence, db.policy.version, candidate ?: db.policy.weights, all.size, 0, 0,
        null, null, null, null, false, "延迟复习证据不足；保留当前参数")
    val cutoff = all[(all.size * 0.8).toInt().coerceAtMost(all.lastIndex)].atMs
    val train = all.filter { it.atMs < cutoff }.map { it.history }
    val validation = all.filter { it.atMs >= cutoff }.map { it.history }
    if (train.size < 8 || validation.size < 4) return Evaluation(id(), db.sequence, db.policy.version, candidate ?: db.policy.weights, train.size, validation.size, cutoff,
        null, null, null, null, false, "无法形成足够的按时间划分训练和验证数据")
    val weights = candidate ?: memory.fit(train, token)
    memory.validate(weights)
    val baseline = memory.evaluate(db.policy.weights, validation); val proposed = memory.evaluate(weights, validation)
    val accepted = proposed.log_loss.isFinite() && proposed.log_loss < baseline.log_loss - 0.0001f && proposed.rmse_bins <= baseline.rmse_bins + 0.02f
    return Evaluation(id(), db.sequence, db.policy.version, weights, train.size, validation.size, cutoff,
        baseline.log_loss, proposed.log_loss, baseline.rmse_bins, proposed.rmse_bins, accepted,
        "时间留出验证；验证前缀只包含当时已发生的作答，历史改善不证明未来收益")
}
