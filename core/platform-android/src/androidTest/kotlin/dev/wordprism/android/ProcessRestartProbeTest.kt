package dev.wordprism.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wordprism.contract.*
import dev.wordprism.engine.CoreEngine
import dev.wordprism.memory.NativeMemoryKernel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Run methods in separate instrumentation invocations with am force-stop between them. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ProcessRestartProbeTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val name = "wordprism-process-probe.db"
    @Test fun aWriteCommittedState() = runBlocking<Unit> {
        context.deleteDatabase(WordPrismDatabase.file(context, name).absolutePath)
        val store = RoomStateStore(WordPrismDatabase.open(context, name))
        val core = CoreEngine(store, KeystoreCredentials(context), NativeMemoryKernel()); assertNull(core.recover().fault)
        assertNull(core.execute(Request("probe-add", Command.SaveWords(listOf(WordSeed("linear", "线性的"))))).fault)
        val session = (core.execute(Request("probe-start", Command.StartSession())).value as View.SessionState).session
        val question = core.execute(Request("probe-next", Command.NextActivity(session.id))).value as View.SessionState
        val submit = Command.SubmitAnswer(session.id, question.session.currentPresentationId!!, Ref(question.activity!!.id, 1), AnswerInput("linear"))
        assertNull(core.execute(Request("probe-answer", submit)).fault)
        assertNull(core.execute(Request("probe-draft", Command.SaveDraft("process-probe", "saved across process boundary"))).fault)
        assertNull(core.execute(Request("probe-pid", Command.SaveDraft("process-pid", android.os.Process.myPid().toString()))).fault)
        store.close()
    }
    @Test fun bReadAfterProcessRestart() = runBlocking<Unit> {
        val store = RoomStateStore(WordPrismDatabase.open(context, name))
        val core = CoreEngine(store, KeystoreCredentials(context), NativeMemoryKernel()); assertNull(core.recover().fault)
        val state = store.read(); assertEquals(1, state.answers.size); assertEquals(1, state.schedules.values.sumOf { it.reviews })
        assertEquals("saved across process boundary", state.drafts["process-probe"]!!.text)
        assertNotEquals(android.os.Process.myPid(), state.drafts["process-pid"]!!.text.toInt())
        assertEquals(1, state.presentations.size)
        val answer = state.answers.values.single(); val presentation = state.presentations.keys.single()
        assertNull(core.execute(Request("probe-answer", Command.SubmitAnswer(answer.sessionId, presentation, Ref(answer.activityId, answer.activityVersion), answer.input))).fault)
        assertEquals(1, store.read().answers.size)
        store.close(); context.deleteDatabase(WordPrismDatabase.file(context, name).absolutePath)
    }
}
