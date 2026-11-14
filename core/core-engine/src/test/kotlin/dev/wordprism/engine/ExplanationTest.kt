package dev.wordprism.engine

import dev.wordprism.contract.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test

/** Scripted model outputs test business behavior, not the truth of generated language content. */
class ExplanationTest {
    private fun content(text: String = "合成讲解：结合语境区分 linear 和 liner。") = ExplanationContent(
        text, "arranged in a line", listOf(WordPart("line", WordPartKind.STEM, "线", "合成测试字段，未经词典核验")),
        EtymologyNote("合成词源说明", "这是测试材料，词源尚未核验"), Mnemonic("想象一条直线穿过图纸。", "直线提示 linear"),
        listOf("观察所修饰的名词与搭配。"), listOf("The graph shows a linear relationship."),
    )
    private fun proposal(text: String = "合成讲解：结合语境区分 linear 和 liner。", reusable: Boolean = true) = ExplanationProposal(content(text),
        listOf(RecommendationSeed("linearity", "合成示例：名词派生关系", content = if (reusable) WordSeed("linearity", "线性", "noun") else null)))
    private fun response(proposal: ExplanationProposal = proposal()) = ProviderResponse(Contract.json.encodeToJsonElement(proposal), 23, 47)
    private suspend fun setup(): Harness = Harness().also { it.configure(false); it.seed() }
    private suspend fun sense(h: Harness) = h.store.read().senses.values.first { it.meaning == "线性的" }
    private suspend fun queue(h: Harness, sense: Sense? = null, key: String = "explain-once"): Operation {
        val current = sense ?: sense(h)
        return (h.command(Command.ExplainSense(Ref(current.id, current.version)), key) as View.OperationResult).operation
    }
    private suspend fun generated(h: Harness, value: ExplanationProposal = proposal()): Operation {
        h.provider.response = { response(value) }; val op = queue(h); h.core.tasks.run(op.id)
        return h.store.read().operations.getValue(op.id).also { assertEquals(OperationStatus.SUCCEEDED, it.status, it.fault.toString()) }
    }
    private suspend fun view(h: Harness, id: Id? = null) = h.core.query(Query.GetExplanation(id ?: sense(h).id)).let { assertNull(it.fault); it.value as View.Explanation }
    private suspend fun enableAutomatic(h: Harness) {
        val s = h.store.read().settings
        h.command(Command.SetSettings(s.copy(automaticAnalysis = true, automaticGeneration = true), s.version))
    }

    @Test fun `typed support preserves original definition and deduplicates requests without adding family words`(): Unit = runBlocking {
        val h = setup(); val before = sense(h); val stateBefore = h.store.read()
        h.provider.response = { response() }
        val queued = queue(h)
        assertEquals(queued.id, queue(h, key = "other-button-delivery").id)
        h.core.tasks.run(queued.id)
        assertEquals(queued.id, queue(h, before).id) // old key retains its original request identity
        h.core.tasks.run(queued.id)
        val saved = view(h); val after = sense(h)
        assertEquals(1, h.provider.requests.size)
        assertEquals(before.meaning, after.meaning); assertEquals(before.examples, after.examples); assertEquals(before.acceptedForms, after.acceptedForms)
        assertEquals(stateBefore.answers, h.store.read().answers); assertEquals(stateBefore.schedules, h.store.read().schedules)
        assertEquals(2, h.store.read().words.size); assertEquals(1, saved.recommendations.size)
        val support = assertNotNull(saved.explanation)
        assertEquals(content(), support.content); assertFalse(saved.stale)
        assertEquals(saved.sense.version, support.basedOnSenseVersion)
        assertEquals(queued.id, support.source.operationId)
        assertEquals(1, h.provider.requests.size) // the read and repeat never send another request
        val effects = h.core.query(Query.OperationEffects(queued.id)).value as View.OperationEffects
        assertTrue(effects.effects.any { it.field == "explanation" })
        val preview = h.core.query(Query.PreviewCall(Feature.WORD_INTAKE, saved.recommendations.map { it.id })).value as View.Preview
        assertFalse(preview.preview.requiresApi)
        validateDatabase(h.store.read(), h.memory)
    }

