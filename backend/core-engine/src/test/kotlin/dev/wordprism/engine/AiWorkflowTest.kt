package dev.wordprism.engine

import dev.wordprism.contract.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test

class AiWorkflowTest {
    @Test fun `intake repeats reuse words and senses and extra words stay recommendations`() = runBlocking {
        val h = Harness(); h.configure(false)
        h.provider.response = { ProviderResponse(json("""{"words":[{"spelling":"linear","meaning":"线性的"},{"spelling":"liner","meaning":"衬里"}],"newGroup":{"title":"linear / liner","relation":"LOOK_ALIKE","purpose":"词形辨析"},"reason":"用户明确易混","recommendations":[{"spelling":"linen","reason":"可以进一步对比","content":{"spelling":"linen","meaning":"亚麻布"}}]}""")) }
        val first = h.command(Command.SubmitIntake("linear / liner"), "intake-once") as View.OperationResult
        h.core.tasks.run(first.operation.id)
        assertEquals(2, h.store.read().words.size); assertEquals(1, h.store.read().recommendations.size)
        h.command(Command.SubmitIntake("linear / liner"), "intake-once"); assertEquals(1, h.provider.requests.size)
        val again = h.command(Command.SubmitIntake("linear / liner")) as View.OperationResult; h.core.tasks.run(again.operation.id)
        assertEquals(2, h.store.read().words.size); assertEquals(2, h.store.read().senses.size); assertEquals(1, h.store.read().groups.size)
        val recommendation = h.store.read().recommendations.values.single()
        h.core.query(Query.Recommendations()); h.core.query(Query.PreviewCall(Feature.WORD_INTAKE, listOf(recommendation.id)))
        assertEquals(2, h.provider.requests.size)
        val accepted = h.command(Command.AcceptRecommendations(listOf(recommendation.id), false)) as View.OperationResult
        assertEquals(3, h.store.read().words.size); assertEquals(2, h.provider.requests.size)
        h.command(Command.UndoOperation(accepted.operation.id)); assertEquals(2, h.store.read().words.values.count { !it.archived })
    }
    @Test fun `cancelled intake cannot write late content and still keeps usage`() = runBlocking {
        val h = Harness(); h.configure(false)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.provider.response = { entered.complete(Unit); release.await(); ProviderResponse(json("""{"words":[{"spelling":"linear","meaning":"线性的"}],"reason":"requested"}"""), 13, 14) }
        val operation = h.command(Command.SubmitIntake("linear")) as View.OperationResult
        val task = launch { h.core.tasks.run(operation.operation.id) }; entered.await()
        h.command(Command.CancelOperation(operation.operation.id)); release.complete(Unit); task.join()
        assertTrue(h.store.read().words.isEmpty()); assertEquals(14L, h.store.read().usage.values.single().outputTokens)
    }
    @Test fun `provider cannot add unrequested recommendation words directly`() = runBlocking {
        val h = Harness(); h.configure(false)
        h.provider.response = { ProviderResponse(json("""{"words":[{"spelling":"linear","meaning":"线性的"},{"spelling":"linen","meaning":"亚麻布"}],"reason":"extra"}""")) }
        val operation = h.command(Command.SubmitIntake("linear")) as View.OperationResult; h.core.tasks.run(operation.operation.id)
        assertTrue(h.store.read().words.isEmpty()); assertEquals(ErrorCode.INVALID_RESPONSE, h.store.read().operations[operation.operation.id]!!.fault?.code)
    }
    @Test fun `preset edits during a request preserve actual source version`() = runBlocking {
        val h = Harness(); h.configure(false)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.provider.response = { entered.complete(Unit); release.await(); ProviderResponse(json("""{"words":[{"spelling":"linear","meaning":"线性的"}],"reason":"requested"}""")) }
        val op = h.command(Command.SubmitIntake("linear")) as View.OperationResult
        val task = launch { h.core.tasks.run(op.operation.id) }; entered.await()
        val old = h.store.read().presets.values.single(); h.command(Command.SavePreset(old.copy(model = "new-model"), old.version))
        release.complete(Unit); task.join()
        val state = h.store.read(); assertEquals(1L, state.words.values.single().source.presetVersion)
        assertEquals("test-model", state.usage.values.single().preset.model); assertEquals(2L, state.presets.values.single().version)
    }
    @Test fun `group edits in flight reject all generated changes atomically`() = runBlocking {
        val h = Harness(); h.configure(false); h.seed()
        val group = h.store.read().groups.values.single(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.provider.response = { entered.complete(Unit); release.await(); ProviderResponse(json("""{"words":[{"spelling":"linen","meaning":"亚麻布"}],"reason":"requested"}""")) }
        val op = h.command(Command.SubmitIntake("linen", Ref(group.id, group.version))) as View.OperationResult
        val task = launch { h.core.tasks.run(op.operation.id) }; entered.await()
        val member = h.store.read().memberships.values.first(); h.command(Command.ChangeMembership(Ref(group.id, group.version), member.senseId, false, "user correction"))
        release.complete(Unit); task.join()
        assertEquals(2, h.store.read().words.size); assertEquals(ErrorCode.VERSION_CONFLICT, h.store.read().operations[op.operation.id]!!.fault?.code)
    }
    @Test fun `ambiguous grouping needs selected stable group and preserves pending result`() = runBlocking {
        val h = Harness(); h.configure(false); h.seed(); val group = h.store.read().groups.values.single()
        h.provider.response = { ProviderResponse(json("""{"words":[{"spelling":"linen","meaning":"亚麻布"}],"candidates":[{"groupId":"${group.id.value}","reason":"possible relation"}],"reason":"ambiguous"}""")) }
        val op = h.command(Command.SubmitIntake("linen")) as View.OperationResult; h.core.tasks.run(op.operation.id)
        assertEquals(OperationStatus.NEEDS_INPUT, h.store.read().operations[op.operation.id]!!.status); assertEquals(2, h.store.read().words.size)
        h.command(Command.ResolveIntake(op.operation.id, listOf(Ref(group.id, group.version))))
        assertEquals(3, h.store.read().words.size); assertEquals(3, h.store.read().memberships.values.count { it.active })
    }
    @Test fun `open feedback belongs to original answer after revision without scheduling it`() = runBlocking {
        val h = Harness(); h.configure(false); h.seed()
        val target = h.store.read().targets.values.first()
        h.provider.response = { ProviderResponse(json("""{"prompt":"Use linear in a sentence","explanation":"Practice expression"}""")) }
        val generation = h.command(Command.GenerateActivity(target.id, ActivityKind.WRITING)) as View.OperationResult; h.core.tasks.run(generation.operation.id)
        h.command(Command.ApplyPolicy(PolicyPatch(mapOf("mix" to json("""{"WRITING":100}""")), "writing only"), 1))
        val card = h.question(); val answer = h.answer(card, AnswerInput("The graph is linear."))
        assertFalse(answer.answer.affectsSchedule)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.provider.response = { entered.complete(Unit); release.await(); ProviderResponse(json("""{"explanation":"Correct use","targetUsage":"appropriate","quotedAnswer":"is linear"}""")) }
        val evaluation = h.command(Command.EvaluateResponse(Ref(answer.answer.id, 1))) as View.OperationResult
        val task = launch { h.core.tasks.run(evaluation.operation.id) }; entered.await()
        h.command(Command.ReviseAnswer(Ref(answer.answer.id, 1), "The relationship is linear.")); release.complete(Unit); task.join()
        assertEquals(1L, h.store.read().feedback.values.single().answerVersion)
        assertTrue((h.core.query(Query.GetFeedback(answer.answer.id)).value as View.Feedbacks).feedback.isEmpty())
        assertEquals(2, h.store.read().answerVersions.size); assertEquals(0, h.store.read().schedules.values.sumOf { it.reviews })
        assertEquals(0, (h.core.query(Query.Overview()).value as View.Snapshot).snapshot.details.expressionUsage.opportunities)
        h.provider.response = { ProviderResponse(json("""{"explanation":"Valid usage","targetUsage":"good","quotedAnswer":"is linear"}""")) }
        val current = h.command(Command.EvaluateResponse(Ref(answer.answer.id, 2))) as View.OperationResult
        h.core.tasks.run(current.operation.id)
        val overview = (h.core.query(Query.Overview()).value as View.Snapshot).snapshot
        assertEquals(Metric(1, 1), overview.details.expressionUsage)
        assertEquals(0, overview.delayedRecall.opportunities)
        val replay = h.command(Command.EvaluateResponse(Ref(answer.answer.id, 2))) as View.OperationResult
        assertEquals(current.operation.id, replay.operation.id)
        h.command(Command.ReviseAnswer(Ref(answer.answer.id, 2), "This graph is linear."))
        h.provider.response = { ProviderResponse(json("""{"explanation":"Malformed category","targetUsage":"anything","quotedAnswer":"is linear"}""")) }
        val malformed = h.command(Command.EvaluateResponse(Ref(answer.answer.id, 3))) as View.OperationResult
        h.core.tasks.run(malformed.operation.id)
        assertEquals(ErrorCode.INVALID_RESPONSE, h.store.read().operations.getValue(malformed.operation.id).fault?.code)
    }
}
