package dev.wordprism.jvm

import dev.wordprism.contract.*
import dev.wordprism.engine.*
import dev.wordprism.memory.NativeMemoryKernel
import java.nio.file.Path
import java.sql.DriverManager
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class PersistenceTest {
    @TempDir lateinit var folder: Path
    private val credentials = object : CredentialStore {
        override fun has(presetId: Id) = false
        override fun read(presetId: Id): CharArray? = null
        override fun write(presetId: Id, secret: CharArray) = error("unused")
        override fun delete(presetId: Id) = Unit
    }
    @Test fun `SQL read failures return the stable storage fault`() = runBlocking {
        val store = SqliteStateStore(folder.resolve("closed.db"))
        val core = CoreEngine(store, credentials, NativeMemoryKernel())
        store.close()
        val result = core.query(Query.Library())
        assertEquals(ErrorCode.STORAGE, result.fault?.code)
        assertTrue(result.fault!!.retryable)
    }
    @Test fun `SQLite restart keeps drafts presentation answers and request identities`() = runBlocking {
        val path = folder.resolve("core.db")
        val store = SqliteStateStore(path); val core = CoreEngine(store, credentials, NativeMemoryKernel()); core.recover()
        val added = core.execute(Request("add", Command.SaveWords(listOf(WordSeed("linear", "线性的")))))
        assertNull(added.fault)
        core.execute(Request("draft", Command.SaveDraft("intake", "linen")))
        val session = (core.execute(Request("start", Command.StartSession())).value as View.SessionState).session
        val question = core.execute(Request("next", Command.NextActivity(session.id))).value as View.SessionState
        val answerCommand = Command.SubmitAnswer(session.id, question.session.currentPresentationId!!, Ref(question.activity!!.id, 1), AnswerInput("linear"))
        val saved = core.execute(Request("answer", answerCommand)); assertNull(saved.fault); val before = store.read(); store.close()
        SqliteStateStore(path).use { restoredStore ->
            val resumed = CoreEngine(restoredStore, credentials, NativeMemoryKernel()); resumed.recover()
            assertNull(resumed.execute(Request("answer", answerCommand)).fault)
            val after = restoredStore.read(); assertEquals(before.answers, after.answers); assertEquals(before.schedules, after.schedules)
            assertEquals("linen", after.drafts["intake"]!!.text); assertEquals(1, after.answers.size)
        }
    }
    @Test fun `two SQLite connections serialize duplicate commands transactionally`() = runBlocking {
        val path = folder.resolve("concurrent.db")
        SqliteStateStore(path).use { first -> SqliteStateStore(path).use { second ->
            val a = CoreEngine(first, credentials, NativeMemoryKernel()); val b = CoreEngine(second, credentials, NativeMemoryKernel())
            a.recover()
            val request = Request("same-key", Command.SaveWords(listOf(WordSeed("liner", "衬里"))))
            val results = listOf(a,b).map { async(Dispatchers.IO) { it.execute(request) } }.awaitAll()
            results.forEach { assertNull(it.fault) }; assertEquals(1, first.read().words.size)
            assertEquals((results[0].value as View.OperationResult).operation.id, (results[1].value as View.OperationResult).operation.id)
        } }
    }
    @Test fun `transaction failure and legacy schema migration preserve committed data`() = runBlocking {
        val path = folder.resolve("rollback.db")
        SqliteStateStore(path).use { store ->
            store.transaction { it.schemaVersion = 1; it.drafts["original"] = Draft("original", "saved", 1, 0) }
            assertFailsWith<IllegalStateException> { store.transaction { it.drafts.clear(); error("injected failure") } }
            assertEquals("saved", store.read().drafts["original"]!!.text)
            store.transaction { it.settings = it.settings.copy(zoneId = "America/Los_Angeles") }
        }
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().executeQuery("SELECT payload FROM records WHERE bucket='_root' AND record_key='schemaVersion'").use { rows ->
                assertTrue(rows.next()); assertEquals("2", rows.getString(1))
            }
        }
    }
}
