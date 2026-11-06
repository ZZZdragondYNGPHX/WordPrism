package app.wordprism.ui.today

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wordprism.sdk.*
import app.wordprism.ui.common.*
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
    Box {
        TextButton(onClick = { menu = true }, enabled = !saving,
            colors = ButtonDefaults.textButtonColors(containerColor = if (managed) Wp.colors.actionSoft else Wp.colors.surface),
            contentPadding = PaddingValues(start = 14.dp, end = 8.dp), shape = RoundedCornerShape(14.dp)) {
            Text(if (saving) "正在切换…" else if (managed) "AI 托管模式" else "普通模式")
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = "切换学习模式")
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            listOf(LearningMode.Standard to "普通模式", LearningMode.AiManaged to "AI 托管模式").forEach { (mode, label) ->
                DropdownMenuItem(text = { Text(label + if (state.mode == mode) " · 当前" else "") }, onClick = {
                    menu = false
                    if (mode != state.mode && !saving) {
                        saving = true
                        scope.launch {
                            try {
                                when (val result = sdk.adaptation.setLearningMode(mode, newRequest())) {
                                    is SdkResult.Ok -> onChanged()
                                    is SdkResult.Err -> snackbar.showSnackbar(result.error.message)
                                }
                            } finally { saving = false }
                        }
                    }
                })
            }
        }
    }
}

@Composable
fun ManagedTodayScreen(
    managed: ManagedLearning, openStudy: (String) -> Unit, openIntake: () -> Unit,
    openPreset: (String?) -> Unit, openTasks: () -> Unit, openAdjustments: () -> Unit,
) {
    val vm = wpViewModel { sdk, saved -> TodayViewModel(sdk, saved) }
    CollectMessages(vm, LocalSnackbar.current)
    val day by vm.day.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val starting by vm.starting.collectAsStateWithLifecycle()
    val sdk = appSdk()
    val adaptation by remember(sdk) { sdk.adaptation.observeSettings() }.collectAsStateWithLifecycle(null)
    val tutoring by remember(sdk) { sdk.study.observeTutor() }.collectAsStateWithLifecycle(emptyList())
    val d = day
    val p = plan
    if (d == null || p == null) { Loading(); return }
    val hasContent = p.libraryWords > 0
    val canStudy = d.openSessionId != null || d.completedItems < d.plannedItems
    val budgetReached = d.budgetState == BudgetState.Reached
    val c = Wp.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Wp.gutter).padding(bottom = 32.dp)) {
        Spacer(Modifier.height(16.dp))
        Text(d.date.long(), style = MaterialTheme.typography.bodyMedium, color = c.muted)
        Spacer(Modifier.height(8.dp))
        Text(if (hasContent) "今天的背诵" else "先添加一组易混词", style = MaterialTheme.typography.headlineSmall, color = c.ink)
        Spacer(Modifier.height(8.dp))
        Text(if (hasContent) "你负责回忆和作答，AI 负责准备与安排。" else "写下分不清的单词，AI 会整理区别、准备讲解和练习。",
            style = MaterialTheme.typography.bodyMedium, color = c.muted)

        managed.configurationError?.let { error ->
            Spacer(Modifier.height(20.dp))
            Surface(color = c.actionSoft, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Key, null, tint = c.action, modifier = Modifier.size(18.dp))
                        Text("连接 AI，交给助教准备", style = MaterialTheme.typography.titleSmall,
                            color = c.ink, modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(error.message, style = MaterialTheme.typography.bodySmall, color = c.muted,
                        modifier = Modifier.padding(top = 6.dp))
                    TextButton(onClick = { openPreset(managed.presetId) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text("配置 API")
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, modifier = Modifier.padding(start = 8.dp).size(16.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        if (hasContent) {
            Surface(color = c.surface, shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (d.openSessionId != null) "接着上次的进度" else "今天的学习安排",
                            style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.AutoStories, null, tint = c.action, modifier = Modifier.size(20.dp))
                    }
                    p.focus.firstOrNull()?.takeIf { canStudy && !budgetReached }?.let { item ->
                        Text(item.label, style = WpType.word, color = c.ink, modifier = Modifier.padding(top = 16.dp))
                        Text(item.reason, style = MaterialTheme.typography.bodySmall, color = c.muted,
                            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    Text(when {
                        budgetReached -> "今天已经学了 ${d.effectiveSeconds / 60} 分钟，先休息一下"
                        canStudy -> "${p.newPlanned} 个新词 · ${p.reviewsDue} 项复习 · 约 ${p.estimatedMinutes} 分钟"
                        else -> "今天的安排已完成，下次复习会自动安排"
                    }, style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(top = 16.dp, bottom = 20.dp))
                    Button(onClick = { vm.start(openStudy) }, enabled = !starting && canStudy && !budgetReached,
                        shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(when {
                            starting -> "正在准备…"
                            budgetReached -> "今天先到这里"
                            d.openSessionId != null -> "继续背诵"
                            canStudy -> "开始背诵"
                            else -> "今天已完成"
                        }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        if (starting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(if (canStudy && !budgetReached) Icons.AutoMirrored.Outlined.ArrowForward else Icons.Outlined.Check, null, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = openIntake).padding(vertical = 12.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(c.actionSoft, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, null, tint = c.action, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("添加新单词组", style = MaterialTheme.typography.titleSmall, color = c.ink)
                    Text("把分不清的词交给助教", style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 2.dp))
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = c.muted, modifier = Modifier.size(20.dp))
            }
        } else {
            Button(onClick = openIntake, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("添加第一组单词") }
        }

        Spacer(Modifier.height(20.dp))
        Hairline()
        tutoring.firstOrNull { it.moment == "recap" && it.message != null }?.let { recap ->
            Spacer(Modifier.height(20.dp))
            Text("助教的课后小结", style = MaterialTheme.typography.titleMedium, color = c.ink)
            Text(recap.message.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = c.action, modifier = Modifier.size(18.dp))
            Text(if (managed.pendingTasks > 0) "助教正在准备 · ${managed.pendingTasks} 项" else "助教在这里",
                style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.padding(start = 8.dp))
        }
        Text(managed.summary, style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(top = 6.dp))
        if (managed.attentionTasks > 0) {
            TextButton(onClick = openTasks) { Text("查看 ${managed.attentionTasks} 项未完成内容") }
        } else {
            TextButton(onClick = openTasks) { Text("查看处理记录") }
        }
        adaptation?.lastRun?.summary?.let { summary ->
            Spacer(Modifier.height(12.dp))
            Text("最近的学习调整", style = MaterialTheme.typography.titleMedium, color = c.ink)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(top = 6.dp))
            TextButton(onClick = openAdjustments) { Text("查看调整与撤销") }
        }
    }
}
