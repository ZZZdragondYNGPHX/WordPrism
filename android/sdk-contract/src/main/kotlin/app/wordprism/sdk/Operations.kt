package app.wordprism.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Persistent business operation (AI task, import, undoable write…). Survives process death. */
@Serializable
data class Operation(
    val id: OperationId,
    val kind: OperationKind,
    val title: String,
    val status: OperationStatus,
    /** Only what the backend actually knows; null means indeterminate. */
    val progress: OperationProgress? = null,
    val feature: FeatureId? = null,
    val presetName: String? = null,
    val presetVersion: Int? = null,
    val actions: Set<OperationAction> = emptySet(),
    val error: SdkError? = null,
    val resultSummary: String? = null,
    val createdAt: EpochMillis,
    val updatedAt: EpochMillis,
    /** Known provider usage (may be unknown even when the operation was cancelled). */
    val usage: UsageFigure? = null,
)

@Serializable
data class OperationProgress(val done: Int, val total: Int, val label: String? = null)

@JvmInline
@Serializable
value class OperationKind(val value: String) {
    companion object {
        val Intake = OperationKind("intake")
        val Recommendation = OperationKind("recommendation")
        val AcceptRecommendation = OperationKind("accept_recommendation")
        val Generation = OperationKind("generation")
        val WritingFeedback = OperationKind("writing_feedback")
        val Analysis = OperationKind("analysis")
        val ConnectionTest = OperationKind("connection_test")
        val Import = OperationKind("import")
        val Export = OperationKind("export")
        val Restore = OperationKind("restore")
        val Edit = OperationKind("edit")
        val Undo = OperationKind("undo")
    }
}

@JvmInline
@Serializable
value class OperationStatus(val value: String) {
    val isTerminal: Boolean get() = value in setOf("succeeded", "failed", "cancelled", "undone", "merged")
    val isActive: Boolean get() = !isTerminal
    companion object {
        val Queued = OperationStatus("queued")
        val WaitingNetwork = OperationStatus("waiting_network")
        val Running = OperationStatus("running")
        val NeedsInput = OperationStatus("needs_input")
        val Succeeded = OperationStatus("succeeded")
        val Failed = OperationStatus("failed")
        val Cancelled = OperationStatus("cancelled")
        val Undone = OperationStatus("undone")
        /** Superseded by a newer task (e.g. stale daily analyses merged). */
        val Merged = OperationStatus("merged")
    }
}

@JvmInline
@Serializable
value class OperationAction(val value: String) {
    companion object {
        val Cancel = OperationAction("cancel")
        val Retry = OperationAction("retry")
        val Undo = OperationAction("undo")
        val Resolve = OperationAction("resolve")
    }
}

@Serializable
data class UsageFigure(
    val requests: Int,
    val inputTokens: Long?,
    val outputTokens: Long?,
    /** Null when price data is unavailable; never fabricated. */
    val estimatedCost: String? = null,
)

interface OperationsApi {
    fun observe(id: OperationId): Flow<Operation?>
    /** Active and recently finished operations, newest first. */
    fun observeRecent(): Flow<List<Operation>>
    suspend fun cancel(id: OperationId): SdkResult<Operation>
    suspend fun retry(id: OperationId, request: ClientRequestId): SdkResult<Operation>
    suspend fun undo(id: OperationId, request: ClientRequestId): SdkResult<UndoOutcome>
}

@Serializable
data class UndoOutcome(
    val undoOperationId: OperationId,
    val summary: String,
    /** Things deliberately kept (e.g. answers made after the change). */
    val preserved: List<String> = emptyList(),
)
