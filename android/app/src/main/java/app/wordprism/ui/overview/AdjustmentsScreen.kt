package app.wordprism.ui.overview

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
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
class AdjustmentsViewModel(sdk: WordPrismSdk, private val saved: SavedStateHandle) : WpViewModel(sdk) {
    val settings = sdk.adaptation.observeSettings().state(null)
    val changes = sdk.adaptation.observeChangeSets().state(null)
    val capabilities = sdk.adaptation.observeCapabilities().state(emptyList())
    private val manualOp = saved.getStateFlow<String?>("op", null)
    /** The op to show: a manual one we started, else whatever the backend reports as active. */
    val activeOp = combine(manualOp, settings) { m, s -> m ?: s?.activeOperationId?.value }.distinctUntilChanged()
        .flatMapLatest { id -> id?.let { sdk.operations.observe(OperationId(it)) } ?: flowOf(null) }.state(null)

    fun setMode(on: Boolean) = viewModelScope.launch {
        when (val r = sdk.adaptation.setMode(if (on) AnalysisMode.DailyAfterDayEnd else AnalysisMode.Off)) {
            is SdkResult.Ok -> say(if (on) "已开启：每天学习结束后自动分析一次" else "已关闭每日自动分析。排队中的自动分析已取消，迟到的结果不会改动设置")
            is SdkResult.Err -> sayError(r.error)
        }
    }

    fun setBudget(min: Int) = viewModelScope.launch { sdk.adaptation.setDailyBudget(min).let { if (it is SdkResult.Err) sayError(it.error) } }

    fun analyze(force: Boolean) = viewModelScope.launch {
        val before = settings.value?.lastRun?.operationId
        when (val r = sdk.adaptation.analyzeNow(force, saved.requestIdFor("analyze"))) {
            is SdkResult.Ok -> {
                saved.clearRequestId("analyze"); saved["op"] = r.value.value
                if (r.value == before && !force) say("数据和上次分析时一样，已沿用上次的结果。需要的话可以选择重新分析")
            }
            is SdkResult.Err -> sayError(r.error)
        }
    }

    fun cancel(op: OperationId) = viewModelScope.launch { sdk.operations.cancel(op).let { if (it is SdkResult.Err) sayError(it.error) } }

    fun undoChange(id: ChangeSetId) = viewModelScope.launch {
        when (val r = sdk.adaptation.undoChangeSet(id, newRequest())) {
            is SdkResult.Ok -> say((listOf(r.value.summary) + r.value.preserved).joinToString("。"))
            is SdkResult.Err -> sayError(r.error)
        }
    }

    fun lock(id: String, locked: Boolean) = viewModelScope.launch { sdk.adaptation.setCapabilityLock(id, locked).let { if (it is SdkResult.Err) sayError(it.error) } }
}

