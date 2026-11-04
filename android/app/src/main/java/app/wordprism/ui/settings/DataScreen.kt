package app.wordprism.ui.settings

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
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
class DataViewModel(sdk: WordPrismSdk, private val saved: SavedStateHandle) : WpViewModel(sdk) {
    val groups = sdk.library.observeLibrary(LibraryQuery()).map { it.groups }.state(emptyList())
    private val opId = saved.getStateFlow<String?>("op", null)
    val op = opId.flatMapLatest { it?.let { sdk.operations.observe(OperationId(it)) } ?: flowOf(null) }.state(null)
    private val _preview = MutableStateFlow<ImportPreview?>(null)
    val preview = _preview.asStateFlow()
    private val _scope = MutableStateFlow<ExportScopeInfo?>(null)
    val scope = _scope.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    val selectedGroups = saved.getStateFlow("groups", emptyList<String>())

    init { viewModelScope.launch { _scope.value = sdk.dataTransfer.exportScope(ExportRequest(PackagePurpose.FullBackup)).valueOrNull() } }

    fun toggleGroup(id: String) { saved["groups"] = selectedGroups.value.let { if (id in it) it - id else it + id } }

    suspend fun suggestedName(purpose: PackagePurpose): String =
        sdk.dataTransfer.exportScope(ExportRequest(purpose)).valueOrNull()?.suggestedFileName ?: "wordprism.wordprism"

    fun export(purpose: PackagePurpose, target: DocumentRef) = viewModelScope.launch {
        val req = ExportRequest(purpose, if (purpose == PackagePurpose.ContentPack) selectedGroups.value.map(::GroupId) else emptyList())
        when (val r = sdk.dataTransfer.export(req, target, newRequest())) {
            is SdkResult.Ok -> saved["op"] = r.value.value
            is SdkResult.Err -> sayError(r.error)
        }
    }

    fun preview(doc: DocumentRef) = viewModelScope.launch {
        _busy.value = true; _preview.value = null
        when (val r = sdk.dataTransfer.previewImport(doc)) {
            is SdkResult.Ok -> _preview.value = r.value
            is SdkResult.Err -> sayError(r.error)
        }
        _busy.value = false
    }

    fun commit() = viewModelScope.launch {
        val p = _preview.value ?: return@launch
        when (val r = sdk.dataTransfer.commitImport(p.previewId, saved.requestIdFor("commit:${p.previewId}"))) {
            is SdkResult.Ok -> { saved["op"] = r.value.value; _preview.value = null }
            is SdkResult.Err -> sayError(r.error)
        }
    }

    fun dismissPreview() { _preview.value = null }
    fun cancel(id: OperationId) = viewModelScope.launch { sdk.operations.cancel(id) }
}

