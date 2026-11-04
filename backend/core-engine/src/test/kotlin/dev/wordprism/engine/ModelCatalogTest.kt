package dev.wordprism.engine

import dev.wordprism.contract.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class ModelCatalogTest {
    private val draft = ModelCatalogRequest("https://example.invalid/v1", ApiProtocols.OPENAI_RESPONSES)
    private val saved = Ref(Id("test-preset"), 1)

    @Test fun `unsaved draft uses an ephemeral secret without changing database usage or credentials`() = runBlocking {
        val h = Harness(); val before = h.store.read(); val chars = UUID.randomUUID().toString().toCharArray()
        val raw = String(chars)
        h.provider.modelResponse = { assertEquals(raw, String(h.provider.modelSecret!!)); listOf(ApiModel("provider-model", "provider")) }
        val reply = h.core.listModels(draft.copy(credentialPreset = Ref(Id("not-saved"), 0)), chars)
        assertEquals(View.Models(listOf(ApiModel("provider-model", "provider"))), reply.value)
        assertNull(reply.fault); assertEquals(before, h.store.read()); assertTrue(chars.all { it == '\u0000' })
        assertFalse(Contract.json.encodeToString(h.store.read()).contains(raw))
        h.core.recover(); assertEquals(1, h.provider.modelRequests.size); assertTrue(h.store.read().jobs.isEmpty())
    }

    @Test fun `saved credential accepts canonical same root across installed protocols and remains stored`() = runBlocking {
        val h = Harness(); h.configure(); val before = h.store.read()
        val reply = h.core.listModels(draft.copy(baseUrl = "https://EXAMPLE.invalid:443/v1/", credentialPreset = saved))
        assertEquals(View.Models(emptyList()), reply.value); assertNull(reply.fault)
        assertTrue(h.provider.modelSecret!!.all { it == '\u0000' }); assertEquals(before, h.store.read())
        assertTrue(h.core.hasCredential(before, saved.id)); assertEquals(1, h.provider.modelRequests.size)
    }

    @Test fun `missing outdated or moved connections never borrow a saved credential`() = runBlocking {
        val h = Harness(); h.configure()
        val cases = listOf(
            draft to ErrorCode.CREDENTIAL_MISSING,
            draft.copy(credentialPreset = Ref(saved.id, 0)) to ErrorCode.VERSION_CONFLICT,
            draft.copy(baseUrl = "https://other.invalid/v1", credentialPreset = saved) to ErrorCode.CREDENTIAL_MISSING,
            draft.copy(baseUrl = "https://example.invalid/other", credentialPreset = saved) to ErrorCode.CREDENTIAL_MISSING,
            draft.copy(contractVersion = 999) to ErrorCode.UNSUPPORTED_VERSION,
        )
        for ((request, code) in cases) assertEquals(code, h.core.listModels(request).fault?.code)
        h.core.deleteCredential(saved.id)
        assertEquals(ErrorCode.CREDENTIAL_MISSING, h.core.listModels(draft.copy(credentialPreset = saved)).fault?.code)
        assertTrue(h.provider.modelRequests.isEmpty())
        for (chars in listOf(charArrayOf(), "bad\nheader".toCharArray())) {
            assertEquals(ErrorCode.VALIDATION, h.core.listModels(draft, chars).fault?.code)
            assertTrue(chars.all { it == '\u0000' })
        }
    }

    @Test fun `deleting replacing or editing the saved credential source invalidates late catalogs`() = runBlocking {
        for (change in listOf<suspend (Harness) -> Unit>(
            { it.core.deleteCredential(saved.id) },
            { it.core.setCredential(saved.id, UUID.randomUUID().toString().toCharArray()) },
            { h -> val p = h.store.read().presets.getValue(saved.id); h.command(Command.SavePreset(p.copy(name = "Renamed"), p.version)) },
            { it.command(Command.DeletePreset(saved)) },
        )) {
            val h = Harness(); h.configure(); val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            h.provider.modelResponse = { started.complete(Unit); finish.await(); listOf(ApiModel("late-model")) }
            val pending = async { h.core.listModels(draft.copy(credentialPreset = saved)) }
            withTimeout(5_000) { started.await() }; change(h); val after = h.store.read(); finish.complete(Unit)
            assertEquals(ErrorCode.VERSION_CONFLICT, withTimeout(5_000) { pending.await() }.fault?.code)
            assertEquals(after, h.store.read()); assertTrue(h.provider.modelSecret!!.all { it == '\u0000' })
        }
    }

    @Test fun `cancellation rejects even a provider that finishes after cancellation`() = runBlocking {
        val h = Harness(); val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        h.provider.modelResponse = { withContext(NonCancellable) { started.complete(Unit); finish.await(); listOf(ApiModel("late-model")) } }
        val chars = UUID.randomUUID().toString().toCharArray(); val before = h.store.read()
        val pending = async { h.core.listModels(draft, chars) }
        withTimeout(5_000) { started.await() }; pending.cancel(); finish.complete(Unit)
        withTimeout(5_000) { pending.join() }
        assertTrue(pending.isCancelled); assertTrue(chars.all { it == '\u0000' }); assertEquals(before, h.store.read())
    }

    @Test fun `provider faults keep network meaning and all validation exits erase secret buffers`() = runBlocking {
        val h = Harness()
        h.provider.modelResponse = { throw ProviderException(Fault(ErrorCode.PROVIDER_AUTH, "Check credential")) }
        val chars = UUID.randomUUID().toString().toCharArray()
        assertEquals(ErrorCode.PROVIDER_AUTH, h.core.listModels(draft, chars).fault?.code)
        assertTrue(chars.all { it == '\u0000' })
        val invalid = UUID.randomUUID().toString().toCharArray()
        assertEquals(ErrorCode.VALIDATION, h.core.listModels(draft.copy(baseUrl = "http://example.invalid"), invalid).fault?.code)
        assertTrue(invalid.all { it == '\u0000' })
        val bridgeSecret = UUID.randomUUID().toString().toCharArray()
        val reply = Contract.json.decodeFromString<Reply>(JsonBridge(h.core).listModels("{}", bridgeSecret))
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, reply.fault?.code); assertTrue(bridgeSecret.all { it == '\u0000' })
    }
}
