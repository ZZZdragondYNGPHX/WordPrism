package app.wordprism.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@JvmInline @Serializable value class PresetId(val value: String)

@Serializable
data class ProtocolAdapter(val id: String, val label: String, val defaultBaseUrl: String?, val notes: String?)

@Serializable
data class ApiPreset(
    val id: PresetId,
    val name: String,
    val version: Int,
    val adapterId: String,
    val adapterLabel: String,
    val baseUrl: String,
    val model: String,
    val maxOutputTokens: Int?,
    val timeoutSeconds: Int,
    /** Never the secret itself. */
    val credential: CredentialState,
    val enabled: Boolean,
    val lastTest: ConnectionTestResult?,
    val boundFeatures: List<FeatureId>,
)

@Serializable
data class CredentialState(val present: Boolean, val maskedHint: String?)

@Serializable
data class ConnectionTestResult(val ok: Boolean, val at: EpochMillis, val message: String, val latencyMs: Long?)

/**
 * @property credential plaintext only on write; null = keep existing. Empty string = remove.
 *   The UI must not retain it after submitting.
 */
@Serializable
data class PresetDraft(
    val id: PresetId? = null,
    val expectedVersion: Int? = null,
    val name: String,
    val adapterId: String,
    val baseUrl: String,
    val model: String,
    val maxOutputTokens: Int?,
    val timeoutSeconds: Int,
    val credential: String? = null,
)

@Serializable
data class FeatureBinding(
    val feature: FeatureId,
    val label: String,
    val description: String,
    val presetId: PresetId?,
    val usesDefault: Boolean,
    val state: ApiReadiness,
)

@Serializable
data class CallPolicy(
    val defaultPresetId: PresetId?,
    val pregenerationEnabled: Boolean,
    val pregenerationMaxPerDay: Int,
    val dailyRequestLimit: Int?,
    val maxRetries: Int,
    val fallbackPresetId: PresetId?,
    val settingsVersion: Long = 0, val policyVersion: Long = 0,
)

@Serializable
data class PresetDeletionImpact(val boundFeatures: List<String>, val runningOperations: Int, val note: String)

interface PresetsApi {
    fun observeAdapters(): Flow<List<ProtocolAdapter>>
    fun observePresets(): Flow<List<ApiPreset>>
    /** Local validation only; never contacts the provider. */
    suspend fun savePreset(draft: PresetDraft, request: ClientRequestId): SdkResult<ApiPreset>
    suspend fun setPresetEnabled(id: PresetId, enabled: Boolean): SdkResult<ApiPreset>
    suspend fun deletionImpact(id: PresetId): SdkResult<PresetDeletionImpact>
    suspend fun deletePreset(id: PresetId, request: ClientRequestId): SdkResult<Unit>
    /** Real request; may incur provider usage. */
    suspend fun testConnection(id: PresetId, request: ClientRequestId): SdkResult<OperationId>
    fun observeBindings(): Flow<List<FeatureBinding>>
    suspend fun bindFeature(feature: FeatureId, presetId: PresetId?): SdkResult<FeatureBinding>
    fun observeCallPolicy(): Flow<CallPolicy>
    suspend fun setCallPolicy(policy: CallPolicy): SdkResult<CallPolicy>
    fun observeUsage(window: OverviewWindow): Flow<List<UsageRow>>
}
