package app.wordprism.ui.today

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            try {
                when (val r = sdk.study.startOrResume(saved.requestIdFor("start"), continuePastBudget)) {
                    is SdkResult.Ok -> { saved.clearRequestId("start"); open(r.value.value) }
                    is SdkResult.Err -> sayError(r.error)
                }
            } finally {
                _starting.value = false
            }
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
    openStudy: (String) -> Unit,
    openIntake: () -> Unit,
    openAdjustments: () -> Unit,
    openTasks: () -> Unit,
    openGroup: (String) -> Unit,
    openApi: () -> Unit,
    openData: () -> Unit,
    openFeedback: () -> Unit,
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
    if (d == null || p == null) {
        Loading()
        return
    }

    val empty = p.libraryWords == 0 && p.reviewsDue == 0 && p.newPlanned == 0 && d.openSessionId == null
    if (empty) {
        EmptyToday(openIntake, openData)
        return
    }

    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Wp.gutter)
            .padding(bottom = 32.dp),
    ) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.date.long(), style = MaterialTheme.typography.titleSmall, color = c.muted, modifier = Modifier.weight(1f))
            val running = ops.count { it.status.isActive }
            if (running > 0) {
                CupertinoPill(
                    text = "$running 项任务进行中",
                    icon = Icons.Outlined.HourglassEmpty,
                    tint = c.ai,
                    onClick = openTasks,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(headline(d, p), style = MaterialTheme.typography.headlineSmall, color = c.ink)

        Spacer(Modifier.height(14.dp))

        // Spotlight quick-entry strip for natural-language word capture
        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = openIntake),
            shape = RoundedCornerShape(16.dp),
            color = c.surface,
            border = BorderStroke(0.5.dp, c.hairline),
            shadowElevation = if (c.isDark) 0.dp else 1.5.dp,
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(c.aiSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Search, null, tint = c.ai, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("写下分不清的词…", style = MaterialTheme.typography.bodyMedium, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                CupertinoPill(text = "+ 添词", tint = c.action, onClick = openIntake)
            }
        }

        Spacer(Modifier.height(16.dp))

        // Hero Spatial Sprint Pass Card
        val hasOpen = d.openSessionId != null
        val hasPlanned = d.completedItems < d.plannedItems
        Panel(
            padding = PaddingValues(20.dp),
            accentLipColor = c.action,
        ) {
            BudgetPassHeader(d)
            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlanMetricWell("${p.reviewsDue}", "到期复习", Modifier.weight(1f))
                PlanMetricWell("${p.newPlanned}", "新学", Modifier.weight(1f))
                PlanMetricWell("${p.estimatedMinutes}", "预计分钟", Modifier.weight(1f))
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { if (d.budgetState == BudgetState.Reached) confirmExtra = true else vm.start(openStudy) },
                enabled = !starting && (hasOpen || hasPlanned),
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                shape = CircleShape,
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
                Text(
                    if (hasOpen || hasPlanned) "今天已经结束，仍可继续未完成的安排，不会触发第二次自动分析。"
                    else "今天的安排已经完成。可以回看词条和表达反馈，下一次复习会按记忆安排出现。",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
            SectionDisclosure("练习组成与估算", "回忆、辨析与表达交替安排") {
                MixBar(p.mix)
                Spacer(Modifier.height(12.dp))
                Text(p.estimateBasis, style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            p.backlogNote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = c.caution, modifier = Modifier.padding(top = 6.dp))
            }
        }

        // Apple Wallet 3D Stacked Confusion Deck
        if (p.focus.isNotEmpty()) {
            var stackExpanded by rememberSaveable { mutableStateOf(false) }
            SectionTitle(
                text = "重点辨析 · 卡包叠放",
                trailing = if (p.focus.size > 1) {
                    {
                        TextButton(
                            onClick = { stackExpanded = !stackExpanded },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(if (stackExpanded) "叠放" else "展开全部 (${p.focus.size})", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                } else null,
            )
            if (stackExpanded || p.focus.size == 1) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    p.focus.forEachIndexed { idx, f ->
                        Panel(
                            padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            accentLipColor = c.member(idx),
                        ) {
                            FocusRow(f, openGroup)
                        }
                    }
                }
            } else {
                FocusWalletDeck(items = p.focus, openGroup = openGroup)
            }
        }

        p.adjustedBy?.let { note ->
            Spacer(Modifier.height(16.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = openAdjustments),
                shape = RoundedCornerShape(20.dp),
                color = c.aiSoft,
                border = BorderStroke(0.5.dp, c.ai.copy(alpha = 0.28f)),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(c.ai.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = c.ai, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(note, style = MaterialTheme.typography.bodyMedium, color = c.ink, fontWeight = FontWeight.Medium)
                        Text("计划第 ${p.planVersion} 版 · 查看调整依据或撤销", style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = c.muted)
                }
            }
        }

        SectionTitle("表达与收尾")
        InsetGroupCard {
            val waiting = ops.count { it.feature == FeatureId.WritingFeedback && it.status.isActive }
            val failed = ops.count {
                it.feature == FeatureId.WritingFeedback && it.status in setOf(OperationStatus.Failed, OperationStatus.NeedsConfiguration, OperationStatus.RestoredPending)
            }
            Row(
                Modifier.fillMaxWidth().clickable(onClick = openFeedback).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.EditNote, null, tint = c.action, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("表达反馈", style = MaterialTheme.typography.bodyLarge, color = c.ink)
                    Text(
                        when {
                            failed > 0 -> "$failed 项需要处理"
                            waiting > 0 -> "$waiting 项等待完成"
                            else -> "查看已提交的句子与 AI 点评"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed > 0) c.negative else c.muted,
                    )
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = c.muted)
            }
            InsetDivider()
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    "结束后会记录今天的学习，${d.dailyAnalysis.label}。之后仍然可以继续学。",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(analysisPill(d.dailyAnalysis), tone = analysisTone(d.dailyAnalysis))
                    Spacer(Modifier.weight(1f))
                    if (d.canEndDay) OutlinedButton(onClick = { confirmEnd = true }, shape = CircleShape) { Text("结束今天") }
                }
                if (d.dailyAnalysis.state == "failed") {
                    TextButton(onClick = openApi, contentPadding = PaddingValues(0.dp)) { Text("检查分析用的 API 预设") }
                }
            }
        }
    }

    if (confirmExtra) AlertDialog(
        onDismissRequest = { confirmExtra = false },
        title = { Text("今天已达到学习预算") },
        text = { Text("可以在这里停下，也可以继续。额外用时会照实记录，不会重复触发自动分析。") },
        confirmButton = { TextButton(onClick = { confirmExtra = false; vm.start(openStudy, continuePastBudget = true) }) { Text("继续学习") } },
        dismissButton = { TextButton(onClick = { confirmExtra = false }) { Text("先休息") } },
    )
    if (confirmEnd) AlertDialog(
        onDismissRequest = { confirmEnd = false },
        title = { Text("结束今天的学习？") },
        text = { Text("已完成的作答都已保存。结束后，开启了每日分析时会用今天的数据分析一次；之后再学不会重复分析，可以在总览里手动分析。") },
        confirmButton = { TextButton(onClick = { confirmEnd = false; vm.endDay() }) { Text("结束今天") } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("继续学习") } },
    )
}

