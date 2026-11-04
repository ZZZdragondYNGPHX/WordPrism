package dev.wordprism.engine

import dev.wordprism.contract.*
import dev.wordprism.memory.MemoryKernel
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToLong

internal const val DAY_MS = 86_400_000L
internal val introductionKinds = setOf(ActivityKind.FAMILIARITY, ActivityKind.CONTRAST)
internal fun Database.day(now: Long): LearningDay {
    days.values.find { now >= it.opensAtMs && now < it.closesAtMs }?.let { return it }
    val zone = ZoneId.of(settings.zoneId); val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return LearningDay(id(), date.toString(), settings.zoneId, date.atStartOfDay(zone).toInstant().toEpochMilli(), date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        .also { days[it.id] = it }
}
internal fun Database.answerDayId(answer: Answer): Id? = answer.dayId ?: days.values.find { answer.atMs in it.opensAtMs until it.closesAtMs }?.id ?: sessions[answer.sessionId]?.dayId
internal fun Database.legacyTime(session: Session, mode: TimerMode): Long {
    val total = when (mode) { TimerMode.STUDY -> session.studyMs; TimerMode.WAITING -> session.waitingMs; TimerMode.BACKGROUND -> session.backgroundMs; else -> 0L }
    return (total - timeSpans.values.filter { it.sessionId == session.id && it.mode == mode }.sumOf { it.toMs - it.fromMs }).coerceAtLeast(0)
}
internal fun Database.studyTime(dayId: Id) = timeSpans.values.filter { it.dayId == dayId && it.mode == TimerMode.STUDY }.sumOf { it.toMs - it.fromMs } +
    sessions.values.filter { it.dayId == dayId }.sumOf { legacyTime(it, TimerMode.STUDY) }
internal fun ActivityKind.ability(): Ability = when (this) {
    ActivityKind.CHOICE -> Ability.RECOGNITION; ActivityKind.RECALL -> Ability.RECALL; ActivityKind.SPELLING -> Ability.SPELLING
    ActivityKind.PASSAGE -> Ability.READING; ActivityKind.WRITING, ActivityKind.REWRITE -> Ability.EXPRESSION
    ActivityKind.FAMILIARITY, ActivityKind.CONTRAST -> error("Introduction is not a scheduling ability")
}
internal fun Ability.kind(): ActivityKind = when (this) {
    Ability.RECOGNITION -> ActivityKind.CHOICE; Ability.RECALL -> ActivityKind.RECALL; Ability.SPELLING -> ActivityKind.SPELLING
    Ability.READING -> ActivityKind.PASSAGE; Ability.EXPRESSION -> ActivityKind.WRITING
}
internal fun Database.activeTarget(target: LearningTarget, usingPolicy: TrainingPolicy = policy): Boolean = !target.suspended && target.id !in usingPolicy.suspendedTargets &&
    words[senses[target.senseId]?.wordId]?.archived == false && (target.groupId == null || groups[target.groupId]?.archived == false)

internal fun Database.plan(day: LearningDay, now: Long, extra: Boolean = false): LearningPlan {
    val totalBudget = settings.budgetMinutes * 60_000L
    val remaining = (minOf(totalBudget, policy.plannedMinutes * 60_000L) - studyTime(day.id)).coerceAtLeast(0)
    val budget = if (extra) policy.sessionTargetLimit * 60_000L else remaining
    val learningAnswers = answers.values.filter { activities[it.activityId]?.kind !in introductionKinds }
    val studied = learningAnswers.filter { answerDayId(it) == day.id }.map { it.targetId }.toSet()
    val firstAnswers = learningAnswers.groupBy { it.targetId }.mapValues { (_, events) -> events.minBy { it.sequence } }
    val newStudied = studied.count { firstAnswers[it]?.let { first -> answerDayId(first) == day.id } == true }
    val newRemaining = (policy.newTargetLimit - newStudied).coerceAtLeast(0)
    val reviewRemaining = (policy.reviewTargetLimit - (studied.size - newStudied)).coerceAtLeast(0)
    val due = targets.values.filter { activeTarget(it) && it.id !in studied && (schedules[it.id]?.dueAtMs ?: 0) <= now }
    val candidates = due.filter { target -> when (target.ability) {
        Ability.READING -> activities.values.any { it.targetId == target.id && !it.disputed && reusable(it, policy, now) }
        Ability.RECOGNITION -> senses[target.senseId]?.let { tested -> senses.values.any { it.wordId != tested.wordId && normalize(it.meaning) != normalize(tested.meaning) } } == true
        else -> true
    } }
    val completedSessions = sessions.values.filter { it.completedTargets.isNotEmpty() }
    val observedPerTarget = completedSessions.sumOf { it.studyMs }.toDouble() / completedSessions.sumOf { it.completedTargets.size }.coerceAtLeast(1)
    val estimateSource = if (completedSessions.sumOf { it.completedTargets.size } >= 8 && observedPerTarget > 0) "personal_observation" else "initial_estimate"
    fun cost(target: LearningTarget): Long {
        val base = when (target.ability) { Ability.READING -> 150_000L; Ability.EXPRESSION -> 120_000L; Ability.RECALL, Ability.SPELLING -> 50_000L; else -> 35_000L }
        return if (estimateSource == "personal_observation") maxOf(base, observedPerTarget.toLong().coerceIn(15_000, 240_000)) else base
    }
    val failures = answers.values.filter { it.valid && it.verdict == Verdict.INCORRECT }.groupingBy { it.targetId }.eachCount()
    val ordered = candidates.sortedWith(compareBy<LearningTarget> { if (it.id in firstAnswers) 0 else 1 }
        .thenBy { if (it.id in policy.focusTargets) 0 else 1 }.thenByDescending { failures[it.id] ?: 0 }
        .thenBy { schedules[it.id]?.dueAtMs ?: 0 }.thenBy { when (it.ability) { Ability.RECALL -> 0; Ability.SPELLING -> 1; Ability.RECOGNITION -> 2; Ability.READING -> 3; Ability.EXPRESSION -> 4 } }.thenBy { it.id.value })
    val selected = mutableListOf<LearningTarget>(); var spent = 0L; var new = 0; var reviews = 0
    val counts = mutableMapOf<Ability, Int>(); val remainingCandidates = ordered.toMutableList()
    while (remainingCandidates.isNotEmpty()) {
        val firstClass = remainingCandidates.first().id in firstAnswers
        val available = remainingCandidates.filter { (it.id in firstAnswers) == firstClass }
        val target = available.minBy { t ->
            val weight = policy.mix.filterKeys { it.ability() == t.ability }.values.sum().coerceAtLeast(1)
            (counts[t.ability] ?: 0).toDouble() / weight + (if (t.id in policy.focusTargets) -1.0 else 0.0)
        }
        remainingCandidates.remove(target)
        val isNew = target.id !in firstAnswers
        if (policy.mix.filterKeys { it.ability() == target.ability }.values.sum() == 0) continue
        if (isNew && new >= newRemaining || !isNew && reviews >= reviewRemaining || spent + cost(target) > budget) continue
        selected += target; spent += cost(target); if (isNew) new++ else reviews++
        counts[target.ability] = (counts[target.ability] ?: 0) + 1
    }
    val knownWords = answers.values.filter { it.valid && !it.input.skipped }.mapNotNull { senses[targets[it.targetId]?.senseId]?.wordId }.toSet()
    val newWords = selected.mapNotNull { senses[it.senseId]?.wordId }.toSet() - knownWords
    return LearningPlan(id(), day.id, policy.version, selected.map { it.id }, new, reviews, due.size - selected.size,
        spent, estimateSource, totalBudget, now, newWords.size).also { plans[it.id] = it; days[day.id] = day.copy(planId = it.id) }
}

internal fun Database.startSession(now: Long, extra: Boolean, introductions: Boolean = false): View.SessionState {
    val day = day(now)
    val existing = sessions.values.filter { it.status != SessionStatus.FINISHED }.maxByOrNull { it.timerAtMs ?: days[it.dayId]?.opensAtMs ?: 0 }
    if (existing != null && (existing.awaitingAdvance || existing.targetIds.any { it !in existing.completedTargets })) {
        requireBusiness(extra || existing.extraBudgetDayId == day.id || existing.currentActivityId != null || studyTime(day.id) < settings.budgetMinutes * 60_000L,
            "已达到当日预算；继续学习需要本次明确选择", ErrorCode.BUDGET_EXCEEDED)
        val resumed = tick(existing.id, TimerMode.STUDY, now).copy(status = SessionStatus.ACTIVE, extraBudgetDayId = if (extra) day.id else existing.extraBudgetDayId)
        sessions[existing.id] = resumed; return sessionView(resumed)
    }
    existing?.let { sessions[it.id] = tick(it.id, TimerMode.PAUSED, now).copy(status = SessionStatus.FINISHED) }
    requireBusiness(extra || studyTime(day.id) < settings.budgetMinutes * 60_000L, "已达到当日预算；继续学习需要本次明确选择", ErrorCode.BUDGET_EXCEEDED)
    val currentPlan = day.planId?.let { plans[it] }?.takeIf { it.policyVersion == policy.version && !extra } ?: plan(day, now, extra)
    val done = answers.values.filter { answerDayId(it) == day.id && activities[it.activityId]?.kind !in introductionKinds }.map { it.targetId }.toSet()
    val selected = currentPlan.targetIds.filter { it !in done && targets[it]?.let { t -> activeTarget(t) } == true }.take(policy.sessionTargetLimit)
    val session = Session(id(), day.id, currentPlan.id, policy.version, targetIds = selected,
        status = if (selected.isEmpty()) SessionStatus.FINISHED else SessionStatus.ACTIVE,
        timerMode = if (selected.isEmpty()) TimerMode.PAUSED else TimerMode.STUDY, timerAtMs = now, extraBudgetDayId = day.id.takeIf { extra }, includeIntroductions = introductions)
    if (selected.isEmpty()) finishDay(day.id, DayEndReason.PLAN_COMPLETED, now)
    sessions[session.id] = session; return sessionView(session)
}
internal fun Database.sessionView(session: Session): View.SessionState {
    val answer = session.lastAnswerId?.let(answers::get)?.takeIf { session.awaitingAdvance }
    val activity = (session.currentActivityId?.let(activities::get) ?: answer?.let { activityVersions["${it.activityId.value}:${it.activityVersion}"] })
        ?.let { card(it).copy(hintAllowed = it.kind !in introductionKinds && policyVersions[session.policyVersion]?.hintPolicy == "on_request") }
    val currentDay = session.timerAtMs?.let { at -> days.values.find { at in it.opensAtMs until it.closesAtMs } } ?: days[session.dayId]
    val budget = currentDay?.let(::budgetState) ?: BudgetState.WITHIN
    return View.SessionState(session, activity, answer?.takeIf { session.revisingAnswerId == null }?.let(::answerView), budget,
        session.status == SessionStatus.PAUSED && budget == BudgetState.REACHED && session.extraBudgetDayId != currentDay?.id && !session.awaitingAdvance)
}

internal fun Database.nextActivity(sessionId: Id, now: Long): View.SessionState {
    var session = sessions[sessionId].found("会话")
    requireBusiness(session.status == SessionStatus.ACTIVE, "会话未在进行中")
    if (session.currentActivityId != null) return sessionView(session)
    if (session.awaitingAdvance) {
        session = session.copy(awaitingAdvance = false, revisingAnswerId = null)
        sessions[session.id] = session
    }
    val currentDay = day(now)
    if (session.extraBudgetDayId != currentDay.id && studyTime(currentDay.id) >= settings.budgetMinutes * 60_000L) {
        val paused = tick(session.id, TimerMode.PAUSED, now).copy(status = SessionStatus.PAUSED)
        sessions[session.id] = paused; finishDay(currentDay.id, DayEndReason.BUDGET_REACHED, now)
        return sessionView(paused)
    }
    val sessionPolicy = policyVersions[session.policyVersion].found("会话策略")
    val target = session.targetIds.filter { it !in session.completedTargets }.mapNotNull { targets[it] }.firstOrNull { activeTarget(it, sessionPolicy) }
    if (target == null) {
        val finished = tick(session.id, TimerMode.PAUSED, now).copy(status = SessionStatus.FINISHED)
        sessions[session.id] = finished; return sessionView(finished)
    }
    val used = answers.values.filter { it.targetId == target.id }.mapNotNull { activities[it.activityId]?.contextKey }.toSet()
    val kindCounts = answers.values.filter { answerDayId(it) == currentDay.id }.mapNotNull { activities[it.activityId]?.kind }.groupingBy { it }.eachCount()
    val familiarity = answers.values.lastOrNull { targets[it.targetId]?.senseId == target.senseId && activities[it.activityId]?.kind == ActivityKind.FAMILIARITY }
    val contrasted = answers.values.any { activities[it.activityId]?.kind == ActivityKind.CONTRAST && targets[it.targetId]?.groupId == target.groupId }
    val kind = when {
        session.includeIntroductions && familiarity == null && answers.values.none { it.targetId == target.id } -> ActivityKind.FAMILIARITY
        session.includeIntroductions && familiarity?.input?.familiar == false && target.groupId != null && !contrasted -> ActivityKind.CONTRAST
        else -> sessionPolicy.mix.filter { it.key !in introductionKinds && it.key.ability() == target.ability && it.value > 0 }.minByOrNull { (kind, weight) -> (kindCounts[kind] ?: 0).toDouble() / weight }?.key ?: target.ability.kind()
    }
    val activity = activities.values.filter { it.targetId == target.id && it.kind == kind && !it.disputed && reusable(it, sessionPolicy, now) }
        .sortedWith(compareBy<Activity> { it.contextKey in used }.thenBy { it.id.value }).firstOrNull() ?: localActivity(target, kind, now)
    val updated = session.copy(currentActivityId = activity.id, currentPresentationId = id())
    if (target.senseId in session.exposedSenseIds) exposures[updated.currentPresentationId!!] = Exposure(answerRevealed = true)
    sessions[session.id] = updated; return sessionView(updated)
}
internal fun Database.reusable(activity: Activity, policy: TrainingPolicy, now: Long): Boolean = activity.senseVersion == senses[targets[activity.targetId]?.senseId]?.version &&
    policy.mix.getOrDefault(activity.kind, 0) > 0 && (activity.source.kind != "ai" ||
    answers.values.filter { it.activityId == activity.id }.maxOfOrNull { it.atMs }?.let { now - it >= policy.reuseAfterDays * DAY_MS } != false)

internal fun Database.localActivity(target: LearningTarget, kind: ActivityKind, now: Long): Activity {
    val sense = senses[target.senseId].found("义项"); val word = words[sense.wordId].found("词条")
    requireBusiness(kind != ActivityKind.PASSAGE, "该阅读目标需要已保存的短文", ErrorCode.OFFLINE)
    val options = if (kind == ActivityKind.CHOICE) {
        val peers = (memberships.values.filter { it.active && it.groupId == target.groupId }.mapNotNull { senses[it.senseId] } + senses.values)
            .filter { it.id != sense.id && it.wordId != word.id && normalize(it.meaning) != normalize(sense.meaning) }.distinctBy { it.wordId }.take(3)
        (peers + sense).map { Choice(it.id.value, words[it.wordId].found("词条").spelling, it.id) }.shuffled(kotlin.random.Random(target.id.value.hashCode().toLong() xor now))
    } else emptyList()
    val activity = Activity(id(), targetId = target.id, kind = kind,
        prompt = when (kind) {
            ActivityKind.WRITING -> "用 ${word.spelling}（${sense.meaning}）写一句符合这个义项的句子。"
            ActivityKind.REWRITE -> "将释义“${sense.meaning}”换一种说法写成英语句子，使用 ${word.spelling}。"
            ActivityKind.FAMILIARITY -> "你认识 ${word.spelling} 吗？"
            ActivityKind.CONTRAST -> groups[target.groupId]?.purpose ?: sense.meaning
            else -> "${sense.partOfSpeech} · ${sense.meaning}"
        },
        choices = options, acceptedAnswers = if (kind in setOf(ActivityKind.WRITING, ActivityKind.REWRITE) + introductionKinds) emptyList() else (sense.acceptedForms + word.spelling).distinct(),
        explanation = sense.meaning, contextKey = "local:${sense.id.value}:${sense.version}:${kind}", source = Provenance("local", atMs = now), senseVersion = sense.version,
        cue = sense.meaning, targetWords = listOf(word.spelling))
    requireBusiness(activities.size < 30000, "当前题目数量已达到 30000 上限", ErrorCode.LIMIT_EXCEEDED)
    activities[activity.id] = activity; activityVersions["${activity.id.value}:${activity.version}"] = activity; return activity
}

internal fun Database.tick(sessionId: Id, mode: TimerMode, now: Long): Session {
    val session = sessions[sessionId].found("会话")
    val elapsed = session.timerAtMs?.let { (now - it).coerceIn(0, 90_000) } ?: 0L
    if (elapsed > 0 && session.timerMode != TimerMode.PAUSED) {
        var cursor = session.timerAtMs!!; val end = cursor + elapsed
        while (cursor < end) {
            val day = day(cursor); val to = minOf(end, day.closesAtMs)
            val previous = timeSpans.values.find { it.sessionId == sessionId && it.dayId == day.id && it.mode == session.timerMode && it.toMs == cursor }
            val span = previous?.copy(toMs = to) ?: TimeSpan(id(), sessionId, day.id, session.timerMode, cursor, to)
            timeSpans[span.id] = span; cursor = to
        }
    }
    val updated = session.copy(timerAtMs = now, timerMode = mode,
        studyMs = session.studyMs + if (session.timerMode == TimerMode.STUDY) elapsed else 0,
        waitingMs = session.waitingMs + if (session.timerMode == TimerMode.WAITING) elapsed else 0,
        backgroundMs = session.backgroundMs + if (session.timerMode == TimerMode.BACKGROUND) elapsed else 0)
    sessions[session.id] = updated; return updated
}

internal fun Database.submitAnswer(command: Command.SubmitAnswer, now: Long, memory: MemoryKernel): View.AnswerResult {
    val exposure = exposures[command.presentationId] ?: Exposure()
    val input = command.answer.copy(hintUsed = command.answer.hintUsed || exposure.hintUsed, answerRevealed = command.answer.answerRevealed || exposure.answerRevealed)
    presentations[command.presentationId]?.let { answerId ->
        val previous = answers[answerId].found("作答")
        requireBusiness(previous.sessionId == command.sessionId && previous.activityId == command.activity.id && previous.input == input,
            "同一次题目展示已经提交了不同答案", ErrorCode.IDEMPOTENCY_CONFLICT)
        return answerView(previous)
    }
    val session = sessions[command.sessionId].found("会话")
    requireBusiness(session.status == SessionStatus.ACTIVE && session.currentActivityId == command.activity.id && session.currentPresentationId == command.presentationId, "题目展示已失效", ErrorCode.VERSION_CONFLICT)
    val activity = activities[command.activity.id].found("题目"); checkRef(command.activity, activity.version)
    requireBusiness(command.answer.text.length <= 16000, "答案过长", ErrorCode.LIMIT_EXCEEDED)
    requireBusiness(answers.size < 100000, "当前作答数量已达到 100000 上限", ErrorCode.LIMIT_EXCEEDED)
    val choice = input.selectedChoiceId?.let { choiceId -> activity.choices.find { it.id == choiceId }.found("选项") }
    val text = choice?.text ?: input.text
    val open = activity.kind in setOf(ActivityKind.WRITING, ActivityKind.REWRITE)
    val introduction = activity.kind in introductionKinds
    if (activity.kind == ActivityKind.FAMILIARITY && !input.skipped) requireBusiness(input.familiar != null, "请提供实际的熟悉程度选择")
    val correct = activity.acceptedAnswers.any { normalize(it) == normalize(text) || it == input.selectedChoiceId }
    val verdict = when { activity.disputed -> Verdict.DISPUTED; input.skipped -> Verdict.SKIPPED; open || introduction -> Verdict.UNGRADED; correct -> Verdict.CORRECT; else -> Verdict.INCORRECT }
    // Hint/reveal is an exposure condition, never fabricated as an independent recall success/failure.
    val rating = when { open || introduction || input.skipped || activity.disputed || input.hintUsed || input.answerRevealed -> null
        !correct -> Rating.AGAIN; input.difficult -> Rating.HARD; else -> Rating.GOOD }
    val target = targets[activity.targetId].found("学习目标"); val before = schedules[target.id].found("记忆状态")
    val answer = Answer(id(), ++sequence, session.id, activity.id, activity.version, target.id,
        input = input, atMs = now, verdict = verdict, gradeSource = if (introduction) GradeSource.USER else if (open || input.skipped) GradeSource.UNGRADED else GradeSource.OBJECTIVE,
        rating = rating, affectsSchedule = rating != null, policyVersion = session.policyVersion,
        delayed = before.lastReviewAtMs?.let { now - it >= DAY_MS } == true,
        newContext = answers.values.none { it.targetId == target.id && activities[it.activityId]?.contextKey == activity.contextKey },
        confusedWith = choice?.senseId?.takeIf { !correct && it != target.senseId }, dayId = day(now).id)
    answers[answer.id] = answer; answerVersions["${answer.id.value}:1"] = answer; presentations[command.presentationId] = answer.id
    if (rating != null) schedules[target.id] = review(before, answer, policyVersions[session.policyVersion].found("策略版本"), memory)
    if (!open && !introduction) {
        val result = Feedback(id(), answer.id, answer.version, activity.explanation, if (input.skipped) GradeSource.UNGRADED else GradeSource.OBJECTIVE)
        feedback[result.id] = result
    }
    val timed = tick(session.id, TimerMode.STUDY, now)
    val exposed = when (activity.kind) {
        ActivityKind.CONTRAST -> memberships.values.filter { it.groupId == target.groupId && it.active }.map { it.senseId }.toSet()
        ActivityKind.FAMILIARITY -> setOf(target.senseId)
        else -> emptySet()
    }
    sessions[session.id] = timed.copy(completedTargets = if (introduction) timed.completedTargets else timed.completedTargets + target.id,
        currentActivityId = null, currentPresentationId = null, lastAnswerId = answer.id, awaitingAdvance = true, revisingAnswerId = null,
        exposedSenseIds = timed.exposedSenseIds + exposed, lastPresentationId = command.presentationId)
    if (open && input.requestFeedback && !input.skipped) queue(Command.EvaluateResponse(Ref(answer.id, answer.version)), Feature.WRITING_FEEDBACK, now)
    val day = days[answer.dayId].found("学习日"); val plan = day.planId?.let { plans[it] }
    val done = answers.values.filter { answerDayId(it) == day.id && activities[it.activityId]?.kind !in introductionKinds }.map { it.targetId }.toSet()
    if (!introduction && plan != null && done.containsAll(plan.targetIds)) finishDay(day.id, DayEndReason.PLAN_COMPLETED, now)
    else if (studyTime(day.id) >= settings.budgetMinutes * 60_000L) finishDay(day.id, DayEndReason.BUDGET_REACHED, now)
    return answerView(answer)
}
internal fun Database.difficultyMark(answer: Answer) = difficultyMarks.values.filter { it.answerId == answer.id && it.answerVersion == answer.version }.maxByOrNull { it.revision }
internal fun Database.rating(answer: Answer): Rating? = if (answer.rating in setOf(Rating.GOOD, Rating.HARD) && difficultyMark(answer) != null) {
    if (difficultyMark(answer)!!.difficult) Rating.HARD else Rating.GOOD
} else answer.rating
internal fun Database.answerView(answer: Answer): View.AnswerResult {
    val activity = activityVersions["${answer.activityId.value}:${answer.activityVersion}"]
    return View.AnswerResult(answer, feedback.values.filter { it.answerId == answer.id && it.answerVersion == answer.version }, schedules[answer.targetId],
        activity?.acceptedAnswers.orEmpty(), activity?.choices?.firstOrNull { c -> activity.acceptedAnswers.any { it == c.id || normalize(it) == normalize(c.text) } }?.id,
        answer.valid && answer.verdict == Verdict.CORRECT && answer.affectsSchedule && answer.gradeSource == GradeSource.OBJECTIVE,
        difficultyMark(answer)?.difficult ?: answer.input.difficult,
        answer.gradeSource == GradeSource.UNGRADED && !answer.input.skipped && activity?.kind in setOf(ActivityKind.WRITING, ActivityKind.REWRITE),
        operations.values.asSequence().filter { op -> (jobs[op.id]?.command as? Command.EvaluateResponse)?.answer == Ref(answer.id, answer.version) && op.status != OperationStatus.SUPERSEDED }.latestOperation()?.id)
}

internal fun review(before: Schedule, answer: Answer, policy: TrainingPolicy, kernel: MemoryKernel): Schedule {
    val elapsed = before.lastReviewAtMs?.let { ((answer.atMs - it).coerceAtLeast(0) / DAY_MS).toInt() } ?: 0
    val next = kernel.schedule(policy.weights, before.memory, elapsed, answer.rating!!, policy.retention)
    val interval = next.interval.roundToLong().coerceIn(1, policy.maxIntervalDays.toLong())
    return before.copy(memory = next.memory, dueAtMs = answer.atMs + interval * DAY_MS, lastReviewAtMs = answer.atMs, reviews = before.reviews + 1, policyVersion = policy.version)
}
internal fun Database.replay(kernel: MemoryKernel, policyOverride: TrainingPolicy? = null) {
    targets.keys.forEach { targetId ->
        var schedule = Schedule(targetId, dueAtMs = schedules[targetId]?.dueAtMs ?: 0, policyVersion = (policyOverride ?: policy).version)
        answers.values.filter { it.targetId == targetId && it.valid && it.affectsSchedule && it.rating != null }.sortedBy { it.sequence }.forEach { answer ->
            schedule = review(schedule, answer.copy(rating = rating(answer)), policyOverride ?: policyVersions[answer.policyVersion].found("历史策略"), kernel)
        }
        schedules[targetId] = schedule
    }
}
