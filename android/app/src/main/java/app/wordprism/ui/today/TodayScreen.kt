package app.wordprism.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.wordprism.sdk.*
import app.wordprism.ui.common.*
import app.wordprism.ui.theme.Wp
import app.wordprism.ui.theme.WpType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TodayViewModel(sdk: WordPrismSdk, private val saved: SavedStateHandle) : WpViewModel(sdk) {
    val day = sdk.study.observeLearningDay().state(null)
    val plan = sdk.study.observePlan().state(null)
    val activeOps = sdk.operations.observeRecent().state(emptyList())
    private val _starting = MutableStateFlow(false)
    val starting = _starting.asStateFlow()

    fun start(open: (String) -> Unit, continuePastBudget: Boolean = false) {
        if (_starting.value) return
        _starting.value = true
        viewModelScope.launch {
            try { when (val r = sdk.study.startOrResume(saved.requestIdFor("start"), continuePastBudget)) {
                is SdkResult.Ok -> { saved.clearRequestId("start"); open(r.value.value) }
                is SdkResult.Err -> sayError(r.error)
            }
            } finally { _starting.value = false }
        }
    }

    fun endDay() = viewModelScope.launch {
        when (val r = sdk.study.finishLearningDay(saved.requestIdFor("endDay"))) {
            is SdkResult.Ok -> { saved.clearRequestId("endDay"); say("今天的学习已结束。" + r.value.dailyAnalysis.label) }
            is SdkResult.Err -> sayError(r.error)
        }
    }
}

