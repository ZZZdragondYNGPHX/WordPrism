package app.wordprism.ui.tasks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.wordprism.sdk.*
import app.wordprism.ui.common.*
import app.wordprism.ui.study.AiReviewBlock
import app.wordprism.ui.theme.Wp
import app.wordprism.ui.theme.WpType
import kotlinx.coroutines.launch

class TasksViewModel(sdk: WordPrismSdk, saved: SavedStateHandle) : WpViewModel(sdk) {
    val ops = sdk.operations.observeRecent().state(null)
    val feedback = mutableStateMapOf<String, Feedback>()
    val loadingFeedback = mutableStateMapOf<String, Boolean>()
    fun loadFeedback(id: OperationId) {
        if (feedback.containsKey(id.value) || loadingFeedback[id.value] == true) return
        loadingFeedback[id.value] = true
        viewModelScope.launch {
            when (val result = sdk.operations.getWritingFeedback(id)) {
                is SdkResult.Ok -> feedback[id.value] = result.value
                is SdkResult.Err -> sayError(result.error)
            }
            loadingFeedback.remove(id.value)
        }
    }
    fun cancel(id: OperationId) = viewModelScope.launch { sdk.operations.cancel(id).let { if (it is SdkResult.Err) sayError(it.error) } }
    fun retry(id: OperationId) = viewModelScope.launch { sdk.operations.retry(id, newRequest()).let { if (it is SdkResult.Err) sayError(it.error) } }
}

@Composable
fun TasksScreen(onBack: () -> Unit, feedbackOnly: Boolean = false, openApi: () -> Unit) {
    val vm = wpViewModel { sdk, s -> TasksViewModel(sdk, s) }
    CollectMessages(vm, LocalSnackbar.current)
    val ops by vm.ops.collectAsStateWithLifecycle()
    val c = Wp.colors
    var filter by rememberSaveable { mutableStateOf(if (feedbackOnly) "feedback" else "all") }
    Column(Modifier.fillMaxSize()) {
        WpTopBar("任务记录", onBack = onBack)
        FlowRow(
            Modifier.padding(horizontal = Wp.gutter, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("all" to "全部", "active" to "待处理", "feedback" to "表达反馈").forEach { (id, label) ->
                FilterChip(selected = filter == id, onClick = { filter = id }, shape = CircleShape, label = { Text(label) })
            }
        }
        val list = ops?.filter {
            when (filter) {
                "active" -> it.status.isActive
                "feedback" -> it.feature == FeatureId.WritingFeedback
                else -> true
            }
        }
        when {
            list == null -> Loading()
            list.isEmpty() -> Box(Modifier.padding(horizontal = Wp.gutter)) {
                EmptyState(
                    if (filter == "feedback") "还没有表达反馈" else if (filter == "active") "没有正在进行的任务" else "还没有任务",
                    if (filter == "feedback") "学习时提交的句子会保存在这里，完成后可以查看分项建议与优化句。" else "AI 添加、生成反馈、分析和导入导出会显示在这里。",
                )
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = Wp.gutter, end = Wp.gutter, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.navigationBarsPadding(),
            ) {
                items(list, key = { it.id.value }) { op ->
                    Panel(padding = PaddingValues(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(op.title, style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f), maxLines = 2)
                            Spacer(Modifier.width(8.dp))
                            StatusPill(op.status.label(), tone = op.status.tone())
                        }
                        Text(
                            op.createdAt.dayAndClock() + (op.presetName?.let { "，预设「$it」" } ?: "") + (op.usage?.let { "，${it.requests} 次请求" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.muted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        op.resultSummary?.takeIf { op.status == OperationStatus.Succeeded }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.padding(top = 6.dp))
                        }
                        if (op.feature == FeatureId.WritingFeedback && op.status == OperationStatus.Succeeded) {
                            var expanded by rememberSaveable(op.id.value) { mutableStateOf(feedbackOnly && op.id == list.firstOrNull()?.id) }
                            LaunchedEffect(expanded, op.id) { if (expanded) vm.loadFeedback(op.id) }
                            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                                Text(if (expanded) "收起反馈" else "查看反馈")
                            }
                            if (expanded) {
                                val result = vm.feedback[op.id.value]
                                if (result != null) {
                                    Text("你的句子 · 版本 ${result.answerVersion}", style = MaterialTheme.typography.labelLarge, color = c.muted)
                                    result.answerText?.let { Text(it, style = WpType.english, color = c.ink, modifier = Modifier.padding(top = 4.dp)) }
                                    result.aiReview?.let { AiReviewBlock(it) }
                                } else if (vm.loadingFeedback[op.id.value] == true) {
                                    Text("正在读取反馈…", style = MaterialTheme.typography.bodySmall, color = c.muted)
                                } else {
                                    TextButton(onClick = { vm.loadFeedback(op.id) }) { Text("重试读取反馈") }
                                }
                            }
                        }
                        if (op.status.isActive || op.status == OperationStatus.Failed) {
                            OperationLine(op, Modifier.padding(top = 8.dp), onCancel = { vm.cancel(op.id) }, onRetry = { vm.retry(op.id) })
                        }
                        if (op.status == OperationStatus.NeedsConfiguration || op.error?.code in setOf(ErrorCode.CredentialMissing, ErrorCode.PresetMissing)) {
                            TextButton(onClick = openApi, contentPadding = PaddingValues(0.dp)) { Text("去配置 API 预设") }
                        }
                        if (OperationAction.Undo in op.actions) {
                            TextButton(onClick = { vm.undo(op.id) }, contentPadding = PaddingValues(0.dp)) { Text("撤销") }
                        }
                    }
                }
            }
        }
    }
}
