package app.wordprism.ui.overview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
class OverviewViewModel(sdk: WordPrismSdk, private val saved: SavedStateHandle) : WpViewModel(sdk) {
    val window = saved.getStateFlow("w", 7)
    val overview = window.flatMapLatest { sdk.overview.observeOverview(OverviewWindow(it)) }.state(null)
    val latestChange = sdk.adaptation.observeChangeSets().map { it.firstOrNull() }.state(null)
    private val _evidence = MutableStateFlow<Pair<ConfusionPair, List<EvidenceItem>?>?>(null)
    val evidence = _evidence.asStateFlow()
    fun setWindow(days: Int) { saved["w"] = days }
    fun openEvidence(p: ConfusionPair) = viewModelScope.launch {
        _evidence.value = p to null
        when (val r = sdk.overview.queryConfusionEvidence(p.from, p.to, 10)) {
            is SdkResult.Ok -> _evidence.value = p to r.value
            is SdkResult.Err -> { _evidence.value = null; sayError(r.error) }
        }
    }
    fun closeEvidence() { _evidence.value = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(openAdjustments: () -> Unit, openGroup: (String) -> Unit) {
    val vm = wpViewModel { sdk, s -> OverviewViewModel(sdk, s) }
    CollectMessages(vm, LocalSnackbar.current)
    val window by vm.window.collectAsStateWithLifecycle()
    val o by vm.overview.collectAsStateWithLifecycle()
    val change by vm.latestChange.collectAsStateWithLifecycle()
    val evidence by vm.evidence.collectAsStateWithLifecycle()
    val c = Wp.colors

    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Wp.gutter)
            .padding(bottom = 32.dp),
    ) {
        Text("学习总览", style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(12.dp))

        // Cupertino Segmented Window Switcher
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = CircleShape,
            color = c.sunk,
            border = BorderStroke(0.5.dp, c.hairline),
        ) {
            Row(Modifier.fillMaxWidth().selectableGroup().padding(3.dp)) {
                listOf(7 to "近 7 天", 30 to "近 30 天", 0 to "全部").forEach { (d, label) ->
                    val selected = window == d
                    Surface(
                        modifier = Modifier.weight(1f).clip(CircleShape)
                            .selectable(selected = selected, role = Role.Tab) { vm.setWindow(d) }.heightIn(min = 48.dp),
                        shape = CircleShape,
                        color = if (selected) c.surface else Color.Transparent,
                        shadowElevation = if (selected && !c.isDark) 1.5.dp else 0.dp,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) c.ink else c.muted,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }

        val ov = o
        if (ov == null) {
            Loading()
            return@Column
        }
        Text(
            "数据截至 ${ov.asOf.dayAndClock()} · AI 分析读取同一份指标",
            style = MaterialTheme.typography.bodySmall,
            color = c.muted,
            modifier = Modifier.padding(top = 10.dp),
        )

        // ------------------------------------------------------------ AI adjustment entry
        Spacer(Modifier.height(14.dp))
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
                    Text("AI 调整", style = MaterialTheme.typography.titleMedium, color = c.ink)
                    Text(
                        change?.let { "最近：${it.summary}（${it.createdAt.dayAndClock()}）" } ?: "还没有调整记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.muted,
                        maxLines = 2,
                    )
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = c.muted)
            }
        }

        // ------------------------------------------------------------ effort
        SectionTitle("投入")
        val e = ov.effort
        Panel(accentLipColor = c.action) {
            Text(
                "活跃 ${e.activeDays} 天 · 有效学习 ${e.effectiveMinutesTotal} 分钟 · 作答 ${e.answers} 次",
                style = MaterialTheme.typography.bodyMedium,
                color = c.ink,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(14.dp))
            EffortChart(e)
            Text(
                "虚线是每日 ${e.budgetMinutes} 分钟预算。等待生成 ${e.waitingMinutesTotal} 分钟单独计算，中断 ${e.interruptedSessions} 次。",
                style = MaterialTheme.typography.bodySmall,
                color = c.muted,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        // ------------------------------------------------------------ memory
        SectionTitle("记忆表现")
        InsetGroupCard {
            ov.memory.forEachIndexed { idx, it ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { RatioRow(it) }
                if (idx < ov.memory.lastIndex) InsetDivider()
            }
        }

        // ------------------------------------------------------------ confusions
        SectionTitle("常混的词") { Text("点开看具体作答", style = MaterialTheme.typography.bodySmall, color = c.muted) }
        if (ov.confusions.isEmpty()) {
            Panel { Text("还没有记录到混淆", style = MaterialTheme.typography.bodyMedium, color = c.muted) }
        } else {
            InsetGroupCard {
                ov.confusions.forEachIndexed { idx, p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.openEvidence(p) }.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            MemberWords(listOf(p.from, p.to))
                            Spacer(Modifier.height(2.dp))
                            Text("该写 ${p.from} 时写成 ${p.to}：${p.opportunities} 次机会里 ${p.count} 次", style = MaterialTheme.typography.bodySmall, color = c.muted)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            StatusPill(
                                p.trendLabel,
                                tone = when (p.trend) {
                                    "improving" -> Tone.Positive
                                    "worse" -> Tone.Negative
                                    else -> Tone.Neutral
                                },
                            )
                            if (p.lowSample) Text("样本少", style = MaterialTheme.typography.labelSmall, color = c.caution, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    if (idx < ov.confusions.lastIndex) InsetDivider()
                }
            }
        }

        // ------------------------------------------------------------ abilities
        SectionTitle("各项能力")
        InsetGroupCard {
            ov.abilities.forEachIndexed { idx, it ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { RatioRow(it) }
                if (idx < ov.abilities.lastIndex) InsetDivider()
            }
        }

        // ------------------------------------------------------------ load
        SectionTitle("接下来 7 天")
        Panel {
            ForecastStrip(ov.load)
            Text(ov.load.forecastBasis, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 8.dp))
        }

        // ------------------------------------------------------------ library & quality
        SectionTitle("词库与质量")
        InsetGroupCard {
            Column(Modifier.padding(16.dp)) {
                KeyValue("词条", "${ov.library.words}")
                KeyValue("词组", "${ov.library.groups}")
                KeyValue("这段时间新增", "${ov.library.addedInWindow}")
                KeyValue("采纳的推荐词", "${ov.library.recommendationsAccepted}")
                KeyValue("待整理", "${ov.library.needsGrouping}")
                if (ov.contentQuality.isNotEmpty()) {
                    Hairline(Modifier.padding(vertical = 8.dp))
                    ov.contentQuality.forEach { KeyValue(it.label, "${it.count}") }
                    Text("没有被纠正不代表已经核对过。", style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        SectionTitle("API 用量")
        Panel {
            if (ov.apiUsage.isEmpty()) {
                Text("这段时间没有调用 API", style = MaterialTheme.typography.bodyMedium, color = c.muted)
            } else {
                ov.apiUsage.forEach { u ->
                    KeyValue(
                        "${u.featureLabel}${u.presetName?.let { "（$it）" } ?: ""}",
                        "${u.usage.requests} 次" + (u.usage.outputTokens?.let { "，约 ${(u.usage.inputTokens ?: 0) + it} tokens" } ?: "") + if (u.unknownUsage) "，部分用量未知" else "",
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("${ov.coverage}。统计口径 ${ov.metricsVersion}，快照 ${ov.snapshotId}。", style = MaterialTheme.typography.bodySmall, color = c.muted)
    }

    evidence?.let { (pair, items) ->
        ModalBottomSheet(onDismissRequest = vm::closeEvidence, containerColor = c.surface, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Wp.gutter).padding(bottom = 32.dp)) {
                MemberWords(listOf(pair.from, pair.to), style = WpType.word)
                Text("最近的相关作答", style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                if (items == null) Loading()
                else if (items.isEmpty()) Text("没有可显示的作答", color = c.muted)
                else items.forEach { ev ->
                    Row(Modifier.padding(vertical = 8.dp)) {
                        Text(ev.at.dayAndClock(), style = MaterialTheme.typography.bodySmall.merge(WpType.tabular), color = c.muted, modifier = Modifier.width(96.dp))
                        Column {
                            Text("${ev.activityLabel}：答了 ${ev.answered}，应为 ${ev.expected}", style = MaterialTheme.typography.bodyMedium, color = c.ink)
                            if (ev.hintUsed) Text("用过提示", style = MaterialTheme.typography.bodySmall, color = c.caution)
                        }
                    }
                    Hairline()
                }
                pair.groupId?.let { gid ->
                    Button(
                        onClick = { vm.closeEvidence(); openGroup(gid.value) },
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) { Text("打开这个词组") }
                }
            }
        }
    }
}

@Composable
private fun RatioRow(m: RatioMetric) {
    val c = Wp.colors
    val r = m.ratio
    val frac = if (r.opportunities == 0) 0f else r.successes / r.opportunities.toFloat()
    val predicted = m.basis == "predicted"
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "${m.label}，${r.successes} / ${r.opportunities}，${r.percent()}，${m.basisLabel}${if (r.lowSample) "，样本少" else ""}"
        },
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(m.label, style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f))
            Text(r.percent(), style = MaterialTheme.typography.titleMedium.merge(WpType.tabular), color = c.ink)
            Text("  ${r.successes}/${r.opportunities}", style = MaterialTheme.typography.bodySmall.merge(WpType.tabular), color = c.muted)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(c.sunk)) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(CircleShape).background(if (predicted) c.ai.copy(alpha = 0.6f) else c.action))
        }
        Row(Modifier.padding(top = 5.dp)) {
            Text(m.basisLabel + (m.note?.let { "，$it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.weight(1f))
            if (r.lowSample) Text("样本少", style = MaterialTheme.typography.labelSmall, color = c.caution)
        }
    }
}

@Composable
private fun EffortChart(e: EffortStats) {
    val c = Wp.colors
    val maxV = (e.daily.maxOfOrNull { it.effectiveMinutes } ?: 0).coerceAtLeast(e.budgetMinutes) * 1.15f
    val desc = e.daily.joinToString("，") { "${it.date.short()} ${it.effectiveMinutes} 分钟" }
    Column {
        Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = "每日有效学习时间：$desc" }) {
            val n = e.daily.size.coerceAtLeast(1)
            val slot = size.width / n
            val barW = (slot * 0.62f).coerceAtMost(28.dp.toPx())
            e.daily.forEachIndexed { i, d ->
                val h = size.height * d.effectiveMinutes / maxV
                val x = i * slot + (slot - barW) / 2
                if (d.effectiveMinutes == 0) drawRect(c.hairline, Offset(x, size.height - 2.dp.toPx()), Size(barW, 2.dp.toPx()))
                else drawRoundRect(
                    if (d.effectiveMinutes > e.budgetMinutes) c.caution else c.action,
                    Offset(x, size.height - h),
                    Size(barW, h),
                    CornerRadius(6.dp.toPx()),
                )
            }
            val y = size.height - size.height * e.budgetMinutes / maxV
            drawLine(c.muted, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            e.daily.firstOrNull()?.let { Text(it.date.short(), style = MaterialTheme.typography.labelSmall, color = c.muted) }
            Spacer(Modifier.weight(1f))
            e.daily.lastOrNull()?.let { Text("今天", style = MaterialTheme.typography.labelSmall, color = c.muted) }
        }
    }
}

@Composable
private fun ForecastStrip(load: LoadStats) {
    val c = Wp.colors
    val max = (load.forecast.maxOfOrNull { it.estimatedMinutes } ?: 1).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        load.forecast.forEach { f ->
            Column(
                Modifier.weight(1f).semantics(mergeDescendants = true) {
                    contentDescription = "${f.date.short()}，约 ${f.estimatedReviews} 项，${f.estimatedMinutes} 分钟"
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.height(56.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth(0.6f).fillMaxHeight(f.estimatedMinutes / max.toFloat()).clip(RoundedCornerShape(6.dp)).background(c.action.copy(alpha = 0.65f)))
                }
                Text("周${f.date.weekdayZh()}", style = MaterialTheme.typography.labelSmall, color = c.muted, modifier = Modifier.padding(top = 4.dp))
                Text("${f.estimatedMinutes}′", style = MaterialTheme.typography.labelSmall.merge(WpType.tabular), color = c.ink)
            }
        }
    }
}
