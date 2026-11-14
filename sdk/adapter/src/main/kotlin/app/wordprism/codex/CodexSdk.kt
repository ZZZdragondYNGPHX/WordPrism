package app.wordprism.codex

import app.wordprism.sdk.*
import dev.wordprism.contract.Command
import dev.wordprism.contract.Contract
import dev.wordprism.contract.Query
import dev.wordprism.contract.View
import dev.wordprism.contract.WordPrism
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import dev.wordprism.contract.PackagePurpose as CPurpose
import dev.wordprism.contract.Id as CId

/** UI-only projections of the single packaged business core. No storage or scoring in this module. */
class CodexSdk(core: WordPrism, scope: CoroutineScope, documents: DocumentAccess, online: StateFlow<Boolean> = MutableStateFlow(true), private val onRetry: suspend () -> Unit = {}) : WordPrismSdk {
    private val b = CodexBridge(core, scope, online)
    override val contractVersion = "core-${Contract.VERSION}"
    override val implementationLabel = "本机核心 0.4 · Room / FSRS-6"
    override val isMock = false
    override val library = CodexLibrary(b)
    override val intake = CodexIntake(b)
    override val study = CodexStudy(b)
    override val overview = CodexOverview(b)
    override val adaptation = CodexAdaptation(b)
    override val presets = CodexPresets(b)
    override val dataTransfer = CodexDataTransfer(b, documents)
    override val operations = CodexOperations(b)
    override fun observeErrors(): Flow<SdkError?> = b.errors
    override suspend fun retryReads() { onRetry(); b.refresh() }
    override fun observeConnectivity(): Flow<Connectivity> = combine(b.online, b.live(Query.Operations(200)) { (it as? View.Operations)?.operations }) { connected, operations ->
        Connectivity(connected, operations.orEmpty().count { it.status == dev.wordprism.contract.OperationStatus.WAITING_NETWORK })
    }
}

/** Supplied by the platform. Android implements this with ContentResolver and SAF URIs. */
interface DocumentAccess {
    fun openInput(uri: String): InputStream
    fun openOutput(uri: String): OutputStream
}

class CodexDataTransfer(private val b: CodexBridge, private val documents: DocumentAccess) : DataTransferApi {
    private fun PackagePurpose.toCore() = when (this) {
        PackagePurpose.ContentPack -> CPurpose.CONTENT; PackagePurpose.FullBackup -> CPurpose.BACKUP
        else -> throw ReadFailure(SdkError(ErrorCode.UnsupportedFormat, "不支持此导出用途", false))
    }
    override suspend fun exportScope(request: ExportRequest): SdkResult<ExportScopeInfo> = safely {
        b.ask(Query.ExportScope(request.purpose.toCore())) { (it as? View.ExportScope)?.let { s -> ExportScopeInfo(request.purpose, s.included, s.excluded, s.suggestedFileName) } }
    }
    override suspend fun export(request: ExportRequest, target: DocumentRef, client: ClientRequestId): SdkResult<OperationId> = safely {
        val words = request.groupIds.flatMap { gid -> (b.view(Query.GetGroup(CId(gid.value))) as View.Library).words.map { it.id } }.toSet()
        withContext(Dispatchers.IO) {
            // A lazy stream keeps a validation failure from truncating an existing destination.
            val output = object : OutputStream() {
                var opened: OutputStream? = null
                private fun destination() = opened ?: documents.openOutput(target.uri).also { opened = it }
                override fun write(b: Int) = destination().write(b)
                override fun write(bytes: ByteArray, offset: Int, length: Int) = destination().write(bytes, offset, length)
                // A SAF provider can report failure on close. Finish it before the core saves a success receipt.
                override fun flush() { opened?.let { stream -> stream.flush(); stream.close(); opened = null } }
                override fun close() { opened?.close() }
            }
            val destinationHash = java.security.MessageDigest.getInstance("SHA-256").digest(target.uri.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            output.use { b.core.exportPackage(request.purpose.toCore(), it, words, dev.wordprism.contract.ExportIdentity(client.value, destinationHash))
                .toResult { v -> (v as? View.Exported)?.packageId?.value?.let(::OperationId) } }
        }
    }
    override suspend fun previewImport(source: DocumentRef): SdkResult<ImportPreview> = safely {
        withContext(Dispatchers.IO) { documents.openInput(source.uri).use { b.core.previewPackage(it) } }.toResult { view ->
            (view as? View.ImportPreview)?.preview?.let { p ->
                val backup = p.purpose == CPurpose.BACKUP
                ImportPreview("${p.purpose.name}:${p.token.value}", source, if (backup) PackagePurpose.FullBackup else PackagePurpose.ContentPack,
                    p.formatVersion.toString(), p.producerVersion, p.createdAtMs,
                    p.modules.map { ModuleStatus(it.name, moduleLabel(it.name), it.version.toString(), it.state, when (it.state) { "migrate" -> "可迁移"; "unknown_optional" -> "保留但不执行"; else -> "可读取" }) },
                    ImportCounts(p.added, p.duplicate, p.conflicts.size, 0), if (p.requiresCredentials) listOf("API 凭证需要重新填写") else emptyList(),
                    buildList { if (backup) add("完整恢复替换当前资料库；未完成请求不会自动付费执行")
                        if (p.archivedModules.isNotEmpty()) add("未知可选模块将保留在完整备份中") },
                    backup || p.conflicts.isEmpty(), if (!backup && p.conflicts.isNotEmpty()) "来源包与本机有冲突，请先在来源资料库修正后重导" else null, backup)
            }
        }
    }
    override suspend fun commitImport(previewId: String, client: ClientRequestId): SdkResult<OperationId> = safely {
        val parts = previewId.split(':', limit = 2)
        if (parts.size != 2) return@safely invalid<OperationId>("预览身份无效，请重新选择文件")
        val command = when (parts[0]) {
            CPurpose.BACKUP.name -> Command.RestoreBackup(CId(parts[1]))
            CPurpose.CONTENT.name -> Command.CommitImport(CId(parts[1]))
            else -> return@safely invalid<OperationId>("预览用途无效")
        }
        b.exec(client, command) { (it as? View.OperationResult)?.operation?.id?.value?.let(::OperationId) }
    }
    private fun moduleLabel(name: String) = when (name) { "library" -> "词库与内容"; "learning" -> "学习历史"; "memory" -> "记忆与设置"; "analysis" -> "分析与调整"; "api" -> "API 配置与用量"; "operations" -> "任务与撤销"; else -> name }
}
