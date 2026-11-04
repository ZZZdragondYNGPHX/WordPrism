package dev.wordprism.engine

import dev.wordprism.contract.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.*

class ProviderTest {
    private fun request(server: MockWebServer) = ProviderRequest(Id("http-test"), ApiPreset(Id("preset"), name = "Loopback test", baseUrl = server.url("/v1").toString(), model = "test"), "Return JSON", json("{}"))
    @Test fun `actual HTTP adapter sends selected model and captures supplied usage`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"stop","message":{"content":"{\"ok\":true}"}}],"usage":{"prompt_tokens":7,"completion_tokens":3}}"""))
            val secret = UUID.randomUUID().toString().toCharArray()
            val response = OpenAiCompatibleProvider(true).generate(request(server), secret)
            assertEquals(7L, response.inputTokens); assertEquals(3L, response.outputTokens); assertTrue(secret.all { it == '\u0000' })
            val actual = server.takeRequest(); assertEquals("/v1/chat/completions", actual.path)
            assertTrue(actual.body.readUtf8().contains("\"model\":\"test\"")); assertEquals(1, server.requestCount)
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
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination.url("/steal")).setBody("{}"))
            assertFailsWith<ProviderException> { OpenAiCompatibleProvider(true).generate(request(origin), UUID.randomUUID().toString().toCharArray()) }
            assertEquals(0, destination.requestCount)
        } }
    }
    @Test fun `ordinary presets reject embedded credentials and insecure transport`() {
        val preset = ApiPreset(Id("preset"), name = "Test", baseUrl = "https://example.invalid/v1", model = "test")
        assertFailsWith<BusinessException> { validatePreset(preset.copy(baseUrl = preset.baseUrl.toHttpUrl().newBuilder().username(UUID.randomUUID().toString()).build().toString())) }
        assertFailsWith<BusinessException> { validatePreset(preset.copy(baseUrl = preset.baseUrl.toHttpUrl().newBuilder().addQueryParameter("api_key", UUID.randomUUID().toString()).build().toString())) }
        assertFailsWith<BusinessException> { validatePreset(preset.copy(baseUrl = "http://example.invalid/v1")) }
    }
}
