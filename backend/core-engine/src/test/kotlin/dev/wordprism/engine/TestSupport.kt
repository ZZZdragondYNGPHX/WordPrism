package dev.wordprism.engine

import dev.wordprism.contract.*
import dev.wordprism.memory.NativeMemoryKernel
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class TestStore : StateStore {
    var state = Database()
    private val mutex = Mutex(); private val revisions = MutableStateFlow(0L)
    override suspend fun read(): Database = mutex.withLock { StateCodec.clone(state) }
    override suspend fun <T> transaction(block: (Database) -> T): T = mutex.withLock {
        val copy = StateCodec.clone(state); val result = block(copy)
        if (copy != state) copy.revision++
        state = copy; revisions.value = copy.revision; result
    }
    override fun revisions() = revisions
    override fun close() = Unit
}
class TestCredentials : CredentialStore {
    private val values = mutableMapOf<Id, CharArray>()
    override fun has(presetId: Id) = presetId in values
    override fun read(presetId: Id) = values[presetId]?.copyOf()
    override fun write(presetId: Id, secret: CharArray) { values[presetId] = secret.copyOf() }
    override fun delete(presetId: Id) { values.remove(presetId)?.fill('\u0000') }
}
class TestClock : Clock() {
    val time = AtomicLong(Instant.parse("2026-11-03T18:00:00Z").toEpochMilli())
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(time.get())
    fun advance(ms: Long) { time.addAndGet(ms) }
}
class TestProvider : LlmProvider {
    val requests = java.util.concurrent.CopyOnWriteArrayList<ProviderRequest>()
    var response: suspend (ProviderRequest) -> ProviderResponse = { ProviderResponse(json("""{"patch":{"values":{},"reason":"Keep current settings","evidenceIds":[]},"actions":[]}"""), 30, 20) }
    override suspend fun generate(request: ProviderRequest, secret: CharArray): ProviderResponse { requests += request; secret.fill('\u0000'); return response(request) }
    override fun cancel(operationId: Id) = Unit // deliberately emulate a provider that still returns a late response
}
fun json(value: String) = Contract.json.parseToJsonElement(value)
class Harness {
    val store = TestStore(); val credentials = TestCredentials(); val clock = TestClock(); val provider = TestProvider()
    val memory = NativeMemoryKernel(); val core = CoreEngine(store, credentials, memory, clock, provider)
    suspend fun command(command: Command, key: String = UUID.randomUUID().toString()): View {
        val reply = core.execute(Request(key, command)); assertNull(reply.fault, reply.fault.toString()); return reply.value!!
    }
    suspend fun configure(auto: Boolean = true, protocol: String = ApiProtocols.OPENAI_CHAT_COMPLETIONS) {
        val preset = ApiPreset(Id("test-preset"), name = "Test provider", baseUrl = "https://example.invalid/v1", model = "test-model", protocol = protocol)
        command(Command.SavePreset(preset)); core.setCredential(preset.id, UUID.randomUUID().toString().toCharArray())
        Feature.entries.forEach { command(Command.BindPreset(Binding(it, preset.id))) }
        val settings = store.read().settings; command(Command.SetSettings(settings.copy(automaticAnalysis = auto), settings.version))
    }
    suspend fun seed() {
        command(Command.SaveWords(listOf(WordSeed("linear", "线性的", "adjective"), WordSeed("liner", "衬里", "noun")), GroupSeed("linear / liner", Relation.LOOK_ALIKE, "区分拼写与含义")))
    }
    suspend fun question(): View.SessionState {
        val session = (command(Command.StartSession()) as View.SessionState).session
        return command(Command.NextActivity(session.id)) as View.SessionState
    }
    suspend fun answer(card: View.SessionState? = null, input: AnswerInput? = null): View.AnswerResult {
        val shown = card ?: question()
        val activity = store.read().activities[shown.activity!!.id]!!
        return command(Command.SubmitAnswer(shown.session.id, shown.session.currentPresentationId!!, Ref(activity.id, activity.version), input ?: AnswerInput(activity.acceptedAnswers.firstOrNull() ?: "A synthetic practice sentence."))) as View.AnswerResult
    }
    suspend fun finishDay(): Operation? {
        val day = store.read().days.values.last()
        return (command(Command.FinishLearningDay(day.id)) as View.Day).automatic
    }
}
