package dev.wordprism.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import androidx.work.*
import dev.wordprism.contract.*
import dev.wordprism.engine.CoreEngine
import dev.wordprism.memory.NativeMemoryKernel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Application-scoped instance. Hosts can use Kotlin directly or wrap it in JsonBridge. */
class AndroidWordPrism private constructor(private val context: Context, internal val core: CoreEngine) : WordPrism by core {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private fun connected() = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    private val connection = MutableStateFlow(connected())
    val online: StateFlow<Boolean> = connection.asStateFlow()
    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update()
            override fun onLost(network: Network) = update()
            private fun update() { connection.value = connected(); scope.launch { schedulePending() } }
        })
    }
    companion object {
        private val mutex = Mutex()
        @Volatile private var singleton: AndroidWordPrism? = null
        suspend fun open(context: Context): AndroidWordPrism = mutex.withLock {
            singleton ?: withContext(Dispatchers.IO) {
                val application = context.applicationContext
                val engine = CoreEngine(RoomStateStore(WordPrismDatabase.open(application)), KeystoreCredentials(application), NativeMemoryKernel())
                engine.store.transaction { db ->
                    if (db.revision == 0L && db.days.isEmpty()) db.settings = db.settings.copy(zoneId = java.time.ZoneId.systemDefault().id)
                }
                val recovery = engine.recover(); check(recovery.fault == null) { recovery.fault!!.message }
                AndroidWordPrism(application, engine).also { singleton = it; it.schedulePending() }
            }
        }
    }
    override suspend fun execute(request: Request): Reply = core.execute(request).also { if (it.fault == null) schedulePending() }
    override suspend fun setCredential(presetId: Id, secret: CharArray): Reply = core.setCredential(presetId, secret).also { if (it.fault == null) schedulePending() }
    internal suspend fun schedulePending() {
        if (!connection.value) core.store.transaction { db ->
            db.operations.replaceAll { _, op -> if (op.feature != null && op.status == OperationStatus.QUEUED)
                op.copy(status = OperationStatus.WAITING_NETWORK, fault = Fault(ErrorCode.OFFLINE, "等待网络", true)) else op }
        }
        val state = core.store.read(); val operations = state.operations.values.toList()
        val manager = WorkManager.getInstance(context)
        // Configuration failures wait for RetryOperation or managed configuration reconciliation.
        // Scheduling them again here
        // makes blocked tasks wake each other and continuously invalidate every UI subscription.
        operations.filter { it.status in setOf(OperationStatus.QUEUED, OperationStatus.WAITING_NETWORK) }.forEach { operation ->
            val constraints = Constraints.Builder().apply { if (operation.feature != null) setRequiredNetworkType(NetworkType.CONNECTED) }.build()
            val delay = ((state.jobs[operation.id]?.notBeforeMs ?: 0) - System.currentTimeMillis()).coerceAtLeast(0)
            val work = OneTimeWorkRequestBuilder<WordPrismWorker>().setInputData(workDataOf("operation_id" to operation.id.value)).setConstraints(constraints)
                .setInitialDelay(delay, java.util.concurrent.TimeUnit.MILLISECONDS).build()
            manager.enqueueUniqueWork("wordprism:${operation.id.value}", ExistingWorkPolicy.KEEP, work)
        }
        operations.filter { it.status == OperationStatus.CANCELLED }.forEach { manager.cancelUniqueWork("wordprism:${it.id.value}") }
    }
}

class WordPrismWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val identity = inputData.getString("operation_id") ?: return Result.failure()
        val sdk = AndroidWordPrism.open(applicationContext)
        val connection = applicationContext.getSystemService(ConnectivityManager::class.java)
        val online = connection.getNetworkCapabilities(connection.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        return try {
            sdk.core.tasks.run(Id(identity), online)
            sdk.schedulePending()
            val operation = (sdk.query(Query.GetOperation(Id(identity))).value as? View.OperationResult)?.operation
            if (operation?.status in setOf(OperationStatus.WAITING_NETWORK, OperationStatus.QUEUED)) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) { sdk.core.tasks.cancel(Id(identity)); throw cancelled }
    }
}
