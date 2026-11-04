package app.wordprism.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.wordprism.sdk.*
import app.wordprism.ui.common.*
import app.wordprism.ui.theme.Wp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class PresetViewModel(sdk: WordPrismSdk, private val saved: SavedStateHandle, initialId: String?) : WpViewModel(sdk) {
    val presetId = saved.getStateFlow("id", initialId)
    val preset = presetId.flatMapLatest { id -> sdk.presets.observePresets().map { l -> l.firstOrNull { it.id.value == id } } }.state(null)
    val adapters = sdk.presets.observeAdapters().state(emptyList())
    private val testOp = saved.getStateFlow<String?>("test", null)
    val test = testOp.flatMapLatest { it?.let { sdk.operations.observe(OperationId(it)) } ?: flowOf(null) }.state(null)
    private val _errors = MutableStateFlow<SdkError?>(null)
    val errors = _errors.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    internal val models = ModelCatalogController(viewModelScope, sdk.presets::listModels)

    fun save(draft: PresetDraft, onSaved: () -> Unit) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            when (val r = sdk.presets.savePreset(draft, saved.requestIdFor("save"))) {
                is SdkResult.Ok -> { saved.clearRequestId("save"); _errors.value = null; saved["id"] = r.value.id.value; say("已保存「${r.value.name}」"); onSaved() }
                is SdkResult.Err -> _errors.value = r.error
            }
            _saving.value = false
        }
    }

    fun test() = viewModelScope.launch {
        val id = presetId.value ?: return@launch
        when (val r = sdk.presets.testConnection(PresetId(id), newRequest())) {
            is SdkResult.Ok -> saved["test"] = r.value.value
            is SdkResult.Err -> sayError(r.error)
        }
    }

    fun setEnabled(on: Boolean) = viewModelScope.launch { presetId.value?.let { sdk.presets.setPresetEnabled(PresetId(it), on) } }

    suspend fun impact(): PresetDeletionImpact? = presetId.value?.let { sdk.presets.deletionImpact(PresetId(it)).valueOrNull() }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        val id = presetId.value ?: return@launch
        when (val r = sdk.presets.deletePreset(PresetId(id), newRequest())) {
            is SdkResult.Ok -> onDone()
            is SdkResult.Err -> sayError(r.error)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetEditScreen(presetId: String?, onBack: () -> Unit) {
    val vm = wpViewModel(key = "preset-${presetId ?: "new"}") { sdk, s -> PresetViewModel(sdk, s, presetId) }
    CollectMessages(vm, LocalSnackbar.current)
    val p by vm.preset.collectAsStateWithLifecycle()
    val adapters by vm.adapters.collectAsStateWithLifecycle()
    val err by vm.errors.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    val savedId by vm.presetId.collectAsStateWithLifecycle()
    val modelState by vm.models.state.collectAsStateWithLifecycle()
    val c = Wp.colors

    var name by rememberSaveable { mutableStateOf("") }
    var adapterId by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var reasoningEffort by rememberSaveable { mutableStateOf<String?>(null) }
    var maxTokens by rememberSaveable { mutableStateOf("1200") }
    var timeout by rememberSaveable { mutableStateOf("30") }
    // The secret is held only while typing; it is never read back from the backend.
    var secret by remember { mutableStateOf("") }
    var showSecret by remember { mutableStateOf(false) }
    var removeSecret by rememberSaveable { mutableStateOf(false) }
    var initialized by rememberSaveable { mutableStateOf(presetId == null) }
    var confirmDelete by remember { mutableStateOf<PresetDeletionImpact?>(null) }
    var protocolMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }
    var modelPicker by remember { mutableStateOf(false) }
    fun resetModels() { vm.models.reset(); modelPicker = false }
    DisposableEffect(vm) { onDispose { vm.models.reset() } }
    LaunchedEffect(p?.version, p?.credential) { resetModels() }
    LaunchedEffect(modelState) {
        if ((modelState as? ModelCatalogState.Ready)?.models?.isNotEmpty() == true) modelPicker = true
    }

    LaunchedEffect(p) {
        val v = p
        if (!initialized && v != null) {
            name = v.name; adapterId = v.adapterId; baseUrl = v.baseUrl; model = v.model
            reasoningEffort = v.reasoningEffort
            maxTokens = v.maxOutputTokens?.toString().orEmpty(); timeout = v.timeoutSeconds.toString(); initialized = true
        }
    }
    LaunchedEffect(adapters, adapterId) {
        if (presetId == null && adapters.isNotEmpty() && adapters.none { it.id == adapterId }) adapterId = adapters.first().id
        if (baseUrl.isBlank()) adapters.firstOrNull { it.id == adapterId }?.defaultBaseUrl?.let { baseUrl = it }
    }
    fun fe(k: String) = err?.fieldErrors?.get(k)

    Column(Modifier.fillMaxSize().imePadding()) {
        WpTopBar(if (savedId == null) "新建 API 预设" else "编辑预设", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Wp.gutter).padding(bottom = 24.dp)) {
            Field("名称", name, { name = it }, fe("name"), placeholder = "例如：日常整理")
            ExposedDropdownMenuBox(protocolMenu, { protocolMenu = it }, Modifier.padding(top = 8.dp)) {
                OutlinedTextField(
                    adapters.firstOrNull { it.id == adapterId }?.label ?: adapterId, {}, readOnly = true, label = { Text("接口协议") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(protocolMenu) },
                    supportingText = { Text(fe("adapterId") ?: adapters.firstOrNull { it.id == adapterId }?.notes.orEmpty()) }, isError = fe("adapterId") != null,
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(protocolMenu, { protocolMenu = false }) {
                    adapters.forEach { a -> DropdownMenuItem(text = { Text(a.label) }, onClick = {
                        resetModels()
                        adapterId = a.id
                        if (baseUrl.isBlank()) baseUrl = a.defaultBaseUrl.orEmpty()
                        protocolMenu = false
                    }) }
                }
            }
            Field("服务地址", baseUrl, { resetModels(); baseUrl = it }, fe("baseUrl"), keyboard = KeyboardType.Uri)
            Field("模型标识", model, { model = it }, fe("model"), placeholder = "服务商提供的模型名称")
            val loadingModels = modelState == ModelCatalogState.Loading
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    modelPicker = false
                    vm.models.fetch(ModelCatalogDraft(baseUrl, adapterId, timeout.toIntOrNull() ?: 0,
                        presetId = savedId?.takeUnless { removeSecret }?.let(::PresetId), expectedVersion = p?.version),
                        secret.takeIf { it.isNotEmpty() }?.toCharArray())
                }, enabled = !loadingModels && !saving && initialized && baseUrl.isNotBlank()) {
                    if (loadingModels) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Text(if (loadingModels) "正在拉取…" else if (modelState is ModelCatalogState.Ready) "重新拉取" else "拉取模型")
                }
                if (loadingModels) TextButton(onClick = { modelPicker = false; vm.models.cancel() }) { Text("取消拉取") }
                (modelState as? ModelCatalogState.Ready)?.models?.takeIf { it.isNotEmpty() }?.let { list ->
                    TextButton(onClick = { modelPicker = true }) { Text("选择模型（${list.size}）") }
                }
            }
            when (val state = modelState) {
                is ModelCatalogState.Failed -> ErrorNotice(state.error)
                else -> Text(when {
                    state == ModelCatalogState.Cancelled -> "已取消拉取，可重试或手动填写模型。"
                    state is ModelCatalogState.Ready && state.models.isEmpty() -> "服务商返回了空列表，可手动填写模型标识。"
                    state is ModelCatalogState.Ready -> "已拉取 ${state.models.size} 个模型，选择后点击保存。"
                    else -> "使用当前地址与密钥查询，也可手动填写模型。"
                }, style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            val efforts = adapters.firstOrNull { it.id == adapterId }?.reasoningEfforts.orEmpty()
            ExposedDropdownMenuBox(effortMenu, { if (!saving && initialized) effortMenu = it }, Modifier.padding(top = 8.dp)) {
                OutlinedTextField(
                    efforts.firstOrNull { it.id == reasoningEffort }?.label ?: reasoningEffort ?: "默认（由模型决定）", {},
                    readOnly = true, enabled = !saving && initialized, label = { Text("思维程度") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(effortMenu) },
                    supportingText = { Text("较高程度可能增加等待和 token 用量；支持档位由模型决定。") },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(effortMenu, { effortMenu = false }) {
                    DropdownMenuItem(text = { Text("默认（由模型决定）") }, onClick = { reasoningEffort = null; effortMenu = false })
                    efforts.forEach { effort -> DropdownMenuItem(text = { Text(effort.label) }, onClick = { reasoningEffort = effort.id; effortMenu = false }) }
                }
            }
            val cred = p?.credential
            OutlinedTextField(
                secret, { resetModels(); secret = it; removeSecret = false }, singleLine = true, label = { Text("API 密钥") },
                placeholder = { Text(if (cred?.present == true && !removeSecret) "已保存，留空则不变" else "粘贴密钥") },
                visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                trailingIcon = { IconButton(onClick = { showSecret = !showSecret }) { Icon(if (showSecret) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showSecret) "隐藏密钥" else "显示密钥") } },
                supportingText = { Text("只保存在本机的加密存储中，不会出现在导出文件里") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            if (cred?.present == true && secret.isEmpty()) {
                TextButton(onClick = { resetModels(); removeSecret = !removeSecret }) { Text(if (removeSecret) "保留已保存的密钥" else "删除已保存的密钥") }
            }
            Row {
                Field("输出上限（tokens）", maxTokens, { maxTokens = it.filter(Char::isDigit) }, fe("maxOutputTokens"), keyboard = KeyboardType.Number, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Field("超时（秒）", timeout, { resetModels(); timeout = it.filter(Char::isDigit) }, fe("timeoutSeconds"), keyboard = KeyboardType.Number, modifier = Modifier.weight(1f))
            }
            err?.takeIf { it.fieldErrors.isEmpty() }?.let { ErrorNotice(it, Modifier.padding(top = 8.dp)) }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    resetModels()
                    vm.save(PresetDraft(
                        id = savedId?.let(::PresetId), expectedVersion = p?.version, name = name, adapterId = adapterId, baseUrl = baseUrl, model = model,
                        maxOutputTokens = maxTokens.toIntOrNull(), timeoutSeconds = timeout.toIntOrNull() ?: 0,
                        credential = when { secret.isNotEmpty() -> secret; removeSecret -> ""; else -> null },
                        reasoningEffort = reasoningEffort,
                    )) { secret = ""; removeSecret = false }
                },
                enabled = !saving && initialized, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text(if (saving) "正在保存…" else "保存") }
            Text("保存只检查格式，不会联网。", style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 6.dp))

            val v = p
            if (v != null) {
                SectionTitle("连接测试")
                Text("使用已保存的预设发送一次真实请求，可能产生用量。", style = MaterialTheme.typography.bodySmall, color = c.muted)
                v.lastTest?.let { t ->
                    Text((if (t.ok) "正常" else "失败") + "：${t.message}（${t.at.dayAndClock()}${t.latencyMs?.let { "，$it ms" } ?: ""}）",
                        style = MaterialTheme.typography.bodyMedium, color = if (t.ok) c.positive else c.negative, modifier = Modifier.padding(top = 8.dp))
                }
                test?.takeIf { it.status.isActive || it.status == OperationStatus.Failed }?.let { OperationLine(it, Modifier.padding(top = 8.dp)) }
                OutlinedButton(onClick = vm::test, enabled = test?.status?.isActive != true, modifier = Modifier.padding(top = 8.dp)) { Text("测试连接") }

                SectionTitle("状态")
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("启用这个预设", style = MaterialTheme.typography.bodyLarge)
                        Text(if (v.boundFeatures.isEmpty()) "没有功能使用它" else "被 ${v.boundFeatures.size} 项功能使用", style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                    Switch(v.enabled, vm::setEnabled)
                }
                val scope = rememberCoroutineScope()
                TextButton(onClick = { scope.launch { confirmDelete = vm.impact() } }, colors = ButtonDefaults.textButtonColors(contentColor = c.negative), modifier = Modifier.padding(top = 12.dp)) { Text("删除预设") }
            }
        }
    }

    if (modelPicker) (modelState as? ModelCatalogState.Ready)?.models?.let { list ->
        ModelPickerDialog(list, model, onSelect = { model = it; modelPicker = false }, onDismiss = { modelPicker = false })
    }

    confirmDelete?.let { impact ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除「${p?.name}」？") },
            text = {
                Text(buildString {
                    if (impact.boundFeatures.isNotEmpty()) append("这些功能会变成待配置：${impact.boundFeatures.joinToString("、")}。\n")
                    if (impact.runningOperations > 0) append("有 ${impact.runningOperations} 个任务正在使用它，会按原预设完成或失败。\n")
                    append(impact.note)
                })
            },
            confirmButton = { TextButton(onClick = { confirmDelete = null; vm.delete(onBack) }) { Text("删除", color = c.negative) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, error: String?, modifier: Modifier = Modifier, placeholder: String? = null, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value, onChange, singleLine = true, label = { Text(label) }, isError = error != null,
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false, imeAction = ImeAction.Next),
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
    )
}
