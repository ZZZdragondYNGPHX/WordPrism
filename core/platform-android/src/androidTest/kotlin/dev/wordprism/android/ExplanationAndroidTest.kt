package dev.wordprism.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wordprism.contract.*
import dev.wordprism.engine.*
import dev.wordprism.memory.NativeMemoryKernel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android Room/Keystore/JNI with an explicitly scripted provider, never a live API. */
@RunWith(AndroidJUnit4::class)
class ExplanationAndroidTest {
    @Test fun offlineExplanationReopensAndBackupRetainsHistoryAndCandidateIdentity() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "explanation-${UUID.randomUUID()}.db"
        val restoreName = "explanation-restore-${UUID.randomUUID()}.db"
        val credentials = KeystoreCredentials(context)
        var store = RoomStateStore(WordPrismDatabase.open(context, name))
        var restored: RoomStateStore? = null
        val credentialRefs = mutableListOf<Id>()
        var calls = 0
        val provider = object : LlmProvider {
            override suspend fun generate(request: ProviderRequest, secret: CharArray): ProviderResponse {
                secret.fill('\u0000'); calls++
                return ProviderResponse(Contract.json.parseToJsonElement("""{
                    "content":{"explanation":"Android 合成讲解","englishDefinition":"synthetic definition",
                        "mnemonic":{"story":"Android 上的虚构助记场景","cue":"这只是记忆联想"}},
                    "recommendations":[{"spelling":"linearity","reason":"合成派生关系","content":{"spelling":"linearity","meaning":"线性","partOfSpeech":"noun"}}]
                }"""), 19, 31)
            }
            override suspend fun listModels(request: ModelCatalogRequest, secret: CharArray): List<ApiModel> {
                secret.fill('\u0000'); error("No model discovery in this test")
            }
            override fun cancel(operationId: Id) = Unit
        }
        var core = CoreEngine(store, credentials, NativeMemoryKernel(), provider = provider)
        suspend fun execute(key: String, command: Command): View = core.execute(Request(key, command)).let {
            assertNull(it.fault?.toString(), it.fault); it.value!!
        }
        try {
            execute("seed", Command.SaveWords(listOf(WordSeed("linear", "线性的"))))
            val preset = ApiPreset(Id("android-explanation-preset"), name = "Scripted test", baseUrl = "https://example.invalid/v1", model = "scripted")
            execute("preset", Command.SavePreset(preset)); assertNull(core.setCredential(preset.id, UUID.randomUUID().toString().toCharArray()).fault)
            credentialRefs += store.read().credentialRefs.values
            execute("binding", Command.BindPreset(Binding(Feature.EXPLANATION, preset.id)))
            val sense = store.read().senses.values.single()
            val command = Command.ExplainSense(Ref(sense.id, sense.version))
            val op = (execute("explain", command) as View.OperationResult).operation
            core.tasks.run(op.id, false); assertEquals(0, calls)
            store.close(); store = RoomStateStore(WordPrismDatabase.open(context, name))
            core = CoreEngine(store, credentials, NativeMemoryKernel(), provider = provider)
            assertNull(core.recover().fault)
            assertEquals(op.id, (execute("explain", command) as View.OperationResult).operation.id)
            core.tasks.run(op.id, true)
            val support = core.query(Query.GetExplanation(sense.id)).value as View.Explanation
            assertEquals(OperationStatus.SUCCEEDED, support.operation!!.status)
            assertEquals("Android 上的虚构助记场景", support.explanation!!.content.mnemonic!!.story)
            assertEquals(1, store.read().words.size)
            val accepted = execute("accept", Command.AcceptRecommendations(support.recommendations.map { it.id }, false)) as View.OperationResult
            assertEquals(2, store.read().words.size); assertEquals(1, calls)
            val session = execute("start", Command.StartSession()) as View.SessionState
            val question = execute("next", Command.NextActivity(session.session.id)) as View.SessionState
            val activity = store.read().activities.getValue(question.activity!!.id)
            execute("answer", Command.SubmitAnswer(session.session.id, question.session.currentPresentationId!!, Ref(activity.id, activity.version),
                AnswerInput(activity.acceptedAnswers.first())))
            val expected = store.read()
            val bytes = ByteArrayOutputStream(); assertNull(core.exportPackage(PackagePurpose.BACKUP, bytes).fault)
            restored = RoomStateStore(WordPrismDatabase.open(context, restoreName))
            val target = CoreEngine(restored!!, credentials, NativeMemoryKernel(), provider = provider)
            val preview = target.previewPackage(ByteArrayInputStream(bytes.toByteArray())); assertNull(preview.fault)
            val restore = target.execute(Request("restore", Command.RestoreBackup((preview.value as View.ImportPreview).preview.token)))
            assertNull(restore.fault)
            assertEquals(expected.senses, restored!!.read().senses); assertEquals(expected.answers, restored!!.read().answers)
            assertEquals(expected.recommendations, restored!!.read().recommendations); assertTrue(restored!!.read().credentialRefs.isEmpty())
            assertNull(target.execute(Request("undo-support", Command.UndoOperation(op.id))).fault)
            assertEquals(expected.answers, restored!!.read().answers)
            assertTrue(restored!!.read().words.values.any { it.spelling == "linearity" && !it.archived })
            assertNull((target.query(Query.GetExplanation(sense.id)).value as View.Explanation).explanation)
            assertEquals(accepted.operation.id, restored!!.read().recommendations.values.single().acceptedOperationId)
            assertEquals(1, calls)
        } finally {
            store.close(); restored?.close(); credentialRefs.forEach(credentials::delete)
            context.deleteDatabase(WordPrismDatabase.file(context, name).absolutePath)
            context.deleteDatabase(WordPrismDatabase.file(context, restoreName).absolutePath)
        }
    }
}
