package app.wordprism.ui.settings

import app.wordprism.sdk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface ModelCatalogState {
    data object Idle : ModelCatalogState
    data object Loading : ModelCatalogState
    data object Cancelled : ModelCatalogState
    data class Ready(val models: List<ApiModel>) : ModelCatalogState
    data class Failed(val error: SdkError) : ModelCatalogState
}

/** Screen-local requests; call from the UI dispatcher. Never stores credentials in saved state. */
internal class ModelCatalogController(
    private val scope: CoroutineScope,
    private val load: suspend (ModelCatalogDraft, CharArray?) -> SdkResult<List<ApiModel>>,
) {
    private val _state = MutableStateFlow<ModelCatalogState>(ModelCatalogState.Idle)
    val state = _state.asStateFlow()
    private var generation = 0L
    private var job: Job? = null

    fun fetch(draft: ModelCatalogDraft, secret: CharArray?) {
        if (_state.value == ModelCatalogState.Loading || !scope.isActive) {
            secret?.fill('\u0000')
            return
        }
        val token = ++generation
        _state.value = ModelCatalogState.Loading
        job = scope.launch {
            try {
                val result = load(draft, secret)
                if (token == generation && isActive) _state.value = when (result) {
                    is SdkResult.Ok -> ModelCatalogState.Ready(result.value)
                    is SdkResult.Err -> ModelCatalogState.Failed(result.error)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (token == generation && isActive) _state.value = ModelCatalogState.Failed(SdkError(ErrorCode.Internal, "拉取模型失败，请重试", true))
            } finally { secret?.fill('\u0000') }
        }.also { task -> task.invokeOnCompletion { secret?.fill('\u0000') } }
    }

    fun reset() {
        generation++
        job?.cancel(); job = null
        _state.value = ModelCatalogState.Idle
    }

    fun cancel() {
        reset()
        _state.value = ModelCatalogState.Cancelled
    }
}
