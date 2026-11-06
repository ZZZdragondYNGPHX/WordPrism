package dev.wordprism.engine

import dev.wordprism.contract.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class ManagedLearningTest {
    private suspend fun Harness.mode(mode: LearningMode = LearningMode.AI_MANAGED) {
        command(Command.SetLearningMode(mode, store.read().settings.version))
    }
    private fun Harness.script() {
        provider.response = { request ->
            val data = request.data.jsonObject
            val cmd = data.getValue("request").jsonObject
            ProviderResponse(when (cmd.getValue("type").jsonPrimitive.content) {
                "submit_intake" -> json("""{"words":[{"spelling":"linear","meaning":"线性的","partOfSpeech":"adjective"},{"spelling":"liner","meaning":"衬里","partOfSpeech":"noun"}],"reason":"用户提供的易混词"}""")
                "explain_sense" -> json("""{"content":{"explanation":"先记住各自含义，再在新语境中回忆。","examples":["This is a linear model."]},"recommendations":[]}""")
                "generate_activity" -> {
                    val word = data.getValue("word").jsonObject.getValue("spelling").jsonPrimitive.content
                    if (cmd.getValue("kind").jsonPrimitive.content == "PASSAGE")
                        json("""{"prompt":"Read and choose.","passage":"The example describes a $word. Read the context carefully.","question":"Which word fits?","choices":[{"id":"a","text":"$word"},{"id":"b","text":"other"}],"acceptedAnswers":["a"],"explanation":"由语境确定。"}""")
                    else json("""{"prompt":"Recall $word in a new context.","acceptedAnswers":["$word"],"explanation":"根据释义回忆。"}""")
                }
                else -> json("""{"patch":{"values":{},"reason":"根据当前作答保持计划"},"actions":[]}""")
            }, 10, 20)
        }
    }
    private suspend fun Harness.smallMix() {
        command(Command.ApplyPolicy(PolicyPatch(mapOf("mix" to json("""{"RECALL":70,"PASSAGE":30}""")), "测试两种学习能力"), store.read().policy.version))
    }
    private suspend fun Harness.drain() {
        repeat(20) {
            if (store.read().operations.values.none { it.status in pendingStatuses }) return
            core.tasks.runPending(true)
        }
        fail("托管流程未收敛")
    }

    @Test fun `one API configuration and intake prepare content without manual routing or choices`() = runBlocking {
        val h = Harness(); h.script(); h.smallMix(); h.mode()
        val preset = ApiPreset(Id("managed-api"), name = "My AI", baseUrl = "https://example.invalid/v1", model = "test")
        h.command(Command.SavePreset(preset))
        h.core.setCredential(preset.id, "synthetic-test-credential".toCharArray())
        assertEquals(preset.id, h.store.read().settings.defaultPresetId)
        assertTrue(h.store.read().bindings.isEmpty())
        val root = (h.command(Command.SubmitIntake("linear, liner"), "add-first-group") as View.OperationResult).operation.id
        h.drain()
        val db = h.store.read()
        assertEquals(2, db.words.size); assertEquals(1, db.groups.size)
        assertTrue(db.senses.values.all { it.explanation != null })
        assertEquals(4, db.activities.size)
        assertTrue(db.operations.values.none { it.status == OperationStatus.FAILED || it.status == OperationStatus.NEEDS_INPUT })
        assertTrue(db.operations.values.filter { it.automatic }.all { it.actor == Actor.AI && it.managed && it.parentOperationId == root })
        val count = h.provider.requests.size
        h.command(Command.SubmitIntake("linear, liner"), "add-first-group")
        h.core.recover(); h.drain()
        assertEquals(count, h.provider.requests.size)
        assertEquals(LearningMode.AI_MANAGED, (h.core.query(Query.ManagedLearning).value as View.ManagedLearning).state.mode)
    }

    @Test fun `disabling managed mode fences a late explanation and preserves ordinary preferences`() = runBlocking {
        val h = Harness(); h.configure(false); h.smallMix(); h.seed(); h.mode()
        val op = h.store.read().operations.values.first { it.feature == Feature.EXPLANATION }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.provider.response = { entered.complete(Unit); release.await(); ProviderResponse(json("""{"content":{"explanation":"late"}}"""), 1, 2) }
        val task = launch { h.core.tasks.run(op.id) }; entered.await()
        h.mode(LearningMode.STANDARD); release.complete(Unit); task.join()
        val db = h.store.read()
        assertTrue(db.senses.values.all { it.explanation == null })
        assertFalse(db.settings.automaticAnalysis); assertFalse(db.settings.automaticGeneration)
        assertEquals(OperationStatus.CANCELLED, db.operations.getValue(op.id).status)
        assertEquals(2, db.words.size)
        assertEquals(2L, db.usage.values.single().outputTokens)
    }

    @Test fun `offline preparation survives recovery without duplicate operations`() = runBlocking {
        val h = Harness(); h.configure(); h.smallMix(); h.seed(); h.mode(); h.script()
        val ids = h.store.read().operations.keys.toSet()
        h.core.tasks.runPending(false)
        assertTrue(h.store.read().operations.values.any { it.status == OperationStatus.WAITING_NETWORK })
        assertTrue(h.provider.requests.isEmpty())
        h.core.recover()
        assertEquals(ids, h.store.read().operations.keys.toSet())
        h.drain()
        assertEquals(6, h.provider.requests.size)
    }

    @Test fun `unknown provider outcomes stay failed and paid work is not repeated`() = runBlocking {
        val h = Harness(); h.configure(); h.smallMix(); h.seed(); h.mode()
        h.provider.response = { throw ProviderException(Fault(ErrorCode.OUTCOME_UNKNOWN, "unknown")) }
        h.drain()
        val count = h.provider.requests.size
        h.core.recover(); h.drain()
        assertEquals(count, h.provider.requests.size)
        assertTrue(h.store.read().operations.values.filter { it.managed }.all { it.status == OperationStatus.FAILED })
    }

    @Test fun `adding and preparing a group can be undone without deleting real answers`() = runBlocking {
        val h = Harness(); h.configure(); h.smallMix(); h.mode(); h.script()
        val root = (h.command(Command.SubmitIntake("linear, liner")) as View.OperationResult).operation.id
        h.drain(); h.answer()
        val answers = h.store.read().answers
        h.command(Command.UndoOperation(root))
        val db = h.store.read()
        assertEquals(answers, db.answers)
        assertTrue(db.words.values.all { it.archived })
        assertTrue(db.operations.values.none { it.managed && it.status in pendingStatuses })
    }

    @Test fun `pausing managed study schedules daily analysis even with normal analysis disabled`() = runBlocking {
        val h = Harness(); h.configure(false); h.smallMix(); h.seed(); h.mode(); h.script(); h.drain()
        val answer = h.answer()
        h.command(Command.FinishSession(answer.answer.sessionId, pause = true))
        assertEquals(1, h.store.read().operations.values.count { it.feature == Feature.LEARNING_ANALYSIS })
        h.drain()
        h.command(Command.FinishSession(answer.answer.sessionId, pause = true))
        assertEquals(1, h.store.read().operations.values.count { it.feature == Feature.LEARNING_ANALYSIS })
        assertEquals(1, h.store.read().answers.size)
    }

    @Test fun `backup restores data with managed execution disabled and no device credential references`() = runBlocking {
        val h = Harness(); h.configure(); h.smallMix(); h.seed(); h.mode(); h.script(); h.drain()
        val output = ByteArrayOutputStream(); assertNull(h.core.exportPackage(PackagePurpose.BACKUP, output).fault)
        val credentialRef = h.store.read().credentialRefs.values.first().value
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (zip.nextEntry != null) assertFalse(zip.readBytes().toString(Charsets.UTF_8).contains(credentialRef))
        }
        val restored = Harness()
        val preview = restored.core.previewPackage(ByteArrayInputStream(output.toByteArray())).value as View.ImportPreview
        restored.command(Command.RestoreBackup(preview.preview.token))
        assertEquals(LearningMode.STANDARD, restored.store.read().settings.learningMode)
        restored.core.tasks.runPending(true)
        assertTrue(restored.provider.requests.isEmpty())
        assertEquals(h.store.read().words, restored.store.read().words)
    }

    @Test fun `old settings decode as ordinary mode`() {
        assertEquals(LearningMode.STANDARD, Contract.json.decodeFromString<UserSettings>("{}").learningMode)
    }

    @Test fun `ambiguous intake keeps original spelling and creates a group without asking`() = runBlocking {
        val h = Harness(); h.configure(); h.seed(); h.mode()
        val group = h.store.read().groups.values.single()
        h.provider.response = { ProviderResponse(json("""{"words":[{"spelling":"linera","meaning":"用户原词"}],"groupId":"${group.id.value}","candidates":[{"groupId":"${group.id.value}","reason":"可能相关"}],"corrections":[{"original":"linera","suggested":{"spelling":"linear","meaning":"线性的"},"reason":"可能拼错"}],"reason":"保留原词"}""")) }
        val op = (h.command(Command.SubmitIntake("linera")) as View.OperationResult).operation
        h.core.tasks.run(op.id)
        val db = h.store.read()
        assertEquals(OperationStatus.SUCCEEDED, db.operations.getValue(op.id).status)
        assertTrue(db.words.values.any { it.spelling == "linera" })
        assertEquals(2, db.groups.size)
        assertEquals(group, db.groups[group.id])
    }

    @Test fun `locked capabilities and daily quota bound preparation and quota resumes next day`() = runBlocking {
        val h = Harness(); h.configure(); h.smallMix(); h.seed()
        val settings = h.store.read().settings
        h.command(Command.SetSettings(settings.copy(lockedCapabilities = setOf("explain_sense"), dailyRequestLimit = 1), settings.version))
        h.mode(); h.script(); h.drain()
        assertEquals(1, h.provider.requests.size)
        assertTrue(h.store.read().operations.values.none { it.feature == Feature.EXPLANATION })
        assertEquals(2, h.store.read().operations.values.count { it.managed })
        h.clock.advance(DAY_MS); h.core.recover(); h.drain()
        assertEquals(2, h.provider.requests.size)
        assertEquals(1, h.store.read().usage.values.count { it.startedAtMs == h.clock.millis() })
    }

    @Test fun `credential replacement resumes a known rejected request only once`() = runBlocking {
        val h = Harness(); h.configure(); h.smallMix(); h.seed(); h.mode()
        h.provider.response = { throw ProviderException(Fault(ErrorCode.PROVIDER_AUTH, "rejected")) }
        h.drain()
        val count = h.provider.requests.size
        h.core.recover(); h.drain(); assertEquals(count, h.provider.requests.size)
        h.script(); h.core.setCredential(Id("test-preset"), "replacement-synthetic-credential".toCharArray())
        h.drain()
        assertTrue(h.store.read().senses.values.all { it.explanation != null })
        assertTrue(h.provider.requests.size > count)
    }

    @Test fun `managed teaching support stays on introduction cards and never credits mastery`() = runBlocking {
        val h = Harness(); h.configure(); h.smallMix(); h.seed(); h.mode(); h.script(); h.drain()
        val session = (h.command(Command.StartSession(includeIntroductions = true)) as View.SessionState).session
        val first = h.command(Command.NextActivity(session.id)) as View.SessionState
        val a = h.answer(first, AnswerInput("", familiar = false))
        assertFalse(a.answer.affectsSchedule)
        val introduction = h.command(Command.NextActivity(session.id)) as View.SessionState
        assertNotNull(introduction.activity?.introduction?.support)
        val ack = h.answer(introduction, AnswerInput(""))
        assertFalse(ack.answer.affectsSchedule)
        val recall = h.command(Command.NextActivity(session.id)) as View.SessionState
        assertNull(recall.activity?.introduction)
        assertFalse(h.answer(recall).answer.affectsSchedule)
    }
}
