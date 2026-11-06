package app.wordprism.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wordprism.sdk.*
import app.wordprism.ui.theme.Wp
import app.wordprism.ui.theme.WpType

// ------------------------------------------------------------------ prism strip

data class PrismRow(val lemma: String, val aligned: String?, val highlights: List<IntRangeDto>, val gloss: String?)

fun GroupMember.prismRow() = PrismRow(lemma, alignedForm, formHighlights, senses.firstOrNull()?.let { "${it.partOfSpeech} ${it.glossZh}" })

/**
 * The app's signature element: look-alike words stacked letter-by-letter in a monospaced face.
 * Shared letters stay ink; letters that differ take the member's spectral colour AND an underline
 * (never colour alone). Columns where members differ get a faint vertical band, so the eye
 * lands on the difference before reading the words.
 */
@Composable
fun PrismStrip(rows: List<PrismRow>, modifier: Modifier = Modifier, fontSize: TextUnit = 30.sp, showGloss: Boolean = true, revealed: Boolean = true) {
    val c = Wp.colors
    val style = WpType.prism.copy(fontSize = fontSize, lineHeight = fontSize * 1.32f, color = c.ink)
    val measurer = rememberTextMeasurer()
    val charWidth = remember(style) { measurer.measure("m", style).size.width.toFloat() }
    val diffCols = remember(rows) { rows.flatMap { r -> r.highlights.flatMap { it.start until it.endExclusive } }.toSet() }
    val bandAlpha = if (revealed) 1f else 0f
    val bandColor = c.action.copy(alpha = if (c.isDark) 0.16f else 0.08f)

    BoxWithConstraints(modifier) {
    val density = LocalDensity.current
    val letterWidth = with(density) { (rows.maxOfOrNull { (it.aligned ?: it.lemma).length } ?: 0).times(charWidth).toDp() }
    val stacked = showGloss && maxWidth < letterWidth + 150.dp
    Column(
        Modifier.fillMaxWidth()
            .then(if (!showGloss) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = rows.joinToString("；") { r ->
                    val aligned = r.aligned ?: r.lemma
                    val diff = r.highlights.joinToString("、") { aligned.substring(it.start.coerceAtMost(aligned.length), it.endExclusive.coerceAtMost(aligned.length)).trim() }
                    "${r.lemma.toList().joinToString(" ")}${if (diff.isNotEmpty()) "，不同的字母：$diff" else ""}${r.gloss?.let { "，$it" } ?: ""}"
                }
            }
            .drawBehind {
                if (!stacked) diffCols.forEach { col ->
                    drawRect(
                        Brush.verticalGradient(listOf(bandColor.copy(alpha = 0f), bandColor.copy(alpha = bandColor.alpha * bandAlpha), bandColor.copy(alpha = 0f))),
                        topLeft = Offset(col * charWidth, 0f), size = Size(charWidth, size.height),
                    )
                }
            },
    ) {
        rows.forEachIndexed { i, row ->
            val tint = if (revealed) c.member(i) else c.ink
            val text = row.aligned ?: row.lemma
            val annotated = buildAnnotatedString {
                text.forEachIndexed { idx, ch ->
                    val hl = row.highlights.any { idx >= it.start && idx < it.endExclusive }
                    if (hl) withStyle(SpanStyle(color = tint, fontWeight = FontWeight.Bold, textDecoration = if (revealed) TextDecoration.Underline else null)) { append(ch) }
                    else append(ch)
                }
            }
            if (stacked) Column(Modifier.padding(bottom = 12.dp)) {
                Text(annotated, style = style, softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()))
                row.gloss?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.muted) }
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                Text(annotated, style = style, softWrap = false, modifier = Modifier)
                if (showGloss && row.gloss != null) {
                    Spacer(Modifier.width(14.dp))
                    Text(row.gloss, style = MaterialTheme.typography.bodyMedium, color = c.muted, maxLines = 2, modifier = Modifier.weight(1f))
                }
            }
        }
    }
    }
}

