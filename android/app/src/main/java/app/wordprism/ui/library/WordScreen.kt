package app.wordprism.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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

data class WordPage(val word: WordDetail?)

@OptIn(ExperimentalCoroutinesApi::class)
class WordViewModel(sdk: WordPrismSdk, private val saved: SavedStateHandle, id: WordId) : WpViewModel(sdk) {
    val page = sdk.library.observeWord(id).map { WordPage(it) }.state(null)
    private val senseId = saved.getStateFlow<String?>("sense", null)
    val sense = combine(page, senseId) { p, selected ->
        p?.word?.senses?.let { list -> list.firstOrNull { it.id.value == selected } ?: list.firstOrNull() }
    }.state(null)
    val explanation = sense.map { it?.id }.distinctUntilChanged().flatMapLatest { selected ->
        selected?.let(sdk.library::observeExplanation) ?: flowOf(null)
    }.state(null)
    val requirement = sdk.library.explanationRequirement().state(null)
    private val selectedIds = saved.getStateFlow("selected", emptyList<String>())
    val selected = combine(selectedIds, explanation, sense) { ids, detail, s ->
        if (detail?.senseId != s?.id) emptyList() else ids.filter { id -> detail?.recommendations?.any { it.id == id } == true }
    }.state(emptyList())
    val acceptRequirement = selected.flatMapLatest { sdk.library.acceptRequirement(it) }.state(null)
    private val acceptId = saved.getStateFlow<String?>("acceptOp", null)
    val acceptOperation = acceptId.flatMapLatest { it?.let { id -> sdk.operations.observe(OperationId(id)) } ?: flowOf(null) }.state(null)
    private val requestedId = saved.getStateFlow<String?>("requestedOp", null)
    private val _sending = MutableStateFlow(false)
    val sending = _sending.asStateFlow()
    private val _accepting = MutableStateFlow(false)
    val accepting = _accepting.asStateFlow()
    private val _error = MutableStateFlow<SdkError?>(null)
    val error = _error.asStateFlow()

    init {
        viewModelScope.launch {
            combine(explanation, requestedId) { detail, requested -> detail?.operation?.takeIf { it.id.value == requested } }
                .filterNotNull().collect { op ->
                    if (op.status == OperationStatus.Succeeded && saved.get<String>("announcedExplanation") != op.id.value) {
                        saved["announcedExplanation"] = op.id.value
                        say("讲解已保存，可以离线查看", op.id.takeIf { OperationAction.Undo in op.actions })
                    }
                }
        }
        viewModelScope.launch {
            acceptOperation.filterNotNull().collect { op ->
                if (op.status == OperationStatus.Succeeded && saved.get<String>("announcedAcceptance") != op.id.value) {
                    saved["announcedAcceptance"] = op.id.value; saved["selected"] = emptyList<String>()
                    say(op.resultSummary ?: "所选词已加入", op.id.takeIf { OperationAction.Undo in op.actions })
                }
            }
        }
    }

    fun selectSense(id: SenseId) { saved["sense"] = id.value; saved["selected"] = emptyList<String>(); _error.value = null }
    fun toggle(id: String) {
        if (_accepting.value || acceptOperation.value?.status?.isActive == true) return
        saved["selected"] = selectedIds.value.let { if (id in it) it - id else it + id }
        saved.clearRequestId("accept")
    }
    fun generate() {
        val current = sense.value ?: return
        if (_sending.value || explanation.value?.takeIf { it.senseId == current.id }?.operation?.status?.isActive == true) return
        _sending.value = true; _error.value = null
        viewModelScope.launch {
            val key = "explain:${current.id.value}:${current.version}"
            try {
                when (val result = sdk.library.requestExplanation(current.id, current.version, saved.requestIdFor(key))) {
                    is SdkResult.Ok -> { saved["requestedOp"] = result.value.value; saved.clearRequestId(key) }
                    is SdkResult.Err -> _error.value = result.error
                }
            } finally { _sending.value = false }
        }
    }
    fun acceptSelected() {
        val ids = selected.value
        if (ids.isEmpty() || _accepting.value || acceptOperation.value?.status?.isActive == true) return
        _accepting.value = true; _error.value = null
        viewModelScope.launch {
            try {
                when (val result = sdk.library.acceptRecommendations(ids, saved.requestIdFor("accept"))) {
                    is SdkResult.Ok -> { saved["acceptOp"] = result.value.value; saved.clearRequestId("accept") }
                    is SdkResult.Err -> _error.value = result.error
                }
            } finally { _accepting.value = false }
        }
    }
    fun cancel(id: OperationId) = viewModelScope.launch {
        when (val result = sdk.operations.cancel(id)) {
            is SdkResult.Ok -> Unit
            is SdkResult.Err -> _error.value = result.error
        }
    }
    fun retry(id: OperationId) = viewModelScope.launch {
        val key = "retry:${id.value}"
        when (val result = sdk.operations.retry(id, saved.requestIdFor(key))) {
            is SdkResult.Ok -> { saved["requestedOp"] = id.value; saved.clearRequestId(key); _error.value = null }
            is SdkResult.Err -> _error.value = result.error
        }
    }
}

