package app.wordprism.sdk

import kotlinx.serialization.Serializable

/**
 * DRAFT frontend-facing contract for the WordPrism local SDK.
 *
 * Authored by the frontend (Claude) from docs/plan/frontend-backend-boundary.md because no
 * backend contract existed yet. Ownership transfers to Codex's `core-contract`: Codex may adopt
 * these types directly or ship its own and provide a thin adapter implementing [WordPrismSdk].
 * Gaps and open questions are tracked in docs/frontend/contract-requirements.md.
 *
 * Rules of the road:
 *  - The UI never computes business facts (scores, schedules, grouping, budgets). Everything it
 *    shows comes from these types.
 *  - Every enum-like value that may grow uses a string-backed value class with an `Unknown`
 *    recovery path so an older UI survives a newer core.
 *  - All mutating calls take a client-generated [ClientRequestId] for idempotency.
 */

@JvmInline
@Serializable
value class ClientRequestId(val value: String)

@JvmInline
@Serializable
value class OperationId(val value: String)

/** Epoch millis. Actual instant of an event; local-date display uses [LocalDay]. */
typealias EpochMillis = Long

/** ISO local date string (yyyy-MM-dd) in the user's effective learning timezone. */
@JvmInline
@Serializable
value class LocalDay(val iso: String)

/** Stable error code. Unknown codes must be rendered with [SdkError.message] and [SdkError.retryable]. */
@JvmInline
@Serializable
value class ErrorCode(val value: String) {
    companion object {
        val Offline = ErrorCode("offline")
        val VersionConflict = ErrorCode("version_conflict")
        val NotFound = ErrorCode("not_found")
        val Validation = ErrorCode("validation")
        val PresetMissing = ErrorCode("preset_missing")
        val CredentialMissing = ErrorCode("credential_missing")
        val QuotaExhausted = ErrorCode("quota_exhausted")
        val ProviderTimeout = ErrorCode("provider_timeout")
        val ProviderRateLimited = ErrorCode("provider_rate_limited")
        val ProviderBadOutput = ErrorCode("provider_bad_output")
        val ProviderTruncated = ErrorCode("provider_truncated")
        val NotCancellable = ErrorCode("not_cancellable")
        val NotUndoable = ErrorCode("not_undoable")
        val UnsupportedFormat = ErrorCode("unsupported_format")
        val Internal = ErrorCode("internal")
    }
}

/**
 * @property message user-facing Chinese text produced by the backend (the UI may prefix context).
 * @property fieldErrors validation errors keyed by request field name.
 */
@Serializable
data class SdkError(
    val code: ErrorCode,
    val message: String,
    val retryable: Boolean,
    val fieldErrors: Map<String, String> = emptyMap(),
)

sealed interface SdkResult<out T> {
    data class Ok<T>(val value: T) : SdkResult<T>
    data class Err(val error: SdkError) : SdkResult<Nothing>
}

inline fun <T> SdkResult<T>.onOk(block: (T) -> Unit): SdkResult<T> { if (this is SdkResult.Ok) block(value); return this }
inline fun <T> SdkResult<T>.onErr(block: (SdkError) -> Unit): SdkResult<T> { if (this is SdkResult.Err) block(error); return this }
fun <T> SdkResult<T>.valueOrNull(): T? = (this as? SdkResult.Ok)?.value

/** Result of a synchronous local write. [undoOperation] is present when the write can be undone. */
@Serializable
data class WriteReceipt(
    val newVersion: Long,
    val operationId: OperationId,
    val undoable: Boolean,
    val summary: String,
)

/** Where content came from; displayed so AI content is never presented as dictionary fact. */
@Serializable
data class ContentSource(
    val kind: String, // "ai_generated" | "user" | "imported" | "dictionary" | other
    val presetName: String? = null,
    val presetVersion: Int? = null,
    val model: String? = null,
    val generatedAt: EpochMillis? = null,
) {
    val isAi: Boolean get() = kind == "ai_generated"
}

@Serializable
data class Connectivity(val online: Boolean, val pendingNetworkOperations: Int)

/** Whether an action will call a model API, and with which preset. Lets the UI disclose cost before acting. */
@Serializable
data class ApiRequirement(
    val feature: FeatureId,
    val requiresApi: Boolean,
    val presetId: String?,
    val presetName: String?,
    val readiness: ApiReadiness,
    val note: String? = null,
)

@JvmInline
@Serializable
value class ApiReadiness(val value: String) {
    companion object {
        val Ready = ApiReadiness("ready")
        val NeedsPreset = ApiReadiness("needs_preset")
        val NeedsCredential = ApiReadiness("needs_credential")
        val QueuedOffline = ApiReadiness("queued_offline")
        val QuotaExhausted = ApiReadiness("quota_exhausted")
        val NotRequired = ApiReadiness("not_required")
    }
}

@JvmInline
@Serializable
value class FeatureId(val value: String) {
    companion object {
        val WordIntake = FeatureId("word_intake")
        val WordRecommendation = FeatureId("word_recommendation")
        val Explanation = FeatureId("explanation")
        val PracticeGeneration = FeatureId("practice_generation")
        val WritingFeedback = FeatureId("writing_feedback")
        val LearningAnalysis = FeatureId("learning_analysis")
    }
}
