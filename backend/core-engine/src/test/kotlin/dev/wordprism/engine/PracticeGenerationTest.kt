package dev.wordprism.engine

import dev.wordprism.contract.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class PracticeGenerationTest {
    @Test fun `legacy practice settings do not block other settings or analysis changes`() = runBlocking {
        val h = Harness(); h.configure(false); h.seed()
        h.store.transaction { db ->
            db.settings = db.settings.copy(preGenerationMaxPerDay = 0, lockedCapabilities = setOf("preGenerateLimit"))
            db.policy = db.policy.copy(preGenerateLimit = 10)
            db.policyVersions[db.policy.version] = db.policy
        }
        val settings = h.store.read().settings
        h.command(Command.SetSettings(settings.copy(budgetMinutes = 35), settings.version))
        h.provider.response = { ProviderResponse(Contract.json.encodeToJsonElement(AnalysisProposal(
            PolicyPatch(mapOf("newTargetLimit" to JsonPrimitive(8)), "调整学习计划")))) }
        val analysis = (h.command(Command.AnalyzeLearning(true)) as View.OperationResult).operation
        h.core.tasks.run(analysis.id)
        val state = h.store.read()
        assertEquals(OperationStatus.SUCCEEDED, state.operations.getValue(analysis.id).status)
        assertEquals(8, state.policy.newTargetLimit)
        validateDatabase(state, h.memory)
    }

    @Test fun `daily analysis and its practice run even when legacy automatic limits are zero`() = runBlocking {
        val h = Harness(); h.configure(); h.seed(); h.answer()
        val settings = h.store.read().settings
        h.command(Command.SetSettings(settings.copy(automaticRequestsPerDay = 0, preGenerationMaxPerDay = 0), settings.version))
        val target = h.store.read().targets.values.first()
        h.provider.response = { ProviderResponse(Contract.json.encodeToJsonElement(AnalysisProposal(
            PolicyPatch(emptyMap(), "补题"), listOf(Command.GenerateActivity(target.id, ActivityKind.WRITING))))) }
        val parent = h.finishDay()!!
        h.core.tasks.run(parent.id)
        assertEquals(OperationStatus.SUCCEEDED, h.store.read().operations.getValue(parent.id).status)
        val child = h.store.read().operations.values.single { it.parentOperationId == parent.id }
        h.provider.response = { ProviderResponse(json("""{"prompt":"Use the word.","acceptedAnswers":[],"explanation":"Synthetic."}""")) }
        h.core.tasks.run(child.id)
        assertEquals(OperationStatus.SUCCEEDED, h.store.read().operations.getValue(child.id).status)
    }

    @Test fun `analysis can add all AI selected exercises without the old switch or separate limits`() = runBlocking {
        val h = Harness(); h.configure(false)
        h.command(Command.SaveWords((1..4).map { WordSeed("word$it", "释义$it", "noun") }))
        val settings = h.store.read().settings
        h.command(Command.SetSettings(settings.copy(automaticRequestsPerDay = 0), settings.version))
        val before = h.store.read()
        assertFalse(before.settings.automaticGeneration); assertEquals(0, before.policy.preGenerateLimit)
        val actions = before.targets.values.take(11).map { Command.GenerateActivity(it.id, it.ability.kind()) }
        h.provider.response = { request ->
            assertTrue(request.system.contains("use generate_activity"))
            assertFalse(request.data.jsonObject.getValue("policy").jsonObject.containsKey("preGenerateLimit"))
            val userSettings = request.data.jsonObject.getValue("userSettings").jsonObject
            assertFalse(userSettings.containsKey("automaticRequestsPerDay")); assertFalse(userSettings.containsKey("preGenerationMaxPerDay"))
            ProviderResponse(Contract.json.encodeToJsonElement(AnalysisProposal(PolicyPatch(emptyMap(), "按学习情况补题"), actions)))
        }
        val parent = (h.command(Command.AnalyzeLearning(true)) as View.OperationResult).operation
        h.core.tasks.run(parent.id)
        val queued = h.store.read()
        val children = queued.operations.values.filter { it.parentOperationId == parent.id }
        assertEquals(OperationStatus.SUCCEEDED, queued.operations.getValue(parent.id).status)
        assertEquals(11, children.size); assertTrue(children.all { it.status == OperationStatus.QUEUED })
        assertContains(queued.operations.getValue(parent.id).summary, "11 项补题任务")

        h.provider.response = { request ->
            val kind = request.data.jsonObject.getValue("request").jsonObject.getValue("kind").jsonPrimitive.content
            val accepted = if (kind == "WRITING" || kind == "REWRITE") "[]" else "[\"word\"]"
            ProviderResponse(json("""{"prompt":"A synthetic exercise.","acceptedAnswers":$accepted,"explanation":"Synthetic feedback."}"""))
        }
        h.core.tasks.runPending(true)
        val after = h.store.read()
        assertTrue(children.all { after.operations.getValue(it.id).status == OperationStatus.SUCCEEDED })
        assertEquals(11, after.activities.values.count { it.source.kind == "ai" })
        assertEquals(before.answers, after.answers); assertEquals(before.schedules, after.schedules)
        assertEquals(12, h.provider.requests.size)
    }

    @Test fun `locking practice keeps analysis and manual practice available`() = runBlocking {
        val h = Harness(); h.configure(false); h.seed(); h.answer()
        val settings = h.store.read().settings
        h.command(Command.SetSettings(settings.copy(lockedCapabilities = settings.lockedCapabilities + "generate_activity"), settings.version))
        h.provider.response = { request ->
            val capability = request.data.jsonObject.getValue("capabilities").jsonArray.map { it.jsonObject }
                .single { it.getValue("id").jsonPrimitive.content == "generate_activity" }
            assertTrue(capability.getValue("userLocked").jsonPrimitive.boolean)
            ProviderResponse(json("""{"patch":{"values":{},"reason":"保持设置"},"actions":[]}"""))
        }
        val parent = (h.command(Command.AnalyzeLearning(true)) as View.OperationResult).operation
        h.core.tasks.run(parent.id)
        assertEquals(OperationStatus.SUCCEEDED, h.store.read().operations.getValue(parent.id).status)
        assertTrue(h.store.read().operations.values.none { it.parentOperationId == parent.id })
        h.provider.response = { ProviderResponse(json("""{"prompt":"Use the word.","acceptedAnswers":[],"explanation":"Synthetic."}""")) }
        val target = h.store.read().targets.values.first()
        val manual = (h.command(Command.GenerateActivity(target.id, ActivityKind.WRITING)) as View.OperationResult).operation
        h.core.tasks.run(manual.id)
        assertEquals(OperationStatus.SUCCEEDED, h.store.read().operations.getValue(manual.id).status)
    }

    @Test fun `practice child follows the user request limit and can be retried`() = runBlocking {
        val h = Harness(); h.configure(false); h.seed()
        val settings = h.store.read().settings
        h.command(Command.SetSettings(settings.copy(dailyRequestLimit = 1), settings.version))
        val target = h.store.read().targets.values.first()
        h.provider.response = { ProviderResponse(Contract.json.encodeToJsonElement(AnalysisProposal(
            PolicyPatch(emptyMap(), "补题"), listOf(Command.GenerateActivity(target.id, ActivityKind.WRITING))))) }
        val parent = (h.command(Command.AnalyzeLearning(true)) as View.OperationResult).operation
        h.core.tasks.run(parent.id)
        val child = h.store.read().operations.values.single { it.parentOperationId == parent.id }
        h.core.tasks.run(child.id)
        assertEquals(ErrorCode.BUDGET_EXCEEDED, h.store.read().operations.getValue(child.id).fault?.code)
        assertEquals(1, h.provider.requests.size)
        val updated = h.store.read().settings
        h.command(Command.SetSettings(updated.copy(dailyRequestLimit = null), updated.version))
        h.command(Command.RetryOperation(child.id))
        h.provider.response = { ProviderResponse(json("""{"prompt":"Use the word.","acceptedAnswers":[],"explanation":"Synthetic."}""")) }
        h.core.tasks.run(child.id)
        assertEquals(OperationStatus.SUCCEEDED, h.store.read().operations.getValue(child.id).status)
        assertEquals(2, h.provider.requests.size)
    }

    @Test fun `locking practice cancels a running child and ignores a late result`() = runBlocking {
        val h = Harness(); h.configure(false); h.seed()
        val target = h.store.read().targets.values.first()
        h.provider.response = { ProviderResponse(Contract.json.encodeToJsonElement(AnalysisProposal(
            PolicyPatch(emptyMap(), "补题"), listOf(Command.GenerateActivity(target.id, ActivityKind.WRITING))))) }
        val parent = (h.command(Command.AnalyzeLearning(true)) as View.OperationResult).operation
        h.core.tasks.run(parent.id)
        val child = h.store.read().operations.values.single { it.parentOperationId == parent.id }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.provider.response = { entered.complete(Unit); release.await(); ProviderResponse(json("""{"prompt":"Use the word.","acceptedAnswers":[],"explanation":"Synthetic."}"""), 4, 5) }
        val running = launch { h.core.tasks.run(child.id) }; entered.await()
        val settings = h.store.read().settings
        h.command(Command.SetSettings(settings.copy(lockedCapabilities = settings.lockedCapabilities + "generate_activity"), settings.version))
        release.complete(Unit); running.join()
        val state = h.store.read()
        assertEquals(OperationStatus.CANCELLED, state.operations.getValue(child.id).status)
        assertTrue(state.activities.isEmpty()); assertEquals(5L, state.usage.values.single { it.operationId == child.id }.outputTokens)
    }

    @Test fun `manual analysis practice is not cancelled by unrelated automatic settings`() = runBlocking {
        val h = Harness(); h.configure(); h.seed()
        val target = h.store.read().targets.values.first()
        h.provider.response = { ProviderResponse(Contract.json.encodeToJsonElement(AnalysisProposal(
            PolicyPatch(emptyMap(), "补题"), listOf(Command.GenerateActivity(target.id, ActivityKind.WRITING))))) }
        val parent = (h.command(Command.AnalyzeLearning(true)) as View.OperationResult).operation
        h.core.tasks.run(parent.id)
        val child = h.store.read().operations.values.single { it.parentOperationId == parent.id }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.provider.response = { entered.complete(Unit); release.await(); ProviderResponse(json("""{"prompt":"Use the word.","acceptedAnswers":[],"explanation":"Synthetic."}""")) }
        val running = launch { h.core.tasks.run(child.id) }; entered.await()
        val first = h.store.read().settings
        h.command(Command.SetSettings(first.copy(automaticAnalysis = false, automaticGeneration = true), first.version))
        val second = h.store.read().settings
        h.command(Command.SetSettings(second.copy(automaticGeneration = false), second.version))
        release.complete(Unit); running.join()
        assertEquals(OperationStatus.SUCCEEDED, h.store.read().operations.getValue(child.id).status)
    }
}