@Composable
fun DataScreen(onBack: () -> Unit) {
    val vm = wpViewModel { sdk, s -> DataViewModel(sdk, s) }
    CollectMessages(vm, LocalSnackbar.current)
    val context = LocalContext.current
    val op by vm.op.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val scopeInfo by vm.scope.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val selected by vm.selectedGroups.collectAsStateWithLifecycle()
    var pendingPurpose by rememberSaveable { mutableStateOf(PackagePurpose.FullBackup.value) }
    var pickGroups by rememberSaveable { mutableStateOf(false) }
    var confirmRestore by rememberSaveable { mutableStateOf(false) }
    val coroutine = rememberCoroutineScope()
    val c = Wp.colors

    fun docRef(uri: Uri): DocumentRef {
        var name: String? = null; var size: Long? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) { name = cur.getString(0); size = cur.getLong(1).takeIf { it > 0 } }
            }
        }
        return DocumentRef(uri.toString(), name, size)
    }
    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.export(PackagePurpose(pendingPurpose), docRef(uri))
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.preview(docRef(uri))
    }

    Column(Modifier.fillMaxSize()) {
        WpTopBar("导入与导出", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Wp.gutter).navigationBarsPadding().padding(bottom = 32.dp)) {
            Text("只支持 WordPrism 专属数据包（.wordprism）。没有云同步，换手机时用完整备份。", style = MaterialTheme.typography.bodyMedium, color = c.muted)

            op?.let { o ->
                Panel(Modifier.padding(top = 16.dp)) {
                    Text(o.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    OperationLine(o, onCancel = { vm.cancel(o.id) })
                    o.resultSummary?.takeIf { o.status == OperationStatus.Succeeded }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
                    if (OperationAction.Undo in o.actions) TextButton(onClick = { vm.undo(o.id) }) { Text("撤销这次导入") }
                }
            }

            SectionTitle("完整备份")
            scopeInfo?.let { s ->
                Text("包含：" + s.included.joinToString("、"), style = MaterialTheme.typography.bodySmall, color = c.ink)
                Text("不含：" + s.excluded.joinToString("、"), style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 2.dp))
            }
            Button(onClick = {
                pendingPurpose = PackagePurpose.FullBackup.value
                coroutine.launch { createDoc.launch(vm.suggestedName(PackagePurpose.FullBackup)) }
            }, enabled = op?.status?.isActive != true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 52.dp)) {
                Icon(Icons.Outlined.Backup, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("导出完整备份")
            }

            SectionTitle("分享词组")
            Text("包含所选词组的词条、释义、例句、归组、已保存讲解、词族候选和练习内容，不含学习进度、AI 调整记录和 API 配置。", style = MaterialTheme.typography.bodySmall, color = c.muted)
            OutlinedButton(onClick = { pickGroups = true }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 52.dp)) {
                Text(if (selected.isEmpty()) "选择要导出的词组" else "已选 ${selected.size} 个词组，导出")
            }

            SectionTitle("导入")
            Text("先预览会发生什么，再决定是否导入。导入本身不会调用 API。", style = MaterialTheme.typography.bodySmall, color = c.muted)
            OutlinedButton(onClick = { openDoc.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 52.dp)) {
                Icon(Icons.Outlined.FileOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (busy) "正在读取…" else "选择数据包")
            }
            preview?.let { p -> PreviewCard(p, onCommit = { if (p.replacesLibrary) confirmRestore = true else vm.commit() }, onDismiss = vm::dismissPreview) }
        }
    }

    if (pickGroups) {
        AlertDialog(
            onDismissRequest = { pickGroups = false },
            title = { Text("选择词组") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    groups.forEach { g ->
                        Row(Modifier.fillMaxWidth().toggleable(g.id.value in selected, role = Role.Checkbox) { vm.toggleGroup(g.id.value) }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(g.id.value in selected, null); Spacer(Modifier.width(8.dp)); Text(g.title)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = selected.isNotEmpty(), onClick = {
                    pickGroups = false; pendingPurpose = PackagePurpose.ContentPack.value
                    coroutine.launch { createDoc.launch(vm.suggestedName(PackagePurpose.ContentPack)) }
                }) { Text("导出 ${selected.size} 个") }
            },
            dismissButton = { TextButton(onClick = { pickGroups = false }) { Text("取消") } },
        )
    }
    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("用备份替换当前资料？") },
            text = { Text("当前的词库、学习记录和 AI 调整记录会被备份中的内容替换。恢复会先在暂存区检查，失败时现有资料保持不变。建议先导出一份当前的完整备份。") },
            confirmButton = { TextButton(onClick = { confirmRestore = false; vm.commit() }) { Text("替换并恢复", color = c.negative) } },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun PreviewCard(p: ImportPreview, onCommit: () -> Unit, onDismiss: () -> Unit) {
    val c = Wp.colors
    Panel(Modifier.padding(top = 16.dp)) {
        Text(p.document.displayName ?: "数据包", style = MaterialTheme.typography.titleMedium)
        Text(
            (if (p.purpose == PackagePurpose.FullBackup) "完整备份" else "词库内容包") + "，格式 ${p.formatVersion}" + (p.createdAt?.let { "，创建于 ${it.dayAndClock()}" } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = c.muted,
        )
        Spacer(Modifier.height(10.dp))
        if (p.replacesLibrary) Text("会替换当前资料库", style = MaterialTheme.typography.bodyMedium, color = c.caution)
        else {
            KeyValue("新增", "${p.counts.newItems}")
            KeyValue("重复（跳过）", "${p.counts.duplicates}")
            if (p.counts.conflicts > 0) KeyValue("同名冲突", "${p.counts.conflicts}", valueColor = c.caution)
            if (p.counts.invalid > 0) KeyValue("无效记录", "${p.counts.invalid}", valueColor = c.negative)
        }
        Text("模块", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
        p.modules.forEach { m ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(m.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(m.stateLabel, style = MaterialTheme.typography.bodySmall, color = when (m.state) { "ok" -> c.muted; "unsupported_required" -> c.negative; else -> c.caution })
            }
        }
        (p.pendingConfiguration + p.warnings).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = c.caution, modifier = Modifier.padding(top = 4.dp)) }
        p.blockingReason?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.negative, modifier = Modifier.padding(top = 8.dp)) }
        Row(Modifier.padding(top = 12.dp)) {
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.weight(1f))
            Button(onClick = onCommit, enabled = p.canCommit) { Text(if (p.replacesLibrary) "恢复备份" else "导入") }
        }
    }
}