/** Stacked passes keep every label tappable and let the active card grow with its content. */
@Composable
internal fun FocusWalletDeck(items: List<FocusItem>, openGroup: (String) -> Unit) {
    if (items.isEmpty()) return
    val c = Wp.colors
    var activeIndex by rememberSaveable(items) { mutableIntStateOf(0) }
    val safeActive = activeIndex.coerceIn(0, items.lastIndex)
    val orderedIndices = items.indices.filter { it != safeActive } + safeActive

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        orderedIndices.forEach { itemIndex ->
            key(items[itemIndex]) {
                val item = items[itemIndex]
                val isFront = itemIndex == safeActive
                val lipColor = c.member(itemIndex)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(if (isFront) 22.dp else 16.dp),
                    color = c.surface,
                    border = BorderStroke(0.5.dp, if (isFront) lipColor.copy(alpha = 0.45f) else c.hairline),
                    shadowElevation = if (c.isDark) 0.dp else 2.dp,
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth()
                                .background(lipColor.copy(alpha = if (c.isDark) 0.22f else 0.12f))
                                .then(if (!isFront) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) { activeIndex = itemIndex } else Modifier)
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(8.dp).background(lipColor, CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (isFront) "重点辨析 · ${itemIndex + 1}/${items.size}" else item.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = c.ink,
                                modifier = Modifier.weight(1f),
                            )
                            if (!isFront) {
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.Outlined.ExpandMore, contentDescription = "展开辨析", tint = c.muted, modifier = Modifier.size(18.dp))
                            }
                        }
                        if (isFront) {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                FocusRow(item, openGroup)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun EmptyToday(openIntake: () -> Unit, openData: () -> Unit) {
    val c = Wp.colors
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Wp.gutter, vertical = 24.dp),
    ) {
        CupertinoPill(text = "你的易混词，在这里分清", tint = c.action)
        Text("从一组易混词开始", style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.padding(top = 14.dp))
        Text(
            "记下总是分不清的词，看清字母和含义的差别，再通过回忆和造句记牢。",
            style = MaterialTheme.typography.bodyLarge,
            color = c.muted,
            modifier = Modifier.padding(top = 10.dp),
        )
        Spacer(Modifier.height(24.dp))
        Panel(accentLipColor = c.action) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("示例词组", style = MaterialTheme.typography.labelMedium, color = c.muted)
                Spacer(Modifier.weight(1f))
                StatusPill("形似辨析", tone = Tone.Action)
            }
            Spacer(Modifier.height(12.dp))
            PrismStrip(
                listOf(
                    PrismRow("linear", "linear", listOf(IntRangeDto(4, 6)), "adj. 线性的"),
                    PrismRow("liner", "liner ", listOf(IntRangeDto(4, 5)), "n. 衬里；邮轮"),
                ),
            )
        }
        Spacer(Modifier.height(26.dp))
        Button(
            onClick = openIntake,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        ) { Text("添加我的第一组词", style = MaterialTheme.typography.titleMedium) }
        OutlinedButton(
            onClick = openData,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 50.dp),
        ) { Text("导入已有词库") }
        Text(
            "已保存的词库和练习可离线使用。AI 整理需要你配置自己的 API 预设。",
            style = MaterialTheme.typography.bodySmall,
            color = c.muted,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
internal fun FocusRow(focus: FocusItem, openGroup: (String) -> Unit) {
    val c = Wp.colors
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = focus.groupId != null) { focus.groupId?.let { openGroup(it.value) } }
            .padding(vertical = 10.dp),
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
    else -> "先复习 ${p.reviewsDue} 项，再学 ${p.newPlanned} 个新词"
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
    "done" -> Tone.Positive
    "failed" -> Tone.Negative
    "waiting_network" -> Tone.Caution
    "queued", "running" -> Tone.Action
    else -> Tone.Neutral
}

