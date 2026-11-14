package app.wordprism.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
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
 * Proposal A Optical Prism Chamber:
 * Look-alike words stacked letter-by-letter in an elevated inset chamber using Atkinson Mono.
 * Differing columns carry subtle vertical prism light beams, bold spectral glyphs, and underline marks
 * (never colour alone) so the eye immediately resolves the orthographical delta.
 */
@Composable
fun PrismStrip(
    rows: List<PrismRow>,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 29.sp,
    showGloss: Boolean = true,
    revealed: Boolean = true,
) {
    val c = Wp.colors
    val style = WpType.prism.copy(fontSize = fontSize, lineHeight = fontSize * 1.34f, color = c.ink)
    val measurer = rememberTextMeasurer()
    val charWidth = remember(style, measurer) { measurer.measure("m", style).size.width.toFloat() }
    val diffCols = remember(rows) { rows.flatMap { r -> r.highlights.flatMap { it.start until it.endExclusive } }.toSet() }
    val bandAlpha = if (revealed) 1f else 0f
    val bandColor = c.action.copy(alpha = if (c.isDark) 0.20f else 0.11f)
    val chamberShape = RoundedCornerShape(18.dp)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = chamberShape,
        color = c.sunk.copy(alpha = if (c.isDark) 0.65f else 0.45f),
        border = BorderStroke(0.5.dp, c.hairline),
        tonalElevation = 0.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
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
                            val left = col * charWidth
                            drawRect(
                                Brush.verticalGradient(
                                    listOf(
                                        bandColor.copy(alpha = 0f),
                                        bandColor.copy(alpha = bandColor.alpha * bandAlpha),
                                        bandColor.copy(alpha = bandColor.alpha * bandAlpha * 0.85f),
                                        bandColor.copy(alpha = 0f),
                                    ),
                                ),
                                topLeft = Offset(left, 0f),
                                size = Size(charWidth, size.height),
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
                            if (hl) {
                                withStyle(
                                    SpanStyle(
                                        color = tint,
                                        fontWeight = FontWeight.Bold,
                                        textDecoration = if (revealed) TextDecoration.Underline else null,
                                    ),
                                ) { append(ch) }
                            } else {
                                append(ch)
                            }
                        }
                    }
                    if (stacked) {
                        Column(Modifier.padding(bottom = if (i < rows.lastIndex) 12.dp else 0.dp)) {
                            Text(annotated, style = style, softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()))
                            row.gloss?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.padding(bottom = if (i < rows.lastIndex) 4.dp else 0.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(annotated, style = style, softWrap = false)
                            if (showGloss && row.gloss != null) {
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    row.gloss,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = c.muted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
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
                if (i > 0) withStyle(SpanStyle(color = c.muted.copy(alpha = 0.45f), fontWeight = FontWeight.Normal)) { append("  /  ") }
                withStyle(SpanStyle(color = c.member(i))) { append(m) }
            }
        },
        style = style,
        modifier = modifier,
    )
}

// ------------------------------------------------------------------ chrome & Cupertino containers

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WpTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Column {
        TopAppBar(
            title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = Wp.colors.action)
                    }
                }
            },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Wp.colors.ground,
                titleContentColor = Wp.colors.ink,
                actionIconContentColor = Wp.colors.action,
            ),
        )
        HorizontalDivider(thickness = 0.5.dp, color = Wp.colors.hairline)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(top = 24.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = Wp.colors.muted,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.invoke()
    }
}

/** Optional detail stays one tap away; expansion never delays reading or keyboard input. */
@Composable
fun SectionDisclosure(title: String, summary: String? = null, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { expanded = !expanded }
                .heightIn(min = 48.dp)
                .padding(vertical = 8.dp)
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Wp.colors.ink, modifier = Modifier.semantics { heading() })
                if (!expanded && summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = Wp.colors.muted)
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(Wp.colors.sunk.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null,
                    tint = Wp.colors.muted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) = HorizontalDivider(modifier, thickness = 0.5.dp, color = Wp.colors.hairline)

@Composable
fun InsetDivider(modifier: Modifier = Modifier) = HorizontalDivider(modifier.padding(start = 16.dp), thickness = 0.5.dp, color = Wp.colors.hairline)

enum class Tone { Neutral, Action, Positive, Negative, Caution, Ai }

@Composable
fun toneColor(t: Tone) = when (t) {
    Tone.Neutral -> Wp.colors.muted
    Tone.Action -> Wp.colors.action
    Tone.Positive -> Wp.colors.positive
    Tone.Negative -> Wp.colors.negative
    Tone.Caution -> Wp.colors.caution
    Tone.Ai -> Wp.colors.ai
}

@Composable
fun StatusPill(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.Neutral) {
    val col = toneColor(tone)
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = col.copy(alpha = if (Wp.colors.isDark) 0.18f else 0.11f),
        border = BorderStroke(0.5.dp, col.copy(alpha = 0.35f)),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = col,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun CupertinoPill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = Wp.colors.action,
    background: Color = tint.copy(alpha = if (Wp.colors.isDark) 0.18f else 0.12f),
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.then(if (onClick != null) Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onClick).heightIn(min = 48.dp) else Modifier),
        shape = CircleShape,
        color = background,
        border = BorderStroke(0.5.dp, tint.copy(alpha = 0.28f)),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
        }
    }
}

