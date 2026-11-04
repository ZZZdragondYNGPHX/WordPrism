package app.wordprism.ui.tasks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.wordprism.sdk.*
import app.wordprism.ui.common.*
import app.wordprism.ui.theme.Wp
import kotlinx.coroutines.launch

class TasksViewModel(sdk: WordPrismSdk, saved: SavedStateHandle) : WpViewModel(sdk) {
    val ops = sdk.operations.observeRecent().state(null)
    fun cancel(id: OperationId) = viewModelScope.launch { sdk.operations.cancel(id).let { if (it is SdkResult.Err) sayError(it.error) } }
    fun retry(id: OperationId) = viewModelScope.launch { sdk.operations.retry(id, newRequest()).let { if (it is SdkResult.Err) sayError(it.error) } }
}

@Composable
fun TasksScreen(onBack: () -> Unit) {
    val vm = wpViewModel { sdk, s -> TasksViewModel(sdk, s) }
    CollectMessages(vm, LocalSnackbar.current)
    val ops by vm.ops.collectAsStateWithLifecycle()
    val c = Wp.colors
    Column(Modifier.fillMaxSize()) {
        WpTopBar("任务记录", onBack = onBack)
        val list = ops
        when {
            list == null -> Loading()
            list.isEmpty() -> EmptyState("还没有任务", "AI 添加、生成反馈、分析和导入导出会显示在这里。", Modifier.padding(horizontal = Wp.gutter))
            else -> LazyColumn(contentPadding = PaddingValues(start = Wp.gutter, end = Wp.gutter, bottom = 32.dp), modifier = Modifier.navigationBarsPadding()) {
                items(list, key = { it.id.value }) { op ->
                    Column(Modifier.padding(vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(op.title, style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f), maxLines = 2)
                            StatusPill(op.status.label(), tone = op.status.tone())
                        }
                        Text(op.createdAt.dayAndClock() + (op.presetName?.let { "，预设「$it」" } ?: "") + (op.usage?.let { "，${it.requests} 次请求" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = c.muted)
                        op.resultSummary?.takeIf { op.status == OperationStatus.Succeeded }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.padding(top = 4.dp)) }
                        if (op.status.isActive || op.status == OperationStatus.Failed) OperationLine(op, Modifier.padding(top = 6.dp), onCancel = { vm.cancel(op.id) }, onRetry = { vm.retry(op.id) })
                        if (OperationAction.Undo in op.actions) TextButton(onClick = { vm.undo(op.id) }, contentPadding = PaddingValues(0.dp)) { Text("撤销") }
                    }
                    Hairline()
                }
            }
        }
    }
}
