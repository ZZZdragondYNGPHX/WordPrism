package app.wordprism.codex

import app.wordprism.sdk.*
import dev.wordprism.contract.Command
import dev.wordprism.contract.Contract
import dev.wordprism.contract.Query
import dev.wordprism.contract.Request
import dev.wordprism.contract.View
import dev.wordprism.contract.WordSeed
import dev.wordprism.contract.GroupSeed
import dev.wordprism.contract.Relation
import dev.wordprism.engine.CoreEngine
import dev.wordprism.engine.CredentialStore
import dev.wordprism.engine.LlmProvider
import dev.wordprism.engine.ProviderRequest
import dev.wordprism.engine.ProviderResponse
import dev.wordprism.jvm.SqliteStateStore
import dev.wordprism.memory.NativeMemoryKernel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.zip.ZipInputStream
import dev.wordprism.contract.Id as CId
import dev.wordprism.contract.OperationStatus as CStatus

/** Real SQLite and Rust JNI; the only simulated component is the explicitly scripted LLM. */
class CoreIntegrationTest {
    private class Credentials : CredentialStore {
        val entries = mutableMapOf<CId, CharArray>()
        override fun has(presetId: CId) = presetId in entries
        override fun read(presetId: CId) = entries[presetId]?.copyOf()
        override fun write(presetId: CId, secret: CharArray) { entries[presetId] = secret.copyOf() }
        override fun delete(presetId: CId) { entries.remove(presetId)?.fill('\u0000') }
    }
    private class Documents : DocumentAccess {
        val files = mutableMapOf<String, ByteArray>()
        override fun openInput(uri: String) = ByteArrayInputStream(files.getValue(uri))
        override fun openOutput(uri: String) = object : ByteArrayOutputStream() { override fun close() { files[uri] = toByteArray(); super.close() } }
    }
    private class Provider : LlmProvider {
        val requests = mutableListOf<ProviderRequest>()
        var response: suspend (ProviderRequest) -> ProviderResponse = { ProviderResponse(Contract.json.parseToJsonElement("""{"patch":{"values":{"newTargetLimit":2},"reason":"合成测试：减少新学"},"actions":[]}"""), 7, 9) }
        override suspend fun generate(request: ProviderRequest, secret: CharArray): ProviderResponse { requests += request; secret.fill('\u0000'); return response(request) }
        override fun cancel(operationId: CId) = Unit // tests late provider delivery intentionally
    }
    private class Harness : AutoCloseable {
        private val directory = Files.createTempDirectory("wordprism-adapter-")
        val documents = Documents(); val credentials = Credentials(); val provider = Provider()
        private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var store = SqliteStateStore(directory.resolve("core.db"))
        private val clock = Clock.fixed(Instant.parse("2026-11-03T18:00:00Z"), ZoneId.of("UTC"))
        var core = CoreEngine(store, credentials, NativeMemoryKernel(), clock, provider)
        var sdk = CodexSdk(core, scope, documents)
        suspend fun restart() {
            scope.cancel(); store.close(); store = SqliteStateStore(directory.resolve("core.db"))
            core = CoreEngine(store, credentials, NativeMemoryKernel(), clock, provider); assertNull(core.recover().fault)
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); sdk = CodexSdk(core, scope, documents)
        }
        suspend fun command(command: Command): View = core.execute(Request(UUID.randomUUID().toString(), command)).let { assertNull(it.fault); it.value!! }
        suspend fun seed() = command(Command.SaveWords(listOf(WordSeed("linear", "线性的", "adjective"), WordSeed("liner", "衬里", "noun")), GroupSeed("linear / liner", Relation.LOOK_ALIKE, "区分形似词")))
        suspend fun scoredSession(): SessionId {
            val session = command(Command.StartSession()) as View.SessionState
            command(Command.NextActivity(session.session.id)); return SessionId(session.session.id.value)
        }
        suspend fun state(session: SessionId) = withTimeout(5_000) { sdk.study.observeSession(session).filterNotNull().first() }
        suspend fun correct(session: SessionId): Pair<ActivityId, Answer> {
            val state = state(session); val activity = state.current!!
            val presentation = core.query(Query.GetPresentation(CId(activity.id.value))).value as View.Presentation
            val actual = store.read().activities.getValue(presentation.activity.id)
            val choice = actual.choices.firstOrNull { c -> c.id in actual.acceptedAnswers || c.text in actual.acceptedAnswers }
            return activity.id to if (choice == null) Answer.Text(actual.acceptedAnswers.firstOrNull() ?: "A synthetic sentence.") else Answer.Choice(choice.id)
        }
        suspend fun configure(): PresetId {
            val draft = PresetDraft(name = "Synthetic provider", adapterId = "openai_chat_completions", baseUrl = "https://example.invalid/v1", model = "test-model",
                maxOutputTokens = 1024, timeoutSeconds = 30, credential = UUID.randomUUID().toString())
            val preset = ok(sdk.presets.savePreset(draft, ClientRequestId("preset-create")))
            val policy = sdk.presets.observeCallPolicy().first()
            ok(sdk.presets.setCallPolicy(policy.copy(defaultPresetId = preset.id)))
            return preset.id
        }
        override fun close() {
            scope.cancel(); store.close()
            credentials.entries.values.forEach { it.fill('\u0000') }
            // Only this test's directly-created files; no recursive shell deletion.
            Files.list(directory).use { it.forEach(Files::deleteIfExists) }; Files.deleteIfExists(directory)
        }
    }
    companion object {
        private fun <T> ok(result: SdkResult<T>): T { if (result is SdkResult.Err) fail(result.error.toString()); return (result as SdkResult.Ok).value }
    }

    @Test fun studySubmissionAndFeedbackSurviveSQLiteReopenAndRemainIdempotentAfterAdvance() = runBlocking {
        Harness().use { h ->
            h.seed(); val session = h.scoredSession(); val (id, answer) = h.correct(session)
            val first = ok(h.sdk.study.submitAnswer(session, id, answer, ClientRequestId("answer-once")))
            assertTrue(first.affectsSchedule)
            h.restart()
            val resumed = h.state(session); assertEquals(first.answerId, resumed.feedback!!.answerId); assertEquals(id, resumed.current!!.id)
            ok(h.sdk.study.reportPresence(session, true))
            assertEquals(first.answerId, ok(h.sdk.study.submitAnswer(session, id, answer, ClientRequestId("answer-once"))).answerId)
            val different = h.sdk.study.submitAnswer(session, id, Answer.Text("different"), ClientRequestId("different-request"))
            assertTrue(different is SdkResult.Err)
            ok(h.sdk.study.advance(session, ClientRequestId("advance-once")))
            assertEquals(first.answerId, ok(h.sdk.study.submitAnswer(session, id, answer, ClientRequestId("answer-once"))).answerId)
            assertEquals(1, h.store.read().answers.size); assertEquals(1, h.store.read().schedules.values.sumOf { it.reviews })
        }
    }
    @Test fun introductionsAreRealUserObservationsAndDoNotClaimIndependentRecall() = runBlocking {
        Harness().use { h ->
            h.seed(); assertEquals(2, h.sdk.study.observePlan().first().newPlanned)
            val session = ok(h.sdk.study.startOrResume(ClientRequestId("start")))
            val intro = h.state(session).current as ActivityView.Familiarity
            val result = ok(h.sdk.study.submitAnswer(session, intro.id, Answer.Familiarity(false), ClientRequestId("familiarity")))
            assertFalse(result.affectsSchedule)
            ok(h.sdk.study.advance(session, ClientRequestId("after-intro")))
            val (id, answer) = h.correct(session)
            val practice = ok(h.sdk.study.submitAnswer(session, id, answer, ClientRequestId("practice")))
            assertFalse(practice.affectsSchedule); assertEquals(Verdict.Practice, practice.verdict)
            val snapshot = ok(h.sdk.overview.observeOverview(OverviewWindow.All).first().let { SdkResult.Ok(it) })
            assertEquals(0, snapshot.memory.first().ratio.opportunities)
        }
    }
    @Test fun libraryEditsAlignmentAndUndoUseTheSameCore() = runBlocking {
        Harness().use { h ->
            h.seed(); val page = h.sdk.library.observeLibrary(LibraryQuery()).first(); assertEquals(2, page.totalWords)
            val group = h.sdk.library.observeGroup(page.groups.single().id).filterNotNull().first()
            assertTrue(group.members.all { it.alignedForm != null }); assertEquals(1, group.members.map { it.alignedForm!!.length }.distinct().size)
            val member = group.members.first(); val word = h.sdk.library.observeWord(member.wordId).filterNotNull().first()
            val patch = WordPatch("Personal note", "Edited gloss", word.senses.first().id, word.senses.first().version)
            val edit = ok(h.sdk.library.updateWord(word.id, word.version, patch, ClientRequestId("edit")))
            assertEquals(edit.operationId, ok(h.sdk.library.updateWord(word.id, word.version, patch, ClientRequestId("edit"))).operationId)
            h.restart()
            assertEquals(edit.operationId, ok(h.sdk.library.updateWord(word.id, word.version, patch, ClientRequestId("edit"))).operationId)
            val stale = h.sdk.library.updateWord(word.id, word.version, WordPatch(note = "stale"), ClientRequestId("stale")); assertTrue(stale is SdkResult.Err)
            ok(h.sdk.operations.undo(edit.operationId, ClientRequestId("undo-edit")))
            assertEquals(word.note, h.sdk.library.observeWord(word.id).filterNotNull().first().note)
            val rename = ok(h.sdk.library.renameGroup(group.id, "Personal contrast", group.version, ClientRequestId("rename")))
            assertEquals("Personal contrast", h.sdk.library.observeGroup(group.id).filterNotNull().first().title)
            ok(h.sdk.operations.undo(rename.operationId, ClientRequestId("undo-rename")))
            assertEquals(group.title, h.sdk.library.observeGroup(group.id).filterNotNull().first().title)
        }
    }
    @Test fun defaultBindingUnbindingAndDailyQuotaActuallyControlTasks() = runBlocking {
        Harness().use { h ->
            val preset = h.configure()
            assertTrue(h.sdk.presets.observeBindings().first().all { it.usesDefault && it.presetId == preset })
            ok(h.sdk.presets.bindFeature(FeatureId.WordIntake, preset)); assertFalse(h.sdk.presets.observeBindings().first().first { it.feature == FeatureId.WordIntake }.usesDefault)
            ok(h.sdk.presets.bindFeature(FeatureId.WordIntake, null)); assertTrue(h.sdk.presets.observeBindings().first().first { it.feature == FeatureId.WordIntake }.usesDefault)
            val policy = h.sdk.presets.observeCallPolicy().first(); ok(h.sdk.presets.setCallPolicy(policy.copy(dailyRequestLimit = 0)))
            val op = ok(h.sdk.intake.submit(IntakeRequest("linear"), ClientRequestId("intake")))
            h.core.tasks.run(CId(op.value)); assertTrue(h.provider.requests.isEmpty())
            assertEquals(ErrorCode.QuotaExhausted, h.sdk.operations.observe(op).filterNotNull().first().error!!.code)
        }
    }
    @Test fun fullBackupThroughDocumentStreamsRestoresFeedbackAndRetriesCommitAfterTokenRemoval() = runBlocking {
        Harness().use { source -> Harness().use { target ->
            source.configure(); source.seed(); val session = source.scoredSession(); val (id, answer) = source.correct(session)
            val feedback = ok(source.sdk.study.submitAnswer(session, id, answer, ClientRequestId("answer")))
            val doc = DocumentRef("content://test/backup", "synthetic.wordprism", null)
            val exported = ok(source.sdk.dataTransfer.export(ExportRequest(PackagePurpose.FullBackup), doc, ClientRequestId("export")))
            assertEquals(OperationStatus.Succeeded, source.sdk.operations.observe(exported).filterNotNull().first().status)
            val bytes = source.documents.files.getValue(doc.uri)
            assertEquals(exported, ok(source.sdk.dataTransfer.export(ExportRequest(PackagePurpose.FullBackup), doc, ClientRequestId("export"))))
            assertTrue(bytes.contentEquals(source.documents.files.getValue(doc.uri)))
            assertTrue(source.sdk.dataTransfer.export(ExportRequest(PackagePurpose.FullBackup), doc.copy(uri = "content://test/other"), ClientRequestId("export")) is SdkResult.Err)
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) { val entry = zip.nextEntry ?: break; val content = zip.readBytes().toString(Charsets.UTF_8)
                    assertFalse(content.contains("credentialRefs")); if (entry.name == "manifest.json") assertTrue(content.contains("\"format\":\"wordprism\"")) }
            }
            target.documents.files[doc.uri] = bytes
            val preview = ok(target.sdk.dataTransfer.previewImport(doc)); assertTrue(preview.canCommit)
            val restored = ok(target.sdk.dataTransfer.commitImport(preview.previewId, ClientRequestId("restore")))
            assertEquals(restored, ok(target.sdk.dataTransfer.commitImport(preview.previewId, ClientRequestId("restore"))))
            assertEquals(2, target.sdk.library.observeLibrary(LibraryQuery()).first().totalWords)
            assertEquals(feedback.answerId, target.state(session).feedback!!.answerId)
            assertTrue(target.sdk.presets.observePresets().first().none { it.credential.present })
            assertEquals(AnalysisMode.Off, target.sdk.adaptation.observeSettings().first().mode)
        } }
    }
    @Test fun appliedAnalysisUndoPreservesLaterLearningAndSharesOverviewFacts() = runBlocking {
        Harness().use { h ->
            h.configure(); h.seed(); val session = h.scoredSession(); val (id, answer) = h.correct(session)
            ok(h.sdk.study.submitAnswer(session, id, answer, ClientRequestId("a1")))
            val word = h.store.read().words.values.first()
            h.provider.response = { ProviderResponse(Contract.json.parseToJsonElement("""{"patch":{"values":{"newTargetLimit":2},"reason":"synthetic analysis"},"actions":[{"type":"update_word","word":{"id":"${word.id.value}","version":${word.version}},"notes":"Clear contrast","tags":[]}]}"""), 8, 12) }
            val op = ok(h.sdk.adaptation.analyzeNow(false, ClientRequestId("analysis"))); h.core.tasks.run(CId(op.value))
            val change = h.sdk.adaptation.observeChangeSets().first().first { it.operationId == op }
            assertTrue(change.undo.available); assertTrue(change.changes.any { it.capabilityId == "newTargetLimit" && it.after == "2" })
            assertTrue(change.changes.any { it.capabilityId == "update_word" && it.after == "Clear contrast" })
            ok(h.sdk.study.advance(session, ClientRequestId("next"))); val (nextId, nextAnswer) = h.correct(session)
            ok(h.sdk.study.submitAnswer(session, nextId, nextAnswer, ClientRequestId("a2")))
            val before = h.store.read().answers
            ok(h.sdk.adaptation.undoChangeSet(change.id, ClientRequestId("undo")))
            assertEquals(before, h.store.read().answers); assertEquals(12, h.store.read().policy.newTargetLimit)
            assertEquals(word.notes, h.store.read().words.getValue(word.id).notes)
            val snapshot = h.sdk.overview.observeOverview(OverviewWindow.All).first()
            assertEquals(before.size, snapshot.effort.answers)
        }
    }
    @Test fun spellingChoicesPreserveInputAndSurviveReopenWithoutAnotherApiCall() = runBlocking {
        for (correct in listOf(true, false)) Harness().use { h ->
            h.configure()
            h.provider.response = { ProviderResponse(Contract.json.parseToJsonElement("""{"words":[{"spelling":"lienar","meaning":"待确认的原词"}],"reason":"confirm spelling","corrections":[{"original":"lienar","suggested":{"spelling":"linear","meaning":"线性的"},"reason":"可能是 linear"}]}"""), 3, 5) }
            val op = ok(h.sdk.intake.submit(IntakeRequest("lienar"), ClientRequestId("spelling-intake")))
            h.core.tasks.run(CId(op.value)); h.restart()
            val pending = h.sdk.intake.observe(op).filterNotNull().first()
            assertEquals("lienar", pending.input); assertNotNull(pending.pendingChoice)
            assertTrue(h.store.read().words.isEmpty())
            val option = pending.pendingChoice!!.options.first { it.id.startsWith(if (correct) "spelling:correct:" else "spelling:keep:") }
            val first = ok(h.sdk.intake.resolve(op, listOf(option.id), ClientRequestId("resolve-spelling")))
            assertEquals(first, ok(h.sdk.intake.resolve(op, listOf(option.id), ClientRequestId("resolve-spelling"))))
            assertEquals(if (correct) "linear" else "lienar", h.store.read().words.values.single().spelling)
            assertEquals(1, h.provider.requests.size)
        }
    }
    @Test fun disablingAutomaticAnalysisThroughUiPortFencesLateResult() = runBlocking {
        Harness().use { h ->
            h.configure(); h.seed(); val session = h.scoredSession(); val (id, answer) = h.correct(session)
            ok(h.sdk.study.submitAnswer(session, id, answer, ClientRequestId("answer")))
            val day = ok(h.sdk.study.finishLearningDay(ClientRequestId("finish"))); val op = day.dailyAnalysis.operationId!!
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val original = h.provider.response
            h.provider.response = { entered.complete(Unit); release.await(); original(it) }
            val job = launch { h.core.tasks.run(CId(op.value)) }; entered.await()
            ok(h.sdk.adaptation.setMode(AnalysisMode.Off)); release.complete(Unit); job.join()
            assertEquals(CStatus.CANCELLED, h.store.read().operations[CId(op.value)]!!.status)
            assertEquals(12, h.store.read().policy.newTargetLimit); assertEquals(1, h.store.read().usage.size)
        }
    }
}
