package app.wordprism.mock

import app.wordprism.sdk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.InputStream
import java.io.OutputStream
import java.time.ZoneId

/** Regression coverage for the debug SDK implementation. */
class MockSdkTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var sdk: MockSdk
    private val noDocs = object : DocumentIo {
        override fun openInput(uri: String): InputStream = error("unused")
        override fun openOutput(uri: String): OutputStream = error("unused")
    }

    @Before fun setUp() {
        sdk = MockSdk(tmp.newFile("db.json").also { it.delete() }, noDocs)
        sdk.controls.setLatency(MockScenario.Latency.Fast)
    }

    private fun req(s: String) = ClientRequestId(s)
    private suspend fun awaitTerminalOrInput(id: OperationId) = withTimeout(10_000) {
        sdk.operations.observe(id).first { it != null && (it.status.isTerminal || it.status == OperationStatus.NeedsInput) }!!
    }

    @Test fun intakeCreatesGroupAndResubmitIsIdempotent() = runBlocking {
        val a = (sdk.intake.submit(IntakeRequest("linear 和 liner 我总是搞混"), req("r1")) as SdkResult.Ok).value
        val b = (sdk.intake.submit(IntakeRequest("linear 和 liner 我总是搞混"), req("r1")) as SdkResult.Ok).value
        assertEquals("same client request id returns same operation", a, b)
        assertEquals(OperationStatus.Succeeded, awaitTerminalOrInput(a).status)
        val outcome = sdk.intake.observe(a).first()!!.outcome!!
        assertEquals(IntakeEffectKind.CreatedGroup, outcome.effects.first().kind)

        // A new request with the same words reuses the group instead of duplicating it.
        val c = (sdk.intake.submit(IntakeRequest("linear, liner"), req("r2")) as SdkResult.Ok).value
        awaitTerminalOrInput(c)
        assertEquals(IntakeEffectKind.ReusedGroup, sdk.intake.observe(c).first()!!.outcome!!.effects.first().kind)
        val groups = sdk.library.observeLibrary(LibraryQuery("liner")).first().groups
        assertEquals(1, groups.size)
    }

    @Test fun cancelBeforeCommitWritesNothing() = runBlocking {
        sdk.controls.setLatency(MockScenario.Latency.Slow)
        val before = sdk.library.observeLibrary(LibraryQuery()).first().totalGroups
        val id = (sdk.intake.submit(IntakeRequest("desert dessert"), req("c1")) as SdkResult.Ok).value
        assertTrue(sdk.operations.cancel(id) is SdkResult.Ok)
        assertEquals(OperationStatus.Cancelled, sdk.operations.observe(id).first()!!.status)
        assertEquals(before, sdk.library.observeLibrary(LibraryQuery()).first().totalGroups)
    }

    @Test fun lateRunningStatusDoesNotRestoreACancelledOperation() = runBlocking {
        val core = MockCore(tmp.newFile("race.json").also { it.delete() }, noDocs, System::currentTimeMillis, ZoneId.systemDefault())
        val op = core.newOperation(core.db.value, OperationKind.Intake, "test", FeatureId.WordIntake)
        core.mutate { db -> with(core) { db.withOp(op) } }
        assertTrue(core.cancel(op.id) is SdkResult.Ok)
        core.setStatus(op.id.value, OperationStatus.Running)
        assertEquals(OperationStatus.Cancelled, core.db.value.operations.getValue(op.id.value).status)
        core.scope.cancel()
    }

    @Test fun undoIntakeRemovesCreatedGroup() = runBlocking {
        val id = (sdk.intake.submit(IntakeRequest("desert dessert"), req("u1")) as SdkResult.Ok).value
        awaitTerminalOrInput(id)
        val withGroup = sdk.library.observeLibrary(LibraryQuery()).first().totalGroups
        assertTrue(sdk.operations.undo(id, req("u2")) is SdkResult.Ok)
        assertEquals(withGroup - 1, sdk.library.observeLibrary(LibraryQuery()).first().totalGroups)
        assertTrue("second undo is rejected", sdk.operations.undo(id, req("u3")) is SdkResult.Err)
    }

    @Test fun ambiguousSingleWordAsksUser() = runBlocking {
        // "adopts"-like single word near an existing group without stated confusion -> choice
        val id = (sdk.intake.submit(IntakeRequest("adapts"), req("a1")) as SdkResult.Ok).value
        val op = awaitTerminalOrInput(id)
        assertEquals(OperationStatus.NeedsInput, op.status)
        val choice = sdk.intake.observe(id).first()!!.pendingChoice!!
        assertTrue(choice.options.any { it.kind == "keep" })
        sdk.intake.resolve(id, listOf("keep"), req("a2"))
        assertEquals(OperationStatus.Succeeded, sdk.operations.observe(id).first()!!.status)
    }

    @Test fun answerIsScoredOnceAndDayEndAnalysesOnce() = runBlocking {
        val sid = (sdk.study.startOrResume(req("s1")) as SdkResult.Ok).value
        val st = sdk.study.observeSession(sid).first()!!
        val act = st.current!!
        val ans = when (act) {
            is ActivityView.Familiarity -> Answer.Familiarity(true)
            is ActivityView.Contrast -> Answer.Acknowledge
            is ActivityView.Choice -> Answer.Choice(act.options.first().id)
            else -> Answer.Text("x")
        }
        val f1 = (sdk.study.submitAnswer(sid, act.id, ans, req("ans1")) as SdkResult.Ok).value
        val f2 = (sdk.study.submitAnswer(sid, act.id, ans, req("ans1")) as SdkResult.Ok).value
        assertEquals(f1, f2)
        assertEquals(1, sdk.study.observeLearningDay().first().completedItems)

        // Need a scored answer for "new data": answer a choice question.
        sdk.study.advance(sid, req("adv1"))
        var cur = sdk.study.observeSession(sid).first()!!.current
        while (cur != null && cur !is ActivityView.Choice) {
            sdk.study.submitAnswer(sid, cur.id, if (cur is ActivityView.Contrast) Answer.Acknowledge else Answer.Familiarity(true), req("x-${cur.id.value}"))
            sdk.study.advance(sid, req("adv-${cur.id.value}")); cur = sdk.study.observeSession(sid).first()!!.current
        }
        sdk.study.submitAnswer(sid, cur!!.id, Answer.Choice((cur as ActivityView.Choice).options.first().id), req("choice"))

        val d1 = (sdk.study.finishLearningDay(req("end1")) as SdkResult.Ok).value
        val op = d1.dailyAnalysis.operationId!!
        val d2 = (sdk.study.finishLearningDay(req("end2")) as SdkResult.Ok).value
        assertEquals("day end twice -> same single analysis", op, d2.dailyAnalysis.operationId)
        assertEquals(OperationStatus.Succeeded, awaitTerminalOrInput(op).status)
        assertTrue(sdk.adaptation.observeChangeSets().first().first().operationId == op)
    }

    @Test fun turningAutoOffCancelsQueuedAutoAnalysisAndLateResultDoesNotApply() = runBlocking {
        sdk.controls.setSimulateOffline(true)
        val sid = (sdk.study.startOrResume(req("s")) as SdkResult.Ok).value
        val act = sdk.study.observeSession(sid).first()!!.current!!
        sdk.study.submitAnswer(sid, act.id, if (act is ActivityView.Familiarity) Answer.Familiarity(false) else Answer.Acknowledge, req("a"))
        val opId = (sdk.study.finishLearningDay(req("e")) as SdkResult.Ok).value.dailyAnalysis.operationId!!
        withTimeout(5_000) { sdk.operations.observe(opId).first { it?.status == OperationStatus.WaitingNetwork } }
        val policyBefore = sdk.study.observePlan().first().policyVersion
        sdk.adaptation.setMode(AnalysisMode.Off)
        sdk.controls.setSimulateOffline(false)
        assertEquals(OperationStatus.Cancelled, sdk.operations.observe(opId).first()!!.status)
        assertEquals(policyBefore, sdk.study.observePlan().first().policyVersion)
    }

    @Test fun stateSurvivesRestart() = runBlocking {
        val file = tmp.newFile("persist.json").also { it.delete() }
        val first = MockSdk(file, noDocs).apply { controls.setLatency(MockScenario.Latency.Fast) }
        val sid = (first.study.startOrResume(req("p1")) as SdkResult.Ok).value
        val act = first.study.observeSession(sid).first()!!.current!!
        first.study.saveDraft(sid, act.id, "draft text")
        val second = MockSdk(file, noDocs)
        val day = second.study.observeLearningDay().first()
        assertEquals(sid, day.openSessionId)
    }
}
