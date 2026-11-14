package app.wordprism.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
    "all" to "全部",
    RelationKind.FormSimilar.value to "形似",
    RelationKind.Synonym.value to "近义",
    RelationKind.WordFamily.value to "同根",
    RelationKind.SoundAlike.value to "音似",
    RelationKind.Personal.value to "个人易混",
    "ungrouped" to "待整理",
)

@Composable
fun LibraryScreen(openGroup: (String) -> Unit, openIntake: (String) -> Unit, openTasks: () -> Unit, openWord: (String) -> Unit) {
    val vm = wpViewModel { sdk, s -> LibraryViewModel(sdk, s) }
    val text by vm.text.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val page by vm.page.collectAsStateWithLifecycle()
    val running by vm.activeOps.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val c = Wp.colors

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Wp.gutter, end = Wp.gutter, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("词库", style = MaterialTheme.typography.headlineSmall, color = c.ink)
                    Spacer(Modifier.width(10.dp))
                    page?.let {
                        Text(
                            "${it.totalWords} 个词 · ${it.totalGroups} 组",
                            style = MaterialTheme.typography.bodyMedium.merge(WpType.tabular),
                            color = c.muted,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (running > 0) {
                        CupertinoPill(text = "$running 个任务", tint = c.ai, onClick = openTasks)
                    }
                }
                Spacer(Modifier.height(12.dp))
                SpotlightSearchBar(
                    query = text,
                    onQueryChange = vm::setText,
                    placeholder = "搜索单词、释义或词组",
                    onSearch = { focus.clearFocus() },
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp).horizontalScrollCompat(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    filters.forEach { (id, label) ->
                        FilterChip(
                            selected = filter == id,
                            onClick = { vm.setFilter(id) },
                            shape = CircleShape,
                            label = { Text(label) },
                        )
                    }
                }
            }
            val p = page
            when {
                p == null -> item { Loading() }
                p.groups.isEmpty() && p.ungroupedWords.isEmpty() -> item {
                    if (text.isNotBlank()) {
                        EmptyState("没有找到“$text”", "可以直接让 AI 把它加进词库。") {
                            Button(onClick = { openIntake(text) }, shape = CircleShape) { Text("用 AI 添加“$text”") }
                        }
                    } else if (p.totalWords == 0) {
                        EmptyState("词库还是空的", "说出你总分不清的几个词，比如“linear 和 liner”，AI 会整理成可以学习的词组。") {
                            Button(onClick = { openIntake("") }, shape = CircleShape) { Text("添加第一组易混词") }
                        }
                    } else {
                        EmptyState("这个分类下还没有内容", "换一个分类看看。")
                    }
                }
                else -> {
                    items(p.groups, key = { it.id.value }) { g ->
                        GroupCard(g) { openGroup(g.id.value) }
                    }
                    if (p.ungroupedWords.isNotEmpty()) {
                        item {
                            SectionTitle("待整理") {
                                Text("可单独学习或稍后归组", style = MaterialTheme.typography.bodySmall, color = c.muted)
                            }
                        }
                        items(p.ungroupedWords, key = { it.id.value }) { w ->
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = c.surface,
                                border = BorderStroke(0.5.dp, c.hairline),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f).clickable { openWord(w.id.value) }.heightIn(min = 44.dp)) {
                                        Text(w.lemma, style = WpType.wordSmall, color = c.ink)
                                        Text(
                                            listOfNotNull(w.partOfSpeech, w.glossZh).joinToString(" "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = c.muted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    TextButton(onClick = { openWord(w.id.value) }) { Text("详解") }
                                    TextButton(onClick = { openIntake("${w.lemma} 和 ") }) { Text("归组") }
                                }
                            }
                        }
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { openIntake("") },
            icon = { Icon(Icons.Outlined.AutoAwesome, null) },
            text = { Text("AI 添加") },
            shape = CircleShape,
            containerColor = c.ai,
            contentColor = c.onAction,
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(horizontal = Wp.gutter, vertical = 14.dp)
                .semantics { contentDescription = "AI 添加易混词" },
        )
    }
}

@Composable
private fun GroupCard(g: GroupSummary, onClick: () -> Unit) {
    val c = Wp.colors
    Surface(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(20.dp),
        color = c.surface,
        border = BorderStroke(0.5.dp, c.hairline),
        shadowElevation = if (c.isDark) 0.dp else 1.5.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        g.members.take(5).forEachIndexed { idx, _ ->
                            Box(Modifier.size(6.dp).background(c.member(idx), CircleShape))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(g.relation.label(), style = MaterialTheme.typography.labelSmall, color = c.muted)
                    }
                    Spacer(Modifier.height(6.dp))
                    MemberWords(g.members.map { it.lemma })
                    g.purpose?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(12.dp))
                StatusPill(
                    if (g.dueCount > 0) "待复习 ${g.dueCount}" else g.status.label(),
                    tone = g.status.tone(),
                )
            }
        }
    }
}

/** Horizontal scroll for chip rows. */
@Composable
fun Modifier.horizontalScrollCompat(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))
