package app.wordprism.codex

import app.wordprism.sdk.*
import dev.wordprism.contract.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import app.wordprism.sdk.ErrorCode as UiErr
import dev.wordprism.contract.ErrorCode as CErr
import dev.wordprism.contract.Operation as COp
import dev.wordprism.contract.OperationStatus as CStatus

/** Minimal in-memory stand-in for Codex's bus; only what these adapter tests touch. */
private class FakeCore : WordPrism {
    val revision = MutableStateFlow(0L)
    val presets = mutableListOf<PresetView>()
    val ops = mutableMapOf<String, COp>()
    var lastSecret: CharArray? = null
    var lastSecretCopy: String? = null
    var readFault: Fault? = null
    override suspend fun execute(request: Request): Reply = when (val c = request.command) {
        is Command.SavePreset -> {
            if (c.preset.baseUrl.isBlank()) Reply(revision.value, fault = Fault(CErr.VALIDATION, "服务地址不能为空"))
            else { presets.removeAll { it.preset.id == c.preset.id }; presets += PresetView(c.preset.copy(version = c.expectedVersion + 1), false); bump(); Reply(revision.value, View.Ok(c.preset.id)) }
        }
        is Command.CancelOperation -> ops.getValue(c.id.value).let { ops[c.id.value] = it.copy(status = CStatus.CANCELLED); bump(); Reply(revision.value, View.OperationResult(ops.getValue(c.id.value))) }
        else -> Reply(revision.value, fault = Fault(CErr.VALIDATION, "unsupported in fake"))
    }
    override suspend fun query(query: Query): Reply = readFault?.let { Reply(revision.value, fault = it) } ?: when (query) {
        is Query.Protocols -> Reply(revision.value, View.Protocols(ApiProtocols.supported))
        is Query.Presets -> Reply(revision.value, View.Presets(presets.toList(), emptyList()))
        is Query.GetOperation -> ops[query.id.value]?.let { Reply(revision.value, View.OperationResult(it)) } ?: Reply(revision.value, fault = Fault(CErr.NOT_FOUND, "无"))
        is Query.Operations -> Reply(revision.value, View.Operations(ops.values.toList()))
        else -> Reply(revision.value, fault = Fault(CErr.NOT_FOUND, "无"))
    }
    override fun observe(): Flow<Long> = revision
    override suspend fun previewPackage(input: InputStream) = Reply(0, View.Ok())
    override suspend fun exportPackage(purpose: dev.wordprism.contract.PackagePurpose, output: OutputStream, wordIds: Set<Id>, identity: ExportIdentity?) = Reply(0, View.Ok())
    override suspend fun setCredential(presetId: Id, secret: CharArray): Reply {
        lastSecret = secret; lastSecretCopy = String(secret)
        presets.replaceAll { if (it.preset.id == presetId) it.copy(hasCredential = true) else it }; bump(); return Reply(revision.value, View.Ok(presetId))
    }
    override suspend fun deleteCredential(presetId: Id) = Reply(revision.value, View.Ok(presetId))
    fun bump() { revision.value += 1 }
}

class CodexAdapterTest {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)

    @Test fun optionalStudyAndIntakeReadsDoNotMisreportStorageFailuresAsMissingEntities() = runBlocking {
        val readers: List<(CodexBridge) -> Flow<Any?>> = listOf(
            { bridge -> CodexStudy(bridge).observeSession(SessionId("missing")) },
            { bridge -> CodexIntake(bridge).observe(OperationId("missing")) },
        )
        for (read in readers) {
            val core = FakeCore().apply { readFault = Fault(CErr.STORAGE, "read failed", true) }
            val bridge = CodexBridge(core, scope)
            val pending = async { withTimeout(3_000) { read(bridge).first() } }
            assertEquals(UiErr("storage"), withTimeout(3_000) { bridge.errors.filterNotNull().first() }.code)
            assertFalse(pending.isCompleted)
            core.readFault = null; bridge.refresh()
            assertNull(pending.await())
        }
    }

    @Test fun readFailureIsVisibleAndRetryResumesTheSameSubscription() = runBlocking {
        val core = FakeCore().apply { readFault = Fault(CErr.STORAGE, "read failed", true) }
        val bridge = CodexBridge(core, scope)
        val pending = async { withTimeout(3_000) { bridge.live(Query.Presets) { it as? View.Presets }.filterNotNull().first() } }
        assertEquals(UiErr("storage"), withTimeout(3_000) { bridge.errors.filterNotNull().first() }.code)
        core.readFault = null; bridge.refresh()
        assertTrue(pending.await().presets.isEmpty())
        assertNull(bridge.errors.value)
    }

    @Test fun faultsMapToUiErrors() {
        val e = Fault(CErr.PROVIDER_RATE_LIMIT, "限流", retryable = true).toSdk()
        assertEquals(UiErr.ProviderRateLimited, e.code); assertTrue(e.retryable)
        assertEquals(UiErr("storage"), Fault(CErr.STORAGE, "x").toSdk().code) // unmapped codes stay readable
    }

    @Test fun operationMappingDerivesActions() {
        val op = COp(Id("o1"), "intake", CStatus.SUCCEEDED, 1, 2, undoable = true, summary = "已创建")
        val ui = op.toSdk()
        assertEquals(OperationStatus.Succeeded, ui.status)
        assertEquals(setOf(OperationAction.Undo), ui.actions)
        assertEquals(OperationStatus.Undone, op.copy(undoneBy = Id("u")).toSdk().status)
        assertEquals("needs_configuration", op.copy(status = CStatus.NEEDS_CONFIGURATION).toSdk().status.value)
    }

    @Test fun observeRequeriesOnRevision() = runBlocking {
        val core = FakeCore().apply { ops["o1"] = COp(Id("o1"), "analysis", CStatus.RUNNING, 1, 1) }
        val api = CodexOperations(CodexBridge(core, scope))
        assertEquals(OperationStatus.Running, withTimeout(3_000) { api.observe(OperationId("o1")).filterNotNull().first() }.status)
        api.cancel(OperationId("o1"))
        assertEquals(OperationStatus.Cancelled, withTimeout(3_000) { api.observe(OperationId("o1")).filterNotNull().first { it.status == OperationStatus.Cancelled } }.status)
    }

    @Test fun secretGoesThroughSetCredentialAndIsWiped() = runBlocking {
        val core = FakeCore()
        val presets = CodexPresets(CodexBridge(core, scope))
        val secretText = java.util.UUID.randomUUID().toString()
        val r = presets.savePreset(PresetDraft(name = "日常", adapterId = "openai_chat_completions", baseUrl = "https://api.example.com/v1",
            model = "m", maxOutputTokens = 1000, timeoutSeconds = 30, credential = secretText), ClientRequestId("r1"))
        assertTrue(r is SdkResult.Ok)
        assertEquals(secretText, core.lastSecretCopy)
        assertTrue("caller buffer wiped", core.lastSecret!!.all { it == '\u0000' })
        assertTrue((r as SdkResult.Ok).value.credential.present)

        val bad = presets.savePreset(PresetDraft(name = "x", adapterId = "openai_chat_completions", baseUrl = "", model = "m", maxOutputTokens = null, timeoutSeconds = 30), ClientRequestId("r2"))
        assertEquals(UiErr.Validation, (bad as SdkResult.Err).error.code)
    }
}
