package dev.wordprism.engine

import dev.wordprism.contract.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.jupiter.api.Test

class TutoringTest {
    @Test fun `learning never calls tutor until explicit end of session review and repeats reuse it`() = runBlocking {
        val h = setup(); val shown = h.question(); h.answer(shown)
        assertTrue(h.store.read().operations.values.none { it.feature == Feature.TUTORING })
        h.command(Command.FinishSession(shown.session.id, pause = true))
        assertTrue(h.store.read().operations.values.none { it.feature == Feature.TUTORING })
        val view = h.core.query(Query.Tutoring()).value as View.Tutoring
        assertEquals(shown.session.id, view.reviewSessionId)
        val command = Command.TutorSession(shown.session.id, TutorMoment.RECAP)
        val first = (h.command(command) as View.OperationResult).operation
        assertEquals(Actor.USER, first.actor)
        assertEquals(first.id, (h.command(command) as View.OperationResult).operation.id)
        h.reply(TutorReport("本轮先巩固词尾区别。")); h.core.tasks.run(first.id)
        assertEquals(first.id, (h.command(command) as View.OperationResult).operation.id)
        assertEquals(1, h.provider.requests.size)
    }

    @Test fun `upgrade cancels legacy unattended tutor jobs but preserves requested questions`() = runBlocking {
        val h = setup(); h.answer(); val legacy = h.tutor(TutorMoment.COACH)
        h.core.recover()
        assertEquals(OperationStatus.CANCELLED, h.store.read().operations.getValue(legacy.id).status)
        h.core.tasks.run(legacy.id)
        assertTrue(h.provider.requests.isEmpty())
    }

    @Test fun `tutor can prioritize only remaining lesson goals and preserves historical grades`() = runBlocking {
        val h = setup(); val answer = h.answer().answer; val op = h.tutor(TutorMoment.COACH)
        val before = h.store.read(); val session = before.sessions.getValue(answer.sessionId)
        val remaining = session.targetIds.filter { it !in session.completedTargets }.reversed()
        assertTrue(remaining.size > 1)
        h.reply(TutorReport("接下来先辨析，再回忆。", nextTargets = remaining))
        h.core.tasks.run(op.id)
        val after = h.store.read(); val updated = after.sessions.getValue(session.id)
        assertEquals(remaining, updated.targetIds.filter { it !in updated.completedTargets })
        assertEquals(before.answers, after.answers); assertEquals(before.schedules, after.schedules)
    }
    private suspend fun setup(): Harness = Harness().also { h ->
        h.configure(auto = false); h.seed()
        val s = h.store.read().settings
        h.command(Command.SetLearningMode(LearningMode.AI_MANAGED, s.version))
    }
    // Historical coach reports remain readable and validated; new learning events never enqueue them.
    private suspend fun Harness.tutor(moment: TutorMoment): Operation = store.transaction { db ->
        val answer = db.answers.values.maxBy { it.sequence }
        db.requestTutor(Command.TutorSession(answer.sessionId, moment, answer = Ref(answer.id, answer.version)), clock.millis(), automatic = true)
    }
    private fun Harness.reply(report: TutorReport) {
        provider.response = { ProviderResponse(Contract.json.encodeToJsonElement(report), 12, 24) }
    }

    @Test fun `question is durable idempotent contextual and marks assistance before any answer`(): Unit = runBlocking {
        val h = setup(); val shown = h.question()
        val request = Command.TutorSession(shown.session.id, TutorMoment.QUESTION, shown.session.currentPresentationId, question = "请只提示区别")
        val op = (h.command(request, "same-question") as View.OperationResult).operation
        assertEquals(op.id, (h.command(request, "same-question") as View.OperationResult).operation.id)
        h.reply(TutorReport("先看词尾，再想它描述的是性质还是物品。")); h.core.tasks.run(op.id)
        val data = h.provider.requests.last().data.jsonObject.getValue("tutoring").jsonObject
        assertFalse("acceptedAnswers" in data.getValue("activity").jsonObject)
        assertEquals("请只提示区别", data.getValue("question").jsonPrimitive.content)
        val answer = h.answer(shown).answer
        assertTrue(answer.input.hintUsed); assertFalse(answer.affectsSchedule)
        assertEquals(1, h.provider.requests.size)
        assertNotNull(h.store.read().jobs.getValue(op.id).tutorReport)
    }

