package app.wordprism.ui.study

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.wordprism.sdk.*
import app.wordprism.ui.common.WpViewModel
import app.wordprism.ui.common.clearRequestId
import app.wordprism.ui.common.newRequest
import app.wordprism.ui.common.requestIdFor
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class StudyViewModel(sdk: WordPrismSdk, private val saved: SavedStateHandle, private val sessionId: SessionId) : WpViewModel(sdk) {
    val session = sdk.study.observeSession(sessionId).state(null)
    val day = sdk.study.observeLearningDay().state(null)

    private val _busy = MutableStateFlow(false)
    /** True while a submit/advance is in flight; the UI disables the action to stop double taps. */
    val busy = _busy.asStateFlow()

    private val _hint = MutableStateFlow<Pair<ActivityId, Hint>?>(null)
    val hint = _hint.asStateFlow()

    /** Live status of the async writing-feedback operation for the current activity, if any. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val pendingReview: StateFlow<Operation?> = session.map { it?.feedback?.pendingOperationId }.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else sdk.operations.observe(id) }.state(null)

    private var draftJob: Job? = null
    private var presenceJob: Job? = null

    fun presence(visible: Boolean) {
        presenceJob?.cancel()
        presenceJob = viewModelScope.launch {
            if (!visible) { sdk.study.reportPresence(sessionId, visible = false); return@launch }
            while (true) {
                if (session.value?.status == SessionStatus.Completed) break
                sdk.study.reportPresence(sessionId, visible = true, waiting = pendingReview.value?.status?.isActive == true)
                delay(15_000)
            }
        }
    }

    fun submit(activity: ActivityId, answer: Answer) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            // Same request id until the backend confirms: a retry after timeout/process death is deduplicated.
            val r = sdk.study.submitAnswer(sessionId, activity, answer, saved.requestIdFor("answer:${activity.value}"))
            when (r) {
                is SdkResult.Ok -> saved.clearRequestId("answer:${activity.value}")
                is SdkResult.Err -> sayError(r.error)
            }
            _busy.value = false
            // Pure acknowledgements (contrast card, familiarity check) have nothing to read: move on directly.
            if (r is SdkResult.Ok && (answer is Answer.Acknowledge || answer is Answer.Familiarity)) advance()
        }
    }

    fun advance() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val key = "advance:${session.value?.done}"
            when (val r = sdk.study.advance(sessionId, saved.requestIdFor(key))) {
                is SdkResult.Ok -> saved.clearRequestId(key)
                is SdkResult.Err -> sayError(r.error)
            }
            _hint.value = null
            _busy.value = false
        }
    }

    /** Debounced draft save; drafts live in the backend so they survive process death. */
    @OptIn(FlowPreview::class)
    fun draftChanged(activity: ActivityId, text: String) {
        draftJob?.cancel()
        draftJob = viewModelScope.launch { delay(600); sdk.study.saveDraft(sessionId, activity, text) }
    }

    fun flushDraft(activity: ActivityId, text: String) = viewModelScope.launch {
        draftJob?.cancel(); sdk.study.saveDraft(sessionId, activity, text)
    }

    fun requestHint(activity: ActivityId) = viewModelScope.launch {
        when (val r = sdk.study.requestHint(sessionId, activity)) {
            is SdkResult.Ok -> _hint.value = activity to r.value
            is SdkResult.Err -> sayError(r.error)
        }
    }

    fun markHard(answerId: String, hard: Boolean) = viewModelScope.launch {
        sdk.study.markHard(sessionId, answerId, hard).let { if (it is SdkResult.Err) sayError(it.error) }
    }

    fun revise(activity: ActivityId) = viewModelScope.launch {
        sdk.study.reviseAnswer(sessionId, activity).let { if (it is SdkResult.Err) sayError(it.error) }
    }

    fun retryReview(op: OperationId) = viewModelScope.launch {
        sdk.operations.retry(op, newRequest()).let { if (it is SdkResult.Err) sayError(it.error) }
    }

    fun skip(activity: ActivityId, reason: String?) = viewModelScope.launch {
        if (_busy.value) return@launch
        _busy.value = true
        when (val r = sdk.study.skip(sessionId, activity, reason)) {
            is SdkResult.Ok -> say("已跳过这题，不会记为遗忘")
            is SdkResult.Err -> sayError(r.error)
        }
        _hint.value = null
        _busy.value = false
    }

    fun dispute(activity: ActivityId, reason: String) = viewModelScope.launch {
        when (val r = sdk.study.dispute(sessionId, activity, reason)) {
            is SdkResult.Ok -> say("已标记。这题的结果暂不计入统计和复习安排")
            is SdkResult.Err -> sayError(r.error)
        }
    }

    fun budgetChoice(continueLearning: Boolean, onStop: () -> Unit) = viewModelScope.launch {
        when (val result = sdk.study.answerBudgetPrompt(sessionId, continueLearning)) {
            is SdkResult.Ok -> if (!continueLearning) onStop()
            is SdkResult.Err -> sayError(result.error)
        }
    }

    fun pause(then: () -> Unit) = viewModelScope.launch {
        when (val result = sdk.study.pause(sessionId)) {
            is SdkResult.Ok -> then()
            is SdkResult.Err -> sayError(result.error)
        }
    }

    fun endDay(then: () -> Unit) = viewModelScope.launch {
        when (val r = sdk.study.finishLearningDay(newRequest())) {
            is SdkResult.Ok -> then()
            is SdkResult.Err -> sayError(r.error)
        }
    }
}
