package app.wordprism.di

import android.content.Context
import androidx.core.net.toUri
import app.wordprism.codex.CodexSdk
import app.wordprism.codex.DocumentAccess
import app.wordprism.sdk.WordPrismSdk
import dev.wordprism.android.AndroidWordPrism
import dev.wordprism.contract.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Application-scoped initialization without blocking the main thread on Room or native loading. */
object SdkProvider {
    fun create(context: Context): WordPrismSdk {
        val app = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val online = MutableStateFlow(false)
        val opening = OpeningCore(app, scope, online)
        return CodexSdk(opening, scope, ContentResolverDocuments(app), online, opening::retry)
    }
}

/** SAF-backed document access: no filesystem paths are assumed. */
private class ContentResolverDocuments(private val context: Context) : DocumentAccess {
    override fun openInput(uri: String): InputStream =
        context.contentResolver.openInputStream(uri.toUri()) ?: throw IOException("无法打开文件")
    override fun openOutput(uri: String): OutputStream =
        context.contentResolver.openOutputStream(uri.toUri(), "wt") ?: throw IOException("无法写入文件")
}

private class OpeningCore(private val context: Context, private val scope: CoroutineScope, private val online: MutableStateFlow<Boolean>) : WordPrism {
    private val mutex = Mutex()
    @Volatile private var opening = start()
    private fun start() = scope.async {
        try {
            val core = AndroidWordPrism.open(context)
            scope.launch { core.online.collect { online.value = it } }
            Result.success(core)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure<AndroidWordPrism>(e) }
        catch (e: LinkageError) { Result.failure<AndroidWordPrism>(e) }
    }
    suspend fun retry() = mutex.withLock { if (opening.await().isFailure) opening = start() }
    private val unavailable get() = Reply(0, fault = Fault(ErrorCode.STORAGE, "本机核心启动失败，请重试；现有数据仍保留", true))
    private suspend fun call(action: suspend (AndroidWordPrism) -> Reply): Reply = opening.await().getOrNull()?.let { action(it) } ?: unavailable
    override suspend fun execute(request: Request) = call { it.execute(request) }
    override suspend fun query(query: Query) = call { it.query(query) }
    override fun observe(): Flow<Long> = flow { opening.await().getOrNull()?.let { emitAll(it.observe()) } ?: emit(-1) }
    override suspend fun previewPackage(input: InputStream) = call { it.previewPackage(input) }
    override suspend fun exportPackage(purpose: PackagePurpose, output: OutputStream, wordIds: Set<Id>, identity: ExportIdentity?) = call { it.exportPackage(purpose, output, wordIds, identity) }
    override suspend fun setCredential(presetId: Id, secret: CharArray): Reply = try { call { it.setCredential(presetId, secret) } } finally { secret.fill('\u0000') }
    override suspend fun deleteCredential(presetId: Id) = call { it.deleteCredential(presetId) }
}
