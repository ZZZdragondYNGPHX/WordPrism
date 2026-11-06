package dev.wordprism.engine

import dev.wordprism.contract.*
import java.time.Instant
import java.time.ZoneId

internal fun Database.studySummary(selected: Collection<LearningTarget>, now: Long): StudySummary {
    val active = selected.filter { activeTarget(it) }
    if (active.isEmpty()) return StudySummary(if (selected.isEmpty()) StudyState.NOT_STARTED else StudyState.PAUSED)
    val states = active.mapNotNull { schedules[it.id] }
    val due = states.count { it.reviews > 0 && it.dueAtMs <= now }
    val activeIds = active.map { it.id }.toSet()
    val practiced = answers.values.any { it.valid && !it.input.skipped && it.targetId in activeIds }
    val state = when {
        due > 0 -> StudyState.DUE
        states.all { it.reviews == 0 } -> if (practiced) StudyState.LEARNING else StudyState.NOT_STARTED
        states.any { it.reviews < 3 || (it.memory?.stability ?: 0f) < 7f } -> StudyState.LEARNING
        else -> StudyState.STABLE
    }
    return StudySummary(state, due)
}

/** Group progress includes its members' shared recall/spelling/expression and this group's contrasts. */
internal fun Database.groupStudyTargets(groupId: Id): List<LearningTarget> {
    val memberSenses = memberships.values.filter { it.groupId == groupId && it.active }.map { it.senseId }.toSet()
    return targets.values.filter { it.groupId == groupId || it.groupId == null && it.senseId in memberSenses }
}

internal fun Database.libraryOverview(query: Query.LibrarySummary, now: Long): LibraryOverview {
    requireBusiness(query.search.length <= 200, "搜索文本过长")
    val liveWords = words.values.filter { !it.archived }
    val liveGroups = groups.values.filter { !it.archived }
    val senseWords = senses.mapValues { it.value.wordId }
    val members = memberships.values.filter { it.active }.groupBy { it.groupId }
    val grouped = members.filterKeys { gid -> groups[gid]?.archived == false }.values.flatten().mapNotNull { senseWords[it.senseId] }.toSet()
    val term = normalize(query.search)
    fun matches(word: Word) = term.isBlank() || term in word.normalized || term in normalize(word.notes) ||
        senses.values.any { it.wordId == word.id && term in normalize(it.meaning) }
    val selected = if (query.ungroupedOnly) emptyList() else liveGroups.filter { query.relation == null || it.relation == query.relation }.mapNotNull { group ->
        val memberWords = groupWords(group.id)
        if (term.isNotBlank() && term !in normalize(group.title) && memberWords.none(::matches)) null
        else GroupOverview(group, memberWords, studySummary(groupStudyTargets(group.id), now))
    }.sortedBy { it.group.id.value }
    val ungrouped = if (query.relation != null) emptyList() else liveWords.filter { it.id !in grouped && matches(it) }.sortedBy { it.id.value }
    val ids = ungrouped.map { it.id }.toSet()
    return LibraryOverview(selected, ungrouped, senses.values.filter { it.wordId in ids },
        ungrouped.associate { word -> word.id to studySummary(targets.values.filter { senseWords[it.senseId] == word.id }, now) }, liveWords.size, liveGroups.size)
}

/** Needleman–Wunsch alignment to a stable anchor, merged by anchor insertion columns. */
internal fun alignWords(words: List<Word>): List<WordAlignment> {
    if (words.isEmpty()) return emptyList()
    val anchor = words.first().spelling
    data class Aligned(val letters: CharArray, val gaps: List<String>)
    fun align(text: String): Aligned {
        val cost = Array(anchor.length + 1) { IntArray(text.length + 1) }
        for (i in 0..anchor.length) cost[i][0] = i
        for (j in 0..text.length) cost[0][j] = j
        for (i in 1..anchor.length) for (j in 1..text.length) {
            cost[i][j] = minOf(cost[i - 1][j] + 1, cost[i][j - 1] + 1, cost[i - 1][j - 1] + if (anchor[i - 1].equals(text[j - 1], true)) 0 else 1)
        }
        val a = StringBuilder(); val b = StringBuilder(); var i = anchor.length; var j = text.length
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && cost[i][j] == cost[i - 1][j - 1] + if (anchor[i - 1].equals(text[j - 1], true)) 0 else 1 -> { a.append(anchor[--i]); b.append(text[--j]) }
                i > 0 && cost[i][j] == cost[i - 1][j] + 1 -> { a.append(anchor[--i]); b.append(' ') }
                else -> { a.append(' '); b.append(text[--j]) }
            }
        }
        a.reverse(); b.reverse()
        val letters = CharArray(anchor.length) { ' ' }; val gaps = MutableList(anchor.length + 1) { "" }; var cursor = 0
        a.indices.forEach { k -> if (a[k] == ' ') gaps[cursor] += b[k] else letters[cursor++] = b[k] }
        return Aligned(letters, gaps)
    }
    val aligned = words.map { align(it.spelling) }
    val widths = (0..anchor.length).map { column -> aligned.maxOf { it.gaps[column].length } }
    val rows = aligned.map { row -> buildString { for (i in 0..anchor.length) { append(row.gaps[i].padEnd(widths[i])); if (i < anchor.length) append(row.letters[i]) } } }
    val differing = rows.first().indices.filter { column -> rows.map { it[column].lowercaseChar() }.distinct().size > 1 }.toSet()
    return words.mapIndexed { index, word ->
        val ranges = mutableListOf<CharacterRange>(); var start: Int? = null
        for (column in 0..rows[index].length) {
            if (column in differing && start == null) start = column
            if (column !in differing && start != null) { ranges += CharacterRange(start, column); start = null }
        }
        WordAlignment(word.id, rows[index], ranges)
    }
}

