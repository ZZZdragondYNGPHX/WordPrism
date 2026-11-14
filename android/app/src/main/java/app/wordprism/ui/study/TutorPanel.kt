package app.wordprism.ui.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wordprism.sdk.*
import app.wordprism.ui.common.*
import app.wordprism.ui.theme.Wp
import kotlinx.coroutines.launch

@Composable
internal fun TutorReviewButton(sessionId: SessionId, openApi: () -> Unit) {
    val sdk = appSdk()
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var submitting by remember { mutableStateOf(false) }
    var opened by rememberSaveable(sessionId.value) { mutableStateOf(false) }
    val messages by remember(sdk, sessionId) { sdk.study.observeTutor(sessionId) }.collectAsStateWithLifecycle(emptyList())
    val c = Wp.colors
    fun action(run: suspend () -> SdkResult<*>) {
        scope.launch {
            val result = run()
            if (result is SdkResult.Err) snackbar.showSnackbar(result.error.message)
        }
    }
    OutlinedButton(
        onClick = {
            if (!submitting) {
                submitting = true
                scope.launch {
                    try {
                        when (val result = sdk.study.reviewWithTutor(sessionId, newRequest())) {
                            is SdkResult.Ok -> opened = true
                            is SdkResult.Err -> snackbar.showSnackbar(result.error.message)
                        }
                    } finally {
                        submitting = false
                    }
                }
            }
        },
        enabled = !submitting,
        shape = CircleShape,
        border = BorderStroke(0.5.dp, c.ai.copy(alpha = 0.45f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = c.aiSoft,
            contentColor = c.ai,
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) {
        Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (submitting) "正在提交…" else "本轮助教总结")
    }
    Text(
        "点击后才调用；同一轮已有总结可直接查看。",
        style = MaterialTheme.typography.bodySmall,
        color = c.muted,
        modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
    )
    if (opened) TutorSheet(
        contextKey = "recap:${sessionId.value}",
        messages = messages.filter { it.moment == "recap" },
        canAsk = false,
        assisted = false,
        submitting = submitting,
        onAsk = { _, _ -> },
        onCancel = { id -> action { sdk.operations.cancel(id) } },
        onRetry = { id -> action { sdk.operations.retry(id, newRequest()) } },
        openApi = openApi,
        onClose = { opened = false },
    )
}

@Composable
internal fun TutorInline(message: TutorMessage?, onOpen: () -> Unit) {
    if (message == null) return
    val c = Wp.colors
    Surface(
        onClick = onOpen,
        color = c.aiSoft,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(0.5.dp, c.ai.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = c.ai, modifier = Modifier.size(16.dp))
                Text(
                    "助教的话",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.ai,
                    modifier = Modifier.padding(start = 6.dp).weight(1f),
                )
                Text("展开与追问 ›", style = MaterialTheme.typography.labelMedium, color = c.ai)
            }
            Text(
                message.message ?: when {
                    message.operation.status.isActive -> "助教正在看这次学习，可以继续下一题。"
                    else -> "这次点评尚未完成，已有反馈和学习进度已保存。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = c.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TutorSheet(
    contextKey: String,
    messages: List<TutorMessage>,
    canAsk: Boolean,
    assisted: Boolean,
    submitting: Boolean,
    onAsk: (String, () -> Unit) -> Unit,
    onCancel: (OperationId) -> Unit,
    onRetry: (OperationId) -> Unit,
    openApi: () -> Unit,
    onClose: () -> Unit,
) {
    var draft by rememberSaveable(contextKey, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val focus = LocalFocusManager.current
    val pending = messages.any { it.moment == "question" && it.operation.status.isActive }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val c = Wp.colors
    val scroll = rememberScrollState()
    var inputFocused by remember { mutableStateOf(false) }

    // New question/reply scrolls into view; keyboard changes alone do not reset the reading position.
    LaunchedEffect(messages.map { Triple(it.operation.id, it.message, it.operation.status) }) {
        scroll.scrollTo(scroll.maxValue)
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { if (!keyboardVisible) BottomSheetDefaults.DragHandle(modifier = Modifier.padding(vertical = 4.dp)) },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.95f).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = c.aiSoft, shape = RoundedCornerShape(12.dp), border = BorderStroke(0.5.dp, c.ai.copy(alpha = 0.25f))) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = c.ai, modifier = Modifier.padding(9.dp).size(18.dp))
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("随身助教", style = MaterialTheme.typography.titleMedium, color = c.ink)
                    if (!keyboardVisible) Text("围绕这次学习，慢慢讲清楚", style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "关闭助教", tint = c.muted) }
            }
            if (!keyboardVisible && assisted) {
                Text(
                    "先给提示，不急着揭晓答案。求助后的作答记为辅助练习。",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted,
                    modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
                )
            }
            Hairline(Modifier.padding(top = 8.dp))
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(top = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (messages.isEmpty()) {
                    Text("是哪一步卡住了？", style = MaterialTheme.typography.titleMedium, color = c.ink)
                    Text(
                        "可以问一个提示，或说说你自己的理解。助教会结合当前题目来讲。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.muted,
                    )
                }
                messages.reversed().forEach { item ->
                    key(item.operation.id) {
                        item.question?.let { question ->
                            Surface(
                                color = c.action,
                                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                                modifier = Modifier.align(Alignment.End).padding(start = 32.dp),
                            ) {
                                Text(
                                    question,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = c.onAction,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
                                        .semantics { contentDescription = "你：$question" },
                                )
                            }
                        }
                        Surface(
                            color = c.sunk.copy(alpha = if (c.isDark) 0.72f else 0.45f),
                            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
                            border = BorderStroke(0.5.dp, c.hairline),
                            modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("助教", style = MaterialTheme.typography.labelMedium, color = c.ai)
                                item.message?.let {
                                    Text(it, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                                    item.checkQuestion?.let { q ->
                                        Surface(
                                            color = c.surface,
                                            shape = RoundedCornerShape(14.dp),
                                            border = BorderStroke(0.5.dp, c.hairline),
                                        ) {
                                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                                Text("试着想一想", style = MaterialTheme.typography.labelMedium, color = c.ai)
                                                Text(q, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.padding(top = 4.dp))
                                            }
                                        }
                                    }
                                } ?: OperationLine(
                                    item.operation,
                                    onCancel = { onCancel(item.operation.id) },
                                    onRetry = { onRetry(item.operation.id) },
                                )
                                if (item.operation.status == OperationStatus.NeedsConfiguration) {
                                    TextButton(onClick = openApi) { Text("配置 API") }
                                }
                            }
                        }
                    }
                }
            }
            if (canAsk) {
                if (!keyboardVisible) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            (if (assisted) "给我提示" else "换个讲法") to
                                (if (assisted) "请只给我一个提示，先不要揭晓答案" else "请换一种更直观的方式讲解"),
                            "还是分不清" to "我还是容易混淆，应该注意哪个区别？",
                        ).forEach { (label, text) ->
                            SuggestionChip(
                                onClick = { draft = TextFieldValue(text, selection = TextRange(text.length)) },
                                label = { Text(label) },
                                shape = CircleShape,
                                border = BorderStroke(0.5.dp, c.hairline),
                            )
                        }
                    }
                }
                Surface(
                    color = c.sunk.copy(alpha = if (c.isDark) 0.8f else 0.55f),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(if (inputFocused) 1.5.dp else 0.5.dp, if (inputFocused) c.ai else c.hairline),
                ) {
                    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.Bottom) {
                        BasicTextField(
                            value = draft,
                            onValueChange = { if (it.text.length <= 2000) draft = it },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.ink),
                            cursorBrush = SolidColor(c.ai),
                            maxLines = if (keyboardVisible) 2 else 4,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                .onFocusChanged { inputFocused = it.isFocused }
                                .semantics { contentDescription = "问助教，或说说你的理解" },
                            decorationBox = { field ->
                                Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                                    if (draft.text.isEmpty()) {
                                        Text("问助教，或说说你的理解", style = MaterialTheme.typography.bodyMedium, color = c.muted)
                                    }
                                    field()
                                }
                            },
                        )
                        FilledIconButton(
                            onClick = {
                                val text = draft.text.trim()
                                draft = draft.copy(composition = null)
                                focus.clearFocus()
                                onAsk(text) { if (draft.text.trim() == text) draft = TextFieldValue("") }
                            },
                            enabled = !submitting && !pending && draft.text.isNotBlank(),
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = c.ai,
                                contentColor = c.onAction,
                            ),
                            modifier = Modifier.size(44.dp).semantics {
                                contentDescription = if (submitting) "正在提交…" else if (pending) "助教正在回复…" else "发送给助教"
                            },
                        ) {
                            if (submitting || pending) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.muted)
                            } else {
                                Icon(Icons.Outlined.ArrowUpward, null, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
