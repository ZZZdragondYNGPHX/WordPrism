package app.wordprism.codex

import app.wordprism.sdk.*
import dev.wordprism.contract.Command
import dev.wordprism.contract.Fault
import dev.wordprism.contract.Query
import dev.wordprism.contract.Reply
import dev.wordprism.contract.Request
import dev.wordprism.contract.View
import dev.wordprism.contract.WordPrism
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import java.util.UUID
import dev.wordprism.contract.ErrorCode as CErr
import dev.wordprism.contract.Feature as CFeature
import dev.wordprism.contract.Id as CId
import dev.wordprism.contract.Operation as COperation
import dev.wordprism.contract.OperationStatus as CStatus

/**
 * Shared plumbing between the UI port (app.wordprism.sdk) and Codex's command/query bus.
 *
 * Codex's [WordPrism.observe] emits durable revision numbers; every UI flow here re-runs its query
 * on each revision. No business rule lives here: this layer only renames, re-shapes and labels.
 */
class CodexBridge(val core: WordPrism, scope: CoroutineScope, val online: StateFlow<Boolean> = MutableStateFlow(true)) {
    private val refresh = MutableStateFlow(0L)
    private val issues = java.util.concurrent.ConcurrentHashMap<Any, SdkError>()
    private val issueState = MutableStateFlow<SdkError?>(null)
    val errors: StateFlow<SdkError?> = issueState.asStateFlow()
    private fun issue(key: Any, error: SdkError?) {
        if (error == null) issues.remove(key) else issues[key] = error
        issueState.value = issues.values.firstOrNull()
    }
    fun refresh() { refresh.value++ }
    /** Current revision, starting immediately so first collection queries without waiting. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val revisions: SharedFlow<Long> = refresh.flatMapLatest {
        core.observe().onEach { issue("observation", null) }.onStart { emit(-1) }
            .catch { if (it is CancellationException) throw it; issue("observation", readError(it)) }
    }
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    fun <T> live(query: Query, map: suspend (View) -> T?): Flow<T?> = live { optionalView(query)?.let { map(it) } }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> live(read: suspend () -> T): Flow<T> {
        val key = Any()
        return revisions.mapLatest {
            try { Result.success(read()).also { issue(key, null) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { issue(key, readError(e)); Result.failure<T>(e) }
        }.filter { it.isSuccess }.map { it.getOrThrow() }.distinctUntilChanged()
            .onCompletion { issue(key, null) }
    }

    suspend fun view(query: Query): View = core.query(query).let { reply ->
        reply.fault?.let { throw ReadFailure(it.toSdk()) }; reply.value!!
    }
    /** Missing optional entities are empty; storage/configuration failures remain observable errors. */
    suspend fun optionalView(query: Query): View? = core.query(query).let { reply ->
        if (reply.fault?.code == CErr.NOT_FOUND) null else {
            reply.fault?.let { throw ReadFailure(it.toSdk()) }; reply.value
        }
    }

    suspend fun <T> exec(request: ClientRequestId, command: Command, map: (View) -> T?): SdkResult<T> =
        safely { core.execute(Request(request.value, command)).toResult(map) }

    suspend fun <T> ask(query: Query, map: (View) -> T?): SdkResult<T> = safely { core.query(query).toResult(map) }
}
class ReadFailure(val error: SdkError) : IllegalStateException(error.message)
private fun readError(error: Throwable): SdkError = when (error) {
    is ReadFailure -> error.error
    is java.io.IOException -> SdkError(ErrorCode("storage"), "本机数据暂时无法读取，请重试", true)
    else -> SdkError(ErrorCode.Internal, "本机核心暂时不可用，请重试", true)
}
internal fun requestId() = ClientRequestId(UUID.randomUUID().toString())
internal fun <T> invalid(message: String) = SdkResult.Err(SdkError(ErrorCode.Validation, message, false))
internal suspend fun <T> safely(action: suspend () -> SdkResult<T>): SdkResult<T> = try { action() }
    catch (e: ReadFailure) { SdkResult.Err(e.error) }
    catch (_: java.io.IOException) { SdkResult.Err(SdkError(ErrorCode("storage"), "文件读写失败，请重新选择可访问的文件", true)) }
    catch (_: SecurityException) { SdkResult.Err(SdkError(ErrorCode("storage"), "当前文件授权已失效，请重新选择", true)) }

