package app.wordprism.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.wordprism.sdk.*
import app.wordprism.ui.common.*
import app.wordprism.ui.theme.Wp
import kotlinx.coroutines.launch

class RoutingViewModel(sdk: WordPrismSdk, saved: SavedStateHandle) : WpViewModel(sdk) {
    val bindings = sdk.presets.observeBindings().state(null)
    val presets = sdk.presets.observePresets().state(emptyList())
    val policy = sdk.presets.observeCallPolicy().state(null)
    val usage = sdk.presets.observeUsage(OverviewWindow.Month).state(emptyList())
    fun bind(f: FeatureId, p: PresetId?) = viewModelScope.launch { sdk.presets.bindFeature(f, p).let { if (it is SdkResult.Err) sayError(it.error) } }
    fun setPolicy(p: CallPolicy) = viewModelScope.launch {
        when (val r = sdk.presets.setCallPolicy(p)) { is SdkResult.Ok -> {}; is SdkResult.Err -> sayError(r.error) }
    }
}

@Composable
fun ApiRoutingScreen(onBack: () -> Unit, openPreset: (String?) -> Unit) {
    val vm = wpViewModel { sdk, s -> RoutingViewModel(sdk, s) }
    CollectMessages(vm, LocalSnackbar.current)
    val bindings by vm.bindings.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    val policy by vm.policy.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val c = Wp.colors

    Column(Modifier.fillMaxSize()) {
        WpTopBar("功能分配与调用", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Wp.gutter).navigationBarsPadding().padding(bottom = 32.dp)) {
            Text("每项 AI 功能可以用不同的预设。没有单独指定时使用默认预设。更换预设不会改动已经生成的内容。", style = MaterialTheme.typography.bodyMedium, color = c.muted)
            val pol = policy
            if (pol != null) {
                SectionTitle("默认预设")
                PresetPicker(pol.defaultPresetId, presets, allowNone = true, noneLabel = "不设默认") { vm.setPolicy(pol.copy(defaultPresetId = it)) }
            }
            SectionTitle("各项功能")
            val list = bindings
            if (list == null) Loading() else list.forEach { b ->
                Column(Modifier.padding(vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.label, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                            Text(b.description, style = MaterialTheme.typography.bodySmall, color = c.muted)
                        }
                        StatusPill(b.state.label(), tone = when (b.state) { ApiReadiness.Ready -> Tone.Positive; ApiReadiness.QueuedOffline -> Tone.Caution; else -> Tone.Negative })
                    }
                    Spacer(Modifier.height(6.dp))
                    PresetPicker(if (b.usesDefault) null else b.presetId, presets, allowNone = true, noneLabel = "使用默认预设") { vm.bind(b.feature, it) }
                    if (b.state == ApiReadiness.NeedsCredential && b.presetId != null) {
                        TextButton(onClick = { openPreset(b.presetId!!.value) }) { Text("为「${presets.firstOrNull { it.id == b.presetId }?.name}」填写密钥") }
                    }
                }
                Hairline()
            }
            if (presets.isEmpty()) TextButton(onClick = { openPreset(null) }) { Text("先添加一个预设") }

            if (pol != null) {
                SectionTitle("调用控制")
                Text("学习分析会根据学习情况补充题库；可在“AI 调整”中锁定此项。", style = MaterialTheme.typography.bodySmall, color = c.muted)
                NumberSetting("每日请求上限", pol.dailyRequestLimit?.toString().orEmpty(), "留空表示不限制。到达上限后，新的 AI 操作会提示额度用完") {
                    vm.setPolicy(pol.copy(dailyRequestLimit = it.toIntOrNull()))
                }
                NumberSetting("失败后自动重试次数", pol.maxRetries.toString(), "0–3 次。超时且结果未知时不会无限重试") { it.toIntOrNull()?.let { n -> vm.setPolicy(pol.copy(maxRetries = n)) } }
                Text("备用预设", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
                Text("主预设失败时改用它。不设置时不会自动换到其他付费预设。", style = MaterialTheme.typography.bodySmall, color = c.muted)
                PresetPicker(pol.fallbackPresetId, presets, allowNone = true, noneLabel = "不使用备用") { vm.setPolicy(pol.copy(fallbackPresetId = it)) }
            }

            SectionTitle("近 30 天用量")
            if (usage.isEmpty()) Text("还没有用量记录", style = MaterialTheme.typography.bodyMedium, color = c.muted)
            usage.forEach { u ->
                KeyValue("${u.featureLabel}${u.presetName?.let { "（$it）" } ?: ""}",
                    "${u.usage.requests} 次" + (u.usage.outputTokens?.let { "，${(u.usage.inputTokens ?: 0) + it} tokens" } ?: "") + (u.usage.estimatedCost?.let { "，约 $it" } ?: "") + if (u.unknownUsage) "，部分未知" else "")
            }
            Text("费用只在有可靠价目时估算；取消或失败的请求也可能已产生用量。", style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetPicker(current: PresetId?, presets: List<ApiPreset>, allowNone: Boolean, noneLabel: String, onPick: (PresetId?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = presets.firstOrNull { it.id == current }?.let { it.name + if (!it.credential.present) "（缺少密钥）" else "" } ?: noneLabel
    ExposedDropdownMenuBox(open, { open = it }) {
        OutlinedTextField(label, {}, readOnly = true, singleLine = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
        ExposedDropdownMenu(open, { open = false }) {
            if (allowNone) DropdownMenuItem(text = { Text(noneLabel) }, onClick = { open = false; onPick(null) })
            presets.forEach { p -> DropdownMenuItem(text = { Text(p.name + "（${p.model}）") }, onClick = { open = false; onPick(p.id) }, enabled = p.enabled) }
        }
    }
}

@Composable
private fun NumberSetting(title: String, value: String, help: String, onCommit: (String) -> Unit) {
    var text by rememberSaveable(value) { mutableStateOf(value) }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(help, style = MaterialTheme.typography.bodySmall, color = Wp.colors.muted)
        }
        Spacer(Modifier.width(12.dp))
        OutlinedTextField(
            text, { text = it.filter(Char::isDigit).take(4) }, singleLine = true, modifier = Modifier.width(92.dp)
                .onFocusChanged { if (!it.isFocused && text != value) onCommit(text) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}