internal fun Database.budgetState(day: LearningDay): BudgetState {
    val used = studyTime(day.id); val budget = settings.budgetMinutes * 60_000L
    return when { used >= budget -> BudgetState.REACHED; used >= budget * 9 / 10 -> BudgetState.NEAR; else -> BudgetState.WITHIN }
}

internal fun Database.daySummary(day: LearningDay): DaySummary {
    val op = day.automaticOperationId?.let(operations::get)
    val plan = day.planId?.let(plans::get)
    val state = when {
        !settings.automaticAnalysis -> "disabled"
        op != null -> when (op.status) {
            OperationStatus.SUCCEEDED -> "done"; OperationStatus.SUPERSEDED -> "skipped_no_new_data"
            else -> op.status.name.lowercase()
        }
        day.endedAtMs != null -> "skipped_no_new_data"
        else -> "will_run_after_day_end"
    }
    return DaySummary(settings.budgetMinutes,
        timeSpans.values.filter { it.dayId == day.id && it.mode == TimerMode.WAITING }.sumOf { it.toMs - it.fromMs } +
            sessions.values.filter { it.dayId == day.id }.sumOf { legacyTime(it, TimerMode.WAITING) },
        budgetState(day), answers.values.filter { answerDayId(it) == day.id && activities[it.activityId]?.kind !in introductionKinds }.map { it.targetId }.distinct().size,
        sessions.values.lastOrNull { it.status != SessionStatus.FINISHED && it.belongsTo(day) }?.id,
        day.endedAtMs == null, settings.automaticAnalysis, state, day.planId?.let { plans[it]?.policyVersion }?.let(policyVersions::get) ?: policy,
        policy.focusTargets.mapNotNull { targets[it]?.groupId }.distinct(),
        when { day.endReason == DayEndReason.PLAN_COMPLETED -> "plan_completed"; day.endedAtMs != null -> "ended"
            answers.values.any { answerDayId(it) == day.id } || studyTime(day.id) > 0 -> "in_progress"; else -> "not_started" },
        plannedNewWords = plan?.let { it.newWordCount ?: legacyNewWordCount(it) } ?: 0)
}

/** Legacy plans predate the explicit count; reconstruct from facts preceding their creation. */
private fun Database.legacyNewWordCount(plan: LearningPlan): Int {
    val knownWords = answers.values.filter { it.valid && !it.input.skipped && it.atMs < plan.createdAtMs }
        .mapNotNull { senses[targets[it.targetId]?.senseId]?.wordId }.toSet()
    return (plan.targetIds.mapNotNull { senses[targets[it]?.senseId]?.wordId }.toSet() - knownWords).size
}

