package app.wordprism.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.wordprism.WordPrismApp
import app.wordprism.sdk.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

fun newRequest() = ClientRequestId(UUID.randomUUID().toString())

/** A one-shot message for the snackbar, optionally undoable through the shared operation system. */
data class UiMessage(val text: String, val undo: OperationId? = null)

abstract class WpViewModel(protected val sdk: WordPrismSdk) : ViewModel() {
    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    protected fun say(text: String, undo: OperationId? = null) { _messages.trySend(UiMessage(text, undo)) }
    protected fun sayError(e: SdkError) = say(e.message)

    protected fun <T> Flow<T>.state(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    fun undo(op: OperationId) = viewModelScope.launch {
        when (val r = sdk.operations.undo(op, newRequest())) {
            is SdkResult.Ok -> say(listOf(r.value.summary).plus(r.value.preserved).joinToString("。"))
            is SdkResult.Err -> sayError(r.error)
        }
    }
}

/**
 * Persist the client request id for an in-flight user intent so a resubmission after
 * process death or a double tap reuses it (backend idempotency).
 */
fun SavedStateHandle.requestIdFor(key: String): ClientRequestId =
    get<String>("req:$key")?.let(::ClientRequestId) ?: newRequest().also { set("req:$key", it.value) }
fun SavedStateHandle.clearRequestId(key: String) { remove<String>("req:$key") }

@Composable
inline fun <reified VM : ViewModel> wpViewModel(key: String? = null, crossinline create: (WordPrismSdk, SavedStateHandle) -> VM): VM {
    val app = LocalContext.current.applicationContext as WordPrismApp
    return viewModel(key = key, factory = viewModelFactory { initializer { create(app.sdk, createSavedStateHandle()) } })
}

@Composable
fun appSdk(): WordPrismSdk = (LocalContext.current.applicationContext as WordPrismApp).sdk

val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("no snackbar host") }

/** Shows a VM's messages; an undoable message gets a 撤销 action routed back to the VM. */
@Composable
fun CollectMessages(vm: WpViewModel, host: SnackbarHostState) {
    LaunchedEffect(vm) {
        vm.messages.collect { m ->
            val r = host.showSnackbar(m.text, actionLabel = if (m.undo != null) "撤销" else null, withDismissAction = m.undo == null,
                duration = if (m.undo != null) SnackbarDuration.Long else SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed && m.undo != null) vm.undo(m.undo)
        }
    }
}

// ------------------------------------------------------------------------------ labels
// Backend values are open-ended; every mapping has a readable fallback.

fun OperationStatus.label() = when (this) {
    OperationStatus.Queued -> "排队中"
    OperationStatus.WaitingNetwork -> "等待联网"
    OperationStatus.Running -> "进行中"
    OperationStatus.NeedsInput -> "需要你选择"
    OperationStatus.Succeeded -> "已完成"
    OperationStatus.Failed -> "失败"
    OperationStatus.Cancelled -> "已取消"
    OperationStatus.Undone -> "已撤销"
    OperationStatus.Merged -> "已合并到较新的任务"
    else -> "状态：$value"
}

fun RelationKind.label() = when (this) {
    RelationKind.FormSimilar -> "形似"
    RelationKind.Synonym -> "近义"
    RelationKind.WordFamily -> "同根"
    RelationKind.SoundAlike -> "音似"
    RelationKind.Personal -> "个人易混"
    else -> value
}

fun StudyStatus.label() = when (this) {
    StudyStatus.NotStarted -> "未开始"
    StudyStatus.Learning -> "学习中"
    StudyStatus.Due -> "待复习"
    StudyStatus.Stable -> "较稳定"
    StudyStatus.Paused -> "已暂停"
    else -> value
}

fun ApiReadiness.label() = when (this) {
    ApiReadiness.Ready -> "可用"
    ApiReadiness.NeedsPreset -> "待配置预设"
    ApiReadiness.NeedsCredential -> "缺少密钥"
    ApiReadiness.QueuedOffline -> "离线，会排队"
    ApiReadiness.QuotaExhausted -> "额度已用完"
    ApiReadiness.NotRequired -> "无需 API"
    else -> value
}

private val zone: ZoneId get() = ZoneId.systemDefault()
fun EpochMillis.clock(): String = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(this).atZone(zone))
fun EpochMillis.dayAndClock(): String = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA).format(Instant.ofEpochMilli(this).atZone(zone))
fun LocalDay.short(): String = LocalDate.parse(iso).let { "${it.monthValue}/${it.dayOfMonth}" }
fun LocalDay.weekdayZh(): String = LocalDate.parse(iso).let { listOf("一", "二", "三", "四", "五", "六", "日")[it.dayOfWeek.value - 1] }
fun LocalDay.long(): String = LocalDate.parse(iso).let { "${it.monthValue}月${it.dayOfMonth}日 周${weekdayZh()}" }

fun Ratio.percent(): String = if (opportunities == 0) "—" else "${(successes * 100.0 / opportunities + 0.5).toInt()}%"
