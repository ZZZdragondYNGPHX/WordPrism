package app.wordprism.mock

import app.wordprism.sdk.*
import kotlinx.coroutines.flow.*

internal class MockPresets(private val c: MockCore, private val insights: MockInsights) : PresetsApi {

    init { c.handlers[OperationKind.ConnectionTest] = ::runTest }

    private val adapters = listOf(
        ProtocolAdapter("openai_compatible", "OpenAI 兼容接口", "https://api.openai.com/v1", "Chat Completions 格式，适用于多数兼容服务"),
        ProtocolAdapter("anthropic_messages", "Anthropic Messages", "https://api.anthropic.com", "Messages 格式"),
    )

    override fun observeAdapters() = flowOf(adapters)

    override suspend fun listModels(draft: ModelCatalogDraft, secret: CharArray?): SdkResult<List<ApiModel>> = try {
        SdkResult.Err(SdkError(ErrorCode.ProviderBadOutput, "模拟 SDK 不查询服务商模型列表，请使用正式核心或手动填写", false))
    } finally { secret?.fill('\u0000') }

    private fun MPreset.view(d: MockDb) = ApiPreset(
        PresetId(id), name, version, adapterId, adapters.firstOrNull { it.id == adapterId }?.label ?: adapterId, baseUrl, model,
        maxOutputTokens, timeoutSeconds, CredentialState(credentialHint != null, credentialHint), enabled, lastTest,
        d.bindings.filterValues { it == id }.keys.map(::FeatureId), reasoningEffort,
    )

    override fun observePresets() = c.db.map { d -> d.presets.values.sortedBy { it.name }.map { it.view(d) } }.distinctUntilChanged()

    override suspend fun savePreset(draft: PresetDraft, request: ClientRequestId): SdkResult<ApiPreset> {
        val errors = buildMap {
            if (draft.name.isBlank()) put("name", "请填写名称")
            if (!draft.baseUrl.matches(Regex("https://[^\\s/]+(/\\S*)?"))) put("baseUrl", "需要以 https:// 开头的完整地址")
            if (draft.model.isBlank()) put("model", "请填写模型标识")
            if (draft.timeoutSeconds?.let { it !in 5..2_147_483 } == true) put("timeoutSeconds", "超时须留空或为 5–2147483 秒")
            if (draft.maxOutputTokens?.let { it < 1 } == true) put("maxOutputTokens", "输出上限须留空或为正整数")
            if (adapters.none { it.id == draft.adapterId }) put("adapterId", "请选择接口协议")
        }
        if (errors.isNotEmpty()) return SdkResult.Err(SdkError(ErrorCode.Validation, "有 ${errors.size} 项需要修改", false, errors))
        return c.tx { d ->
            val existing = draft.id?.let { d.presets[it.value] }
            if (existing != null && draft.expectedVersion != null && existing.version != draft.expectedVersion)
                return@tx d to SdkResult.Err(SdkError(ErrorCode.VersionConflict, "这个预设已被修改，已载入最新内容", true))
            if (d.presets.values.any { it.name == draft.name.trim() && it.id != existing?.id })
                return@tx d to SdkResult.Err(SdkError(ErrorCode.Validation, "名称已被使用", false, mapOf("name" to "名称已被使用")))
            val hint = when (draft.credential) {
                null -> existing?.credentialHint
                "" -> null
                else -> "••••" + draft.credential!!.takeLast(4)
            }
            val p = MPreset(existing?.id ?: c.newId("p"), draft.name.trim(), (existing?.version ?: 0) + 1, draft.adapterId, draft.baseUrl.trim().trimEnd('/'),
                draft.model.trim(), draft.maxOutputTokens, draft.timeoutSeconds, hint, existing?.enabled ?: true, if (existing?.let { it.baseUrl == draft.baseUrl && it.model == draft.model && draft.reasoningEffort == it.reasoningEffort && draft.credential == null } == true) existing.lastTest else null,
                reasoningEffort = draft.reasoningEffort)
            val n = d.copy(presets = d.presets + (p.id to p))
            n to SdkResult.Ok(p.view(n))
        }
    }

    override suspend fun setPresetEnabled(id: PresetId, enabled: Boolean): SdkResult<ApiPreset> = c.tx { d ->
        val p = d.presets[id.value] ?: return@tx d to SdkResult.Err(SdkError(ErrorCode.NotFound, "预设不存在", false))
        val n = d.copy(presets = d.presets + (p.id to p.copy(enabled = enabled, version = p.version + 1)))
        n to SdkResult.Ok(n.presets.getValue(p.id).view(n))
    }

    override suspend fun deletionImpact(id: PresetId): SdkResult<PresetDeletionImpact> {
        val d = c.db.value
        val bound = d.bindings.filterValues { it == id.value }.keys.map { c.featureLabel(FeatureId(it)) }
        val running = d.operations.values.count { it.status.isActive && it.presetName == d.presets[id.value]?.name }
        return SdkResult.Ok(PresetDeletionImpact(bound, running,
            "已生成的内容和历史记录会保留来源；${if (bound.isEmpty()) "没有功能使用它" else "这些功能会变成待配置"}"))
    }

