package dev.wordprism.engine

import dev.wordprism.contract.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.*

class ProviderTest {
    private fun request(server: MockWebServer, protocol: String = ApiProtocols.OPENAI_CHAT_COMPLETIONS) = ProviderRequest(Id("http-test"),
        ApiPreset(Id("preset"), name = "Loopback test", baseUrl = server.url("/v1").toString(), model = "test", protocol = protocol), "Return JSON", json("{}"))
    private val chatSuccess = json("""{"choices":[{"finish_reason":"stop","message":{"content":"{\"ok\":true}"}}],"usage":{"prompt_tokens":7,"completion_tokens":3}}""").jsonObject
    private val responsesSuccess = json("""{"status":"completed","error":null,"output":[{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"{\"ok\":true}"}]}],"usage":{"input_tokens":7,"output_tokens":3}}""").jsonObject
    @Test fun `actual HTTP adapter sends selected model and captures supplied usage`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(chatSuccess.toString()))
            val secret = UUID.randomUUID().toString().toCharArray()
            val response = OpenAiCompatibleProvider(true).generate(request(server), secret)
            assertEquals(7L, response.inputTokens); assertEquals(3L, response.outputTokens); assertTrue(secret.all { it == '\u0000' })
            val actual = server.takeRequest(); assertEquals("/v1/chat/completions", actual.path)
            val body = json(actual.body.readUtf8()).jsonObject
            assertEquals(json("""[{"role":"system","content":"Return JSON"},{"role":"user","content":"{}"}]"""), body["messages"])
            assertEquals(json("2048"), body["max_tokens"]); assertEquals(json("""{"type":"json_object"}"""), body["response_format"])
            assertEquals(json("\"test\""), body["model"]); assertEquals(json("false"), body["stream"])
            assertFalse(body.containsKey("input")); assertFalse(body.containsKey("max_output_tokens")); assertEquals(1, server.requestCount)
        }
    }
    @Test fun `native Responses uses its own wire format and extracts message text after reasoning`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{
                "status":"completed","error":null,"output_text":"SDK helpers are not raw HTTP output",
                "output":[
                    {"type":"reasoning","id":"reasoning-test","summary":[],"text":"not the answer"},
                    {"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"{\"ok\":"}]},
                    {"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"true}"}]}
                ],
                "usage":{"input_tokens":11,"output_tokens":13,"output_tokens_details":{"reasoning_tokens":9}}
            }"""))
            val original = request(server, ApiProtocols.OPENAI_RESPONSES)
            val request = original.copy(preset = original.preset.copy(baseUrl = server.url("/gateway/v1/").toString(), maxOutputTokens = 768, temperature = 0.2f),
                data = json("""{"input":"linear / liner","meaning":"线性的"}"""))
            val secretText = UUID.randomUUID().toString(); val secret = secretText.toCharArray()
            val response = OpenAiCompatibleProvider(true).generate(request, secret)
            assertEquals(json("""{"ok":true}"""), response.content)
            assertEquals(11L, response.inputTokens); assertEquals(13L, response.outputTokens) // reasoning is already included
            assertTrue(secret.all { it == '\u0000' })
            val actual = server.takeRequest(); assertEquals("/gateway/v1/responses", actual.path)
            assertEquals("Bearer $secretText", actual.getHeader("Authorization")); assertEquals("POST", actual.method)
            val body = json(actual.body.readUtf8()).jsonObject
            assertEquals(setOf("model", "stream", "temperature", "max_output_tokens", "store", "instructions", "input", "text"), body.keys)
            assertEquals(JsonPrimitive(request.system), body["instructions"])
            assertEquals(JsonPrimitive("Return a JSON object for the following task data:\n${request.data}"), body["input"])
            assertEquals(json("768"), body["max_output_tokens"]); assertEquals(json("0.2"), body["temperature"])
            assertEquals(json("false"), body["stream"]); assertEquals(json("false"), body["store"])
            assertEquals(json("""{"format":{"type":"json_object"}}"""), body["text"]); assertEquals(1, server.requestCount)
        }
    }
    @Test fun `unconfigured optional fields are omitted for both protocols`() = runBlocking {
        MockWebServer().use { server ->
            for ((protocol, response) in listOf(ApiProtocols.OPENAI_CHAT_COMPLETIONS to chatSuccess, ApiProtocols.OPENAI_RESPONSES to responsesSuccess)) {
                server.enqueue(MockResponse().setBody(response.toString()))
                val request = request(server, protocol).let { it.copy(preset = it.preset.copy(jsonMode = false, temperature = null)) }
                OpenAiCompatibleProvider(true).generate(request, UUID.randomUUID().toString().toCharArray())
                val body = json(server.takeRequest().body.readUtf8()).jsonObject
                assertFalse(body.containsKey("temperature")); assertFalse(body.containsKey("response_format")); assertFalse(body.containsKey("text"))
                assertFalse(body.containsKey("reasoning_effort")); assertFalse(body.containsKey("reasoning")); assertFalse(body.containsKey("max_completion_tokens"))
                if (protocol == ApiProtocols.OPENAI_RESPONSES) assertEquals(JsonPrimitive(request.data.toString()), body["input"])
            }
        }
    }
    @Test fun `all explicit reasoning efforts use the selected protocol and completion budget`() = runBlocking {
        MockWebServer().use { server ->
            val provider = OpenAiCompatibleProvider(true)
            val values = listOf(ReasoningEffort.NONE to "none", ReasoningEffort.MINIMAL to "minimal", ReasoningEffort.LOW to "low",
                ReasoningEffort.MEDIUM to "medium", ReasoningEffort.HIGH to "high", ReasoningEffort.XHIGH to "xhigh", ReasoningEffort.MAX to "max")
            for ((protocol, response) in listOf(ApiProtocols.OPENAI_CHAT_COMPLETIONS to chatSuccess, ApiProtocols.OPENAI_RESPONSES to responsesSuccess)) {
                for ((effort, wireValue) in values) {
                    server.enqueue(MockResponse().setBody(response.toString()))
                    val request = request(server, protocol).let { it.copy(preset = it.preset.copy(reasoningEffort = effort, maxOutputTokens = 4096)) }
                    provider.generate(request, UUID.randomUUID().toString().toCharArray())
                    val body = json(server.takeRequest().body.readUtf8()).jsonObject
                    assertFalse(body.containsKey("max_tokens")); assertFalse(body.containsKey("temperature"))
                    if (protocol == ApiProtocols.OPENAI_RESPONSES) {
                        assertEquals(json("""{"effort":"$wireValue"}"""), body["reasoning"])
                        assertEquals(json("4096"), body["max_output_tokens"])
                        assertFalse(body.containsKey("reasoning_effort")); assertFalse(body.containsKey("max_completion_tokens"))
                    } else {
                        assertEquals(JsonPrimitive(wireValue), body["reasoning_effort"])
                        assertEquals(json("4096"), body["max_completion_tokens"])
                        assertFalse(body.containsKey("reasoning")); assertFalse(body.containsKey("max_output_tokens"))
                    }
                }
            }
            assertEquals(14, server.requestCount)
        }
    }
    @Test fun `pasted endpoints normalize once for both protocols without changing custom prefixes`() = runBlocking {
        MockWebServer().use { server ->
            val provider = OpenAiCompatibleProvider(true)
            for ((protocol, response) in listOf(ApiProtocols.OPENAI_CHAT_COMPLETIONS to chatSuccess, ApiProtocols.OPENAI_RESPONSES to responsesSuccess)) {
                for (suffix in listOf("", "/", "/responses", "/responses/", "/chat/completions/", "/models")) {
                    server.enqueue(MockResponse().setBody(response.toString()))
                    val request = request(server, protocol).let { it.copy(preset = it.preset.copy(baseUrl = server.url("/gateway/v1$suffix").toString())) }
                    provider.generate(request, UUID.randomUUID().toString().toCharArray())
                    val path = if (protocol == ApiProtocols.OPENAI_RESPONSES) "/gateway/v1/responses" else "/gateway/v1/chat/completions"
                    assertEquals(path, server.takeRequest().path)
                }
            }
            assertEquals(12, server.requestCount)
            assertEquals("/proxy-responses", validateConnection(server.url("/proxy-responses").toString(), ApiProtocols.OPENAI_RESPONSES, 30, true).encodedPath)
        }
    }
    @Test fun `configuration and authentication failures identify HTTP status without leaking provider echoes or retrying`() = runBlocking {
        MockWebServer().use { server ->
            for (protocol in ApiProtocols.supported.map { it.id }) {
                for ((status, code) in listOf(400 to ErrorCode.VALIDATION, 422 to ErrorCode.VALIDATION,
                    401 to ErrorCode.PROVIDER_AUTH, 403 to ErrorCode.PROVIDER_AUTH, 404 to ErrorCode.PROVIDER_UNAVAILABLE)) {
                    val marker = UUID.randomUUID().toString()
                    server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":{"message":"$marker","param":"$marker","code":"$marker","type":"$marker"}}"""))
                    val request = request(server, protocol).let { it.copy(preset = it.preset.copy(reasoningEffort = ReasoningEffort.HIGH)) }
                    val failure = assertFailsWith<ProviderException> { OpenAiCompatibleProvider(true).generate(request, marker.toCharArray()) }
                    assertEquals(code, failure.fault.code); assertFalse(failure.fault.retryable)
                    assertContains(failure.fault.message, "HTTP $status"); assertFalse(failure.fault.message.contains(marker))
                }
            }
            assertEquals(10, server.requestCount)
        }
    }
    @Test fun `known parameter errors retain actionable diagnostics without provider text`() = runBlocking {
        MockWebServer().use { server ->
            val examples = listOf(
                """{"error":{"message":"Response input messages must contain the word 'json' in some form to use 'text.format' of type 'json_object'.","type":"invalid_request_error","param":"input","code":null}}""" to "JSON 输出指令",
                """{"error":{"message":"private provider echo","type":"invalid_request_error","param":"reasoning.effort","code":"unsupported_value"}}""" to "思维程度",
            )
            for ((body, expected) in examples) {
                server.enqueue(MockResponse().setResponseCode(400).setBody(body))
                val error = assertFailsWith<ProviderException> { OpenAiCompatibleProvider(true).generate(request(server, ApiProtocols.OPENAI_RESPONSES), UUID.randomUUID().toString().toCharArray()) }
                assertEquals(ErrorCode.VALIDATION, error.fault.code); assertContains(error.fault.message, expected)
                assertContains(error.fault.message, "参数"); assertContains(error.fault.message, "invalid_request_error")
                assertFalse(error.fault.message.contains("private provider echo")); assertFalse(error.fault.retryable)
            }
        }
    }
    @Test fun `native incomplete refusal failed and pending states retain usage without implicit retries`() = runBlocking {
        MockWebServer().use { server ->
            val cases = listOf(
                Triple("""{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"}}""", ErrorCode.TRUNCATED_RESPONSE, false),
                Triple("""{"status":"incomplete","incomplete_details":{"reason":"content_filter"}}""", ErrorCode.INVALID_RESPONSE, false),
                Triple("""{"status":"incomplete","incomplete_details":{"reason":"steered"}}""", ErrorCode.INVALID_RESPONSE, false),
                Triple("""{"status":"completed","output":[{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"{}"},{"type":"refusal","refusal":"Refused"}]}]}""", ErrorCode.INVALID_RESPONSE, false),
                Triple("""{"status":"failed","error":{"code":"rate_limit_exceeded"}}""", ErrorCode.PROVIDER_RATE_LIMIT, true),
                Triple("""{"status":"failed","error":{"code":"server_error"}}""", ErrorCode.PROVIDER_UNAVAILABLE, true),
                Triple("""{"status":"failed","error":{"code":"invalid_prompt"}}""", ErrorCode.VALIDATION, false),
                Triple("""{"status":"failed","error":{"code":"future_error"}}""", ErrorCode.PROVIDER_UNAVAILABLE, false),
                Triple("""{"status":"queued"}""", ErrorCode.OUTCOME_UNKNOWN, false),
                Triple("""{"status":"in_progress"}""", ErrorCode.OUTCOME_UNKNOWN, false),
                Triple("""{"status":"cancelled"}""", ErrorCode.CANCELLED, false),
            )
            val provider = OpenAiCompatibleProvider(true)
            for ((raw, expected, retryable) in cases) {
                server.enqueue(MockResponse().setBody(JsonObject(json(raw).jsonObject + ("usage" to json("""{"input_tokens":17,"output_tokens":19}"""))).toString()))
                val error = assertFailsWith<ProviderException>(raw) { provider.generate(request(server, ApiProtocols.OPENAI_RESPONSES), UUID.randomUUID().toString().toCharArray()) }
                assertEquals(expected, error.fault.code, raw); assertEquals(retryable, error.fault.retryable, raw)
                assertEquals(17L, error.inputTokens); assertEquals(19L, error.outputTokens)
            }
            assertEquals(cases.size, server.requestCount)
        }
    }
    @Test fun `native malformed output is a known invalid response rather than an unknown network result`() = runBlocking {
        MockWebServer().use { server ->
            val cases = listOf(
                """{"status":[],"output":[]}""",
                """{"status":"completed","error":{"code":"server_error"},"output":[]}""",
                """{"status":"completed","output":[],"output_text":"{}"}""",
                """{"status":"completed","output":[{"type":"reasoning","summary":[]}]}""",
                """{"status":"completed","output":{}}""",
                """{"status":"completed","output":[7]}""",
                """{"status":"completed","output":[{"type":"message","role":"user","status":"completed","content":[]}]}""",
                """{"status":"completed","output":[{"type":"message","role":"assistant","status":"incomplete","content":[]}]}""",
                """{"status":"completed","output":[{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":{}}]}]}""",
                """{"status":"completed","output":[{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"[1,2]"}]}]}""",
                """{"status":"completed","output":[{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"not JSON"}]}]}""",
            )
            val provider = OpenAiCompatibleProvider(true)
            for (raw in cases) {
                server.enqueue(MockResponse().setBody(JsonObject(json(raw).jsonObject + ("usage" to json("""{"input_tokens":5,"output_tokens":8}"""))).toString()))
                val error = assertFailsWith<ProviderException>(raw) { provider.generate(request(server, ApiProtocols.OPENAI_RESPONSES), UUID.randomUUID().toString().toCharArray()) }
                assertEquals(ErrorCode.INVALID_RESPONSE, error.fault.code, raw)
                assertEquals(5L, error.inputTokens); assertEquals(8L, error.outputTokens); assertFalse(error.fault.retryable)
            }
            assertEquals(cases.size, server.requestCount)
        }
    }
    @Test fun `malformed token counts stay unknown and valid counts survive for both protocols`() = runBlocking {
        MockWebServer().use { server ->
            val cases = listOf(
                Triple(ApiProtocols.OPENAI_CHAT_COMPLETIONS, chatSuccess, json("""{"prompt_tokens":{},"completion_tokens":3}""")),
                Triple(ApiProtocols.OPENAI_RESPONSES, responsesSuccess, json("""{"input_tokens":-1,"output_tokens":3}""")),
                Triple(ApiProtocols.OPENAI_RESPONSES, responsesSuccess, json("""{"input_tokens":[],"output_tokens":3}""")),
            )
            for ((protocol, raw, usage) in cases) {
                server.enqueue(MockResponse().setBody(JsonObject(raw + ("usage" to usage)).toString()))
                val response = OpenAiCompatibleProvider(true).generate(request(server, protocol), UUID.randomUUID().toString().toCharArray())
                assertNull(response.inputTokens); assertEquals(3L, response.outputTokens)
            }
        }
    }
    @Test fun `native transport cancellation stops waiting and does not resend`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val provider = OpenAiCompatibleProvider(true); val request = request(server, ApiProtocols.OPENAI_RESPONSES)
            val secret = UUID.randomUUID().toString().toCharArray()
            val pending = async { runCatching { provider.generate(request, secret) } }
            val sent = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
            assertNotNull(sent); assertEquals("/v1/responses", sent.path)
            provider.cancel(request.operationId)
            val failure = withTimeout(5_000) { pending.await() }.exceptionOrNull()
            assertIs<ProviderException>(failure); assertEquals(ErrorCode.OUTCOME_UNKNOWN, failure.fault.code)
            assertFalse(failure.fault.retryable); assertTrue(secret.all { it == '\u0000' }); assertEquals(1, server.requestCount)
        }
    }
    @Test fun `credentials are cleared even when validation or request size rejects before sending`() = runBlocking {
        MockWebServer().use { server ->
            val valid = request(server, ApiProtocols.OPENAI_RESPONSES)
            val cases = listOf(valid.copy(preset = valid.preset.copy(protocol = "unsupported")), valid.copy(data = JsonPrimitive("x".repeat(256 * 1024))))
            for (request in cases) {
                val secret = UUID.randomUUID().toString().toCharArray()
                assertFailsWith<BusinessException> { OpenAiCompatibleProvider(true).generate(request, secret) }
                assertTrue(secret.all { it == '\u0000' })
            }
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun `rate limits truncated output and malformed JSON are distinct and do not auto retry`() = runBlocking {
        MockWebServer().use { server ->
            val responses = listOf(
                MockResponse().setResponseCode(429).setBody("{}") to ErrorCode.PROVIDER_RATE_LIMIT,
                MockResponse().setBody("""{"choices":[{"finish_reason":"length","message":{"content":"{"}}],"usage":{"prompt_tokens":2}}""") to ErrorCode.TRUNCATED_RESPONSE,
                MockResponse().setBody("""{"choices":[{"message":{"content":"not json"}}]}""") to ErrorCode.INVALID_RESPONSE,
                MockResponse().setResponseCode(401).setBody("{}") to ErrorCode.PROVIDER_AUTH,
            )
            for ((response, code) in responses) {
                server.enqueue(response)
                val error = assertFailsWith<ProviderException> { OpenAiCompatibleProvider(true).generate(request(server), UUID.randomUUID().toString().toCharArray()) }
                assertEquals(code, error.fault.code)
            }
            assertEquals(4, server.requestCount)
        }
    }
    @Test fun `redirects never forward authorization to another endpoint`() = runBlocking {
        MockWebServer().use { origin -> MockWebServer().use { destination ->
            for (protocol in ApiProtocols.supported.map { it.id }) {
                origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination.url("/steal")).setBody("{}"))
                assertFailsWith<ProviderException> { OpenAiCompatibleProvider(true).generate(request(origin, protocol), UUID.randomUUID().toString().toCharArray()) }
            }
            assertEquals(0, destination.requestCount)
        } }
    }
    @Test fun `ordinary presets reject embedded credentials and insecure transport`() {
        val preset = ApiPreset(Id("preset"), name = "Test", baseUrl = "https://example.invalid/v1", model = "test")
        assertFailsWith<BusinessException> { validatePreset(preset.copy(baseUrl = preset.baseUrl.toHttpUrl().newBuilder().username(UUID.randomUUID().toString()).build().toString())) }
        assertFailsWith<BusinessException> { validatePreset(preset.copy(baseUrl = preset.baseUrl.toHttpUrl().newBuilder().addQueryParameter("api_key", UUID.randomUUID().toString()).build().toString())) }
        assertFailsWith<BusinessException> { validatePreset(preset.copy(baseUrl = "http://example.invalid/v1")) }
        ApiProtocols.supported.forEach { validatePreset(preset.copy(protocol = it.id)) }
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, assertFailsWith<BusinessException> { validatePreset(preset.copy(protocol = "unknown")) }.fault.code)
    }
}
