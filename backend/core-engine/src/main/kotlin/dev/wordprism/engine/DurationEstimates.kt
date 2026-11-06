package dev.wordprism.engine

import dev.wordprism.contract.*
import kotlin.math.roundToLong

/** Cold-start priors only. Observed durations may be shorter or longer than these defaults. */
private fun initialDuration(ability: Ability): Long = when (ability) {
    Ability.READING -> 150_000L
    Ability.EXPRESSION -> 120_000L
    Ability.RECALL, Ability.SPELLING -> 50_000L
    Ability.RECOGNITION -> 35_000L
}

internal data class DurationEstimates(val costs: Map<Ability, Long>, val samples: Map<Ability, Int>) {
    val sampleCount get() = samples.values.sum()
    fun cost(ability: Ability) = costs.getValue(ability)
    fun source(abilities: Set<Ability>) = when {
        sampleCount == 0 -> "initial_estimate"
        abilities.isNotEmpty() && abilities.all { samples.getOrDefault(it, 0) >= 3 } -> "personal_observation"
        else -> "mixed_observation"
    }
}

private data class DurationSample(val ability: Ability, val atMs: Long, val ms: Long)
private fun List<Double>.median(): Double = sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

/** Uses recorded STUDY intervals, never wall-clock gaps, waiting, or background time.
 * New records attribute reading, introductions, answers and feedback to the same target.
 * Legacy intervals can only be reconstructed between submissions, so they remain approximate.
 */
internal fun Database.durationEstimates(now: Long): DurationEstimates {
    val collected = mutableListOf<DurationSample>()
    val spansBySession = timeSpans.values.filter { it.mode == TimerMode.STUDY && it.toMs <= now && it.toMs > now - 60 * DAY_MS }
        .groupBy { it.sessionId }
    val answersBySession = answers.values.filter { it.atMs <= now && activities[it.activityId]?.kind !in introductionKinds }.groupBy { it.sessionId }
    for ((sessionId, spans) in spansBySession) {
        val session = sessions[sessionId] ?: continue
        val events = answersBySession[sessionId].orEmpty().sortedBy { it.sequence }
        if (events.isEmpty()) continue
        val unsettled = if (session.status == SessionStatus.FINISHED) null else
            session.currentActivityId?.let { activities[it]?.targetId }
                ?: session.lastAnswerId?.takeIf { session.awaitingAdvance }?.let { answers[it]?.targetId }
        fun add(answer: Answer, duration: Long) {
            val target = targets[answer.targetId] ?: return
            if (duration > 0 && answer.valid && !answer.input.skipped && answer.verdict != Verdict.DISPUTED && answer.targetId != unsettled)
                collected += DurationSample(target.ability, answer.atMs, duration)
        }
        if (spans.all { it.targetId != null }) {
            val perTarget = spans.groupBy { it.targetId }
            events.distinctBy { it.targetId }.forEach { answer -> add(answer, perTarget[answer.targetId].orEmpty().sumOf { it.toMs - it.fromMs }) }
        } else {
            var start = spans.minOf { it.fromMs }
            events.forEachIndexed { index, answer ->
                val end = if (index == events.lastIndex && session.status == SessionStatus.FINISHED && session.currentActivityId == null)
                    spans.maxOf { it.toMs } else answer.atMs
                add(answer, spans.sumOf { (minOf(it.toMs, end) - maxOf(it.fromMs, start)).coerceAtLeast(0) })
                start = answer.atMs
            }
        }
    }
    // Recent per-ability medians resist a single unusually long reading/feedback interval.
    val recent = collected.groupBy { it.ability }.mapValues { (_, values) -> values.sortedByDescending { it.atMs }.take(30) }
    val all = recent.values.flatten()
    val personalScale = if (all.size >= 3) all.map { it.ms.toDouble() / initialDuration(it.ability) }.median() else 1.0
    val costs = Ability.entries.associateWith { ability ->
        val samples = recent[ability].orEmpty()
        val prior = initialDuration(ability) * personalScale
        val confidence = (samples.size / 3.0).coerceAtMost(1.0)
        val observed = if (samples.isEmpty()) prior else samples.map { it.ms.toDouble() }.median()
        (observed * confidence + prior * (1 - confidence)).roundToLong().coerceAtLeast(1_000)
    }
    return DurationEstimates(costs, recent.mapValues { it.value.size })
}