internal fun dev.wordprism.contract.Provenance.toSdk() = ContentSource(
    when (kind) { "ai", "recommendation" -> "ai_generated"; else -> kind },
    presetVersion = presetVersion?.toInt(), model = model, generatedAt = atMs.takeIf { it > 0 },
)
internal fun featureLabel(feature: CFeature): String = when (feature) {
    CFeature.WORD_INTAKE -> "AI 添加"; CFeature.WORD_RECOMMENDATION -> "相似词推荐"; CFeature.EXPLANATION -> "补充讲解"
    CFeature.PRACTICE_GENERATION -> "练习生成"; CFeature.WRITING_FEEDBACK -> "表达反馈"; CFeature.LEARNING_ANALYSIS -> "学习分析"
}
internal fun dev.wordprism.contract.CallPreview.toSdk(online: Boolean) = ApiRequirement(feature.toSdk(), requiresApi, preset?.id?.value, preset?.name,
    when {
        !requiresApi -> ApiReadiness.NotRequired
        fault?.code == CErr.PRESET_MISSING -> ApiReadiness.NeedsPreset
        fault?.code == CErr.CREDENTIAL_MISSING -> ApiReadiness.NeedsCredential
        fault?.code == CErr.BUDGET_EXCEEDED -> ApiReadiness.QuotaExhausted
        !online -> ApiReadiness.QueuedOffline
        else -> ApiReadiness.Ready
    }, fault?.message ?: if (!requiresApi) "使用已有内容，不调用模型" else if (!online) "已授权请求将等待联网" else "本次操作将调用所选模型；用量按实际返回记录")
internal fun CodexBridge.requirement(feature: CFeature, ids: List<CId> = emptyList()): Flow<ApiRequirement> =
    combine(live(Query.PreviewCall(feature, ids)) { (it as? View.Preview)?.preview }, online) { preview, connected ->
        preview?.toSdk(connected) ?: ApiRequirement(feature.toSdk(), true, null, null, ApiReadiness.NeedsPreset)
    }
internal fun usageRows(rows: List<dev.wordprism.contract.Usage>): List<UsageRow> = rows.groupBy { it.feature to it.preset.name }.map { (key, records) ->
    UsageRow(key.first.toSdk(), featureLabel(key.first), key.second,
        UsageFigure(records.size, records.sumOf { it.inputTokens ?: 0 }, records.sumOf { it.outputTokens ?: 0 }), records.any { it.inputTokens == null || it.outputTokens == null })
}

fun <T> Reply.toResult(map: (View) -> T?): SdkResult<T> {
    fault?.let { return SdkResult.Err(it.toSdk()) }
    val mapped = value?.let(map) ?: return SdkResult.Err(SdkError(ErrorCode.Internal, "本地核心返回了无法识别的结果", false))
    return SdkResult.Ok(mapped)
}

fun Fault.toSdk(): SdkError = SdkError(
    code = when (code) {
        CErr.VALIDATION, CErr.LIMIT_EXCEEDED -> ErrorCode.Validation
        CErr.NOT_FOUND -> ErrorCode.NotFound
        CErr.VERSION_CONFLICT, CErr.IDEMPOTENCY_CONFLICT -> ErrorCode.VersionConflict
        CErr.PRESET_MISSING -> ErrorCode.PresetMissing
        CErr.CREDENTIAL_MISSING, CErr.PROVIDER_AUTH -> ErrorCode.CredentialMissing
        CErr.OFFLINE -> ErrorCode.Offline
        CErr.BUDGET_EXCEEDED -> ErrorCode.QuotaExhausted
        CErr.PROVIDER_TIMEOUT, CErr.OUTCOME_UNKNOWN -> ErrorCode.ProviderTimeout
        CErr.PROVIDER_RATE_LIMIT -> ErrorCode.ProviderRateLimited
        CErr.INVALID_RESPONSE -> ErrorCode.ProviderBadOutput
        CErr.TRUNCATED_RESPONSE -> ErrorCode.ProviderTruncated
        CErr.UNSUPPORTED_VERSION, CErr.INVALID_PACKAGE -> ErrorCode.UnsupportedFormat
        else -> ErrorCode(code.name.lowercase())
    },
    message = message,
    retryable = retryable,
)

fun CFeature.toSdk(): FeatureId = FeatureId(name.lowercase())
fun FeatureId.toCodex(): CFeature? = CFeature.entries.firstOrNull { it.name.lowercase() == value }