    @Test fun `offline job resumes after core recovery and saved support is readable without API`(): Unit = runBlocking {
        val h = setup(); h.provider.response = { response() }; val op = queue(h)
        h.core.tasks.run(op.id, false)
        assertEquals(OperationStatus.WAITING_NETWORK, view(h).operation!!.status); assertTrue(h.provider.requests.isEmpty())
        val reopened = CoreEngine(h.store, h.credentials, h.memory, h.clock, h.provider)
        assertNull(reopened.recover().fault); reopened.tasks.runPending(true)
        assertNotNull(view(h).explanation); assertEquals(1, h.provider.requests.size)
        h.core.tasks.runPending(false); assertNotNull(view(h).explanation); assertEquals(1, h.provider.requests.size)
    }

    @Test fun `cancelling an offline explanation clears obsolete waiting and retry state`(): Unit = runBlocking {
        val h = setup(); val op = queue(h)
        h.core.tasks.run(op.id, false)
        assertEquals(ErrorCode.OFFLINE, view(h).operation!!.fault?.code)
        h.command(Command.CancelOperation(op.id))
        val cancelled = view(h).operation!!
        assertEquals(OperationStatus.CANCELLED, cancelled.status)
        assertNull(cancelled.fault); assertFalse(cancelled.retryable)
        h.core.tasks.runPending(true)
        assertTrue(h.provider.requests.isEmpty()); assertNull(view(h).explanation)
    }

    @Test fun `latest explanation task follows recorded time after storage changes map order`(): Unit = runBlocking {
        val h = setup(); val old = queue(h)
        h.core.tasks.run(old.id, false); h.command(Command.CancelOperation(old.id))
        h.clock.advance(10)
        val current = queue(h, key = "second-explanation"); h.core.tasks.run(current.id, false)
        h.store.transaction { db ->
            val rows = db.operations.toList().asReversed()
            db.operations.clear(); db.operations.putAll(rows)
        }
        assertEquals(current.id, view(h).operation!!.id)
        assertEquals(OperationStatus.WAITING_NETWORK, view(h).operation!!.status)
        h.provider.response = { response() }; h.core.tasks.run(current.id)
        assertEquals(current.id, view(h).operation!!.id)
        assertEquals(OperationStatus.SUCCEEDED, view(h).operation!!.status)
        assertEquals(old.id, (h.core.query(Query.GetOperation(old.id)).value as View.OperationResult).operation.id)
    }

    @Test fun `cancelled explanation cannot install late content or candidate but records real usage`(): Unit = runBlocking {
        val h = setup(); val before = sense(h); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.provider.response = { entered.complete(Unit); release.await(); response() }
        val op = queue(h); val run = launch { h.core.tasks.run(op.id) }; entered.await()
        h.command(Command.CancelOperation(op.id)); release.complete(Unit); run.join()
        assertEquals(before, sense(h)); assertTrue(h.store.read().recommendations.isEmpty())
        assertEquals(OperationStatus.CANCELLED, view(h).operation!!.status); assertEquals(47L, h.store.read().usage.values.single().outputTokens)
    }

    @Test fun `changing the sense during generation rejects the entire supplement`(): Unit = runBlocking {
        val h = setup(); val before = sense(h); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.provider.response = { entered.complete(Unit); release.await(); response() }
        val op = queue(h); val run = launch { h.core.tasks.run(op.id) }; entered.await()
        h.command(Command.UpdateSense(Ref(before.id, before.version), "用户新释义", before.acceptedForms, before.examples))
        release.complete(Unit); run.join()
        assertEquals(ErrorCode.VERSION_CONFLICT, view(h, before.id).operation!!.fault?.code)
        assertNull(view(h, before.id).explanation); assertTrue(h.store.read().recommendations.isEmpty())
        assertEquals("用户新释义", h.store.read().senses.getValue(before.id).meaning)
    }