fun OperationStatus.tone() = when (this) {
    OperationStatus.Succeeded -> Tone.Positive
    OperationStatus.Failed -> Tone.Negative
    OperationStatus.WaitingNetwork, OperationStatus.NeedsInput, OperationStatus.NeedsConfiguration, OperationStatus.RestoredPending -> Tone.Caution
    OperationStatus.Running, OperationStatus.Queued -> Tone.Action
    else -> Tone.Neutral
}

fun StudyStatus.tone() = when (this) {
    StudyStatus.Due -> Tone.Caution
    StudyStatus.Stable -> Tone.Positive
    StudyStatus.Learning -> Tone.Action
    else -> Tone.Neutral
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
        Box(Modifier.size(7.dp).background(toneColor(tone), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (tone == Tone.Neutral) c.muted else toneColor(tone),
            modifier = Modifier.weight(1f),
        )
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
                if (p != null && p.total > 0) CircularProgressIndicator(progress = { p.done / p.total.toFloat() }, Modifier.size(18.dp), strokeWidth = 2.dp, color = c.ai)
                else CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.ai)
                Spacer(Modifier.width(10.dp))
            }
            if (op.status == OperationStatus.WaitingNetwork) {
                Icon(Icons.Outlined.CloudOff, null, tint = c.caution, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    when (op.status) {
                        OperationStatus.WaitingNetwork -> "等待联网，联网后自动继续"
                        OperationStatus.Running -> op.progress?.label?.let { "进行中：$it（${op.progress!!.done}/${op.progress!!.total}）" } ?: "进行中"
                        else -> op.status.label()
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = toneColor(op.status.tone()),
                )
                op.presetName?.let {
                    Text(
                        "预设「$it」${op.presetVersion?.let { v -> " 第 $v 版" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.muted,
                    )
                }
            }
            if (onCancel != null && OperationAction.Cancel in op.actions) TextButton(onClick = onCancel) { Text("取消") }
            if (onRetry != null && OperationAction.Retry in op.actions) TextButton(onClick = onRetry) { Text("重试") }
        }
        op.error?.let { ErrorNotice(it, Modifier.padding(top = 8.dp)) }
    }
}

@Composable
fun ErrorNotice(error: SdkError, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Wp.colors.negative.copy(alpha = 0.08f),
        border = BorderStroke(0.5.dp, Wp.colors.negative.copy(alpha = 0.25f)),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = Wp.colors.negative, modifier = Modifier.size(18.dp).padding(top = 2.dp))
            Spacer(Modifier.width(8.dp))
            Text(error.message, style = MaterialTheme.typography.bodyMedium, color = Wp.colors.negative)
        }
    }
}

@Composable
fun OfflineBanner(conn: Connectivity?, modifier: Modifier = Modifier) {
    if (conn == null || conn.online) return
    val c = Wp.colors
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = Wp.gutter, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(14.dp),
        color = c.caution.copy(alpha = if (c.isDark) 0.16f else 0.10f),
        border = BorderStroke(0.5.dp, c.caution.copy(alpha = 0.35f)),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.CloudOff, null, tint = c.caution, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "离线：词库和学习照常可用" + if (conn.pendingNetworkOperations > 0) "，${conn.pendingNetworkOperations} 个 AI 任务等联网后继续" else "",
                style = MaterialTheme.typography.bodySmall,
                color = c.ink,
            )
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Panel(modifier = modifier.padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Wp.colors.ink)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Wp.colors.muted)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            Modifier.size(28.dp).semantics { contentDescription = "正在载入" },
            strokeWidth = 2.5.dp,
            color = Wp.colors.action,
        )
    }
}

/** Raised Cupertino squircle card surface with hairline border and subtle ambient elevation. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(18.dp),
    containerColor: Color = Wp.colors.surface,
    accentLipColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Wp.colors
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = if (c.isDark) 0.dp else 2.dp,
        border = BorderStroke(0.5.dp, c.hairline),
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (accentLipColor != null) {
                Box(Modifier.fillMaxWidth().height(4.dp).background(accentLipColor))
            }
            Column(Modifier.fillMaxWidth().padding(padding), content = content)
        }
    }
}

/** Apple Settings-style Inset Grouped container with crisp zero-padding items separated by inset hairlines. */
@Composable
fun InsetGroupCard(
    modifier: Modifier = Modifier,
    containerColor: Color = Wp.colors.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Wp.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = if (c.isDark) 0.dp else 1.5.dp,
        border = BorderStroke(0.5.dp, c.hairline),
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

/** iOS Spotlight-style search bar with sunken rounded field and leading search icon. */
@Composable
fun SpotlightSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
) {
    val c = Wp.colors
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(min = 46.dp),
        shape = RoundedCornerShape(16.dp),
        color = c.sunk.copy(alpha = if (c.isDark) 0.85f else 0.72f),
        border = BorderStroke(0.5.dp, c.hairline),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, null, tint = c.muted, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.ink),
                    cursorBrush = SolidColor(c.action),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
                    decorationBox = { field ->
                        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) { field() }
                    },
                )
            }
            if (query.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = "清空搜索", tint = c.ink, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Small key/value row used in settings and summaries. */
@Composable
fun KeyValue(key: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Wp.colors.ink) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