    override suspend fun deletePreset(id: PresetId, request: ClientRequestId): SdkResult<Unit> = c.tx { d ->
        if (!d.presets.containsKey(id.value)) return@tx d to SdkResult.Ok(Unit)
        d.copy(
            presets = d.presets - id.value,
            bindings = d.bindings.mapValues { (_, v) -> v.takeIf { it != id.value } },
            callPolicy = d.callPolicy.copy(
                defaultPresetId = d.callPolicy.defaultPresetId?.takeIf { it != id },
                fallbackPresetId = d.callPolicy.fallbackPresetId?.takeIf { it != id },
            ),
        ) to SdkResult.Ok(Unit)
    }

    override suspend fun testConnection(id: PresetId, request: ClientRequestId): SdkResult<OperationId> {
        val r = c.tx { d ->
            c.existing(d, request)?.let { return@tx d to SdkResult.Ok(OperationId(it)) }
            val p = d.presets[id.value] ?: return@tx d to SdkResult.Err(SdkError(ErrorCode.NotFound, "预设不存在", false))
            if (p.credentialHint == null) return@tx d to SdkResult.Err(SdkError(ErrorCode.CredentialMissing, "请先填写 API 密钥", false))
            if (!c.online.value) return@tx d to SdkResult.Err(SdkError(ErrorCode.Offline, "当前离线，联网后再测试", true))
            val now = c.clock()
            val op = Operation(OperationId(c.newId("op")), OperationKind.ConnectionTest, "测试「${p.name}」", OperationStatus.Queued,
                presetName = p.name, presetVersion = p.version, actions = setOf(OperationAction.Cancel), createdAt = now, updatedAt = now, resultSummary = p.id)
            with(c) { d.withOp(op).remember(request, op.id.value) } to SdkResult.Ok(op.id)
        }
        r.onOk { c.launch(it.value) }
        return r
    }

    private suspend fun runTest(opId: String) {
        val pid = c.db.value.operations[opId]?.resultSummary ?: return
        val ok = c.aiStep(opId, UsageFigure(1, 12, 4))
        val result = if (ok) ConnectionTestResult(true, c.clock(), "连接正常，模型可用（模拟）", c.scenario.value.latency.millis)
        else ConnectionTestResult(false, c.clock(), c.db.value.operations[opId]?.error?.message ?: "测试未完成", null)
        c.tx { d ->
            val p = d.presets[pid] ?: return@tx d to Unit
            var n = d.copy(presets = d.presets + (pid to p.copy(lastTest = result)))
            if (ok) n = with(c) { n.updateOp(opId) { it.copy(status = OperationStatus.Succeeded, actions = emptySet(), resultSummary = result.message) } }
            n to Unit
        }
    }

    override fun observeBindings() = combine(c.db, c.online) { d, o ->
        listOf(FeatureId.WordIntake, FeatureId.WordRecommendation, FeatureId.Explanation, FeatureId.PracticeGeneration, FeatureId.WritingFeedback, FeatureId.LearningAnalysis).map { f ->
            val explicit = d.bindings[f.value]
            FeatureBinding(f, c.featureLabel(f), describe(f), (explicit ?: d.callPolicy.defaultPresetId?.value)?.let(::PresetId), explicit == null, c.requirement(d, f, online = o).readiness)
        }
    }.distinctUntilChanged()

    private fun describe(f: FeatureId) = when (f) {
        FeatureId.WordIntake -> "理解你的输入，整理词条并归组"
        FeatureId.WordRecommendation -> "你主动寻找更多相似词时使用"
        FeatureId.Explanation -> "补充释义、例句和差异说明"
        FeatureId.PracticeGeneration -> "生成短句、短文和造句题"
        FeatureId.WritingFeedback -> "评价你写的句子"
        FeatureId.LearningAnalysis -> "每日分析与学习计划调整"
        else -> ""
    }

    override suspend fun bindFeature(feature: FeatureId, presetId: PresetId?): SdkResult<FeatureBinding> {
        c.mutate { d -> d.copy(bindings = d.bindings + (feature.value to presetId?.value)) }
        return SdkResult.Ok(observeBindings().first().first { it.feature == feature })
    }

    override fun observeCallPolicy() = c.db.map { it.callPolicy }.distinctUntilChanged()

    override suspend fun setCallPolicy(policy: CallPolicy): SdkResult<CallPolicy> {
        if (policy.maxRetries !in 0..3) return SdkResult.Err(SdkError(ErrorCode.Validation, "重试次数范围 0–3", false, mapOf("maxRetries" to "0–3")))
        if ((policy.dailyRequestLimit ?: 1) < 1) return SdkResult.Err(SdkError(ErrorCode.Validation, "额度至少为 1", false, mapOf("dailyRequestLimit" to "至少为 1")))
        c.mutate { it.copy(callPolicy = policy) }
        return SdkResult.Ok(policy)
    }

    override fun observeUsage(window: OverviewWindow) = c.db.map { insights.usage(it) }.distinctUntilChanged()
}