    @Test fun `malformed story or automatic group insertion is atomic`(): Unit = runBlocking {
        for (invalid in listOf(proposal().copy(content = content().copy(mnemonic = Mnemonic("x".repeat(4001)))),
            proposal().copy(content = content().copy(wordParts = listOf(WordPart("", WordPartKind.ROOT, "invalid")))))) {
            val h = setup(); val before = sense(h); h.provider.response = { response(invalid) }; val op = queue(h); h.core.tasks.run(op.id)
            assertEquals(ErrorCode.INVALID_RESPONSE, view(h).operation!!.fault?.code)
            assertEquals(before, sense(h)); assertTrue(h.store.read().recommendations.isEmpty())
        }
        val h = setup(); val before = sense(h)
        h.provider.response = { response(proposal().copy(recommendations = listOf(RecommendationSeed("linearity", "unrequested group", h.store.state.groups.keys.first())))) }
        val op = queue(h); h.core.tasks.run(op.id)
        assertEquals(ErrorCode.INVALID_RESPONSE, view(h).operation!!.fault?.code)
        assertEquals(before, sense(h)); assertEquals(2, h.store.read().memberships.size)
    }

    @Test fun `generation uses current support and excludes stale support after an edit`(): Unit = runBlocking {
        val h = setup(); generated(h); val saved = view(h)
        val target = h.store.read().targets.values.first { it.senseId == saved.sense.id }
        h.provider.response = { request ->
            assertEquals(saved.explanation!!.content, Contract.json.decodeFromJsonElement<SenseExplanation>(request.data.jsonObject.getValue("studyExplanation")).content)
            assertTrue("never test a mnemonic story as history" in request.system)
            ProviderResponse(json("""{"prompt":"Use linear to describe a new graph.","explanation":"合成练习"}"""))
        }
        val generated = h.command(Command.GenerateActivity(target.id, ActivityKind.WRITING)) as View.OperationResult; h.core.tasks.run(generated.operation.id)
        assertEquals(OperationStatus.SUCCEEDED, h.store.read().operations.getValue(generated.operation.id).status)
        val sense = sense(h)
        h.command(Command.UpdateSense(Ref(sense.id, sense.version), sense.meaning, sense.acceptedForms, listOf("User example.")))
        assertTrue(view(h).stale); assertTrue(view(h).recommendations.isEmpty())
        h.provider.response = { request ->
            assertEquals(JsonNull, request.data.jsonObject["studyExplanation"])
            assertNull(request.data.jsonObject.getValue("sense").jsonObject["explanation"])
            ProviderResponse(json("""{"prompt":"Write about a second linear trend.","explanation":"合成练习"}"""))
        }
        val next = h.command(Command.GenerateActivity(target.id, ActivityKind.WRITING)) as View.OperationResult; h.core.tasks.run(next.operation.id)
        assertEquals(OperationStatus.SUCCEEDED, h.store.read().operations.getValue(next.operation.id).status)
    }

    @Test fun `daily analysis schedules one explanation and parent undo retains later accepted words and answers`(): Unit = runBlocking {
        val h = setup(); enableAutomatic(h); h.answer(); val sense = sense(h)
        h.provider.response = { request ->
            val inventory = request.data.jsonObject.getValue("explanations").jsonArray
            assertTrue(inventory.any { it.jsonObject["senseId"]?.jsonPrimitive?.content == sense.id.value })
            ProviderResponse(Contract.json.encodeToJsonElement(AnalysisProposal(PolicyPatch(emptyMap(), "合成测试：为薄弱义项补讲解"), listOf(Command.ExplainSense(Ref(sense.id, sense.version))))))
        }
        val parent = h.finishDay()!!; h.core.tasks.run(parent.id)
        assertEquals(parent.id, h.finishDay()!!.id)
        val child = h.store.read().operations.values.single { it.parentOperationId == parent.id }
        assertEquals(Feature.EXPLANATION, child.feature); assertTrue(child.automatic)
        h.provider.response = { response() }; h.core.tasks.run(child.id)
        val candidate = view(h).recommendations.single()
        h.command(Command.AcceptRecommendations(listOf(candidate.id), false))
        assertNotNull(view(h).family.single().libraryWordId)
        h.answer(); val answers = h.store.read().answers; val schedules = h.store.read().schedules
        h.command(Command.UndoOperation(parent.id))
        assertNull(view(h).explanation); assertTrue((h.core.query(Query.Recommendations()).value as View.Recommendations).recommendations.isEmpty())
        assertTrue(h.store.read().words.values.any { it.spelling == "linearity" && !it.archived })
        assertEquals(answers, h.store.read().answers); assertEquals(schedules, h.store.read().schedules)
        assertEquals(2, h.provider.requests.size); validateDatabase(h.store.read(), h.memory)
    }