@Composable
fun TodayScreen(
    openStudy: (String) -> Unit, openIntake: () -> Unit, openAdjustments: () -> Unit,
    openTasks: () -> Unit, openGroup: (String) -> Unit, openApi: () -> Unit,
    openData: () -> Unit, openFeedback: () -> Unit,
) {
    val vm = wpViewModel { sdk, s -> TodayViewModel(sdk, s) }
    CollectMessages(vm, LocalSnackbar.current)
    val day by vm.day.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val ops by vm.activeOps.collectAsStateWithLifecycle()
    val starting by vm.starting.collectAsStateWithLifecycle()
    var confirmExtra by rememberSaveable { mutableStateOf(false) }
    var confirmEnd by rememberSaveable { mutableStateOf(false) }
    val c = Wp.colors

    val d = day
    val p = plan
    if (d == null || p == null) { Loading(); return }

    val empty = p.libraryWords == 0 && p.reviewsDue == 0 && p.newPlanned == 0 && d.openSessionId == null
    if (empty) {
        EmptyToday(openIntake, openData)
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Wp.gutter).padding(bottom = 32.dp)) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.date.long(), style = MaterialTheme.typography.titleMedium, color = c.muted, modifier = Modifier.weight(1f))
            val running = ops.count { it.status.isActive }
            if (running > 0) TextButton(onClick = openTasks) { Text("$running 项任务待处理") }
        }
        Spacer(Modifier.height(4.dp))
        Text(headline(d, p), style = MaterialTheme.typography.headlineSmall, color = c.ink)

        Spacer(Modifier.height(20.dp))
        val hasOpen = d.openSessionId != null
        val hasPlanned = d.completedItems < d.plannedItems
        Button(
            onClick = { if (d.budgetState == BudgetState.Reached) confirmExtra = true else vm.start(openStudy) }, enabled = !starting && (hasOpen || hasPlanned),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(50),
        ) {
            Text(
                when {
                    starting -> "正在准备…"
                    hasOpen -> "继续学习（已完成 ${d.completedItems}/${d.plannedItems}）"
                    !hasPlanned -> "今天暂时没有待学内容"
                    d.status == DayStatus.Ended -> "继续今天的安排"
                    else -> "开始今天的学习"
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (d.status == DayStatus.Ended || d.status == DayStatus.PlanCompleted) {
            Text(if (hasOpen || hasPlanned) "今天已经结束，仍可继续未完成的安排，不会触发第二次自动分析。" else "今天的安排已经完成。可以回看词条和表达反馈，下一次复习会按记忆安排出现。", style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = openFeedback) {
            val waiting = ops.count { it.feature == FeatureId.WritingFeedback && it.status.isActive }
            val failed = ops.count { it.feature == FeatureId.WritingFeedback && it.status in setOf(OperationStatus.Failed, OperationStatus.NeedsConfiguration, OperationStatus.RestoredPending) }
            Text("表达反馈" + when { failed > 0 -> " · $failed 项需要处理"; waiting > 0 -> " · $waiting 项等待完成"; else -> " · 查看已提交的句子" })
        }
        Spacer(Modifier.height(12.dp))
        BudgetBar(d)
        SectionTitle("今天的安排")
        Panel {
            Row {
                PlanFigure("${p.reviewsDue}", "到期复习", Modifier.weight(1f))
                PlanFigure("${p.newPlanned}", "新学", Modifier.weight(1f))
                PlanFigure("${p.estimatedMinutes}", "预计分钟", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            SectionDisclosure("练习组成与估算", "回忆、辨析与表达交替安排") {
                MixBar(p.mix)
                Spacer(Modifier.height(14.dp))
                Text(p.estimateBasis, style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            p.backlogNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.caution, modifier = Modifier.padding(top = 4.dp)) }
        }

        if (p.focus.isNotEmpty()) {
            SectionTitle("重点辨析")
            p.focus.forEach { f ->
                FocusRow(f, openGroup)
                Hairline()
            }
        }

        p.adjustedBy?.let { note ->
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(c.actionSoft).clickable(onClick = openAdjustments).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = c.action, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(note, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                    Text("计划第 ${p.planVersion} 版，可以查看依据或撤销", style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = c.muted)
            }
        }

        SectionTitle("添加易混词")
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(c.surface).clickable(onClick = openIntake).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("说出你分不清的词", style = MaterialTheme.typography.bodyLarge, color = c.ink)
                Text("例如“linear 和 liner 我总搞混”，AI 会整理成词组", style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = c.muted)
        }

        SectionTitle("结束今天")
        Text(
            "结束后会记录今天的学习，${d.dailyAnalysis.label}。之后仍然可以继续学。",
            style = MaterialTheme.typography.bodyMedium, color = c.muted,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(analysisPill(d.dailyAnalysis), tone = analysisTone(d.dailyAnalysis))
            Spacer(Modifier.weight(1f))
            if (d.canEndDay) OutlinedButton(onClick = { confirmEnd = true }) { Text("结束今天") }
        }
        if (d.dailyAnalysis.state == "failed") TextButton(onClick = openApi) { Text("检查分析用的 API 预设") }
    }

    if (confirmExtra) AlertDialog(
        onDismissRequest = { confirmExtra = false }, title = { Text("今天已达到学习预算") },
        text = { Text("可以在这里停下，也可以继续。额外用时会照实记录，不会重复触发自动分析。") },
        confirmButton = { TextButton(onClick = { confirmExtra = false; vm.start(openStudy, continuePastBudget = true) }) { Text("继续学习") } },
        dismissButton = { TextButton(onClick = { confirmExtra = false }) { Text("先休息") } },
    )
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("结束今天的学习？") },
            text = { Text("已完成的作答都已保存。结束后，开启了每日分析时会用今天的数据分析一次；之后再学不会重复分析，可以在总览里手动分析。") },
            confirmButton = { TextButton(onClick = { confirmEnd = false; vm.endDay() }) { Text("结束今天") } },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("继续学习") } },
        )
    }
}

@Composable
internal fun EmptyToday(openIntake: () -> Unit, openData: () -> Unit) {
    val c = Wp.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Wp.gutter, vertical = 28.dp)) {
        Text("你的易混词，在这里分清", style = MaterialTheme.typography.labelLarge, color = c.action)
        Text("从一组易混词开始", style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.padding(top = 12.dp))
        Text("留下总是分不清的词，先理解差异，再用回忆和表达把它们记牢。", style = MaterialTheme.typography.bodyLarge, color = c.muted, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(28.dp))
        Panel {
            Text("例如", style = MaterialTheme.typography.labelMedium, color = c.muted)
            Spacer(Modifier.height(12.dp))
            PrismStrip(listOf(PrismRow("linear", "linear", listOf(IntRangeDto(4, 6)), "adj. 线性的"), PrismRow("liner", "liner ", listOf(IntRangeDto(4, 5)), "n. 衬里；邮轮")))
        }
        Spacer(Modifier.height(28.dp))
        Button(onClick = openIntake, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("添加我的第一组词") }
        OutlinedButton(onClick = openData, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 52.dp)) { Text("导入已有词库") }
        Text("已保存的词库和练习可离线使用。AI 整理需要你配置自己的 API 预设。", style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
internal fun FocusRow(focus: FocusItem, openGroup: (String) -> Unit) {
    val c = Wp.colors
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            .clickable(enabled = focus.groupId != null) { focus.groupId?.let { openGroup(it.value) } }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val parts = focus.label.split(Regex("\\s*(?:→|/)\\s*")).filter { it.isNotBlank() }
            MemberWords(parts, Modifier.fillMaxWidth())
            Text(focus.reason, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        if (focus.groupId != null) {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "打开词组", tint = c.muted)
        }
    }
}