internal fun Database.snapshotDetails(now: Long, fromMs: Long, confusions: List<Confusion>, calls: List<Usage>): SnapshotDetails {
    val window = answers.values.filter { it.atMs in fromMs..now && activities[it.activityId]?.kind !in introductionKinds }
    val zone = ZoneId.of(settings.zoneId); val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val overdue = targets.values.filter { activeTarget(it) && (schedules[it.id]?.reviews ?: 0) > 0 }
    val expressionFeedback = feedback.values.filter { it.source == GradeSource.AI && answers[it.answerId]?.let { a ->
        a.valid && a.atMs in fromMs..now && a.version == it.answerVersion } == true }
    val classifiedUsage = expressionFeedback.filter { it.targetUsage in setOf("good", "appropriate", "equally_valid", "acceptable_unnatural", "misused") }
    val estimates = durationEstimates(now)
    return SnapshotDetails(
        addedWords = words.values.count { !it.archived && it.source.atMs in fromMs..now },
        acceptedRecommendations = recommendations.values.count { r -> r.acceptedOperationId?.let { operations[it] }?.let { it.undoneBy == null && it.updatedAtMs in fromMs..now } == true },
        undoneOperations = operations.values.count { it.kind == "undo" && it.createdAtMs in fromMs..now },
        ungroupedWords = words.values.count { word -> !word.archived && memberships.values.none { it.active && groups[it.groupId]?.archived == false && senses[it.senseId]?.wordId == word.id } },
        newTargets = window.map { it.targetId }.distinct().count { tid -> answers.values.filter { it.targetId == tid && activities[it.activityId]?.kind !in introductionKinds }.minByOrNull { it.sequence }?.atMs?.let { it >= fromMs } == true },
        interruptedSessions = sessions.values.count { it.status == SessionStatus.PAUSED && days[it.dayId]?.let { d -> d.closesAtMs >= fromMs && d.opensAtMs <= now } == true },
        budgetMinutes = settings.budgetMinutes,
        daily = days.values.filter { it.closesAtMs > fromMs && it.opensAtMs <= now }.sortedBy { it.opensAtMs }.map { d ->
            DayEffort(d.date, timeSpans.values.filter { it.dayId == d.id && it.mode == TimerMode.STUDY }.sumOf { (minOf(it.toMs, now) - maxOf(it.fromMs, fromMs)).coerceAtLeast(0) } +
                sessions.values.filter { it.dayId == d.id }.sumOf { legacyTime(it, TimerMode.STUDY) }, d.planId?.let { plans[it]?.estimatedMs } ?: 0)
        },
        forecast = (1L..7L).map { offset ->
            val date = today.plusDays(offset); val start = date.atStartOfDay(zone).toInstant().toEpochMilli(); val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val due = overdue.filter { schedules[it.id]?.dueAtMs?.let { due -> due in start until end } == true }
            LoadForecast(date.toString(), due.size, due.sumOf { estimates.cost(it.ability) })
        },
        overdueTargets = overdue.count { (schedules[it.id]?.dueAtMs ?: now) < today.atStartOfDay(zone).toInstant().toEpochMilli() },
        confusions = confusions.map { c -> ConfusionDetail(c.targetId, c.selectedSenseId,
            words[senses[targets[c.targetId]?.senseId]?.wordId]?.spelling.orEmpty(), words[senses[c.selectedSenseId]?.wordId]?.spelling.orEmpty(),
            c.count, c.opportunities, targets[c.targetId]?.groupId, c.opportunities < 20) },
        usage = calls.groupBy { it.feature to it.preset.id }.map { (key, calls) -> UsageTotal(key.first, key.second, calls.last().preset.name, calls.size,
            calls.sumOf { it.inputTokens ?: 0 }, calls.sumOf { it.outputTokens ?: 0 }, calls.count { it.inputTokens == null || it.outputTokens == null }) },
        aiUsage = expressionFeedback.groupBy { it.targetUsage }.mapValues { (_, values) -> Metric(values.size, expressionFeedback.size) },
        expressionUsage = Metric(classifiedUsage.count { it.targetUsage in setOf("good", "appropriate", "equally_valid") }, classifiedUsage.size),
    )
}

internal fun Database.callPolicy() = CallPolicy(settings.defaultPresetId, settings.fallbackPresetId, settings.dailyRequestLimit,
    settings.maxRetries, settings.automaticGeneration, settings.preGenerationMaxPerDay, settings.version, policy.version)

internal fun Database.connectionTest(preset: ApiPreset): ConnectionTest? = operations.values.filter { jobs[it.id]?.command is Command.TestConnection && it.preset?.let { source -> source.id == preset.id && source.version == preset.version } == true && it.status in terminalStatuses }
    .maxByOrNull { it.updatedAtMs }?.let { op ->
        val attempt = usage.values.lastOrNull { it.operationId == op.id }
        ConnectionTest(op.status == OperationStatus.SUCCEEDED, op.updatedAtMs, op.fault?.message ?: "连接测试完成",
            attempt?.endedAtMs?.let { it - attempt.startedAtMs })
    }

internal fun Database.intakeEffects(before: Map<Pair<String, String>, kotlinx.serialization.json.JsonElement>, results: List<Id>, reason: String): List<IntakeEffect> {
    val resultWords = results.mapNotNull { senses[it]?.wordId ?: words[it]?.id }.distinct()
    val resultGroups = results.mapNotNull { groups[it] }
    return resultGroups.map { group ->
        val old = before["groups" to group.id.value]
        val changed = memberships.values.any { m -> m.groupId == group.id && m.active && before["memberships" to m.id.value] != Contract.json.encodeToJsonElement(Membership.serializer(), m) }
        IntakeEffect(if (old == null) "created_group" else if (changed) "joined_group" else "reused_group", group.title, reason, group.id)
    } + resultWords.map { wid -> IntakeEffect(if (before["words" to wid.value] == null) "created_word" else "reused_word", words[wid]!!.spelling, reason, wordId = wid) } +
        if (resultGroups.isEmpty()) resultWords.map { wid -> IntakeEffect("saved_ungrouped", words[wid]!!.spelling, "已保存，可稍后归组", wordId = wid) } else emptyList()
}