    @Test fun `closing automatic generation rejects the child result arriving afterwards`(): Unit = runBlocking {
        val h = setup(); enableAutomatic(h); h.answer(); val sense = sense(h)
        h.provider.response = { ProviderResponse(Contract.json.encodeToJsonElement(AnalysisProposal(PolicyPatch(emptyMap(), "合成动作"), listOf(Command.ExplainSense(Ref(sense.id, sense.version)))))) }
        val parent = h.finishDay()!!; h.core.tasks.run(parent.id)
        val child = h.store.read().operations.values.single { it.parentOperationId == parent.id }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.provider.response = { entered.complete(Unit); release.await(); response() }
        val run = launch { h.core.tasks.run(child.id) }; entered.await()
        val settings = h.store.read().settings
        h.command(Command.SetSettings(settings.copy(automaticGeneration = false), settings.version))
        release.complete(Unit); run.join()
        assertNull(view(h).explanation); assertTrue(h.store.read().recommendations.isEmpty())
        assertEquals(OperationStatus.CANCELLED, view(h).operation!!.status)
        assertEquals(47L, h.store.read().usage.values.single { it.operationId == child.id }.outputTokens)
    }

    @Test fun `capability lock blocks automatic supplement and manual generation remains available`(): Unit = runBlocking {
        val h = setup(); enableAutomatic(h); val sense = sense(h); val settings = h.store.read().settings
        h.command(Command.SetSettings(settings.copy(lockedCapabilities = setOf("explain_sense")), settings.version))
        h.provider.response = { ProviderResponse(Contract.json.encodeToJsonElement(AnalysisProposal(PolicyPatch(emptyMap(), "合成动作"), listOf(Command.ExplainSense(Ref(sense.id, sense.version)))))) }
        val parent = h.command(Command.AnalyzeLearning(true)) as View.OperationResult; h.core.tasks.run(parent.operation.id)
        assertEquals(ErrorCode.DISABLED, h.store.read().operations.getValue(parent.operation.id).fault?.code)
        assertTrue(h.store.read().operations.values.none { it.parentOperationId == parent.operation.id })
        generated(h); assertNotNull(view(h).explanation)
    }

    @Test fun `undo regeneration restores previous content and keeps it current`(): Unit = runBlocking {
        val h = setup(); val original = generated(h); val first = view(h).explanation!!
        h.provider.response = { response(proposal("第二次合成讲解")) }
        val next = queue(h, key = "regenerate"); h.core.tasks.run(next.id)
        assertEquals("第二次合成讲解", view(h).explanation!!.content.explanation)
        h.answer(); val history = h.store.read().answers
        h.command(Command.UndoOperation(next.id))
        val restored = view(h)
        val support = assertNotNull(restored.explanation)
        assertEquals(first.content, support.content); assertFalse(restored.stale)
        assertEquals(original.id, support.source.operationId); assertEquals(history, h.store.read().answers)
        assertTrue(support.version > first.version); validateDatabase(h.store.read(), h.memory)
    }

