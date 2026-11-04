package app.wordprism.ui.intake

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.wordprism.DraftStore
import app.wordprism.WordPrismApp
import app.wordprism.sdk.*
import app.wordprism.ui.common.*
import app.wordprism.ui.theme.Wp
import app.wordprism.ui.theme.WpType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val DRAFT_KEY = "intake"

@OptIn(ExperimentalCoroutinesApi::class)
class IntakeViewModel(sdk: WordPrismSdk, private val saved: SavedStateHandle, private val drafts: DraftStore) : WpViewModel(sdk) {
    val requirement = sdk.intake.requirement().state(null)
    /** Current intake operation id; kept in SavedStateHandle so a restored screen re-attaches to the real task. */
    private val opId = saved.getStateFlow<String?>("op", null)
    val state = opId.flatMapLatest { id -> id?.let { sdk.intake.observe(OperationId(it)) } ?: flowOf(null) }.state(null)
    private val _submitting = MutableStateFlow(false)
    val submitting = _submitting.asStateFlow()

    suspend fun initialDraft(prefill: String): String = prefill.ifBlank { drafts.read(DRAFT_KEY) }
    fun draft(text: String) = viewModelScope.launch { drafts.write(DRAFT_KEY, text) }

    fun submit(text: String) {
        if (_submitting.value || text.isBlank()) return
        _submitting.value = true
        viewModelScope.launch {
            when (val r = sdk.intake.submit(IntakeRequest(text.trim()), saved.requestIdFor("intake"))) {
                is SdkResult.Ok -> { saved["op"] = r.value.value; saved.clearRequestId("intake") }
                is SdkResult.Err -> sayError(r.error)
            }
            _submitting.value = false
        }
    }

    fun resolve(options: List<String>) = viewModelScope.launch {
        val id = opId.value ?: return@launch
        sdk.intake.resolve(OperationId(id), options, saved.requestIdFor("resolve")).let { if (it is SdkResult.Err) sayError(it.error) else saved.clearRequestId("resolve") }
    }

    fun cancel() = viewModelScope.launch {
        val id = opId.value ?: return@launch
        when (val r = sdk.operations.cancel(OperationId(id))) {
            is SdkResult.Ok -> say("已取消，没有写入任何内容")
            is SdkResult.Err -> sayError(r.error) // e.g. it already finished: the screen shows the real result with undo
        }
    }

    fun retry() = viewModelScope.launch {
        val id = opId.value ?: return@launch
        sdk.operations.retry(OperationId(id), newRequest()).let { if (it is SdkResult.Err) sayError(it.error) }
    }

    fun undoResult() { opId.value?.let { undo(OperationId(it)) } }

    fun clearDraftAfterSuccess() = viewModelScope.launch { drafts.write(DRAFT_KEY, "") }

    fun startOver() { saved["op"] = null }
}

private val examples = listOf("linear 和 liner 我总是搞混", "又遇到 linen，也容易跟它们混", "affect, effect", "complement compliment")

@Composable
fun IntakeScreen(prefill: String, onClose: () -> Unit, openGroup: (String) -> Unit, openApi: () -> Unit) {
    val app = LocalContext.current.applicationContext as WordPrismApp
    val vm = wpViewModel { sdk, s -> IntakeViewModel(sdk, s, app.drafts) }
    CollectMessages(vm, LocalSnackbar.current)
    val req by vm.requirement.collectAsStateWithLifecycle()
    val st by vm.state.collectAsStateWithLifecycle()
    val submitting by vm.submitting.collectAsStateWithLifecycle()
    val c = Wp.colors
    val focusManager = LocalFocusManager.current

    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var loaded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!loaded) { val t = vm.initialDraft(prefill); value = TextFieldValue(t, TextRange(t.length)); loaded = true }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(loaded) { if (loaded && st == null) runCatching { focus.requestFocus() } }
    LaunchedEffect(st?.operation?.status) { if (st?.operation?.status == OperationStatus.Succeeded) vm.clearDraftAfterSuccess() }

    Column(Modifier.fillMaxSize().imePadding()) {
        WpTopBar("AI 添加", onBack = onClose)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Wp.gutter)) {
            val s = st
            if (s == null) {
                Text("用自己的话说出分不清的词。中英文混着写也可以。", style = MaterialTheme.typography.bodyLarge, color = c.muted)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; vm.draft(it.text) },
                    placeholder = { Text("例如：linear 和 liner 我总是搞混") },
                    minLines = 4,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    // Enter adds a line; the IME's confirm key never submits (protects Chinese IME composition).
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    supportingText = { Text("草稿会自动保存") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Spacer(Modifier.height(8.dp))
                Text("试试这样说", style = MaterialTheme.typography.bodySmall, color = c.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    examples.forEach { e -> SuggestionChip(onClick = { value = TextFieldValue(e, TextRange(e.length)); vm.draft(e) }, label = { Text(e) }) }
                }
            } else {
                Text("你说的", style = MaterialTheme.typography.bodySmall, color = c.muted)
                Text(s.input, style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                Hairline()
                Spacer(Modifier.height(16.dp))
                when {
                    s.pendingChoice != null -> ChoiceBlock(s.pendingChoice!!, onResolve = vm::resolve, onCancel = vm::cancel)
                    s.outcome != null -> OutcomeBlock(s, openGroup, vm::undoResult)
                    else -> OperationLine(s.operation, onCancel = vm::cancel, onRetry = vm::retry)
                }
                if (s.operation.status == OperationStatus.Failed && s.operation.error?.code in setOf(ErrorCode.PresetMissing, ErrorCode.CredentialMissing)) {
                    TextButton(onClick = openApi) { Text("去配置 API 预设") }
                }
            }
        }
        // ------------------------------------------------------------------- bottom action
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Wp.gutter, vertical = 12.dp)) {
            val s = st
            when {
                s == null -> {
                    ApiDisclosure(req, onConfigure = openApi)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            // An explicit click commits the visible text, just as on the study screen.
                            // IMEs can retain composition even after the keyboard has been hidden.
                            val text = value.text
                            value = value.copy(composition = null); focusManager.clearFocus()
                            vm.submit(text)
                        },
                        enabled = !submitting && value.text.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) { Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (submitting) "正在提交…" else "交给 AI 整理") }
                }
                s.operation.status.isTerminal -> OutlinedButton(onClick = { vm.startOver(); value = TextFieldValue("") }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (s.operation.status == OperationStatus.Succeeded) "再添加一组" else "重新输入")
                }
                else -> {}
            }
        }
    }
}