@Composable
private fun PlanMetricWell(value: String, label: String, modifier: Modifier) {
    val c = Wp.colors
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(16.dp),
        color = c.sunk.copy(alpha = if (c.isDark) 0.72f else 0.50f),
        border = BorderStroke(0.5.dp, c.hairline),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(value, style = WpType.figure.copy(fontSize = 26.sp, lineHeight = 30.sp), color = c.ink)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
    }
}

@Composable
private fun BudgetPassHeader(d: LearningDay) {
    val c = Wp.colors
    val minutes = (d.effectiveSeconds / 60).toInt()
    val frac = (d.effectiveSeconds / (d.budgetMinutes * 60f)).coerceIn(0f, 1f)
    val color = when (d.budgetState) {
        BudgetState.Reached, BudgetState.Near -> c.caution
        else -> c.action
    }
    Row(
        Modifier.fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "今天已学 $minutes 分钟，预算 ${d.budgetMinutes} 分钟" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                drawArc(color = c.sunk, startAngle = -90f, sweepAngle = 360f, useCenter = false, style = stroke)
                drawArc(color = color, startAngle = -90f, sweepAngle = 360f * frac, useCenter = false, style = stroke)
            }
            Text("${(frac * 100).toInt()}%", style = MaterialTheme.typography.labelSmall.merge(WpType.tabular), color = c.ink, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$minutes", style = WpType.figure, color = c.ink)
                Text(" / ${d.budgetMinutes} 分钟", style = MaterialTheme.typography.bodyLarge, color = c.muted, modifier = Modifier.padding(bottom = 3.dp))
            }
            Text(
                when (d.budgetState) {
                    BudgetState.Reached -> "已到达今日学习预算"
                    BudgetState.Near -> "接近今日预算"
                    else -> "今日专注进度"
                } + if (d.waitingSeconds > 0) " · 等待生成 ${d.waitingSeconds / 60} 分钟" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (d.budgetState == BudgetState.Within) c.muted else c.caution,
            )
        }
    }
}

/** Training mix as one stacked bar with an inline legend (single hue, varying lightness). */
@Composable
private fun MixBar(mix: List<MixShare>) {
    val c = Wp.colors
    val alphas = listOf(1f, 0.7f, 0.45f, 0.25f, 0.15f)
    Row(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)) {
        mix.filter { it.share > 0f }.forEachIndexed { i, m ->
            Box(Modifier.weight(m.share).fillMaxHeight().background(c.action.copy(alpha = alphas[i % alphas.size])))
        }
    }
    Spacer(Modifier.height(10.dp))
    mix.forEachIndexed { i, m ->
        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(c.action.copy(alpha = alphas[i % alphas.size])))
            Spacer(Modifier.width(8.dp))
            Text(m.label, style = MaterialTheme.typography.bodySmall, color = c.ink, modifier = Modifier.weight(1f))
            Text("${(m.share * 100 + 0.5f).toInt()}%", style = MaterialTheme.typography.bodySmall.merge(WpType.tabular), color = c.muted, fontWeight = FontWeight.Medium)
        }
    }
}
