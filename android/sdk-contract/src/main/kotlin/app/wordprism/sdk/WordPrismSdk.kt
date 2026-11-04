package app.wordprism.sdk

import kotlinx.coroutines.flow.Flow

/** Entry point the UI depends on. Real implementation: Codex local core. Interim: :sdk-mock. */
interface WordPrismSdk {
    val contractVersion: String
    /** Human-readable description of the implementation, e.g. "模拟后端 0.1". Shown in settings. */
    val implementationLabel: String
    val isMock: Boolean

    val library: LibraryApi
    val intake: IntakeApi
    val study: StudyApi
    val overview: OverviewApi
    val adaptation: AdaptationApi
    val presets: PresetsApi
    val dataTransfer: DataTransferApi
    val operations: OperationsApi

    fun observeConnectivity(): Flow<Connectivity>
    /** Persistent read/initialization errors; commands still return their own SdkResult. */
    fun observeErrors(): Flow<SdkError?> = kotlinx.coroutines.flow.flowOf(null)
    suspend fun retryReads() = Unit
}

const val CONTRACT_VERSION = "0.1.0-draft"