    @Test fun `backup keeps support provenance history and pending state while content package omits personal acceptance`(): Unit = runBlocking {
        val h = setup(); generated(h); val candidate = view(h).recommendations.single()
        h.command(Command.AcceptRecommendations(listOf(candidate.id), false)); h.answer()
        val pending = queue(h, key = "pending-refresh"); val original = h.store.read()
        val backup = ByteArrayOutputStream(); assertNull(h.core.exportPackage(PackagePurpose.BACKUP, backup).fault)
        val restored = Harness()
        val preview = restored.core.previewPackage(ByteArrayInputStream(backup.toByteArray())); assertNull(preview.fault)
        val token = (preview.value as View.ImportPreview).preview.token
        restored.command(Command.RestoreBackup(token), "restore-support"); restored.command(Command.RestoreBackup(token), "restore-support")
        assertEquals(original.senses, restored.store.read().senses); assertEquals(original.recommendations, restored.store.read().recommendations)
        assertEquals(original.answers, restored.store.read().answers); assertEquals(original.undos, restored.store.read().undos)
        assertEquals(OperationStatus.RESTORED_PENDING, restored.store.read().operations.getValue(pending.id).status)
        restored.core.tasks.runPending(true); assertTrue(restored.provider.requests.isEmpty())
        val content = ByteArrayOutputStream(); assertNull(h.core.exportPackage(PackagePurpose.CONTENT, content, setOf(sense(h).wordId)).fault)
        val imported = Harness(); val p = imported.core.previewPackage(ByteArrayInputStream(content.toByteArray())); assertNull(p.fault, p.fault.toString())
        imported.command(Command.CommitImport((p.value as View.ImportPreview).preview.token))
        assertEquals(2, imported.store.read().words.size); assertTrue(imported.store.read().answers.isEmpty()); assertTrue(imported.store.read().presets.isEmpty())
        val fresh = view(imported, sense(h).id); assertEquals(1, fresh.recommendations.size); assertNull(fresh.recommendations.single().acceptedOperationId)
        val added = imported.command(Command.AcceptRecommendations(fresh.recommendations.map { it.id }, false)) as View.OperationResult
        imported.command(Command.AcceptRecommendations(fresh.recommendations.map { it.id }, false))
        assertEquals(3, imported.store.read().words.size); assertTrue(imported.provider.requests.isEmpty())
        assertTrue(view(imported, sense(h).id).recommendations.isEmpty()); assertEquals(1, view(imported, sense(h).id).family.size)
        imported.command(Command.UndoOperation(added.operation.id)); assertEquals(1, view(imported, sense(h).id).recommendations.size)
        validateDatabase(imported.store.read(), imported.memory)
    }

    @Test fun `withdrawing explanation while candidate API is running prevents late automatic addition`(): Unit = runBlocking {
        val h = setup(); val explained = generated(h, proposal(reusable = false)); val candidate = view(h).recommendations.single()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.provider.response = { entered.complete(Unit); release.await(); ProviderResponse(json("""{"words":[{"spelling":"linearity","meaning":"线性"}],"reason":"用户已选择"}""")) }
        val accept = h.command(Command.AcceptRecommendations(listOf(candidate.id), true)) as View.OperationResult
        val run = launch { h.core.tasks.run(accept.operation.id) }; entered.await()
        h.command(Command.UndoOperation(explained.id)); release.complete(Unit); run.join()
        assertEquals(2, h.store.read().words.size)
        assertEquals(ErrorCode.VERSION_CONFLICT, h.store.read().operations.getValue(accept.operation.id).fault?.code)
        val attempt = h.core.execute(Request("accept-withdrawn", Command.AcceptRecommendations(listOf(candidate.id), false)))
        assertEquals(ErrorCode.VERSION_CONFLICT, attempt.fault?.code)
    }