/** A member lemma in its identity colour (used in lists so the same word keeps the same hue everywhere). */
@Composable
fun MemberWords(members: List<String>, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = WpType.wordSmall) {
    val c = Wp.colors
    Text(
        buildAnnotatedString {
            members.forEachIndexed { i, m ->
                if (i > 0) withStyle(SpanStyle(color = c.hairline, fontWeight = FontWeight.Normal)) { append("  /  ") }
                withStyle(SpanStyle(color = c.member(i))) { append(m) }
            }
        },
        style = style, modifier = modifier,
    )
}

// ------------------------------------------------------------------ chrome

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WpTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1) },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回") } },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Wp.colors.ground),
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 28.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = Wp.colors.ink, modifier = Modifier.weight(1f).semantics { heading() })
        trailing?.invoke()
    }
}

/** Optional detail stays one tap away; expansion never delays reading or keyboard input. */
@Composable
fun SectionDisclosure(title: String, summary: String? = null, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }
            .heightIn(min = 56.dp).padding(vertical = 12.dp)
            .semantics { stateDescription = if (expanded) "已展开" else "已收起" }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Wp.colors.ink, modifier = Modifier.semantics { heading() })
                if (!expanded && summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = Wp.colors.muted)
            }
            Spacer(Modifier.width(12.dp))
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = Wp.colors.muted)
        }
        if (expanded) content()
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) = HorizontalDivider(modifier, thickness = 1.dp, color = Wp.colors.hairline)

enum class Tone { Neutral, Action, Positive, Negative, Caution }

@Composable
fun toneColor(t: Tone) = when (t) {
    Tone.Neutral -> Wp.colors.muted; Tone.Action -> Wp.colors.action; Tone.Positive -> Wp.colors.positive
    Tone.Negative -> Wp.colors.negative; Tone.Caution -> Wp.colors.caution
}