fun CStatus.toSdk(): OperationStatus = when (this) {
    CStatus.QUEUED, CStatus.RESTORED_PENDING -> OperationStatus.Queued
    CStatus.WAITING_NETWORK -> OperationStatus.WaitingNetwork
    CStatus.RUNNING -> OperationStatus.Running
    // NEEDS_CONFIGURATION is a real waiting state (missing preset/credential); keep it distinct.
    CStatus.NEEDS_CONFIGURATION -> OperationStatus("needs_configuration")
    CStatus.NEEDS_INPUT -> OperationStatus.NeedsInput
    CStatus.SUCCEEDED -> OperationStatus.Succeeded
    CStatus.FAILED -> OperationStatus.Failed
    CStatus.CANCELLED -> OperationStatus.Cancelled
    CStatus.SUPERSEDED -> OperationStatus.Merged
}

internal val kindTitles = mapOf(
    "intake" to "AI 添加", "submit_intake" to "AI 添加", "recommendation" to "寻找相似词", "request_recommendations" to "寻找相似词", "accept_recommendations" to "加入推荐词",
    "analysis" to "学习分析", "analyze_learning" to "学习分析", "test_connection" to "连接测试", "evaluate_response" to "造句反馈",
    "generate_activity" to "生成练习", "explain_sense" to "补充讲解", "commit_import" to "导入", "restore_backup" to "恢复备份", "export" to "导出",
    "save_words" to "添加词条", "edit_word" to "编辑词条", "update_word" to "编辑词条", "update_sense" to "编辑义项", "rename_group" to "重命名词组",
    "change_membership" to "修改归组", "edit_memberships" to "修改归组", "apply_policy" to "调整学习策略", "fit_memory" to "本机参数拟合", "evaluate_parameters" to "参数评估", "undo" to "撤销", "bind_preset" to "分配 API 预设", "dispute_activity" to "标记争议题",
)

fun COperation.toSdk(): Operation {
    val status = if (undoneBy != null) OperationStatus.Undone else status.toSdk()
    val actions = buildSet {
        if (status.isActive) add(OperationAction.Cancel)
        if ((retryable && status == OperationStatus.Failed) || this@toSdk.status in setOf(CStatus.NEEDS_CONFIGURATION, CStatus.RESTORED_PENDING)) add(OperationAction.Retry)
        if (undoable && undoneBy == null && status == OperationStatus.Succeeded) add(OperationAction.Undo)
        if (status == OperationStatus.NeedsInput) add(OperationAction.Resolve)
    }
    return Operation(
        id = OperationId(id.value),
        kind = OperationKind(kind),
        title = kindTitles[kind] ?: kind,
        status = status,
        progress = null, // Codex reports no progress; the UI shows an indeterminate state.
        feature = feature?.toSdk(),
        presetName = preset?.name,
        presetVersion = preset?.version?.toInt(),
        actions = actions,
        error = fault?.toSdk(),
        resultSummary = summary.ifBlank { null },
        createdAt = createdAtMs,
        updatedAt = updatedAtMs,
        usage = null, // per-attempt usage comes from Query.GetUsage, not the operation
    )
}

fun OperationId.toCodex() = CId(value)

/** Fully mapped: every operation call the UI makes. */
class CodexOperations(private val b: CodexBridge) : OperationsApi {
    override fun observe(id: OperationId): Flow<Operation?> =
        b.live(Query.GetOperation(id.toCodex())) { (it as? View.OperationResult)?.operation?.toSdk() }

    override fun observeRecent(): Flow<List<Operation>> =
        b.live(Query.Operations(limit = 30)) { v -> (v as? View.Operations)?.operations?.map { it.toSdk() }?.sortedByDescending { it.createdAt } }
            .map { it.orEmpty() }

    override suspend fun getWritingFeedback(id: OperationId): SdkResult<Feedback> =
        b.ask(Query.GetOperationFeedback(id.toCodex())) { (it as? View.Feedbacks)?.details?.let(CodexStudy(b)::mapFeedback) }

    override suspend fun cancel(id: OperationId): SdkResult<Operation> =
        b.exec(requestId(), Command.CancelOperation(id.toCodex())) { opView(it) }

    override suspend fun retry(id: OperationId, request: ClientRequestId): SdkResult<Operation> =
        b.exec(request, Command.RetryOperation(id.toCodex())) { opView(it) }

    override suspend fun undo(id: OperationId, request: ClientRequestId): SdkResult<UndoOutcome> =
        b.exec(request, Command.UndoOperation(id.toCodex())) { v ->
            val op = (v as? View.OperationResult)?.operation
            UndoOutcome(OperationId(op?.id?.value ?: (v as? View.Ok)?.id?.value ?: id.value), op?.summary?.ifBlank { null } ?: "已撤销", listOf("之后完成的真实作答与历史"))
        }

    private fun opView(v: View): Operation? = (v as? View.OperationResult)?.operation?.toSdk()
}