private fun headline(d: LearningDay, p: LearningPlan) = when {
    d.status == DayStatus.Ended -> "今天到这里，学得不错"
    d.status == DayStatus.PlanCompleted -> "今天的计划完成了"
    d.openSessionId != null -> "上次学到一半，接着来"
    p.reviewsDue == 0 && p.newPlanned == 0 -> "今天没有到期内容"
    else -> "今天先复习 ${p.reviewsDue} 项，再学 ${p.newPlanned} 个新词"
}

private fun analysisPill(a: DailyAnalysisStatus) = when (a.state) {
    "will_run_after_day_end" -> "每日分析：结束后运行"
    "queued", "running" -> "每日分析：进行中"
    "waiting_network" -> "每日分析：等待联网"
    "done" -> "每日分析：已完成"
    "disabled" -> "每日分析：已关闭"
    "skipped_no_new_data" -> "每日分析：无新数据"
    "failed" -> "每日分析：失败"
    else -> a.label
}

private fun analysisTone(a: DailyAnalysisStatus) = when (a.state) {
    "done" -> Tone.Positive; "failed" -> Tone.Negative; "waiting_network" -> Tone.Caution
    "queued", "running" -> Tone.Action; else -> Tone.Neutral
}

@Composable
private fun PlanFigure(value: String, label: String, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(value, style = WpType.figure, color = Wp.colors.ink)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Wp.colors.muted)
    }
}

/** Time spent today against the user's budget; backend decides when it is "near" or "reached". */
@Composable
private fun BudgetBar(d: LearningDay) {
    val c = Wp.colors
    val minutes = (d.effectiveSeconds / 60).toInt()
    val frac = (d.effectiveSeconds / (d.budgetMinutes * 60f)).coerceIn(0f, 1f)
    val color = when (d.budgetState) { BudgetState.Reached -> c.caution; BudgetState.Near -> c.caution; else -> c.action }
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "今天已学 $minutes 分钟，预算 ${d.budgetMinutes} 分钟" }) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$minutes", style = WpType.figure, color = c.ink)
            Text(" / ${d.budgetMinutes} 分钟", style = MaterialTheme.typography.bodyLarge, color = c.muted, modifier = Modifier.padding(bottom = 4.dp))
            Spacer(Modifier.weight(1f))
            Text(
                when (d.budgetState) { BudgetState.Reached -> "已到预算"; BudgetState.Near -> "接近预算"; else -> "预算以内" },
                style = MaterialTheme.typography.bodySmall, color = if (d.budgetState == BudgetState.Within) c.muted else c.caution, modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(c.sunk)) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(50)).background(color))
        }
        if (d.waitingSeconds > 0) Text("等待生成 ${d.waitingSeconds / 60} 分钟，单独计算", style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Training mix as one stacked bar with an inline legend (single hue, varying lightness). */
@Composable
private fun MixBar(mix: List<MixShare>) {
    val c = Wp.colors
    val alphas = listOf(1f, 0.7f, 0.45f, 0.25f, 0.15f)
    Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50))) {
        mix.filter { it.share > 0f }.forEachIndexed { i, m ->
            Box(Modifier.weight(m.share).fillMaxHeight().background(c.action.copy(alpha = alphas[i % alphas.size])))
        }
    }
    Spacer(Modifier.height(10.dp))
    mix.forEachIndexed { i, m ->
        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c.action.copy(alpha = alphas[i % alphas.size])))
            Spacer(Modifier.width(8.dp))
            Text(m.label, style = MaterialTheme.typography.bodySmall, color = c.ink, modifier = Modifier.weight(1f))
            Text("${(m.share * 100 + 0.5f).toInt()}%", style = MaterialTheme.typography.bodySmall.merge(WpType.tabular), color = c.muted, fontWeight = FontWeight.Medium)
        }
    }
}
