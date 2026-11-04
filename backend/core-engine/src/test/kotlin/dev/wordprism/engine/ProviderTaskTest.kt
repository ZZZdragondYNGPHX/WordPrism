package dev.wordprism.engine

import dev.wordprism.contract.*
import dev.wordprism.memory.NativeMemoryKernel
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Real HTTP transport and business tasks; the server returns explicitly synthetic model output. */
class ProviderTaskTest {
    private class HttpHarness(val server: MockWebServer) {
        val store = TestStore(); val credentials = TestCredentials(); val clock = TestClock()
        val core = CoreEngine(store, credentials, NativeMemoryKernel(), clock, OpenAiCompatibleProvider(true), allowLocalHttp = true)
        suspend fun command(command: Command, key: String = UUID.randomUUID().toString()): View {
            val reply = core.execute(Request(key, command)); assertNull(reply.fault, reply.fault.toString()); return reply.value!!
        }
        suspend fun preset(protocol: String, effort: ReasoningEffort? = null): ApiPreset {
            val preset = ApiPreset(Id(protocol), name = protocol, baseUrl = server.url("/v1").toString(), model = "synthetic-model", protocol = protocol, reasoningEffort = effort)
            command(Command.SavePreset(preset)); assertNull(core.setCredential(preset.id, UUID.randomUUID().toString().toCharArray()).fault)
            return preset
        }
    }

    private val intake = """{"words":[{"spelling":"linear","meaning":"线性的"}],"reason":"Synthetic HTTP response"}"""
    private fun nativeSuccess(content: String = intake) = buildJsonObject {
        put("status", "completed")
        putJsonArray("output") { addJsonObject {
            put("type", "message"); put("role", "assistant"); put("status", "completed")
            putJsonArray("content") { addJsonObject { put("type", "output_text"); put("text", content) } }
        } }
        put("usage", json("""{"input_tokens":7,"output_tokens":9}"""))
    }.toString()

    @Test fun `connection test JSON instruction is present in Responses input as required by the real endpoint`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = json(request.body.readUtf8()).jsonObject
                    val input = body["input"]!!.jsonPrimitive.content
                    // Reproduces the HTTP 400 observed from the real API when only instructions said JSON.
                    if (!input.contains("json", ignoreCase = true)) return MockResponse().setResponseCode(400)
                        .setBody("""{"error":{"type":"invalid_request_error","param":"input","message":"Response input messages must contain the word 'json' in some form to use 'text.format' of type 'json_object'."}}""")
                    return MockResponse().setBody(nativeSuccess("""{"ok":true}"""))
                }
            }
            val h = HttpHarness(server); val preset = h.preset(ApiProtocols.OPENAI_RESPONSES, ReasoningEffort.LOW)
            val op = (h.command(Command.TestConnection(Ref(preset.id, preset.version))) as View.OperationResult).operation
            h.core.tasks.run(op.id)
            assertEquals(OperationStatus.SUCCEEDED, h.store.read().operations.getValue(op.id).status)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `Responses intake waits offline then commits once with its preset snapshot and usage`() = runBlocking {
        MockWebServer().use { server ->
            val h = HttpHarness(server); val preset = h.preset(ApiProtocols.OPENAI_RESPONSES, ReasoningEffort.HIGH)
            h.command(Command.BindPreset(Binding(Feature.WORD_INTAKE, preset.id)))
            val command = Command.SubmitIntake("linear")
            val op = (h.command(command, "same-intake") as View.OperationResult).operation
            h.core.tasks.run(op.id, online = false)
            assertEquals(OperationStatus.WAITING_NETWORK, h.store.read().operations.getValue(op.id).status)
            assertEquals(0, server.requestCount); assertTrue(h.store.read().usage.isEmpty())
            // Editing this reusable preset cannot relabel or reroute an already authorized request.
            h.command(Command.SavePreset(preset.copy(protocol = ApiProtocols.OPENAI_CHAT_COMPLETIONS, reasoningEffort = ReasoningEffort.LOW), preset.version))
            server.enqueue(MockResponse().setBody(nativeSuccess()))
            h.core.tasks.run(op.id)
            val replay = h.command(command, "same-intake") as View.OperationResult
            assertEquals(op.id, replay.operation.id); assertEquals(OperationStatus.SUCCEEDED, replay.operation.status)
            h.core.tasks.run(op.id)
            val state = h.store.read()
            assertEquals("linear", state.words.values.single().spelling)
            assertEquals(preset, state.usage.values.single().preset)
            assertEquals(7L, state.usage.values.single().inputTokens); assertEquals(9L, state.usage.values.single().outputTokens)
            assertEquals("received", state.usage.values.single().outcome)
            val sent = server.takeRequest()
            assertEquals("/v1/responses", sent.path); assertEquals(json("""{"effort":"high"}"""), json(sent.body.readUtf8()).jsonObject["reasoning"])
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `configured fallback crosses protocols while retaining native failure usage`() = runBlocking {
        MockWebServer().use { server ->
            val h = HttpHarness(server); val native = h.preset(ApiProtocols.OPENAI_RESPONSES, ReasoningEffort.HIGH); val chat = h.preset(ApiProtocols.OPENAI_CHAT_COMPLETIONS, ReasoningEffort.LOW)
            h.command(Command.BindPreset(Binding(Feature.WORD_INTAKE, native.id)))
            val settings = h.store.read().settings
            h.command(Command.SetSettings(settings.copy(maxRetries = 1, fallbackPresetId = chat.id), settings.version))
            val op = (h.command(Command.SubmitIntake("linear")) as View.OperationResult).operation
            server.enqueue(MockResponse().setBody("""{"status":"failed","error":{"code":"rate_limit_exceeded"},"usage":{"input_tokens":3,"output_tokens":1}}"""))
            h.core.tasks.run(op.id)
            assertEquals(OperationStatus.QUEUED, h.store.read().operations.getValue(op.id).status)
            assertTrue(h.store.read().words.isEmpty()); assertEquals(1, server.requestCount)
            h.clock.advance(1_001)
            val chatResponse = buildJsonObject {
                putJsonArray("choices") { addJsonObject { put("finish_reason", "stop"); putJsonObject("message") { put("content", intake) } } }
                put("usage", json("""{"prompt_tokens":5,"completion_tokens":6}"""))
            }
            server.enqueue(MockResponse().setBody(chatResponse.toString()))
            h.core.tasks.run(op.id)
            val state = h.store.read(); val usage = state.usage.values.sortedBy { it.attempt }
            assertEquals(OperationStatus.SUCCEEDED, state.operations.getValue(op.id).status)
            assertEquals(listOf(native, chat), usage.map { it.preset })
            assertEquals(listOf(3L, 5L), usage.map { it.inputTokens }); assertEquals(listOf(1L, 6L), usage.map { it.outputTokens })
            assertEquals(listOf("PROVIDER_RATE_LIMIT", "received"), usage.map { it.outcome })
            val first = server.takeRequest(); val second = server.takeRequest()
            assertEquals("/v1/responses", first.path); assertEquals("/v1/chat/completions", second.path)
            assertEquals(json("""{"effort":"high"}"""), json(first.body.readUtf8()).jsonObject["reasoning"])
            assertEquals(JsonPrimitive("low"), json(second.body.readUtf8()).jsonObject["reasoning_effort"])
            assertEquals(2, server.requestCount); assertEquals(1, state.words.size)
        }
    }
}