    @Test fun `coaching remembers cited mistakes and drives targeted content without rewriting scores`() = runBlocking {
        val h = setup(); val answer = h.answer(input = AnswerInput("wrong answer")).answer
        val op = h.tutor(TutorMoment.COACH)
        val before = h.store.read(); val ref = Ref(answer.id, answer.version)
        h.reply(TutorReport("这次把词形线索混在一起了，先对照词尾。", "能用自己的话说出区别吗？", "本次混淆可能来自词尾，下一题换一个语境检验。", listOf(ref), ActivityKind.RECALL))
        h.core.tasks.run(op.id)
        val after = h.store.read()
        assertEquals(before.answers, after.answers); assertEquals(before.schedules, after.schedules)
        assertEquals(1, after.tutorMemory().size)
        val child = after.operations.values.single { it.parentOperationId == op.id }
        assertEquals(Command.GenerateActivity(answer.targetId, ActivityKind.RECALL), after.jobs.getValue(child.id).command)
        h.provider.response = { ProviderResponse(json("""{"prompt":"针对本次区别的新语境","acceptedAnswers":["linear"],"explanation":"解释"}""")) }
        h.core.tasks.run(child.id)
        val prompt = h.provider.requests.last().data.jsonObject
        assertTrue(prompt.getValue("learnerMemory").jsonArray.isNotEmpty())
        assertTrue(prompt.getValue("teachingGoal").jsonPrimitive.content.contains("词尾"))
        assertTrue(prompt.getValue("learningEvidence").jsonArray.any { it.jsonObject["id"]?.jsonPrimitive?.content == answer.id.value })
    }

    @Test fun `learner can ask about a submitted answer and transcript is carried into followup`() = runBlocking {
        val h = setup(); val shown = h.question(); val answer = h.answer(shown).answer
        val command = Command.TutorSession(shown.session.id, TutorMoment.QUESTION, shown.session.currentPresentationId, Ref(answer.id, answer.version), "为什么？")
        val op = (h.command(command) as View.OperationResult).operation
        h.reply(TutorReport("我们换一个具体例子来理解。")); h.core.tasks.run(op.id)
        val second = (h.command(command.copy(question = "还是不理解，再换个讲法")) as View.OperationResult).operation
        h.core.tasks.run(second.id)
        val context = h.provider.requests.last().data.jsonObject.getValue("tutoring").jsonObject
        assertTrue(context.getValue("conversation").jsonArray.any { it.jsonObject["learner"]?.jsonPrimitive?.content == "为什么？" })
        assertEquals(answer, h.store.read().answers.getValue(answer.id))
    }

    @Test fun `fabricated evidence and outside lesson targets roll back all tutor effects`() = runBlocking {
        val h = setup(); val answer = h.answer().answer; val op = h.tutor(TutorMoment.COACH)
        val before = h.store.read().sessions
        h.reply(TutorReport("假设的反馈", observation = "没有证据的印象", evidence = listOf(Ref(Id("invented"), 1)), nextTargets = listOf(Id("invented-target"))))
        h.core.tasks.run(op.id)
        val db = h.store.read()
        assertEquals(OperationStatus.FAILED, db.operations.getValue(op.id).status)
        assertNull(db.jobs.getValue(op.id).tutorReport); assertEquals(before, db.sessions)
        assertTrue(db.tutorMemory().isEmpty()); assertEquals(answer, db.answers[answer.id])
    }