/** Uses the existing typography, controls and operation components; all state comes from the SDK. */
@Composable
fun WordScreen(wordId: String, onBack: () -> Unit, openApi: () -> Unit, openGroup: (String) -> Unit, openWord: (String) -> Unit) {
    val vm = wpViewModel(key = "word-$wordId") { sdk, saved -> WordViewModel(sdk, saved, WordId(wordId)) }
    CollectMessages(vm, LocalSnackbar.current)
    val page by vm.page.collectAsStateWithLifecycle()
    val sense by vm.sense.collectAsStateWithLifecycle()
    val detail by vm.explanation.collectAsStateWithLifecycle()
    val requirement by vm.requirement.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val acceptReq by vm.acceptRequirement.collectAsStateWithLifecycle()
    val acceptOp by vm.acceptOperation.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val accepting by vm.accepting.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val c = Wp.colors
    val word = page?.word
    val current = sense
    val support = detail?.takeIf { it.senseId == current?.id }

    Column(Modifier.fillMaxSize()) {
        WpTopBar("单词详解", onBack = onBack)
        when {
            page == null -> Loading()
            word == null -> EmptyState("没有找到这个词", "请返回词库重新选择。")
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Wp.gutter)
                .navigationBarsPadding().padding(bottom = 32.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(word.lemma, style = WpType.word, color = c.ink, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp)); StatusPill(word.status.label(), tone = word.status.tone())
                }
                if (word.senses.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    word.senses.forEachIndexed { index, s ->
                        FilterChip(selected = s.id == current?.id, onClick = { vm.selectSense(s.id) },
                            label = { Text("义项 ${index + 1} · ${s.partOfSpeech}") })
                    }
                }
                current?.let { s ->
                    SelectionContainer { Text("${s.partOfSpeech}  ${s.glossZh}", style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.padding(top = 10.dp)) }
                    if (s.examples.isNotEmpty()) {
                        SectionTitle("已有例句")
                        s.examples.forEach { ex -> SelectionContainer { Text(ex.text, style = WpType.english, color = c.ink, modifier = Modifier.padding(bottom = 10.dp)) } }
                    }
                }
                word.note?.let { SelectionContainer { Text("我的笔记：$it", color = c.ink, modifier = Modifier.padding(top = 12.dp)) } }
                if (word.groups.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    word.groups.forEach { g -> TextButton(onClick = { openGroup(g.id.value) }) { Text(g.title) } }
                }
                Spacer(Modifier.height(20.dp)); Hairline()
                SectionTitle("理解与记忆")
                support?.operation?.let { op ->
                    OperationLine(op, onCancel = { vm.cancel(op.id) }, onRetry = { vm.retry(op.id) })
                    if (OperationAction.Undo in op.actions) TextButton(onClick = { vm.undo(op.id) }) { Text("撤销这次讲解") }
                    Spacer(Modifier.height(8.dp))
                }
                if (support?.stale == true) {
                    Text("义项已修改，下面是旧版讲解。更新后才会继续用于生成练习。", style = MaterialTheme.typography.bodyMedium, color = c.caution)
                    Spacer(Modifier.height(12.dp))
                }
                if (support?.version == null) {
                    Text("从构词、词源和记忆联想理解这个义项，还可以发现相关词族。生成后保存在手机上。", style = MaterialTheme.typography.bodyMedium, color = c.muted)
                } else {
                    val source = support.source
                    val sourceText = listOfNotNull(if (source?.isAi == true) "AI 生成" else "已保存讲解",
                        source?.presetName?.let { "预设「$it」" }, source?.model,
                        source?.generatedAt?.dayAndClock(), "内容第 ${support.version} 版").joinToString(" · ")
                    Text(sourceText, style = MaterialTheme.typography.bodySmall, color = c.muted)
                    key(current?.id) {
                        val primary = setOf("explanation", "english_definition", "usage")
                        val order = listOf("explanation", "english_definition", "usage", "examples", "word_parts", "etymology", "mnemonic")
                        support.sections.sortedBy { order.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }.forEach { section ->
                            SectionDisclosure(section.title, initiallyExpanded = section.id in primary) {
                                SelectionContainer {
                                    Text(section.text, style = if (section.id in setOf("english_definition", "examples")) WpType.english else MaterialTheme.typography.bodyLarge, color = c.ink)
                                }
                                section.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 8.dp)) }
                                Spacer(Modifier.height(16.dp))
                            }
                            Hairline()
                        }
                    }
                    SectionTitle("关联词族")
                    Text("选中后加入词库，可以撤销；不会自动并入当前词组。", style = MaterialTheme.typography.bodySmall, color = c.muted)
                    if (support.recommendations.isEmpty()) Text(if (support.stale) "更新讲解后再查看词族候选" else "暂无待加入的词族候选", color = c.muted, modifier = Modifier.padding(vertical = 12.dp))
                    support.family.filter { family -> support.recommendations.none { it.id == family.id } }.forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.lemma + r.glossZh?.let { "  $it" }.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = c.ink)
                                Text(r.reason, style = MaterialTheme.typography.bodySmall, color = c.muted)
                            }
                            r.wordId?.let { id -> TextButton(onClick = { openWord(id.value) }) { Text("查看词条") } }
                        }
                    }
                    support.recommendations.forEach { r ->
                        Row(Modifier.fillMaxWidth().toggleable(r.id in selected, role = Role.Checkbox,
                            enabled = !accepting && acceptOp?.status?.isActive != true) { vm.toggle(r.id) }.heightIn(min = 56.dp).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(r.id in selected, onCheckedChange = null); Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.lemma + r.glossZh?.let { "  $it" }.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = c.ink)
                                Text(r.reason, style = MaterialTheme.typography.bodySmall, color = c.muted)
                                if (r.alreadyInLibrary) Text("已在词库中，将复用已有内容", style = MaterialTheme.typography.bodySmall, color = c.muted)
                            }
                        }
                    }
                    if (selected.isNotEmpty()) {
                        ApiDisclosure(acceptReq, onConfigure = openApi)
                        Button(onClick = vm::acceptSelected, enabled = !accepting && acceptOp?.status?.isActive != true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 52.dp)) {
                            Text(if (accepting) "正在加入…" else "加入所选的 ${selected.size} 个词")
                        }
                    }
                }
                acceptOp?.let { op ->
                    Spacer(Modifier.height(12.dp))
                    OperationLine(op, onCancel = { vm.cancel(op.id) }, onRetry = { vm.retry(op.id) })
                    if (OperationAction.Undo in op.actions) TextButton(onClick = { vm.undo(op.id) }) { Text("撤销加入") }
                }
                error?.let { ErrorNotice(it, Modifier.padding(top = 12.dp)) }
                Spacer(Modifier.height(20.dp))
                ApiDisclosure(requirement, onConfigure = openApi)
                OutlinedButton(onClick = vm::generate, enabled = current != null && support != null && !sending && support.operation?.status?.isActive != true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 52.dp)) {
                    Text(when {
                        sending -> "正在提交…"
                        support?.operation?.status?.isActive == true -> "等待讲解完成"
                        support?.stale == true -> "更新 AI 讲解"
                        support?.version != null -> "重新生成 AI 讲解"
                        else -> "让 AI 补充讲解"
                    })
                }
                Text("已保存的讲解会供 AI 分析和后续练习使用。自动补充可在 AI 调整中控制。", style = MaterialTheme.typography.bodySmall,
                    color = c.muted, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}
