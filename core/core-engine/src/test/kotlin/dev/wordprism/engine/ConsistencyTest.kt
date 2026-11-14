package dev.wordprism.engine

import dev.wordprism.contract.*
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test

class ConsistencyTest {
    @Test fun `concurrent submit and new keys cannot double count a presentation`() = runBlocking {
        val h = Harness(); h.seed(); val card = h.question(); val activity = h.store.read().activities[card.activity!!.id]!!
        val command = Command.SubmitAnswer(card.session.id, card.session.currentPresentationId!!, Ref(activity.id, 1), AnswerInput(activity.acceptedAnswers.first()))
        val replies = (1..12).map { async(Dispatchers.Default) { h.core.execute(Request("answer-$it", command)) } }.awaitAll()
        replies.forEach { assertNull(it.fault) }
        val state = h.store.read(); assertEquals(1, state.answers.size); assertEquals(1, state.schedules[activity.targetId]!!.reviews)
        assertEquals(1, replies.map { (it.value as View.AnswerResult).answer.id }.distinct().size)
        val conflict = h.core.execute(Request("answer-1", command.copy(answer = AnswerInput("wrong"))))
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, conflict.fault?.code)
    }
    @Test fun `daily finish deduplicates across sessions restart and repeated events`() = runBlocking {
        val h = Harness(); h.configure(); h.seed(); h.answer()
        val first = h.finishDay()!!; repeat(3) { assertEquals(first.id, h.finishDay()!!.id) }
        h.core.recover(); h.core.tasks.runPending(false)
        assertEquals(OperationStatus.WAITING_NETWORK, h.store.read().operations[first.id]!!.status)
        h.core.tasks.runPending(true); h.core.tasks.runPending(true)
        assertEquals(1, h.provider.requests.size); assertEquals(OperationStatus.SUCCEEDED, h.store.read().operations[first.id]!!.status)
        h.answer(); h.finishDay(); h.core.tasks.runPending(true)
        assertEquals(1, h.provider.requests.size)
    }
    @Test fun `pauses do not trigger analysis and timers omit background and dead process gaps`() = runBlocking {
        val h = Harness(); h.configure(); h.seed(); val card = h.question()
        h.clock.advance(30_000); h.command(Command.SetTimer(card.session.id, TimerMode.BACKGROUND))
        h.clock.advance(60_000); h.command(Command.SetTimer(card.session.id, TimerMode.STUDY))
        h.clock.advance(30_000); h.command(Command.FinishSession(card.session.id, pause = true))
        assertEquals(60_000L, h.store.read().sessions[card.session.id]!!.studyMs)
        assertTrue(h.store.read().operations.values.none { it.feature == Feature.LEARNING_ANALYSIS })
        h.command(Command.StartSession()); h.clock.advance(DAY_MS); h.core.recover()
        assertEquals(60_000L, h.store.read().sessions[card.session.id]!!.studyMs)
    }
    @Test fun `disable fences a late automatic response while accounting actual usage`() = runBlocking {
        val h = Harness(); h.configure(); h.seed(); h.answer(); val op = h.finishDay()!!
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.provider.response = { entered.complete(Unit); release.await(); ProviderResponse(json("""{"patch":{"values":{"newTargetLimit":0},"reason":"reduce load"},"actions":[]}"""), 11, 12) }
        val job = launch { h.core.tasks.run(op.id) }; entered.await()
        val settings = h.store.read().settings; h.command(Command.SetSettings(settings.copy(automaticAnalysis = false), settings.version))
        release.complete(Unit); job.join()
        val state = h.store.read(); assertEquals(OperationStatus.CANCELLED, state.operations[op.id]!!.status)
        assertEquals(12, state.policy.newTargetLimit); assertTrue(state.changes.isEmpty()); assertEquals(12L, state.usage.values.single().outputTokens)
        h.provider.response = { ProviderResponse(json("""{"patch":{"values":{"newTargetLimit":0},"reason":"manual"}}""")) }
        val manual = h.command(Command.AnalyzeLearning()) as View.OperationResult; h.core.tasks.run(manual.operation.id)
        assertEquals(0, h.store.read().policy.newTargetLimit)
    }
    @Test fun `manual and automatic analysis merge identical evidence without a second charge`() = runBlocking {
        val h = Harness(); h.configure(); h.seed(); h.answer()
        val manual = h.command(Command.AnalyzeLearning()) as View.OperationResult
        assertEquals(manual.operation.id, h.finishDay()!!.id)
        h.core.tasks.runPending(true)
        assertEquals(1, h.provider.requests.size)
        assertEquals(manual.operation.id, (h.command(Command.AnalyzeLearning()) as View.OperationResult).operation.id)
    }
    @Test fun `delayed offline analyses coalesce and occupy actual execution day`() = runBlocking {
        val h = Harness(); h.configure(); h.seed(); h.answer(); val old = h.finishDay()!!
        h.core.tasks.runPending(false)
        h.clock.advance(DAY_MS); h.answer(); val latest = h.finishDay()!!
        assertEquals(OperationStatus.SUPERSEDED, h.store.read().operations[old.id]!!.status)
        h.core.tasks.runPending(true)
        val state = h.store.read(); assertEquals(1, h.provider.requests.size)
        assertEquals(state.days.values.last().id, state.operations[latest.id]!!.executionDayId)
    }
    @Test fun `undoing a changed policy preserves answers completed after application`() = runBlocking {
        val h = Harness(); h.seed(); h.answer()
        val changed = h.command(Command.ApplyPolicy(PolicyPatch(mapOf("retention" to json("0.92")), "user trial"), 1)) as View.OperationResult
        val second = h.answer(); assertEquals(1L, second.answer.policyVersion) // the already-started session is pinned
        val before = h.store.read().answers
        h.command(Command.UndoOperation(changed.operation.id))
        val state = h.store.read(); assertEquals(before, state.answers); assertEquals(0.9f, state.policy.retention)
        assertEquals(2, state.schedules.values.sumOf { it.reviews }); assertEquals(3L, state.policy.version)
    }
    @Test fun `new explicit policy changes block an old undo`() = runBlocking {
        val h = Harness()
        val first = h.command(Command.ApplyPolicy(PolicyPatch(mapOf("newTargetLimit" to json("4")), "first"), 1)) as View.OperationResult
        h.command(Command.ApplyPolicy(PolicyPatch(mapOf("newTargetLimit" to json("6")), "second"), 2))
        val result = h.core.execute(Request("undo-first", Command.UndoOperation(first.operation.id)))
        assertEquals(ErrorCode.VERSION_CONFLICT, result.fault?.code); assertEquals(6, h.store.read().policy.newTargetLimit)
    }
    @Test fun `hinted success and skipping are recorded without false recall ratings`() = runBlocking {
        val h = Harness(); h.seed(); val card = h.question(); val activity = h.store.read().activities[card.activity!!.id]!!
        val answer = h.answer(card, AnswerInput(activity.acceptedAnswers.first(), hintUsed = true))
        assertEquals(Verdict.CORRECT, answer.answer.verdict); assertFalse(answer.answer.affectsSchedule); assertEquals(0, answer.schedule!!.reviews)
        val skipped = h.answer(input = AnswerInput("", skipped = true)); assertFalse(skipped.answer.affectsSchedule)
        val snapshot = (h.core.query(Query.Overview()).value as View.Snapshot).snapshot
        assertEquals(2, snapshot.answers); assertEquals(0, snapshot.byAbility.values.sumOf { it.opportunities })
    }
    @Test fun `candidate optimization with insufficient real history keeps parameters`() = runBlocking {
        val h = Harness(); h.seed(); h.answer()
        val task = h.command(Command.FitMemory(applyIfBetter = true)) as View.OperationResult
        h.core.tasks.run(task.operation.id, false)
        val state = h.store.read(); assertFalse(state.evaluations.values.single().accepted); assertEquals(1L, state.policy.version)
    }
}
