package dev.wordprism.engine

import dev.wordprism.contract.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

class DurationEstimatesTest {
    private suspend fun recallHarness(): Harness = Harness().also { h ->
        h.command(Command.SaveWords((1..10).map { WordSeed("word$it", "meaning $it") }))
        h.command(Command.ApplyPolicy(PolicyPatch(mapOf("mix" to json("""{"RECALL":100}""")), "recall practice"), 1))
    }

    @Test fun `measured question and feedback time replaces defaults while wait and background are excluded`() = runBlocking {
        val h = recallHarness()
        var question = h.question()
        repeat(3) {
            h.command(Command.SetTimer(question.session.id, TimerMode.WAITING)); h.clock.advance(30_000)
            h.command(Command.SetTimer(question.session.id, TimerMode.BACKGROUND)); h.clock.advance(60_000)
            h.command(Command.SetTimer(question.session.id, TimerMode.STUDY)); h.clock.advance(10_000)
            h.answer(question); h.clock.advance(5_000)
            question = h.command(Command.NextActivity(question.session.id)) as View.SessionState
        }
        val state = h.store.read()
        val estimates = state.durationEstimates(h.clock.millis())
        assertEquals(3, estimates.samples[Ability.RECALL])
        assertEquals(15_000L, estimates.cost(Ability.RECALL))
        assertEquals("personal_observation", estimates.source(setOf(Ability.RECALL)))
        assertEquals("mixed_observation", estimates.source(setOf(Ability.RECALL, Ability.EXPRESSION)))
        assertEquals(45_000L, state.timeSpans.values.filter { it.mode == TimerMode.STUDY }.sumOf { it.toMs - it.fromMs })
        assertEquals(90_000L, state.timeSpans.values.filter { it.mode == TimerMode.WAITING }.sumOf { it.toMs - it.fromMs })
        // New and legacy records both derive duration from effective intervals, never answer gaps.
        h.store.transaction { db -> db.timeSpans.replaceAll { _, span -> span.copy(targetId = null) } }
        assertEquals(15_000L, h.store.read().durationEstimates(h.clock.millis()).cost(Ability.RECALL))
    }

    @Test fun `one long response does not dominate typical duration and old evidence ages out`() = runBlocking {
        val h = recallHarness(); var question = h.question()
        for (duration in listOf(10_000L, 10_000L, 10_000L, 80_000L)) {
            h.clock.advance(duration); h.answer(question)
            question = h.command(Command.NextActivity(question.session.id)) as View.SessionState
        }
        val estimates = h.store.read().durationEstimates(h.clock.millis())
        assertEquals(10_000L, estimates.cost(Ability.RECALL))
        assertEquals(4, estimates.sampleCount)
        h.clock.advance(61 * DAY_MS)
        val old = h.store.read().durationEstimates(h.clock.millis())
        assertEquals(0, old.sampleCount)
        assertEquals("initial_estimate", old.source(setOf(Ability.RECALL)))
    }

    @Test fun `unanswered and skipped targets cannot produce a fast personal estimate`() = runBlocking {
        val h = recallHarness(); var question = h.question()
        h.clock.advance(10_000)
        assertEquals(0, h.store.read().durationEstimates(h.clock.millis()).sampleCount)
        h.answer(question, AnswerInput("", skipped = true))
        question = h.command(Command.NextActivity(question.session.id)) as View.SessionState
        assertNotNull(question.activity)
        assertEquals(0, h.store.read().durationEstimates(h.clock.millis()).sampleCount)
    }
}
