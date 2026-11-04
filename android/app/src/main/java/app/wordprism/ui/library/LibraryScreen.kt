package app.wordprism.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wordprism.sdk.*
import app.wordprism.ui.common.*
import app.wordprism.ui.theme.Wp
import app.wordprism.ui.theme.WpType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LibraryViewModel(sdk: WordPrismSdk, private val saved: SavedStateHandle) : WpViewModel(sdk) {
    val text = saved.getStateFlow("q", "")
    val filter = saved.getStateFlow("f", "all")
    val page = combine(text.debounce(200), filter) { t, f ->
        LibraryQuery(t, relation = f.takeIf { it != "all" && it != "ungrouped" }?.let(::RelationKind), onlyNeedsGrouping = f == "ungrouped")
    }.flatMapLatest { sdk.library.observeLibrary(it) }.state(null)
    val activeOps = sdk.operations.observeRecent().map { l -> l.count { it.status.isActive } }.state(0)
    fun setText(t: String) { saved["q"] = t }
    fun setFilter(f: String) { saved["f"] = f }
}

private val filters = listOf(
    "all" to "全部", RelationKind.FormSimilar.value to "形似", RelationKind.Synonym.value to "近义",
    RelationKind.WordFamily.value to "同根", RelationKind.SoundAlike.value to "音似", RelationKind.Personal.value to "个人易混", "ungrouped" to "待整理",
)

@Composable
fun LibraryScreen(openGroup: (String) -> Unit, openIntake: (String) -> Unit, openTasks: () -> Unit) {
    val vm = wpViewModel { sdk, s -> LibraryViewModel(sdk, s) }
    val text by vm.text.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val page by vm.page.collectAsStateWithLifecycle()
    val running by vm.activeOps.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val c = Wp.colors

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Wp.gutter, end = Wp.gutter, bottom = 96.dp)) {
            item {
                Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.Bottom) {
                    Text("词库", style = MaterialTheme.typography.headlineSmall, color = c.ink)
                    Spacer(Modifier.width(12.dp))
                    page?.let { Text("${it.totalWords} 个词，${it.totalGroups} 组", style = MaterialTheme.typography.bodyMedium.merge(WpType.tabular), color = c.muted, modifier = Modifier.padding(bottom = 3.dp)) }
                    Spacer(Modifier.weight(1f))
                    if (running > 0) TextButton(onClick = openTasks) { Text("$running 个任务") }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = text, onValueChange = vm::setText, singleLine = true,
                    placeholder = { Text("搜索单词、释义或词组") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { vm.setText("") }) { Icon(Icons.Outlined.Clear, "清空搜索") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    filters.forEach { (id, label) -> FilterChip(selected = filter == id, onClick = { vm.setFilter(id) }, label = { Text(label) }) }
                }
            }
            val p = page
            when {
                p == null -> item { Loading() }
                p.groups.isEmpty() && p.ungroupedWords.isEmpty() -> item {
                    if (text.isNotBlank()) EmptyState("没有找到“$text”", "可以直接让 AI 把它加进词库。") {
                        Button(onClick = { openIntake(text) }) { Text("用 AI 添加“$text”") }
                    } else if (p.totalWords == 0) EmptyState("词库还是空的", "说出你总分不清的几个词，比如“linear 和 liner”，AI 会整理成可以学习的词组。") {
                        Button(onClick = { openIntake("") }) { Text("添加第一组易混词") }
                    } else EmptyState("这个分类下还没有内容", "换一个分类看看。")
                }
                else -> {
                    items(p.groups, key = { it.id.value }) { g -> GroupRow(g) { openGroup(g.id.value) }; Hairline() }
                    if (p.ungroupedWords.isNotEmpty()) {
                        item { SectionTitle("待整理") { Text("可以单独学习，也可以以后归组", style = MaterialTheme.typography.bodySmall, color = c.muted) } }
                        items(p.ungroupedWords, key = { it.id.value }) { w ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(w.lemma, style = WpType.wordSmall, color = c.ink)
                                    Text(listOfNotNull(w.partOfSpeech, w.glossZh).joinToString(" "), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1)
                                }
                                TextButton(onClick = { openIntake("${w.lemma} 和 ") }) { Text("归组") }
                            }
                            Hairline()
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { openIntake("") },
            icon = { Icon(Icons.Outlined.AutoAwesome, null) }, text = { Text("AI 添加") },
            containerColor = c.action, contentColor = c.onAction,
            modifier = Modifier.align(Alignment.BottomEnd).padding(Wp.gutter).semantics { contentDescription = "AI 添加易混词" },
        )
    }
}

@Composable
private fun GroupRow(g: GroupSummary, onClick: () -> Unit) {
    val c = Wp.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            MemberWords(g.members.map { it.lemma })
            Spacer(Modifier.height(4.dp))
            Text(listOfNotNull(g.relation.label(), g.purpose).joinToString("：")  , style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 2)
        }
        Spacer(Modifier.width(12.dp))
        StatusPill(if (g.dueCount > 0) "待复习 ${g.dueCount}" else g.status.label(), tone = g.status.tone())
    }
}

/** Horizontal scroll for chip rows. */
@Composable
fun Modifier.horizontalScrollCompat(): Modifier = this.then(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()))