@Composable
private fun ChoiceBlock(choice: IntakeChoice, onResolve: (List<String>) -> Unit, onCancel: () -> Unit) {
    val c = Wp.colors
    var picked by rememberSaveable { mutableStateOf(listOf<String>()) }
    Text(choice.prompt, style = MaterialTheme.typography.titleMedium, color = c.ink)
    Text(if (choice.allowMultiple) "可以选多个，词可以同时属于几个词组" else "选一项", style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
    Column(Modifier.selectableGroup()) {
        choice.options.forEach { o ->
            val on = o.id in picked
            val toggle = {
                picked = if (choice.allowMultiple) {
                    if (on) picked - o.id else (if (o.kind == "keep" || o.kind == "new_group") listOf(o.id) else picked.filter { id -> choice.options.first { it.id == id }.kind == "group" } + o.id)
                } else listOf(o.id)
            }
            Row(
                (if (choice.allowMultiple) Modifier.toggleable(on, role = Role.Checkbox) { toggle() } else Modifier.selectable(on, role = Role.RadioButton) { toggle() })
                    .fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (choice.allowMultiple) Checkbox(on, null) else RadioButton(on, null)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(o.label, style = if (o.kind == "group" || o.kind == "spelling") WpType.wordSmall else MaterialTheme.typography.bodyLarge, color = c.ink)
                    o.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row {
        TextButton(onClick = onCancel) { Text("取消这次添加") }
        Spacer(Modifier.weight(1f))
        Button(onClick = { onResolve(picked) }, enabled = picked.isNotEmpty()) { Text("确定") }
    }
}

@Composable
private fun OutcomeBlock(s: IntakeState, openGroup: (String) -> Unit, onUndo: () -> Unit) {
    val c = Wp.colors
    val undone = s.operation.status == OperationStatus.Undone
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (undone) Icons.Outlined.Undo else Icons.Outlined.CheckCircle, null, tint = if (undone) c.muted else c.positive)
        Spacer(Modifier.width(10.dp))
        Text(if (undone) "已撤销这次添加" else s.outcome!!.headline, style = MaterialTheme.typography.titleLarge, color = c.ink)
    }
    s.outcome!!.effects.firstOrNull { it.label == s.outcome!!.headline }?.reason?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(start = 34.dp, top = 2.dp))
    }
    Spacer(Modifier.height(12.dp))
    s.outcome!!.effects.filter { it.label != s.outcome!!.headline }.forEach { e ->
        Row(Modifier.padding(vertical = 6.dp)) {
            Icon(
                when (e.kind) {
                    IntakeEffectKind.CreatedGroup, IntakeEffectKind.JoinedGroup, IntakeEffectKind.ReusedGroup -> Icons.Outlined.Workspaces
                    IntakeEffectKind.SavedUngrouped -> Icons.Outlined.Inbox
                    else -> Icons.Outlined.Spellcheck
                }, null, tint = c.muted, modifier = Modifier.size(18.dp).padding(top = 2.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(e.label, style = MaterialTheme.typography.bodyLarge, color = if (undone) c.muted else c.ink)
                e.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
            }
        }
    }
    s.operation.presetName?.let { Text("由预设「$it」生成，内容可以在词组里修改", style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 10.dp)) }
    if (!undone) {
        Spacer(Modifier.height(16.dp))
        Row {
            s.outcome!!.groupId?.let { gid -> Button(onClick = { openGroup(gid.value) }) { Text("打开词组") } }
            Spacer(Modifier.width(8.dp))
            if (OperationAction.Undo in s.operation.actions) OutlinedButton(onClick = onUndo) { Text("撤销这次添加") }
        }
    }
}
