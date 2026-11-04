package app.wordprism.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * A platform document chosen via the Storage Access Framework. The backend opens it through
 * ContentResolver; no filesystem path is assumed.
 */
@Serializable
data class DocumentRef(val uri: String, val displayName: String?, val sizeBytes: Long?)

@JvmInline
@Serializable
value class PackagePurpose(val value: String) {
    companion object {
        val FullBackup = PackagePurpose("full_backup")
        val ContentPack = PackagePurpose("content_pack")
    }
}

@Serializable
data class ExportRequest(val purpose: PackagePurpose, val groupIds: List<GroupId> = emptyList())

@Serializable
data class ExportScopeInfo(val purpose: PackagePurpose, val included: List<String>, val excluded: List<String>, val suggestedFileName: String)

@Serializable
data class ImportPreview(
    val previewId: String,
    val document: DocumentRef,
    val purpose: PackagePurpose,
    val formatVersion: String,
    val producerVersion: String,
    val createdAt: EpochMillis?,
    val modules: List<ModuleStatus>,
    val counts: ImportCounts,
    val pendingConfiguration: List<String>,
    val warnings: List<String>,
    val canCommit: Boolean,
    val blockingReason: String?,
    /** Full backups replace the current library; content packs append. */
    val replacesLibrary: Boolean,
)

@Serializable
data class ModuleStatus(val id: String, val label: String, val schemaVersion: String, val state: String /* ok | migrate | unknown_optional | unsupported_required */, val stateLabel: String)

@Serializable
data class ImportCounts(val newItems: Int, val duplicates: Int, val conflicts: Int, val invalid: Int)

interface DataTransferApi {
    suspend fun exportScope(request: ExportRequest): SdkResult<ExportScopeInfo>
    /** Writes into [target] (created by the UI via SAF). Progress via [OperationsApi.observe]. */
    suspend fun export(request: ExportRequest, target: DocumentRef, client: ClientRequestId): SdkResult<OperationId>
    /** Reads manifest and computes impact; never writes and never calls a model. */
    suspend fun previewImport(source: DocumentRef): SdkResult<ImportPreview>
    /** Content pack append, or full restore via staging (atomic switch). */
    suspend fun commitImport(previewId: String, client: ClientRequestId): SdkResult<OperationId>
}
