package dev.wordprism.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import dev.wordprism.contract.*
import dev.wordprism.engine.CoreEngine
import dev.wordprism.engine.LlmProvider
import dev.wordprism.engine.ProviderRequest
import dev.wordprism.engine.ProviderResponse
import dev.wordprism.memory.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidCoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun analysisAddsPracticeAndRoomKeepsItAfterReopen() = runBlocking<Unit> {
        val name = "practice-${UUID.randomUUID()}.db"
        val presetId = Id("practice-preset-${UUID.randomUUID()}")
        val credentials = KeystoreCredentials(context)
        val provider = object : LlmProvider {
            override suspend fun generate(request: ProviderRequest, secret: CharArray): ProviderResponse {
                secret.fill('\u0000')
                val data = request.data.jsonObject
                val type = data.getValue("request").jsonObject.getValue("type").jsonPrimitive.content
                val result = if (type == "analyze_learning") {
                    val targetId = data.getValue("targets").jsonArray.first().jsonObject.getValue("id").jsonPrimitive.content
                    """{"patch":{"values":{},"reason":"合成分析"},"actions":[{"type":"generate_activity","targetId":"$targetId","kind":"WRITING"}]}"""
                } else """{"prompt":"Use the word in a sentence.","acceptedAnswers":[],"explanation":"合成练习"}"""
                return ProviderResponse(Contract.json.parseToJsonElement(result))
            }
            override suspend fun listModels(request: ModelCatalogRequest, secret: CharArray): List<ApiModel> { secret.fill('\u0000'); return emptyList() }
            override fun cancel(operationId: Id) = Unit
        }
        val store = RoomStateStore(WordPrismDatabase.open(context, name))
        val core = CoreEngine(store, credentials, NativeMemoryKernel(), provider = provider)
        assertNull(core.recover().fault)
        val preset = ApiPreset(presetId, name = "synthetic", baseUrl = "https://example.invalid/v1", model = "synthetic")
        assertNull(core.execute(Request("preset", Command.SavePreset(preset))).fault)
        assertNull(core.setCredential(presetId, UUID.randomUUID().toString().toCharArray()).fault)
        assertNull(core.execute(Request("binding", Command.BindPreset(Binding(Feature.LEARNING_ANALYSIS, presetId)))).fault)
        assertNull(core.execute(Request("practice-binding", Command.BindPreset(Binding(Feature.PRACTICE_GENERATION, presetId)))).fault)
        assertNull(core.execute(Request("word", Command.SaveWords(listOf(WordSeed("linear", "线性的"))))).fault)
        val parent = (core.execute(Request("analysis", Command.AnalyzeLearning(true))).value as View.OperationResult).operation
        core.tasks.run(parent.id)
        val parentResult = (core.query(Query.GetOperation(parent.id)).value as View.OperationResult).operation
        assertEquals(OperationStatus.SUCCEEDED, parentResult.status)
        val childId = parentResult.resultIds.single()
        core.tasks.run(childId)
        val child = (core.query(Query.GetOperation(childId)).value as View.OperationResult).operation
        assertEquals(OperationStatus.SUCCEEDED, child.status)
        val activityId = child.resultIds.single()
        assertNull(core.deleteCredential(presetId).fault)
        store.close()
        val reopened = RoomStateStore(WordPrismDatabase.open(context, name))
        val saved = reopened.read()
        assertFalse(saved.settings.automaticGeneration)
        assertEquals("Use the word in a sentence.", saved.activities.getValue(activityId).prompt)
        assertEquals(parent.id, saved.operations.getValue(childId).parentOperationId)
        reopened.close(); context.deleteDatabase(WordPrismDatabase.file(context, name).absolutePath)
    }
    @Test fun roomNativeMemoryAndProcessReopen() = runBlocking<Unit> {
        val name = "test-${UUID.randomUUID()}.db"
        val store = RoomStateStore(WordPrismDatabase.open(context, name))
        val core = CoreEngine(store, KeystoreCredentials(context), NativeMemoryKernel())
        assertNull(core.recover().fault)
        assertNull(core.execute(Request("add", Command.SaveWords(listOf(WordSeed("linear", "线性的"))))).fault)
        val session = (core.execute(Request("start", Command.StartSession())).value as View.SessionState).session
        val question = core.execute(Request("question", Command.NextActivity(session.id))).value as View.SessionState
        val command = Command.SubmitAnswer(session.id, question.session.currentPresentationId!!, Ref(question.activity!!.id, 1), AnswerInput("linear"))
        assertNull(core.execute(Request("answer", command)).fault)
        val expected = store.read().answers; store.close()
        val reopened = RoomStateStore(WordPrismDatabase.open(context, name))
        val resumed = CoreEngine(reopened, KeystoreCredentials(context), NativeMemoryKernel())
        assertNull(resumed.recover().fault); assertNull(resumed.execute(Request("answer", command)).fault)
        assertEquals(expected, reopened.read().answers); assertEquals(1, reopened.read().schedules.values.sumOf { it.reviews })
        reopened.close(); context.deleteDatabase(WordPrismDatabase.file(context, name).absolutePath)
    }
    @Test fun keystoreEncryptionAndRemoval() {
        val credentials = KeystoreCredentials(context); val id = Id("instrumentation-${UUID.randomUUID()}")
        val expected = UUID.randomUUID().toString()
        val input = expected.toCharArray(); credentials.write(id, input)
        assertTrue(input.all { it == '\u0000' }); assertEquals(expected, String(credentials.read(id)!!))
        credentials.delete(id); assertFalse(credentials.has(id))
    }
    @Test fun nativeSchedulingAndLocalFitting() {
        val kernel = NativeMemoryKernel(); val initial = kernel.schedule(emptyList(), null, 0, Rating.GOOD, 0.9f)
        assertEquals(2.3065f, initial.memory.stability, 0.00001f)
        val histories = (0 until 120).flatMap { card ->
            val reviews = listOf(Review(3,0), Review(if (card % 5 == 0) 1 else 3,2), Review(3,4), Review(if (card % 7 == 0) 1 else 4,10))
            (2..4).map { History(reviews.take(it)) }
        }
        val fitted = kernel.fit(histories, 123456L); assertEquals(21, fitted.size); kernel.validate(fitted)
        assertTrue(kernel.evaluate(fitted, histories).log_loss.isFinite())
    }
    @Test fun roomMigrationFromVersionOne() = runBlocking<Unit> {
        val name = "migration-${UUID.randomUUID()}.db"
        context.openOrCreateDatabase(WordPrismDatabase.file(context, name).absolutePath, Context.MODE_PRIVATE, null).use { sqlite ->
            sqlite.execSQL("CREATE TABLE records (bucket TEXT NOT NULL, record_key TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(bucket, record_key))")
            sqlite.execSQL("INSERT INTO records VALUES ('_root','schemaVersion','1')"); sqlite.version = 1
        }
        val store = RoomStateStore(WordPrismDatabase.open(context, name)); assertEquals(2, store.read().schemaVersion)
        assertTrue(WordPrismDatabase.file(context, name).canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + java.io.File.separator))
        store.close(); context.deleteDatabase(WordPrismDatabase.file(context, name).absolutePath)
    }
    @Test fun applicationFacadeSchedulesLocalWorkManagerTask() = runBlocking<Unit> {
        val sdk = AndroidWordPrism.open(context)
        assertSame(sdk, AndroidWordPrism.open(context))
        val result = sdk.execute(Request(UUID.randomUUID().toString(), Command.FitMemory()))
        assertNull(result.fault)
        val id = (result.value as View.OperationResult).operation.id
        val completed = withTimeout(30_000) {
            var current: Operation
            do {
                delay(100)
                current = (sdk.query(Query.GetOperation(id)).value as View.OperationResult).operation
            } while (current.status == OperationStatus.QUEUED || current.status == OperationStatus.RUNNING)
            current
        }
        assertEquals(OperationStatus.SUCCEEDED, completed.status)
        val evaluation = sdk.query(Query.GetEvaluation(completed.resultIds.first())).value as View.EvaluationResult
        assertFalse(evaluation.evaluation.accepted)
    }
}
