package app.wordprism.codex

import app.wordprism.sdk.*
import dev.wordprism.contract.Binding
import dev.wordprism.contract.Command
import dev.wordprism.contract.Feature
import dev.wordprism.contract.Query
import dev.wordprism.contract.ProtocolInfo
import dev.wordprism.contract.Ref
import dev.wordprism.contract.View
import kotlinx.coroutines.flow.*
import java.util.UUID
import dev.wordprism.contract.ApiPreset as CPreset
import dev.wordprism.contract.Id as CId
import dev.wordprism.contract.PresetView as CPresetView

class CodexPresets(private val b: CodexBridge) : PresetsApi {
    override fun observeAdapters() = b.live(Query.Protocols) { (it as? View.Protocols)?.protocols?.map { p -> ProtocolAdapter(p.id, p.label, p.defaultBaseUrl, p.notes) } }.map { it.orEmpty() }
    private fun CPresetView.map(bindings: List<Binding>, protocols: List<ProtocolInfo>) = ApiPreset(PresetId(preset.id.value), preset.name, preset.version.toInt(), preset.protocol,
        protocols.firstOrNull { it.id == preset.protocol }?.label ?: preset.protocol, preset.baseUrl, preset.model, preset.maxOutputTokens, preset.timeoutSeconds, CredentialState(hasCredential, null), preset.enabled,
        lastTest?.let { ConnectionTestResult(it.ok, it.atMs, it.message, it.latencyMs) }, bindings.filter { it.presetId == preset.id }.map { it.feature.toSdk() })
    private suspend fun protocols() = (b.view(Query.Protocols) as View.Protocols).protocols
    override fun observePresets(): Flow<List<ApiPreset>> = b.live {
        val v = b.view(Query.Presets) as View.Presets
        val protocols = protocols()
        v.presets.map { it.map(v.bindings, protocols) }
    }
    private suspend fun current(id: PresetId) = (b.view(Query.Presets) as View.Presets).presets.firstOrNull { it.preset.id.value == id.value }
    private suspend fun read(id: PresetId): SdkResult<ApiPreset> {
        val v = b.view(Query.Presets) as View.Presets
        return v.presets.firstOrNull { it.preset.id.value == id.value }?.let { SdkResult.Ok(it.map(v.bindings, protocols())) } ?: notFound()
    }