@Composable
fun StatusPill(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.Neutral) {
    val col = toneColor(tone)
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = col, maxLines = 1,
        modifier = modifier.border(1.dp, col.copy(alpha = 0.45f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

fun OperationStatus.tone() = when (this) {
    OperationStatus.Succeeded -> Tone.Positive
    OperationStatus.Failed -> Tone.Negative
    OperationStatus.WaitingNetwork, OperationStatus.NeedsInput, OperationStatus.NeedsConfiguration, OperationStatus.RestoredPending -> Tone.Caution
    OperationStatus.Running, OperationStatus.Queued -> Tone.Action
    else -> Tone.Neutral
}

fun StudyStatus.tone() = when (this) {
    StudyStatus.Due -> Tone.Caution; StudyStatus.Stable -> Tone.Positive; StudyStatus.Learning -> Tone.Action; else -> Tone.Neutral
}

/** Tells the user, before acting, whether this will call a model API and with which preset. */
@Composable
fun ApiDisclosure(req: ApiRequirement?, modifier: Modifier = Modifier, onConfigure: (() -> Unit)? = null) {
    if (req == null) return
    val c = Wp.colors
    val (text, tone) = when (req.readiness) {
        ApiReadiness.NotRequired -> "不会调用 API" to Tone.Neutral
        ApiReadiness.Ready -> "会调用 API：使用预设「${req.presetName}」" to Tone.Neutral
        ApiReadiness.QueuedOffline -> "离线中：提交后排队，联网后用「${req.presetName}」执行" to Tone.Caution
        else -> (req.note ?: req.readiness.label()) to Tone.Negative
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(toneColor(tone), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (tone == Tone.Neutral) c.muted else toneColor(tone), modifier = Modifier.weight(1f))
        if (onConfigure != null && req.readiness in setOf(ApiReadiness.NeedsPreset, ApiReadiness.NeedsCredential)) {
            TextButton(onClick = onConfigure) { Text("去配置") }
        }
    }
}

/** Live, screen-reader-announced line for a backend operation; shows only states the backend reports. */
@Composable
fun OperationLine(op: Operation, modifier: Modifier = Modifier, onCancel: (() -> Unit)? = null, onRetry: (() -> Unit)? = null) {
    val c = Wp.colors
    Column(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (op.status == OperationStatus.Running || op.status == OperationStatus.Queued) {
                val p = op.progress
                if (p != null && p.total > 0) CircularProgressIndicator(progress = { p.done / p.total.toFloat() }, Modifier.size(18.dp), strokeWidth = 2.dp)
                else CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            if (op.status == OperationStatus.WaitingNetwork) { Icon(Icons.Outlined.CloudOff, null, tint = c.caution, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)) }
            Column(Modifier.weight(1f)) {
                Text(
                    when (op.status) {
                        OperationStatus.WaitingNetwork -> "等待联网，联网后自动继续"
                        OperationStatus.Running -> op.progress?.label?.let { "进行中：$it（${op.progress!!.done}/${op.progress!!.total}）" } ?: "进行中"
                        else -> op.status.label()
                    },
                    style = MaterialTheme.typography.bodyMedium, color = toneColor(op.status.tone()),
                )
                op.presetName?.let { Text("预设「$it」${op.presetVersion?.let { v -> " 第 $v 版" } ?: ""}", style = MaterialTheme.typography.bodySmall, color = c.muted) }
            }
            if (onCancel != null && OperationAction.Cancel in op.actions) TextButton(onClick = onCancel) { Text("取消") }
            if (onRetry != null && OperationAction.Retry in op.actions) TextButton(onClick = onRetry) { Text("重试") }
        }
        op.error?.let { ErrorNotice(it, Modifier.padding(top = 8.dp)) }
    }
}

@Composable
fun ErrorNotice(error: SdkError, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.ErrorOutline, null, tint = Wp.colors.negative, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Text(error.message, style = MaterialTheme.typography.bodyMedium, color = Wp.colors.negative)
    }
}

@Composable
fun OfflineBanner(conn: Connectivity?, modifier: Modifier = Modifier) {
    if (conn == null || conn.online) return
    val c = Wp.colors
    Row(
        modifier.fillMaxWidth().background(c.sunk).padding(horizontal = Wp.gutter, vertical = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.CloudOff, null, tint = c.caution, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            "离线：词库和学习照常可用" + if (conn.pendingNetworkOperations > 0) "，${conn.pendingNetworkOperations} 个 AI 任务等联网后继续" else "",
            style = MaterialTheme.typography.bodySmall, color = c.ink,
        )
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.Start) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Wp.colors.ink)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Wp.colors.muted)
        if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp).semantics { contentDescription = "正在载入" }, strokeWidth = 2.dp)
    }
}

/** Raised surface for primary content blocks. Radius follows hierarchy: content 14, sheets 28. */
@Composable
fun Panel(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(18.dp), content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Wp.colors.surface, MaterialTheme.shapes.medium)
            .border(1.dp, Wp.colors.hairline, MaterialTheme.shapes.medium).padding(padding),
        content = content,
    )
}

/** Small key/value row used in settings and summaries. */
@Composable
fun KeyValue(key: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Wp.colors.ink) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = Wp.colors.muted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium.merge(WpType.tabular), color = valueColor)
    }
}

@Composable
fun SourceNote(source: ContentSource?, modifier: Modifier = Modifier) {
    if (source == null) return
    val text = when {
        source.isAi -> "AI 生成" + (source.presetName?.let { "，预设「$it」" } ?: "") + "，可以修改"
        source.kind == "user" -> "你编辑过"
        source.kind == "imported" -> "从数据包导入"
        source.kind == "dictionary" -> "词典资料"
        else -> "来源：${source.kind}"
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = Wp.colors.muted, modifier = modifier)
}
