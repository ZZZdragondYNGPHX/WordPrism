package app.wordprism.ui.settings

import app.wordprism.sdk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ModelCatalogControllerTest {
    private val draft = ModelCatalogDraft("https://example.invalid/v1", "openai_responses", 30)
    private val models = listOf(ApiModel("model-a"), ApiModel("model-b"))
    private fun secret() = UUID.randomUUID().toString().toCharArray()

    @Test fun repeatedClicksMakeOneRequestAndEraseBothBuffers() = runTest {
        val finish = CompletableDeferred<Unit>(); var calls = 0
        val controller = ModelCatalogController(this) { _, _ -> calls++; finish.await(); SdkResult.Ok(models) }
        assertEquals(ModelCatalogState.Idle, controller.state.value)
        val first = secret(); val duplicate = secret()
        controller.fetch(draft, first); controller.fetch(draft, duplicate)
        assertEquals(ModelCatalogState.Loading, controller.state.value); assertTrue(duplicate.all { it == '\u0000' })
        runCurrent(); assertEquals(1, calls)
        finish.complete(Unit); runCurrent()
        assertEquals(ModelCatalogState.Ready(models), controller.state.value); assertTrue(first.all { it == '\u0000' })
    }

    @Test fun cancelPreventsEvenAnUncooperativeLateSuccess() = runTest {
        val finish = CompletableDeferred<Unit>()
        val controller = ModelCatalogController(this) { _, _ -> withContext(NonCancellable) { finish.await(); SdkResult.Ok(models) } }
        val chars = secret(); controller.fetch(draft, chars); runCurrent()
        controller.cancel(); assertEquals(ModelCatalogState.Cancelled, controller.state.value)
        finish.complete(Unit); runCurrent()
        assertEquals(ModelCatalogState.Cancelled, controller.state.value); assertTrue(chars.all { it == '\u0000' })
    }

    @Test fun editingTheConnectionRejectsOldFailureAfterANewerRequestSucceeds() = runTest {
        val firstDone = CompletableDeferred<Unit>(); var calls = 0
        val controller = ModelCatalogController(this) { _, _ ->
            if (++calls == 1) withContext(NonCancellable) {
                firstDone.await(); SdkResult.Err(SdkError(ErrorCode.CredentialMissing, "Old failure", false))
            } else SdkResult.Ok(models)
        }
        val stale = secret(); controller.fetch(draft, stale); runCurrent()
        controller.reset(); assertEquals(ModelCatalogState.Idle, controller.state.value)
        controller.fetch(draft.copy(baseUrl = "https://new.invalid/v1"), secret()); runCurrent()
        assertEquals(ModelCatalogState.Ready(models), controller.state.value)
        firstDone.complete(Unit); runCurrent()
        assertEquals(ModelCatalogState.Ready(models), controller.state.value); assertEquals(2, calls); assertTrue(stale.all { it == '\u0000' })
    }

    @Test fun leavingBeforeDispatchErasesSecretWithoutMakingARequest() = runTest {
        var calls = 0
        val controller = ModelCatalogController(this) { _, _ -> calls++; SdkResult.Ok(models) }
        val chars = secret(); controller.fetch(draft, chars); controller.reset(); runCurrent()
        assertEquals(0, calls); assertEquals(ModelCatalogState.Idle, controller.state.value); assertTrue(chars.all { it == '\u0000' })
    }

    @Test fun cancelledScreenScopeDoesNotKeepASecretOrStartWork() = runTest {
        val screen = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val controller = ModelCatalogController(screen) { _, _ -> error("Cancelled scope must not call provider") }
        screen.cancel(); val chars = secret(); controller.fetch(draft, chars); runCurrent()
        assertEquals(ModelCatalogState.Idle, controller.state.value); assertTrue(chars.all { it == '\u0000' })
    }

    @Test fun failureWaitsForExplicitRetryAndEmptyResultIsNotAnError() = runTest {
        var calls = 0
        val error = SdkError(ErrorCode.Offline, "Offline", true)
        val controller = ModelCatalogController(this) { _, _ -> if (++calls == 1) SdkResult.Err(error) else SdkResult.Ok(emptyList()) }
        controller.fetch(draft, secret()); advanceUntilIdle()
        assertEquals(ModelCatalogState.Failed(error), controller.state.value); assertEquals(1, calls)
        controller.fetch(draft, secret()); advanceUntilIdle()
        assertEquals(ModelCatalogState.Ready(emptyList()), controller.state.value); assertEquals(2, calls)
    }
}
