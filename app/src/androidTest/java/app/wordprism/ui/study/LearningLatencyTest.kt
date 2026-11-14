package app.wordprism.ui.study

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.wordprism.android.RoomStateStore
import dev.wordprism.android.WordPrismDatabase
import dev.wordprism.android.KeystoreCredentials
import dev.wordprism.android.StoredRecord
import dev.wordprism.engine.StateCodec
import kotlinx.serialization.json.*
import dev.wordprism.contract.*
import dev.wordprism.engine.CoreEngine
import dev.wordprism.memory.NativeMemoryKernel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.system.measureNanoTime

/** Optional local benchmark. Private input is never a checked-in test fixture or logged. */
class LearningLatencyTest {
    @Test fun localBackupPreviewRestoreAndColdReopen() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(context.packageName.endsWith(".managedtest"))
        val path = InstrumentationRegistry.getArguments().getString("benchmarkBackup")
        assumeTrue(path != null)
        val name = "backup-roundtrip-${UUID.randomUUID()}.db"
        fun open() = RoomStateStore(WordPrismDatabase.open(context, name))
        fun engine(store: RoomStateStore) = CoreEngine(store, KeystoreCredentials(context), NativeMemoryKernel())
        try {
            val token = open().use { store ->
                val core = engine(store)
                assertNull(core.recover().fault)
                val preview = File(path!!).inputStream().use { core.previewPackage(it) }
                assertNull(preview.fault)
                (preview.value as View.ImportPreview).preview.token
            }
            val restored = open().use { store ->
                val core = engine(store)
                assertNull(core.query(Query.GetPackagePreview(token)).fault)
                assertNull(core.execute(Request(UUID.randomUUID().toString(), Command.RestoreBackup(token))).fault)
                store.read().also { assertTrue(it.words.isNotEmpty()) }
            }
            open().use { store -> assertTrue("Restored state survives cold reopen", restored == store.read()) }
        } finally { context.deleteDatabase(WordPrismDatabase.file(context, name).absolutePath) }
    }

    @Test fun localBackupLearningLatency() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(context.packageName.endsWith(".managedtest"))
        val path = InstrumentationRegistry.getArguments().getString("benchmarkBackup")
        assumeTrue(path != null)
        val name = "latency-${UUID.randomUUID()}.db"
        val database = WordPrismDatabase.open(context, name)
        val store = RoomStateStore(database)
        val core = CoreEngine(store, KeystoreCredentials(context), NativeMemoryKernel())
        fun request(command: Command) = Request(UUID.randomUUID().toString(), command)
        try {
            // Seed the isolated benchmark directly; package-preview latency is a separate operation.
            val data = java.util.zip.ZipFile(File(path!!)).use { zip ->
                val root = mutableMapOf<String, JsonElement>()
                zip.entries().asSequence().filter { it.name.startsWith("modules/") }.forEach { entry ->
                    root.putAll(Contract.json.parseToJsonElement(zip.getInputStream(entry).bufferedReader().use { it.readText() }).jsonObject)
                }
                StateCodec.decodeJson(JsonObject(root))
            }
            data.settings = data.settings.copy(learningMode = LearningMode.STANDARD, automaticAnalysis = false, automaticGeneration = false)
            database.records().put(StateCodec.rows(data).map { StoredRecord(it.bucket, it.key, it.payload) })
            assertNull(core.recover().fault)
            val start = core.execute(request(Command.StartSession(continuePastBudget = true, includeIntroductions = true)))
            assertNull(start.fault)
            val sessionId = (start.value as View.SessionState).session.id
            // Keep the benchmark repeatable even if the real day's plan was already completed.
            store.transaction { db ->
                val session = db.sessions.getValue(sessionId)
                db.sessions[sessionId] = session.copy(status = SessionStatus.ACTIVE, targetIds = db.targets.keys.take(10),
                    completedTargets = emptySet(), currentActivityId = null, currentPresentationId = null,
                    awaitingAdvance = false, lastAnswerId = null)
            }
            val reads = List(10) { measureNanoTime { assertNull(core.query(Query.GetSession(sessionId)).fault) } / 1_000_000.0 }
            val advances = mutableListOf<Double>()
            repeat(5) {
                val shown = core.execute(request(Command.NextActivity(sessionId)))
                assertNull(shown.fault)
                val state = shown.value as View.SessionState
                val card = state.activity
                if (card != null && !state.session.awaitingAdvance) {
                    assertNull(core.execute(request(Command.SubmitAnswer(sessionId, state.session.currentPresentationId!!,
                        Ref(card.id, card.version), AnswerInput("", skipped = true)))).fault)
                }
                advances += measureNanoTime { assertNull(core.execute(request(Command.NextActivity(sessionId))).fault) } / 1_000_000.0
            }
            val result = "session query ms median=${reads.sorted()[5]}, max=${reads.max()}; next activity ms median=${advances.sorted()[2]}, max=${advances.max()}"
            println(result)
            File(context.externalCacheDir, "learning-latency.txt").writeText(result)
        } finally {
            store.close()
            context.deleteDatabase(WordPrismDatabase.file(context, name).absolutePath)
        }
    }
}
