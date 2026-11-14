package app.wordprism.ui.today

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wordprism.sdk.*
import app.wordprism.ui.common.*
import app.wordprism.ui.study.TutorReviewButton
import app.wordprism.ui.theme.Wp
import app.wordprism.ui.theme.WpType
import kotlinx.coroutines.launch

@Composable
fun LearningModeControl(state: ManagedLearning, onChanged: () -> Unit) {
    val sdk = appSdk()
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var menu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val managed = state.mode == LearningMode.AiManaged
    val c = Wp.colors
    Box {
        Surface(
            shape = CircleShape,
            color = if (managed) c.aiSoft else c.surface,
            border = BorderStroke(0.5.dp, if (managed) c.ai.copy(alpha = 0.45f) else c.hairline),
            shadowElevation = if (c.isDark) 0.dp else 1.5.dp,
            modifier = Modifier.clip(CircleShape).clickable(enabled = !saving, role = androidx.compose.ui.semantics.Role.Button) { menu = true }.heightIn(min = 48.dp),
        ) {
            Row(
                Modifier.padding(start = 13.dp, end = 9.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(7.dp).background(if (managed) c.ai else c.action, CircleShape),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    if (saving) "正在切换…" else if (managed) "AI 托管模式" else "普通模式",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (managed) c.ai else c.ink,
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    Icons.Outlined.ArrowDropDown,
                    contentDescription = "切换学习模式",
                    tint = if (managed) c.ai else c.muted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            listOf(LearningMode.Standard to "普通模式", LearningMode.AiManaged to "AI 托管模式").forEach { (mode, label) ->
                DropdownMenuItem(
                    text = { Text(label + if (state.mode == mode) " · 当前" else "") },
                    onClick = {
                        menu = false
                        if (mode != state.mode && !saving) {
                            saving = true
                            scope.launch {
                                try {
                                    when (val result = sdk.adaptation.setLearningMode(mode, newRequest())) {
                                        is SdkResult.Ok -> onChanged()
                                        is SdkResult.Err -> snackbar.showSnackbar(result.error.message)
                                    }
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun ManagedTodayScreen(
    managed: ManagedLearning,
    openStudy: (String) -> Unit,
    openIntake: () -> Unit,
    openPreset: (String?) -> Unit,
    openTasks: () -> Unit,
    openAdjustments: () -> Unit,
) {
    val vm = wpViewModel { sdk, saved -> TodayViewModel(sdk, saved) }
    CollectMessages(vm, LocalSnackbar.current)
    val day by vm.day.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val starting by vm.starting.collectAsStateWithLifecycle()
    val sdk = appSdk()
    val adaptation by remember(sdk) { sdk.adaptation.observeSettings() }.collectAsStateWithLifecycle(null)
    val tutoring by remember(sdk) { sdk.study.observeTutor() }.collectAsStateWithLifecycle(emptyList())
    val reviewSession by remember(sdk) { sdk.study.observeTutorReviewSession() }.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val d = day
    val p = plan
    if (d == null || p == null) {
        Loading()
        return
    }
    val hasContent = p.libraryWords > 0
    val canStudy = d.openSessionId != null || d.completedItems < d.plannedItems
    val budgetReached = d.budgetState == BudgetState.Reached
    val c = Wp.colors

    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Wp.gutter)
            .padding(bottom = 32.dp),
    ) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.date.long(), style = MaterialTheme.typography.titleSmall, color = c.muted, modifier = Modifier.weight(1f))
            CupertinoPill(text = "AI 助教协同", icon = Icons.Outlined.AutoAwesome, tint = c.ai)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (hasContent) "今天的背诵" else "先添加一组易混词",
            style = MaterialTheme.typography.headlineSmall,
            color = c.ink,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (hasContent) "你负责回忆和作答，AI 负责准备、辨析与进度调度。" else "写下分不清的单词，AI 会整理区别、准备讲解和练习。",
            style = MaterialTheme.typography.bodyMedium,
            color = c.muted,
        )

        managed.configurationError?.let { error ->
            Spacer(Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = c.aiSoft,
                border = BorderStroke(0.5.dp, c.ai.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Key, null, tint = c.ai, modifier = Modifier.size(18.dp))
                        Text(
                            "连接 AI，交给助教准备",
                            style = MaterialTheme.typography.titleSmall,
                            color = c.ink,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Text(
                        error.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.muted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    TextButton(
                        onClick = { openPreset(managed.presetId) },
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    ) {
                        Text("配置 API", color = c.ai)
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowForward,
                            null,
                            tint = c.ai,
                            modifier = Modifier.padding(start = 6.dp).size(16.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        if (hasContent) {
            Panel(
                padding = PaddingValues(20.dp),
                accentLipColor = c.ai,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (d.openSessionId != null) "接着上次的进度" else "今天的学习安排",
                        style = MaterialTheme.typography.labelLarge,
                        color = c.muted,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Outlined.AutoStories, null, tint = c.ai, modifier = Modifier.size(20.dp))
                }

                p.focus.firstOrNull()?.takeIf { canStudy && !budgetReached }?.let { item ->
                    Spacer(Modifier.height(14.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = c.sunk.copy(alpha = if (c.isDark) 0.7f else 0.45f),
                        border = BorderStroke(0.5.dp, c.hairline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            val parts = item.label.split(Regex("\\s*(?:→|/)\\s*")).filter { it.isNotBlank() }
                            MemberWords(parts, style = WpType.word.copy(fontSize = 24.sp, lineHeight = 30.sp))
                            Spacer(Modifier.height(4.dp))
                            Text(
                                item.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = c.muted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                if (canStudy && !budgetReached) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ManagedStatWell("${p.newPlanned}", "新词", Modifier.weight(1f))
                        ManagedStatWell("${p.reviewsDue}", "复习", Modifier.weight(1f))
                        ManagedStatWell("${p.estimatedMinutes}′", "预计时长", Modifier.weight(1f))
                    }
                } else {
                    Text(
                        when {
                            budgetReached -> "今天已经学了 ${d.effectiveSeconds / 60} 分钟，先休息一下"
                            else -> "今天的安排已完成，下次复习会自动安排"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.muted,
                    )
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { vm.start(openStudy) },
                    enabled = !starting && canStudy && !budgetReached,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = c.action),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                ) {
                    Text(
                        when {
                            starting -> "正在准备…"
                            budgetReached -> "今天先到这里"
                            d.openSessionId != null -> "继续背诵"
                            canStudy -> "开始背诵"
                            else -> "今天已完成"
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (starting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.onAction)
                    } else {
                        Icon(
                            if (canStudy && !budgetReached) Icons.AutoMirrored.Outlined.ArrowForward else Icons.Outlined.Check,
                            null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Panel(
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = openIntake),
                padding = PaddingValues(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(42.dp).background(c.aiSoft, RoundedCornerShape(13.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Add, null, tint = c.ai, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("添加新单词组", style = MaterialTheme.typography.titleSmall, color = c.ink)
                        Text("把分不清的词交给助教", style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 2.dp))
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = c.muted, modifier = Modifier.size(20.dp))
                }
            }
        } else {
            Button(
                onClick = openIntake,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
            ) { Text("添加第一组单词", style = MaterialTheme.typography.titleMedium) }
        }

        reviewSession?.let { session ->
            Spacer(Modifier.height(16.dp))
            TutorReviewButton(session) { openPreset(managed.presetId) }
        }

        d.dailyAnalysis.operationId?.let { operationId ->
            val operation by remember(sdk, operationId) { sdk.operations.observe(operationId) }.collectAsStateWithLifecycle(null)
            Spacer(Modifier.height(16.dp))
            Panel {
                Text("每日学习分析", style = MaterialTheme.typography.titleSmall, color = c.ink)
                operation?.let { op ->
                    OperationLine(
                        op,
                        Modifier.padding(top = 8.dp),
                        onRetry = {
                            scope.launch {
                                val result = sdk.operations.retry(op.id, newRequest())
                                if (result is SdkResult.Err) snackbar.showSnackbar(result.error.message)
                            }
                        },
                        onCancel = {
                            scope.launch {
                                val result = sdk.operations.cancel(op.id)
                                if (result is SdkResult.Err) snackbar.showSnackbar(result.error.message)
                            }
                        },
                    )
                    if (op.error != null) {
                        TextButton(onClick = { openPreset(op.presetId ?: managed.presetId) }, contentPadding = PaddingValues(0.dp)) {
                            Text("检查 API 配置")
                        }
                    }
                }
            }
        }

        tutoring.firstOrNull { it.moment == "recap" && it.message != null }?.let { recap ->
            Spacer(Modifier.height(14.dp))
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = c.ai, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("助教的课后小结", style = MaterialTheme.typography.titleSmall, color = c.ink)
                }
                Text(recap.message.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(top = 8.dp))
            }
        }

        Spacer(Modifier.height(14.dp))
        InsetGroupCard {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = c.ai, modifier = Modifier.size(18.dp))
                    Text(
                        if (managed.pendingTasks > 0) "助教正在准备 · ${managed.pendingTasks} 项" else "助教在这里",
                        style = MaterialTheme.typography.titleSmall,
                        color = c.ink,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(managed.summary, style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(top = 6.dp))
                TextButton(onClick = openTasks, contentPadding = PaddingValues(0.dp)) {
                    Text(if (managed.attentionTasks > 0) "查看 ${managed.attentionTasks} 项未完成内容" else "查看处理记录")
                }
            }
            adaptation?.lastRun?.summary?.let { summary ->
                InsetDivider()
                Column(Modifier.padding(16.dp)) {
                    Text("最近的学习调整", style = MaterialTheme.typography.titleSmall, color = c.ink)
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(top = 6.dp))
                    TextButton(onClick = openAdjustments, contentPadding = PaddingValues(0.dp)) { Text("查看调整与撤销") }
                }
            }
        }
    }
}

@Composable
private fun ManagedStatWell(value: String, label: String, modifier: Modifier = Modifier) {
    val c = Wp.colors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = c.sunk.copy(alpha = if (c.isDark) 0.72f else 0.52f),
        border = BorderStroke(0.5.dp, c.hairline),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge.merge(WpType.tabular), color = c.ink, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = c.muted)
        }
    }
}