@Composable
fun AdjustmentsScreen(onBack: () -> Unit, openApi: () -> Unit) {
    val vm = wpViewModel { sdk, s -> AdjustmentsViewModel(sdk, s) }
    CollectMessages(vm, LocalSnackbar.current)
    val s by vm.settings.collectAsStateWithLifecycle()
    val changes by vm.changes.collectAsStateWithLifecycle()
    val caps by vm.capabilities.collectAsStateWithLifecycle()
    val op by vm.activeOp.collectAsStateWithLifecycle()
    var undoTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var showReanalyze by rememberSaveable { mutableStateOf(false) }
    val c = Wp.colors

    Column(Modifier.fillMaxSize()) {
        WpTopBar("AI 调整", onBack = onBack)
        val st = s
        if (st == null) { Loading(); return@Column }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Wp.gutter).navigationBarsPadding().padding(bottom = 32.dp)) {
            Text("AI 会读取学习总览，在你的时间预算内调整明天学多少、重点练什么和训练比例，也可以微调记忆算法。每次改动都能看到依据并撤销。", style = MaterialTheme.typography.bodyMedium, color = c.muted)

            // ------------------------------------------------------ mode
            Spacer(Modifier.height(20.dp))
            val on = st.mode == AnalysisMode.DailyAfterDayEnd
            Row(
                Modifier.fillMaxWidth().toggleable(on, role = Role.Switch) { vm.setMode(it) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("每天学习结束后自动分析", style = MaterialTheme.typography.titleMedium, color = c.ink)
                    Text("每天最多一次。暂停或退出不会触发；没有新数据时跳过", style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
                Switch(checked = on, onCheckedChange = null)
            }
            ApiDisclosure(st.requirement, onConfigure = openApi)

            // ------------------------------------------------------ manual
            Spacer(Modifier.height(16.dp))
            op?.let { o ->
                Panel(Modifier.padding(bottom = 12.dp)) {
                    Text(o.title, style = MaterialTheme.typography.titleMedium, color = c.ink)
                    Spacer(Modifier.height(6.dp))
                    OperationLine(o, onCancel = { vm.cancel(o.id) })
                    o.resultSummary?.takeIf { o.status == OperationStatus.Succeeded }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.padding(top = 6.dp)) }
                }
            }
            Row {
                Button(onClick = { vm.analyze(false) }, enabled = op?.status?.isActive != true, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("现在分析") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { showReanalyze = true }, enabled = op?.status?.isActive != true, modifier = Modifier.heightIn(min = 52.dp)) { Text("重新分析") }
            }
            Text("手动分析不占用每日自动分析的次数。", style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 6.dp))

            // ------------------------------------------------------ budget (user-owned)
            SectionTitle("每日时间预算")
            var budget by remember(st.dailyBudgetMinutes) { mutableFloatStateOf(st.dailyBudgetMinutes.toFloat()) }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${budget.toInt()}", style = WpType.figure, color = c.ink)
                Text(" 分钟以内", style = MaterialTheme.typography.bodyLarge, color = c.muted, modifier = Modifier.padding(bottom = 4.dp))
            }
            Slider(
                value = budget, onValueChange = { budget = it }, onValueChangeFinished = { vm.setBudget(budget.toInt()) },
                valueRange = st.budgetRange.start.toFloat()..(st.budgetRange.endExclusive - 1).toFloat(), steps = ((st.budgetRange.endExclusive - 1 - st.budgetRange.start) / 5) - 1,
                modifier = Modifier.semantics { contentDescription = "每日时间预算" },
            )
            Text("预算由你决定。AI 只在预算内安排，质量优先，可能少学或提前结束。", style = MaterialTheme.typography.bodySmall, color = c.muted)

            // ------------------------------------------------------ change history
            SectionTitle("调整记录")
            val list = changes
            when {
                list == null -> Loading()
                list.isEmpty() -> Text("还没有调整。结束一天的学习或手动分析后，这里会显示实际生效的改动。", style = MaterialTheme.typography.bodyMedium, color = c.muted)
                else -> list.forEach { cs -> ChangeCard(cs) { undoTarget = cs.id.value }; Spacer(Modifier.height(12.dp)) }
            }

            // ------------------------------------------------------ capabilities & locks
            SectionTitle("AI 可以调整的项目")
            Text("锁定后，AI 不会更改这一项。带锁的灰色项目始终由你决定。", style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(bottom = 8.dp))
            caps.forEach { cap ->
                Row(
                    Modifier.fillMaxWidth().toggleable(cap.lockedByUser, enabled = cap.lockable, role = Role.Switch) { vm.lock(cap.id, it) }
                        .padding(vertical = 10.dp).semantics { stateDescription = if (cap.lockedByUser) "已锁定" else "AI 可调整" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(cap.label, style = MaterialTheme.typography.bodyLarge, color = if (cap.lockable) c.ink else c.muted)
                        Text("当前 ${cap.currentValue}${cap.range?.let { "，范围 $it" } ?: ""}${if (cap.affectsSchedule) "，影响复习时间" else ""}", style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                    Icon(if (cap.lockedByUser) Icons.Outlined.Lock else Icons.Outlined.LockOpen, null, tint = if (cap.lockedByUser) c.ink else c.muted)
                }
                Hairline()
            }
        }
    }

    undoTarget?.let { id ->
        val cs = changes?.firstOrNull { it.id.value == id }
        AlertDialog(
            onDismissRequest = { undoTarget = null },
            title = { Text("撤销这次调整？") },
            text = { Text("会恢复调整前的设置并重新计算计划。调整之后你完成的作答都会保留，并用于重新计算记忆状态。\n\n${cs?.summary.orEmpty()}") },
            confirmButton = { TextButton(onClick = { undoTarget = null; vm.undoChange(ChangeSetId(id)) }) { Text("撤销调整") } },
            dismissButton = { TextButton(onClick = { undoTarget = null }) { Text("保留") } },
        )
    }
    if (showReanalyze) {
        AlertDialog(
            onDismissRequest = { showReanalyze = false },
            title = { Text("重新分析？") },
            text = { Text("即使数据没有变化，也会再调用一次 API。") },
            confirmButton = { TextButton(onClick = { showReanalyze = false; vm.analyze(true) }) { Text("重新分析") } },
            dismissButton = { TextButton(onClick = { showReanalyze = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ChangeCard(cs: ChangeSet, onUndo: () -> Unit) {
    val c = Wp.colors
    val (statusText, tone) = when (cs.status) {
        ChangeStatus.Applied -> "已生效" to Tone.Positive
        ChangeStatus.Scheduled -> "待生效" to Tone.Action
        ChangeStatus.Undone -> "已撤销" to Tone.Neutral
        ChangeStatus.NoChange -> "保持不变" to Tone.Neutral
        ChangeStatus.Failed -> "未应用" to Tone.Negative
        ChangeStatus.Superseded -> "已被替代" to Tone.Neutral
        else -> cs.status.value to Tone.Neutral
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((if (cs.trigger == "auto") "每日自动" else "手动") + "，" + cs.createdAt.dayAndClock(), style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.weight(1f))
            StatusPill(statusText, tone = tone)
        }
        Text(cs.summary, style = MaterialTheme.typography.titleMedium, color = if (cs.status == ChangeStatus.Undone) c.muted else c.ink, modifier = Modifier.padding(top = 6.dp))
        if (cs.changes.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            cs.changes.forEach { ch ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) { contentDescription = "${ch.label}，从 ${ch.before} 改为 ${ch.after}，${ch.scope}" }, verticalAlignment = Alignment.CenterVertically) {
                    Text(ch.label, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f))
                    Text(ch.before, style = MaterialTheme.typography.bodyMedium.merge(WpType.tabular), color = c.muted)
                    Text("  改为  ", style = MaterialTheme.typography.bodySmall, color = c.muted)
                    Text(ch.after, style = MaterialTheme.typography.bodyMedium.merge(WpType.tabular), color = c.ink, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (cs.rationale.isNotEmpty()) {
            Text("依据", style = MaterialTheme.typography.labelLarge, color = c.ink, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
            cs.rationale.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = c.muted) }
        }
        Text("生效：${cs.effectiveFrom}。依据的数据截至 ${cs.snapshotAsOf.dayAndClock()}", style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 8.dp))
        if (cs.status == ChangeStatus.Applied) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                cs.undo.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.weight(1f)) } ?: Spacer(Modifier.weight(1f))
                if (cs.undo.available) TextButton(onClick = onUndo) { Text("撤销") }
            }
        }
    }
}
