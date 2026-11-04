package app.wordprism.mock

import app.wordprism.sdk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Platform document access. The Android app implements this with ContentResolver. */
interface DocumentIo {
    fun openInput(uri: String): InputStream
    fun openOutput(uri: String): OutputStream
}

/** Developer-only levers to put the mock into states that are hard to reach naturally. */
data class MockScenario(
    val simulateOffline: Boolean = false,
    val latency: Latency = Latency.Normal,
    val failNextAi: ErrorCode? = null,
) {
    enum class Latency(val millis: Long, val label: String) { Fast(400, "快"), Normal(2200, "正常"), Slow(7000, "慢") }
}

internal class MockCore(
    private val store: File,
    val documents: DocumentIo,
    val clock: () -> Long,
    val zone: ZoneId,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; allowStructuredMapKeys = true }
    val db = MutableStateFlow(load())
    val scenario = MutableStateFlow(MockScenario())
    val deviceOnline = MutableStateFlow(true)
    val online: StateFlow<Boolean> = combine(deviceOnline, scenario) { d, s -> d && !s.simulateOffline }
        .stateIn(scope, SharingStarted.Eagerly, true)
    private val jobs = mutableMapOf<String, Job>()
    /** Kind -> handler that performs the AI/long work for an operation. Registered by feature files. */
    val handlers = mutableMapOf<OperationKind, suspend (String) -> Unit>()

    private fun load(): MockDb = runCatching {
        if (store.exists()) json.decodeFromString(MockDb.serializer(), store.readText()) else null
    }.getOrNull() ?: MockSeed.create(System.currentTimeMillis(), ZoneId.systemDefault())

    private fun persist(value: MockDb) {
        val tmp = File(store.parentFile, store.name + ".tmp")
        tmp.writeText(json.encodeToString(MockDb.serializer(), value))
        if (!tmp.renameTo(store)) { store.delete(); tmp.renameTo(store) }
    }

    suspend fun <T> tx(block: (MockDb) -> Pair<MockDb, T>): T = mutex.withLock {
        val (next, result) = block(db.value)
        if (next !== db.value) {
            db.value = next
            withContext(Dispatchers.IO) { persist(next) }
        }
        result
    }

    suspend fun mutate(block: (MockDb) -> MockDb) = tx { block(it) to Unit }

    fun newId(prefix: String) = "$prefix-" + UUID.randomUUID().toString().take(8)
    fun today(): String = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate().toString()

    // ------------------------------------------------------------------ API readiness

    fun presetFor(d: MockDb, feature: FeatureId): MPreset? {
        val explicit = d.bindings[feature.value]
        val id = explicit ?: d.callPolicy.defaultPresetId?.value
        return id?.let { d.presets[it] }
    }

    fun requirement(d: MockDb, feature: FeatureId, requiresApi: Boolean = true, online: Boolean = this.online.value): ApiRequirement {
        if (!requiresApi) return ApiRequirement(feature, false, null, null, ApiReadiness.NotRequired)
        val p = presetFor(d, feature)
        val readiness = when {
            p == null || !p.enabled -> ApiReadiness.NeedsPreset
            p.credentialHint == null -> ApiReadiness.NeedsCredential
            usedToday(d) >= (d.callPolicy.dailyRequestLimit ?: Int.MAX_VALUE) -> ApiReadiness.QuotaExhausted
            !online -> ApiReadiness.QueuedOffline
            else -> ApiReadiness.Ready
        }
        val note = when (readiness) {
            ApiReadiness.NeedsPreset -> "「${featureLabel(feature)}」还没有可用的 API 预设"
            ApiReadiness.NeedsCredential -> "预设「${p?.name}」缺少 API 密钥"
            ApiReadiness.QueuedOffline -> "当前离线，提交后会排队，联网后执行"
            ApiReadiness.QuotaExhausted -> "今日请求额度已用完"
            else -> null
        }
        return ApiRequirement(feature, true, p?.id, p?.name, readiness, note)
    }

    fun observeRequirement(feature: FeatureId): Flow<ApiRequirement> =
        combine(db, online) { d, o -> requirement(d, feature, online = o) }.distinctUntilChanged()

    private fun usedToday(d: MockDb): Int {
        val start = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        return d.operations.values.filter { it.createdAt >= start }.sumOf { it.usage?.requests ?: 0 }
    }

    fun featureLabel(f: FeatureId) = when (f) {
        FeatureId.WordIntake -> "AI 添加与归组"
        FeatureId.WordRecommendation -> "相似词推荐"
        FeatureId.Explanation -> "讲解与例句"
        FeatureId.PracticeGeneration -> "练习生成"
        FeatureId.WritingFeedback -> "造句反馈"
        FeatureId.LearningAnalysis -> "学习分析与调整"
        else -> f.value
    }

    /** Fails fast for states where queuing would be pointless. */
    fun blockingError(req: ApiRequirement): SdkError? = when (req.readiness) {
        ApiReadiness.NeedsPreset -> SdkError(ErrorCode.PresetMissing, req.note ?: "缺少 API 预设", false)
        ApiReadiness.NeedsCredential -> SdkError(ErrorCode.CredentialMissing, req.note ?: "缺少 API 密钥", false)
        ApiReadiness.QuotaExhausted -> SdkError(ErrorCode.QuotaExhausted, "今日请求额度已用完，可在设置中调整", false)
        else -> null
    }

    // ------------------------------------------------------------------ operations

    fun newOperation(
        d: MockDb, kind: OperationKind, title: String, feature: FeatureId?, status: OperationStatus = OperationStatus.Queued,
    ): Operation {
        val p = feature?.let { presetFor(d, it) }
        val now = clock()
        return Operation(
            id = OperationId(newId("op")), kind = kind, title = title, status = status,
            feature = feature, presetName = p?.name, presetVersion = p?.version,
            actions = if (status.isActive) setOf(OperationAction.Cancel) else emptySet(),
            createdAt = now, updatedAt = now,
        )
    }

    fun MockDb.withOp(op: Operation) = copy(operations = operations + (op.id.value to op))

    fun MockDb.updateOp(id: String, f: (Operation) -> Operation): MockDb {
        val op = operations[id] ?: return this
        return copy(operations = operations + (id to f(op).copy(updatedAt = clock())))
    }

    suspend fun setStatus(id: String, status: OperationStatus, actions: Set<OperationAction>? = null) = mutate { d ->
        d.updateOp(id) { if (it.status.isTerminal) it else it.copy(status = status, actions = actions ?: if (status.isActive) setOf(OperationAction.Cancel) else it.actions) }
    }

    fun launch(opId: String) {
        val op = db.value.operations[opId] ?: return
        val handler = handlers[op.kind] ?: return
        synchronized(jobs) {
            if (jobs[opId]?.isActive == true) return
            jobs[opId] = scope.launch {
                try { handler(opId) } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                    failOp(opId, SdkError(ErrorCode.Internal, "模拟后端内部错误：${e.message}", true))
                }
            }
        }
    }

    /**
     * Standard AI step: wait for network, run with latency, honour injected failure.
     * Returns false if the op is no longer running (cancelled) or failed.
     */
    suspend fun aiStep(opId: String, usage: UsageFigure = UsageFigure(1, 900, 350)): Boolean {
        if (!online.value) {
            setStatus(opId, OperationStatus.WaitingNetwork)
            online.first { it }
        }
        if (!isActive(opId)) return false
        setStatus(opId, OperationStatus.Running)
        delay(scenario.value.latency.millis)
        if (!isActive(opId)) return false
        val fail = scenario.value.failNextAi
        if (fail != null) {
            scenario.update { it.copy(failNextAi = null) }
            failOp(opId, errorFor(fail), usage.copy(outputTokens = null))
            return false
        }
        mutate { d -> d.updateOp(opId) { it.copy(usage = usage) } }
        return true
    }

    fun isActive(opId: String) = db.value.operations[opId]?.status?.isActive == true

    private fun errorFor(code: ErrorCode) = when (code) {
        ErrorCode.ProviderTimeout -> SdkError(code, "模型服务超时。输入已保存，可以重试。", true)
        ErrorCode.ProviderRateLimited -> SdkError(code, "模型服务限流，请稍后重试。", true)
        ErrorCode.ProviderBadOutput -> SdkError(code, "模型返回的格式无法解析，未写入任何内容。", true)
        ErrorCode.ProviderTruncated -> SdkError(code, "模型输出被截断，未写入任何内容。可提高输出上限后重试。", true)
        else -> SdkError(code, "模拟错误：${code.value}", true)
    }

    suspend fun failOp(opId: String, error: SdkError, usage: UsageFigure? = null) = mutate { d ->
        d.updateOp(opId) {
            if (it.status.isTerminal) it else it.copy(
                status = OperationStatus.Failed, error = error, usage = usage ?: it.usage,
                actions = if (error.retryable) setOf(OperationAction.Retry) else emptySet(),
            )
        }
    }

    /** Commits only if still running — the cancel/commit race resolves to the real terminal state. */
    suspend fun <T> commit(opId: String, block: (MockDb) -> Pair<MockDb, T>): T? = tx { d ->
        val op = d.operations[opId]
        if (op == null || op.status.isTerminal) d to null else {
            val (n, r) = block(d)
            n to r
        }
    }

    suspend fun cancel(id: OperationId): SdkResult<Operation> {
        val result = tx { d ->
            val op = d.operations[id.value] ?: return@tx d to SdkResult.Err(SdkError(ErrorCode.NotFound, "任务不存在", false))
            if (op.status.isTerminal) {
                d to SdkResult.Err(SdkError(ErrorCode.NotCancellable, "任务已${statusLabel(op.status)}，无法取消", false))
            } else {
                val n = d.updateOp(id.value) { it.copy(status = OperationStatus.Cancelled, actions = emptySet()) }
                n to SdkResult.Ok(n.operations.getValue(id.value))
            }
        }
        synchronized(jobs) { jobs.remove(id.value)?.cancel() }
        return result
    }

    fun statusLabel(s: OperationStatus) = when (s) {
        OperationStatus.Succeeded -> "完成"; OperationStatus.Failed -> "失败"; OperationStatus.Cancelled -> "取消"
        OperationStatus.Undone -> "撤销"; OperationStatus.Merged -> "合并"; else -> "进行中"
    }

    /** Idempotency: same client request id returns the original operation. */
    fun existing(d: MockDb, request: ClientRequestId): String? = d.requestIndex[request.value]
    fun MockDb.remember(request: ClientRequestId, opId: String) = copy(requestIndex = requestIndex + (request.value to opId))

    fun resumeAfterRestart() {
        val active = db.value.operations.values.filter { it.status.isActive && it.status != OperationStatus.NeedsInput }
        active.forEach { launch(it.id.value) }
    }
}
