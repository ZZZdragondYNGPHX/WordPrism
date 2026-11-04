package app.wordprism.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class AnalysisMode(val value: String) {
    companion object {
        val DailyAfterDayEnd = AnalysisMode("daily_after_day_end")
        val Off = AnalysisMode("off")
    }
}

@JvmInline @Serializable value class ChangeSetId(val value: String)

@Serializable
data class AdaptationSettings(
    val mode: AnalysisMode,
    val dailyBudgetMinutes: Int,
    val budgetRange: IntRangeDto,
    val requirement: ApiRequirement,
    val lastRun: AnalysisRun?,
    val activeOperationId: OperationId?,
)

@Serializable
data class AnalysisRun(
    val operationId: OperationId,
    val trigger: String, // "auto" | "manual"
    val triggerDay: LocalDay?,
    val executedAt: EpochMillis?,
    val snapshotAsOf: EpochMillis?,
    val status: OperationStatus,
    val summary: String?,
)

@Serializable
data class ChangeSet(
    val id: ChangeSetId,
    val operationId: OperationId,
    val trigger: String,
    val createdAt: EpochMillis,
    val snapshotId: String,
    val snapshotAsOf: EpochMillis,
    val summary: String,
    val rationale: List<String>,
    val changes: List<ParamChange>,
    val status: ChangeStatus,
    val effectiveFrom: String,
    val undo: UndoAvailability,
)

@Serializable
data class ParamChange(
    val capabilityId: String,
    val label: String,
    val before: String,
    val after: String,
    val scope: String,
    val affectsSchedule: Boolean,
)

@JvmInline
@Serializable
value class ChangeStatus(val value: String) {
    companion object {
        val Applied = ChangeStatus("applied")
        val Scheduled = ChangeStatus("scheduled")
        val Undone = ChangeStatus("undone")
        val NoChange = ChangeStatus("no_change")
        val Failed = ChangeStatus("failed")
        val Superseded = ChangeStatus("superseded")
    }
}

@Serializable
data class UndoAvailability(val available: Boolean, val note: String?)

@Serializable
data class Capability(
    val id: String,
    val label: String,
    val description: String,
    val currentValue: String,
    val range: String?,
    val affectsSchedule: Boolean,
    val lockedByUser: Boolean,
    val lockable: Boolean,
)

interface AdaptationApi {
    fun observeSettings(): Flow<AdaptationSettings>
    suspend fun setMode(mode: AnalysisMode): SdkResult<AdaptationSettings>
    suspend fun setDailyBudget(minutes: Int): SdkResult<AdaptationSettings>
    /** Manual analysis. [forceReanalyze]=false lets the backend reuse a result for an identical snapshot. */
    suspend fun analyzeNow(forceReanalyze: Boolean, request: ClientRequestId): SdkResult<OperationId>
    fun observeChangeSets(): Flow<List<ChangeSet>>
    suspend fun undoChangeSet(id: ChangeSetId, request: ClientRequestId): SdkResult<UndoOutcome>
    fun observeCapabilities(): Flow<List<Capability>>
    suspend fun setCapabilityLock(capabilityId: String, locked: Boolean): SdkResult<Unit>
}