    override suspend fun savePreset(draft: PresetDraft, request: ClientRequestId): SdkResult<ApiPreset> = safely {
        val id = CId(draft.id?.value ?: UUID.nameUUIDFromBytes("preset:${request.value}".toByteArray()).toString())
        val version = draft.expectedVersion?.toLong() ?: 0
        // Only submitted fields determine this command, so an idempotent replay stays identical.
        val preset = CPreset(id, version + 1, draft.name.trim(), draft.baseUrl.trim(), draft.model.trim(), draft.adapterId,
            timeoutSeconds = draft.timeoutSeconds, maxOutputTokens = draft.maxOutputTokens ?: 2048)
        val saved = b.exec(request, Command.SavePreset(preset, version, preserveAdvancedSettings = true)) { it }
        if (saved is SdkResult.Err) return@safely saved
        draft.credential?.let { secret ->
            if (current(PresetId(id.value))?.preset?.version != version + 1) return@safely SdkResult.Err(SdkError(ErrorCode.VersionConflict, "预设已被后续修改，请重新打开后再设置凭证", false))
            val reply = if (secret.isEmpty()) b.core.deleteCredential(id) else secret.toCharArray().let { chars ->
                try { b.core.setCredential(id, chars) } finally { chars.fill('\u0000') }
            }
            reply.fault?.let { return@safely SdkResult.Err(it.toSdk()) }
        }
        read(PresetId(id.value))
    }
    override suspend fun setPresetEnabled(id: PresetId, enabled: Boolean): SdkResult<ApiPreset> = safely {
        val p = current(id)?.preset ?: return@safely notFound()
        val r = b.exec(requestId(), Command.SavePreset(p.copy(enabled = enabled), p.version)) { it }
        if (r is SdkResult.Err) r else read(id)
    }
    override suspend fun deletionImpact(id: PresetId): SdkResult<PresetDeletionImpact> = safely {
        val v = b.view(Query.Presets) as View.Presets
        val ops = (b.view(Query.Operations(200)) as View.Operations).operations
        SdkResult.Ok(PresetDeletionImpact(v.bindings.filter { it.presetId.value == id.value }.map { featureLabel(it.feature) },
            ops.count { it.preset?.id?.value == id.value && it.toSdk().status.isActive }, "删除后清除本机凭证与功能绑定；历史仍保留原预设版本。已发出的请求可能继续产生用量。"))
    }
    override suspend fun deletePreset(id: PresetId, request: ClientRequestId): SdkResult<Unit> = safely {
        val p = current(id)?.preset ?: return@safely SdkResult.Ok(Unit)
        b.exec(request, Command.DeletePreset(Ref(p.id, p.version))) { Unit }
    }
    override suspend fun testConnection(id: PresetId, request: ClientRequestId): SdkResult<OperationId> = safely {
        val p = current(id)?.preset ?: return@safely notFound()
        b.exec(request, Command.TestConnection(Ref(p.id, p.version))) { (it as? View.OperationResult)?.operation?.id?.value?.let(::OperationId) }
    }
    override fun observeBindings(): Flow<List<FeatureBinding>> = b.live {
        val v = b.view(Query.Presets) as View.Presets
        Feature.entries.map { f ->
            val preview = (b.view(Query.PreviewCall(f)) as View.Preview).preview
            FeatureBinding(f.toSdk(), featureLabel(f), "由此功能单独选择模型；无绑定时使用默认预设", preview.preset?.id?.value?.let(::PresetId),
                v.bindings.none { it.feature == f }, preview.toSdk(true).readiness)
        }
    }.combine(b.online) { bindings, online -> bindings.map { if (!online && it.state == ApiReadiness.Ready) it.copy(state = ApiReadiness.QueuedOffline) else it } }
    override suspend fun bindFeature(feature: FeatureId, presetId: PresetId?): SdkResult<FeatureBinding> = safely {
        val f = feature.toCodex() ?: return@safely invalid<FeatureBinding>("未知功能")
        val command = presetId?.let { Command.BindPreset(Binding(f, CId(it.value))) } ?: Command.UnbindPreset(f)
        val r = b.exec(requestId(), command) { it }
        if (r is SdkResult.Err) r else SdkResult.Ok(observeBindings().first().first { it.feature == feature })
    }
    private fun dev.wordprism.contract.CallPolicy.map() = CallPolicy(defaultPresetId?.value?.let(::PresetId), automaticGeneration, preGenerationMaxPerDay,
        dailyRequestLimit, maxRetries, fallbackPresetId?.value?.let(::PresetId), settingsVersion, policyVersion)
    override fun observeCallPolicy(): Flow<CallPolicy> = b.live { (b.view(Query.CallPolicy) as View.CallPolicy).policy.map() }
    override suspend fun setCallPolicy(policy: CallPolicy): SdkResult<CallPolicy> = safely {
        val current = (b.view(Query.CallPolicy) as View.CallPolicy).policy
        val value = current.copy(defaultPresetId = policy.defaultPresetId?.value?.let(::CId), fallbackPresetId = policy.fallbackPresetId?.value?.let(::CId),
            dailyRequestLimit = policy.dailyRequestLimit, maxRetries = policy.maxRetries, automaticGeneration = policy.pregenerationEnabled,
            preGenerationMaxPerDay = policy.pregenerationMaxPerDay, settingsVersion = policy.settingsVersion.takeIf { it > 0 } ?: current.settingsVersion,
            policyVersion = policy.policyVersion.takeIf { it > 0 } ?: current.policyVersion)
        b.exec(requestId(), Command.SetCallPolicy(value)) { (it as? View.CallPolicy)?.policy?.map() }
    }
    override fun observeUsage(window: OverviewWindow): Flow<List<UsageRow>> = b.live {
        val from = if (window.days == 0) 0 else System.currentTimeMillis() - window.days * 86_400_000L
        usageRows((b.view(Query.GetUsage(fromMs = from)) as View.Usages).usage)
    }
    private fun <T> notFound(): SdkResult<T> = SdkResult.Err(SdkError(ErrorCode.NotFound, "预设不存在", false))
}
