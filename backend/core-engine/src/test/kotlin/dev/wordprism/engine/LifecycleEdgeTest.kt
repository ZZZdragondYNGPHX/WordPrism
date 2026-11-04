package dev.wordprism.engine

import dev.wordprism.contract.*
import java.time.Instant
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class LifecycleEdgeTest {
    @Test fun `AI action locks reject the entire mixed change atomically`() = runBlocking {
        val h = Harness(); h.configure(); h.seed(); h.answer()
        val word = h.store.read().words.values.first()
        val settings = h.store.read().settings
        h.command(Command.SetSettings(settings.copy(lockedCapabilities = setOf("update_word")), settings.version))
        val capabilities = (h.core.query(Query.Capabilities).value as View.Capabilities).capabilities
        assertTrue(capabilities.single { it.id == "update_word" }.userLocked)
        h.provider.response = { ProviderResponse(json("""{"patch":{"values":{"newTargetLimit":0},"reason":"atomic change"},"actions":[{"type":"update_word","word":{"id":"${word.id.value}","version":1},"notes":"must not save","tags":[]}]}""")) }
        val op = h.finishDay()!!; h.core.tasks.run(op.id)
        val state = h.store.read()
        assertEquals(ErrorCode.DISABLED, state.operations[op.id]!!.fault?.code)
        assertEquals(12, state.policy.newTargetLimit); assertEquals(word, state.words[word.id]); assertTrue(state.changes.isEmpty())
    }

    @Test fun `manual task covering an automatic request occupies its actual execution day`() = runBlocking {
        val h = Harness(); h.configure(); h.seed(); h.answer()
        val manual = (h.command(Command.AnalyzeLearning()) as View.OperationResult).operation
        assertEquals(manual.id, h.finishDay()!!.id)
        h.clock.advance(DAY_MS); h.core.tasks.run(manual.id)
        val executed = h.store.read().operations[manual.id]!!
        assertFalse(executed.automatic); assertNotNull(executed.executionDayId)
        h.answer(); val next = h.finishDay()!!; h.core.tasks.run(next.id)
        assertEquals(1, h.provider.requests.size)
        assertEquals(OperationStatus.SUPERSEDED, h.store.read().operations[next.id]!!.status)
    }

    @Test fun `repeated recommendation acceptance with new request keys shares one API task`() = runBlocking {
        val h = Harness(); h.configure(false); h.seed()
        val group = h.store.read().groups.values.single()
        h.provider.response = { ProviderResponse(json("""{"recommendations":[{"spelling":"linen","reason":"compare shape","groupId":"${group.id.value}"}]}""")) }
        val recommendations = h.command(Command.RequestRecommendations(Ref(group.id, group.version))) as View.OperationResult
        h.core.tasks.run(recommendations.operation.id)
        val selected = h.store.read().recommendations.keys.toList()
        val first = h.command(Command.AcceptRecommendations(selected, true)) as View.OperationResult
        val second = h.command(Command.AcceptRecommendations(selected, true)) as View.OperationResult
        assertEquals(first.operation.id, second.operation.id)
        h.provider.response = { ProviderResponse(json("""{"words":[{"spelling":"linen","meaning":"亚麻布"}],"reason":"selected by user"}""")) }
        h.core.tasks.runPending(true)
        assertEquals(2, h.provider.requests.size); assertEquals(3, h.store.read().words.size)
    }

    @Test fun `undoing analysis cancels its generated child and fences a late response`() = runBlocking {
        val h = Harness(); h.configure(); h.seed(); h.answer()
        val target = h.store.read().targets.values.first()
        h.provider.response = { ProviderResponse(json("""{"patch":{"values":{},"reason":"practice next context"},"actions":[{"type":"generate_activity","targetId":"${target.id.value}","kind":"PASSAGE"}]}""")) }
        val parent = h.finishDay()!!; h.core.tasks.run(parent.id)
        val child = h.store.read().operations.values.single { it.parentOperationId == parent.id }
        assertEquals(Actor.AI, child.actor)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.provider.response = { entered.complete(Unit); release.await(); ProviderResponse(json("""{"prompt":"A new short passage and question.","acceptedAnswers":["linear"],"explanation":"test"}"""), 8, 9) }
        val running = launch { h.core.tasks.run(child.id) }; entered.await()
        val before = h.store.read().activities
        h.command(Command.UndoOperation(parent.id)); release.complete(Unit); running.join()
        val state = h.store.read()
        assertEquals(OperationStatus.CANCELLED, state.operations[child.id]!!.status)
        assertEquals(before, state.activities)
        assertEquals(9L, state.usage.values.single { it.operationId == child.id }.outputTokens)
    }

    @Test fun `midnight splits actual learning time and attributes the answer to its occurrence day`() = runBlocking {
        val h = Harness(); h.clock.time.set(Instant.parse("2026-11-03T23:59:45Z").toEpochMilli()); h.seed()
        val card = h.question(); h.clock.advance(30_000); val result = h.answer(card)
        val state = h.store.read()
        val old = state.days.getValue(card.session.dayId); val current = state.days.getValue(result.answer.dayId!!)
        assertEquals("2026-11-03", old.date); assertEquals("2026-11-04", current.date)
        assertEquals(15_000L, state.studyTime(old.id)); assertEquals(15_000L, state.studyTime(current.id))
        val snapshot = (h.core.query(Query.Overview(current.opensAtMs)).value as View.Snapshot).snapshot
        assertEquals(15_000L, snapshot.studyMs); assertEquals("recorded_intervals", snapshot.timeSource)
        assertEquals(1, snapshot.activeDays); assertEquals(1, snapshot.answers)
    }

    @Test fun `a budget change allows finishing the current question but requires explicit continuation`() = runBlocking {
        val h = Harness(); h.seed(); val card = h.question()
        val settings = h.store.read().settings
        h.command(Command.SetSettings(settings.copy(budgetMinutes = 1), settings.version))
        h.clock.advance(65_000); h.answer(card)
        val paused = h.command(Command.NextActivity(card.session.id)) as View.SessionState
        assertEquals(SessionStatus.PAUSED, paused.session.status); assertNull(paused.activity)
        val blocked = h.core.execute(Request("budget-without-continuation", Command.StartSession()))
        assertEquals(ErrorCode.BUDGET_EXCEEDED, blocked.fault?.code)
        val continued = h.command(Command.StartSession(continuePastBudget = true)) as View.SessionState
        assertNotNull((h.command(Command.NextActivity(continued.session.id)) as View.SessionState).activity)
    }

    @Test fun `new sessions use updated sense content while old answer evidence remains unchanged`() = runBlocking {
        val h = Harness(); h.seed(); val card = h.question(); val answer = h.answer(card)
        h.command(Command.FinishSession(card.session.id))
        val before = h.store.read(); val target = before.targets.getValue(answer.answer.targetId); val sense = before.senses.getValue(target.senseId)
        h.command(Command.UpdateSense(Ref(sense.id, sense.version), "updated meaning", sense.acceptedForms, sense.examples))
        h.clock.advance(5 * DAY_MS)
        val next = h.question(); val state = h.store.read()
        assertEquals(target.id, next.activity!!.targetId); assertTrue(next.activity!!.prompt.contains("updated meaning"))
        assertEquals(2L, state.activities.getValue(next.activity!!.id).senseVersion)
        assertEquals(before.activityVersions, state.activityVersions.filterKeys { it in before.activityVersions })
        assertEquals(answer.answer, state.answers[answer.answer.id])
    }

    @Test fun `unavailable reading material is counted as deferred and never treated as learned`() = runBlocking {
        val h = Harness(); h.configure(false); h.seed(); val target = h.store.read().targets.values.first()
        h.provider.response = { ProviderResponse(json("""{"prompt":"Read and choose.","passage":"This synthetic passage describes a linear structure.","question":"Which structure is described?","choices":[{"id":"a","text":"linear"},{"id":"b","text":"circular"}],"acceptedAnswers":["linear"],"explanation":"A synthetic test."}""")) }
        val generation = h.command(Command.GenerateActivity(target.id, ActivityKind.PASSAGE)) as View.OperationResult
        h.core.tasks.run(generation.operation.id)
        assertEquals(OperationStatus.SUCCEEDED, h.store.read().operations[generation.operation.id]!!.status)
        h.command(Command.ApplyPolicy(PolicyPatch(mapOf("mix" to json("""{"PASSAGE":100}""")), "reading practice"), 1))
        val card = h.question(); val answer = h.answer(card, AnswerInput("linear")); h.command(Command.FinishSession(card.session.id))
        h.clock.advance(3 * DAY_MS)
        val day = h.core.query(Query.GetLearningDay()).value as View.Day
        assertTrue(day.plan!!.deferredCount > 0); assertFalse(answer.answer.targetId in day.plan!!.targetIds)
        assertEquals(1, h.store.read().answers.size)
    }

    @Test fun `rewrite mix is consumed by the offline planner and remains ungraded`() = runBlocking {
        val h = Harness(); h.seed()
        h.command(Command.ApplyPolicy(PolicyPatch(mapOf("mix" to json("""{"REWRITE":100}""")), "rewrite practice"), 1))
        val card = h.question(); assertEquals(ActivityKind.REWRITE, card.activity!!.kind)
        val answer = h.answer(card, AnswerInput("Synthetic rewritten sentence."))
        assertFalse(answer.answer.affectsSchedule); assertEquals(GradeSource.UNGRADED, answer.answer.gradeSource)
        assertTrue(h.provider.requests.isEmpty())
    }
}
