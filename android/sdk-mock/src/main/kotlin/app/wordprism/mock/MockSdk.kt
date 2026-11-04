package app.wordprism.mock

import app.wordprism.sdk.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.time.ZoneId

/**
 * Contract-compatible mock of the WordPrism local SDK. Swap for the Codex implementation in
 * app/src/main/java/app/wordprism/di/SdkProvider.kt — nothing else in the app references this module
 * except the clearly separated developer panel.
 */
class MockSdk(
    storeFile: File,
    documents: DocumentIo,
    clock: () -> Long = System::currentTimeMillis,
    zone: ZoneId = ZoneId.systemDefault(),
) : WordPrismSdk {
    private val core = MockCore(storeFile, documents, clock, zone)
    private val insights = MockInsights(core)

    override val contractVersion = CONTRACT_VERSION
    override val implementationLabel = "模拟后端（mock 0.1）"
    override val isMock = true

    override val library: LibraryApi = MockLibrary(core)
    override val intake: IntakeApi = MockIntake(core)
    override val study: StudyApi = MockStudy(core) { insights }
    override val overview: OverviewApi = insights
    override val adaptation: AdaptationApi = insights
    override val presets: PresetsApi = MockPresets(core, insights)
    override val dataTransfer: DataTransferApi = MockTransfer(core)
    override val operations: OperationsApi = object : OperationsApi {
        override fun observe(id: OperationId) = core.db.map { it.operations[id.value] }.distinctUntilChanged()
        override fun observeRecent() = core.db.map { d -> d.operations.values.sortedByDescending { it.createdAt }.take(30) }.distinctUntilChanged()
        override suspend fun cancel(id: OperationId) = core.cancel(id)
        override suspend fun retry(id: OperationId, request: ClientRequestId): SdkResult<Operation> {
            val op = core.db.value.operations[id.value] ?: return SdkResult.Err(SdkError(ErrorCode.NotFound, "任务不存在", false))
            if (OperationAction.Retry !in op.actions) return SdkResult.Err(SdkError(ErrorCode.Validation, "这个任务不能重试", false))
            core.mutate { d -> with(core) { d.updateOp(id.value) { it.copy(status = OperationStatus.Queued, error = null, actions = setOf(OperationAction.Cancel)) } } }
            core.launch(id.value)
            return SdkResult.Ok(core.db.value.operations.getValue(id.value))
        }
        override suspend fun undo(id: OperationId, request: ClientRequestId) = core.undoOperation(id, request)
    }

    override fun observeConnectivity() = combine(core.online, core.db) { o, d ->
        Connectivity(o, d.operations.values.count { it.status == OperationStatus.WaitingNetwork })
    }.distinctUntilChanged()

    // ---------------------------------------------------------------- platform + developer hooks

    /** Real device connectivity, fed by the app (a real backend would observe ConnectivityManager itself). */
    fun setDeviceOnline(online: Boolean) { core.deviceOnline.value = online }

    val controls = Controls()

    inner class Controls {
        val scenario: StateFlow<MockScenario> get() = core.scenario
        fun setSimulateOffline(v: Boolean) = core.scenario.update { it.copy(simulateOffline = v) }
        fun setLatency(l: MockScenario.Latency) = core.scenario.update { it.copy(latency = l) }
        fun failNextAi(code: ErrorCode?) = core.scenario.update { it.copy(failNextAi = code) }
        /** Jump today's effective time to just before the budget to exercise the budget prompt. */
        suspend fun nearBudget() = core.mutate { d ->
            val today = core.today()
            val day = d.days[today] ?: return@mutate d
            d.copy(days = d.days + (today to day.copy(effectiveSeconds = d.settings.budgetMinutes * 60L - 2, budgetPromptAnswered = false)))
        }
        /** Re-open today (as if a new learning day began) without wiping the library. */
        suspend fun newLearningDay() = core.mutate { d -> d.copy(days = d.days - core.today()) }
        suspend fun resetAll() = core.mutate { MockSeed.create(core.clock(), core.zone) }
    }

    init { core.resumeAfterRestart() }
}
