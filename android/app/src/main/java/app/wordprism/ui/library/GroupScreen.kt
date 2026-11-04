package app.wordprism.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.wordprism.sdk.*
import app.wordprism.ui.common.*
import app.wordprism.ui.theme.Wp
import app.wordprism.ui.theme.WpType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class GroupViewModel(sdk: WordPrismSdk, private val saved: SavedStateHandle, private val id: GroupId) : WpViewModel(sdk) {
    val group = sdk.library.observeGroup(id).state(null)
    val recs = sdk.library.observeRecommendations(id).state(null)
    val recReq = sdk.library.recommendationRequirement().state(null)
    val selected = saved.getStateFlow("sel", emptyList<String>())
    val acceptReq = selected.flatMapLatest { sdk.library.acceptRequirement(id, it) }.state(null)
    private val recOpId = saved.getStateFlow<String?>("recOp", null)
    private val acceptOpId = saved.getStateFlow<String?>("acceptOp", null)
    val recOp = recOpId.flatMapLatest { it?.let { sdk.operations.observe(OperationId(it)) } ?: flowOf(null) }.state(null)
    val acceptOp = acceptOpId.flatMapLatest { it?.let { sdk.operations.observe(OperationId(it)) } ?: flowOf(null) }.state(null)
    private val _editError = MutableStateFlow<SdkError?>(null)
    val editError = _editError.asStateFlow()

    init {
        // Announce the accept result with an undo action once the backend confirms it.
        viewModelScope.launch {
            acceptOp.filterNotNull().filter { it.status == OperationStatus.Succeeded }.distinctUntilChangedBy { it.id }.collect { op ->
                say(op.resultSummary ?: "已加入", op.id.takeIf { OperationAction.Undo in op.actions })
                saved["sel"] = emptyList<String>()
            }
        }
    }

    fun toggle(recId: String) { saved["sel"] = selected.value.let { if (recId in it) it - recId else it + recId } }

    fun findMore() = viewModelScope.launch {
        when (val r = sdk.library.requestRecommendations(id, saved.requestIdFor("findMore"))) {
            is SdkResult.Ok -> { saved["recOp"] = r.value.value; saved.clearRequestId("findMore") }
            is SdkResult.Err -> sayError(r.error)
        }
    }

    fun acceptSelected() = viewModelScope.launch {
        when (val r = sdk.library.acceptRecommendations(id, selected.value, saved.requestIdFor("accept"))) {
            is SdkResult.Ok -> { saved["acceptOp"] = r.value.value; saved.clearRequestId("accept") }
            is SdkResult.Err -> sayError(r.error)
        }
    }

    fun cancel(op: OperationId) = viewModelScope.launch { sdk.operations.cancel(op).let { if (it is SdkResult.Err) sayError(it.error) } }

    fun removeMember(word: WordId, lemma: String) = viewModelScope.launch {
        val g = group.value ?: return@launch
        when (val r = sdk.library.changeMembership(MembershipChange(id, remove = listOf(word)), g.version, newRequest())) {
            is SdkResult.Ok -> say(r.value.summary, r.value.operationId.takeIf { r.value.undoable })
            is SdkResult.Err -> sayError(r.error)
        }
    }

    fun saveWord(word: WordId, version: Long, sense: Sense?, gloss: String, note: String, done: () -> Unit) = viewModelScope.launch {
        _editError.value = null
        when (val r = sdk.library.updateWord(word, version, WordPatch(note = note, glossZh = gloss, senseId = sense?.id, senseVersion = sense?.version), newRequest())) {
            is SdkResult.Ok -> { done(); say(r.value.summary, r.value.operationId.takeIf { r.value.undoable }) }
            is SdkResult.Err -> _editError.value = r.error
        }
    }

    fun rename(title: String, done: () -> Unit) = viewModelScope.launch {
        val g = group.value ?: return@launch
        when (val r = sdk.library.renameGroup(id, title, g.version, newRequest())) {
            is SdkResult.Ok -> { done(); say(r.value.summary, r.value.operationId) }
            is SdkResult.Err -> sayError(r.error)
        }
    }

    fun clearEditError() { _editError.value = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(groupId: String, onBack: () -> Unit, openApi: () -> Unit, openWord: (String) -> Unit) {
    val vm = wpViewModel(key = "group-$groupId") { sdk, s -> GroupViewModel(sdk, s, GroupId(groupId)) }
    CollectMessages(vm, LocalSnackbar.current)
    val group by vm.group.collectAsStateWithLifecycle()
    val recs by vm.recs.collectAsStateWithLifecycle()
    val recReq by vm.recReq.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val acceptReq by vm.acceptReq.collectAsStateWithLifecycle()
    val recOp by vm.recOp.collectAsStateWithLifecycle()
    val acceptOp by vm.acceptOp.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    val c = Wp.colors

    Column(Modifier.fillMaxSize()) {
        WpTopBar(group?.title ?: "词组", onBack = onBack) {
            if (group != null) IconButton(onClick = { renaming = true }) { Icon(Icons.Outlined.Edit, "重命名词组") }
        }
        val g = group
        if (g == null) { Loading(); return@Column }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Wp.gutter).navigationBarsPadding().padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(g.relation.label(), tone = Tone.Action)
                Spacer(Modifier.width(10.dp))
                g.purpose?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.muted) }
            }
            Spacer(Modifier.height(20.dp))
            PrismStrip(g.members.map { it.prismRow() })
            g.oneLineDifference?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.padding(top = 16.dp)) }
            SourceNote(g.source, Modifier.padding(top = 6.dp))

            g.members.forEachIndexed { i, m ->
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c.member(i)))
                    Spacer(Modifier.width(10.dp))
                    Text(m.lemma, style = WpType.word.copy(fontSize = WpType.word.fontSize * 0.8f), color = c.ink, modifier = Modifier.weight(1f))
                    StatusPill(m.status.label(), tone = m.status.tone())
                    IconButton(onClick = { editing = m.wordId.value }) { Icon(Icons.Outlined.EditNote, "编辑 ${m.lemma}") }
                    IconButton(onClick = { removing = m.wordId.value }, enabled = g.members.size > 1) { Icon(Icons.Outlined.RemoveCircleOutline, "从词组移出 ${m.lemma}") }
                }
                m.senses.forEach { s -> Text("${s.partOfSpeech}  ${s.glossZh}", style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.padding(start = 20.dp, top = 4.dp)) }
                Column(Modifier.padding(start = 20.dp)) {
                    if (m.collocations.isNotEmpty()) Text("搭配：" + m.collocations.joinToString("，"), style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(top = 4.dp))
                    m.examples.forEach { ex ->
                        Text(ex.text, style = WpType.english.copy(fontSize = WpType.english.fontSize * 0.9f), color = c.ink, modifier = Modifier.padding(top = 8.dp))
                        ex.translationZh?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
                    }
                    m.note?.let { Text("笔记：$it", style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.padding(top = 8.dp)) }
                    m.joinReason?.let { Text("归组理由：$it", style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 6.dp)) }
                    TextButton(onClick = { openWord(m.wordId.value) }) { Text("词源与助记") }
                }
                Spacer(Modifier.height(12.dp)); Hairline()
            }

            // --------------------------------------------------------- recommendations
            SectionTitle("相似词推荐")
            Text("推荐的词不会自动加入，选中后才会加入这个词组；加入后可以撤销。查看推荐不调用 API。", style = MaterialTheme.typography.bodySmall, color = c.muted)
            val items = recs?.items.orEmpty()
            if (items.isEmpty()) Text("暂时没有推荐", style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(vertical = 12.dp))
            items.forEach { r ->
                Row(
                    Modifier.fillMaxWidth().toggleable(r.id in selected, role = Role.Checkbox, enabled = acceptOp?.status?.isActive != true) { vm.toggle(r.id) }
                        .heightIn(min = 56.dp).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = r.id in selected, onCheckedChange = null)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.lemma + (r.glossZh?.let { "  $it" } ?: ""), style = MaterialTheme.typography.bodyLarge, color = c.ink)
                        Text(r.reason + if (r.alreadyInLibrary) "（已在词库中，直接复用）" else "", style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                }
            }
            acceptOp?.takeIf { it.status.isActive || it.status == OperationStatus.Failed }?.let { OperationLine(it, Modifier.padding(vertical = 8.dp), onCancel = { vm.cancel(it.id) }) }
            if (selected.isNotEmpty()) {
                ApiDisclosure(acceptReq, Modifier.padding(top = 4.dp), onConfigure = openApi)
                Button(onClick = vm::acceptSelected, enabled = acceptOp?.status?.isActive != true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 52.dp)) {
                    Text("加入所选的 ${selected.size} 个词")
                }
            }
            Spacer(Modifier.height(16.dp))
            recOp?.takeIf { it.status.isActive || it.status == OperationStatus.Failed }?.let { OperationLine(it, Modifier.padding(bottom = 8.dp), onCancel = { vm.cancel(it.id) }) }
            ApiDisclosure(recReq, onConfigure = openApi)
            OutlinedButton(onClick = vm::findMore, enabled = recOp?.status?.isActive != true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.TravelExplore, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("让 AI 再找一些")
            }
        }
    }

    val g = group
    editing?.let { wid ->
        val m = g?.members?.firstOrNull { it.wordId.value == wid }
        if (m == null) { editing = null } else WordEditSheet(m, vm, onDismiss = { editing = null; vm.clearEditError() })
    }
    removing?.let { wid ->
        val m = g?.members?.firstOrNull { it.wordId.value == wid }
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("把 ${m?.lemma} 移出这个词组？") },
            text = { Text("词条本身和它的学习记录会保留，只是不再和这组词一起辨析。可以撤销。") },
            confirmButton = { TextButton(onClick = { removing = null; if (m != null) vm.removeMember(m.wordId, m.lemma) }) { Text("移出") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } },
        )
    }
    if (renaming && g != null) {
        var title by rememberSaveable { mutableStateOf(g.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("重命名词组") },
            text = { OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("名称") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)) },
            confirmButton = { TextButton(onClick = { vm.rename(title) { renaming = false } }, enabled = title.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WordEditSheet(m: GroupMember, vm: GroupViewModel, onDismiss: () -> Unit) {
    val sdk = appSdk()
    val word by remember(m.wordId) { sdk.library.observeWord(m.wordId) }.collectAsStateWithLifecycle(null)
    val error by vm.editError.collectAsStateWithLifecycle()
    var gloss by rememberSaveable(m.wordId.value) { mutableStateOf(m.senses.firstOrNull()?.glossZh.orEmpty()) }
    var note by rememberSaveable(m.wordId.value) { mutableStateOf(m.note.orEmpty()) }
    // Version the user started editing from; a newer version on save is reported as a conflict.
    var baseVersion by rememberSaveable(m.wordId.value) { mutableStateOf<Long?>(null) }
    LaunchedEffect(word) { if (baseVersion == null) baseVersion = word?.version }
    LaunchedEffect(error) {
        if (error?.code == ErrorCode.VersionConflict) word?.let { w -> baseVersion = w.version } // keep what the user typed; saving again confirms it
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Wp.colors.surface) {
        Column(Modifier.padding(horizontal = Wp.gutter).padding(bottom = 24.dp).imePadding()) {
            Text("编辑 ${m.lemma}", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(gloss, { gloss = it }, label = { Text("中文释义") }, isError = error?.fieldErrors?.containsKey("glossZh") == true,
                supportingText = { error?.fieldErrors?.get("glossZh")?.let { Text(it) } }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(note, { note = it }, label = { Text("我的笔记") }, minLines = 2, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            error?.takeIf { it.fieldErrors.isEmpty() }?.let { ErrorNotice(it, Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { baseVersion?.let { v -> vm.saveWord(m.wordId, v, m.senses.firstOrNull(), gloss, note, onDismiss) } },
                enabled = baseVersion != null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("保存") }
        }
    }
}