    @Test fun `late hint never attaches to an answered or different question`() = runBlocking {
        val h = setup(); val shown = h.question()
        val op = (h.command(Command.TutorSession(shown.session.id, TutorMoment.QUESTION, shown.session.currentPresentationId, question = "给提示")) as View.OperationResult).operation
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.provider.response = { entered.complete(Unit); release.await(); ProviderResponse(Contract.json.encodeToJsonElement(TutorReport("迟到的提示")), 8, 9) }
        val job = launch { h.core.tasks.run(op.id) }; entered.await()
        h.answer(shown); release.complete(Unit); job.join()
        val db = h.store.read()
        assertNull(db.jobs.getValue(op.id).tutorReport)
        assertEquals(ErrorCode.VERSION_CONFLICT, db.operations.getValue(op.id).fault?.code)
        assertEquals(9, db.usage.values.single { it.operationId == op.id }.outputTokens)
    }

    @Test fun `turning off mode cancels late teaching and keeps actual usage`() = runBlocking {
        val h = setup(); h.answer(); val op = h.tutor(TutorMoment.COACH)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.provider.response = { entered.complete(Unit); release.await(); ProviderResponse(Contract.json.encodeToJsonElement(TutorReport("旧点评")), 5, 6) }
        val job = launch { h.core.tasks.run(op.id) }; entered.await()
        h.command(Command.SetLearningMode(LearningMode.STANDARD, h.store.read().settings.version))
        release.complete(Unit); job.join()
        assertNull(h.store.read().jobs.getValue(op.id).tutorReport)
        assertEquals(OperationStatus.CANCELLED, h.store.read().operations.getValue(op.id).status)
        assertEquals(6, h.store.read().usage.values.single { it.operationId == op.id }.outputTokens)
    }

    @Test fun `disputed evidence expires memory and backup preserves tutor records without restarting requests`() = runBlocking {
        val h = setup(); val shown = h.question(); val answer = h.answer(shown).answer; val op = h.tutor(TutorMoment.COACH)
        h.reply(TutorReport("先分清词尾", observation = "词尾值得再练", evidence = listOf(Ref(answer.id, answer.version))))
        h.core.tasks.run(op.id)
        val output = ByteArrayOutputStream(); assertNull(h.core.exportPackage(PackagePurpose.BACKUP, output).fault)
        val restored = Harness()
        val preview = (restored.core.previewPackage(ByteArrayInputStream(output.toByteArray())).value as View.ImportPreview).preview
        restored.command(Command.RestoreBackup(preview.token))
        val db = restored.store.read()
        assertNotNull(db.jobs.getValue(op.id).tutorReport); assertEquals(LearningMode.STANDARD, db.settings.learningMode)
        assertEquals(1, db.tutorMemory().size)
        h.command(Command.DisputeActivity(Ref(answer.activityId, answer.activityVersion), "题目存在歧义"))
        assertTrue(h.store.read().tutorMemory().isEmpty())
    }

    @Test fun `session and answer events are deduplicated and failed tutor never loops`() = runBlocking {
        val h = setup(); val shown = h.question(); h.answer(shown)
        val op = h.tutor(TutorMoment.COACH)
        h.provider.response = { throw ProviderException(Fault(ErrorCode.OUTCOME_UNKNOWN, "未知结果")) }
        h.core.tasks.run(op.id); repeat(3) { h.core.recover() }
        h.command(Command.FinishSession(shown.session.id, pause = true))
        h.command(Command.FinishSession(shown.session.id, pause = true))
        val db = h.store.read(); val moments = db.jobs.values.mapNotNull { (it.command as? Command.TutorSession)?.moment }
        assertEquals(1, moments.count { it == TutorMoment.COACH }); assertEquals(0, moments.count { it == TutorMoment.RECAP })
        assertEquals(1, h.provider.requests.size); assertEquals(OperationStatus.FAILED, db.operations.getValue(op.id).status)
    }
}