    @Test fun `accepting an existing family word is undoable without removing the original word or its history`(): Unit = runBlocking {
        val h = setup()
        generated(h, proposal().copy(recommendations = listOf(RecommendationSeed("liner", "同组已拥有的关联词",
            content = WordSeed("liner", "AI 换了一种说法的衬里释义", "noun")))))
        val candidate = view(h).recommendations.single()
        assertTrue(candidate.inLibrary)
        assertEquals("衬里", candidate.content!!.meaning)
        val before = h.store.read()
        val accepted = h.command(Command.AcceptRecommendations(listOf(candidate.id), false)) as View.OperationResult
        assertTrue(accepted.operation.undoable)
        assertTrue(view(h).recommendations.isEmpty())
        assertNotNull(view(h).family.single().libraryWordId)
        assertEquals(accepted.operation.id, (h.command(Command.AcceptRecommendations(listOf(candidate.id), false)) as View.OperationResult).operation.id)
        h.answer(); val history = h.store.read().answers; val schedules = h.store.read().schedules
        h.command(Command.UndoOperation(accepted.operation.id))
        assertEquals(before.words, h.store.read().words)
        assertEquals(before.senses, h.store.read().senses)
        assertEquals(history, h.store.read().answers); assertEquals(schedules, h.store.read().schedules)
        assertEquals(candidate.id, view(h).recommendations.single().id)
        assertEquals(1, h.provider.requests.size)
        validateDatabase(h.store.read(), h.memory)
    }

    @Test fun `provider null optional details use declared defaults but required content and enums stay strict`(): Unit = runBlocking {
        val h = setup()
        h.provider.response = { ProviderResponse(json("""{
            "content":{"explanation":"合成的完整讲解","wordParts":[{"form":"line","kind":"STEM","meaning":"线","note":null}],
                "etymology":{"explanation":"合成词源说明","uncertainty":null},"mnemonic":{"story":"合成记忆联想","cue":null},
                "usageNotes":null,"examples":null},
            "recommendations":[{"spelling":"linearity","reason":"合成派生词",
                "content":{"spelling":"linearity","meaning":"线性","partOfSpeech":null,"acceptedForms":null,"examples":null}}]
        }""")) }
        val queued = queue(h); h.core.tasks.run(queued.id)
        val saved = view(h)
        assertEquals(OperationStatus.SUCCEEDED, saved.operation!!.status)
        val content = assertNotNull(saved.explanation).content
        assertEquals("", content.etymology!!.uncertainty); assertEquals("", content.mnemonic!!.cue)
        assertEquals("", content.wordParts.single().note); assertTrue(content.usageNotes.isEmpty())
        assertEquals("", saved.recommendations.single().content!!.partOfSpeech)
        h.command(Command.AcceptRecommendations(saved.recommendations.map { it.id }, false))
        validateDatabase(h.store.read(), h.memory)
        for (invalid in listOf("""{"content":{"explanation":null}}""",
            """{"content":{"explanation":"有正文","wordParts":[{"form":"x","kind":"INVENTED","meaning":"x"}]}}""")) {
            val before = sense(h)
            h.provider.response = { ProviderResponse(json(invalid)) }
            val bad = queue(h, key = java.util.UUID.randomUUID().toString()); h.core.tasks.run(bad.id)
            assertEquals(ErrorCode.INVALID_RESPONSE, view(h).operation!!.fault?.code)
            assertEquals(before, sense(h))
        }
        assertFails { Contract.json.decodeFromJsonElement<ExplanationContent>(json("""{"explanation":"strict local contract","usageNotes":null}""")) }
    }

    @Test fun `legacy drafts and undo records remain readable without changing their serialized identity`(): Unit = runBlocking {
        val h = setup(); val sense = sense(h)
        assertFalse(Contract.json.encodeToJsonElement(sense).jsonObject.containsKey("explanation"))
        val command = Command.ExplainSense(Ref(sense.id, 1))
        val oldJson = """{"type":"explain_sense","sense":{"id":"${sense.id.value}","version":1}}"""
        assertEquals(oldJson, Contract.json.encodeToString<Command>(command))
        val oldOp = h.store.transaction { db -> db.localOperation("explain_sense", h.clock.millis()) {
            db.senses[sense.id] = sense.copy(version = 2)
            val key = "explanation:${sense.id.value}"; db.drafts[key] = Draft(key, "旧版讲解原文", 1, h.clock.millis()); listOf(sense.id)
        }.operation }
        val before = h.store.read()
        assertEquals("旧版讲解原文", view(h).explanation!!.content.explanation)
        assertEquals(before, h.store.read())
        h.command(Command.UndoOperation(oldOp.id)); assertNull(view(h).explanation)
    }
}
