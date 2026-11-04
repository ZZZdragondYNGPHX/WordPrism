package dev.wordprism.engine

import dev.wordprism.contract.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ModelCatalogProviderTest {
    private fun request(server: MockWebServer) = ModelCatalogRequest(server.url("/gateway/v1/").toString())
    private fun secret() = UUID.randomUUID().toString().toCharArray()

    @Test fun `both protocols GET models without a model or inference and normalize provider IDs`() = runBlocking {
        MockWebServer().use { server ->
            val provider = OpenAiCompatibleProvider(true)
            for (protocol in ApiProtocols.supported) for (suffix in listOf("", "responses/", "chat/completions", "models")) {
                server.enqueue(MockResponse().setBody("""{"object":"list","data":[
                    {"id":"z-model","owned_by":"provider","created":123,"shutdown_date":null},
                    {"id":"a-model","unknown":true},{"id":"z-model","owned_by":"duplicate"}]}
                """))
                val chars = secret(); val sentSecret = String(chars)
                val models = provider.listModels(request(server).copy(protocol = protocol.id, baseUrl = server.url("/gateway/v1/$suffix").toString()), chars)
                assertEquals(listOf(ApiModel("a-model"), ApiModel("z-model", "provider")), models)
                assertTrue(chars.all { it == '\u0000' })
                val sent = server.takeRequest()
                assertEquals("GET", sent.method); assertEquals("/gateway/v1/models", sent.path)
                assertEquals("Bearer $sentSecret", sent.getHeader("Authorization")); assertEquals(0L, sent.bodySize)
                assertEquals("application/json", sent.getHeader("Accept"))
            }
            assertEquals(8, server.requestCount)
        }
    }

    @Test fun `empty catalog is valid and malformed catalogs fail without exposing raw responses`() = runBlocking {
        MockWebServer().use { server ->
            val provider = OpenAiCompatibleProvider(true)
            server.enqueue(MockResponse().setBody("""{"data":[]}"""))
            assertEquals(emptyList(), provider.listModels(request(server), secret()))
            val invalid = listOf("[]", "not json", "{}", """{"data":{}}""", """{"data":[7]}""",
                """{"data":[{"id":42}]}""", """{"data":[{"id":" "}]}""", """{"data":[{"id":"bad\nmodel"}]}""")
            for (body in invalid) {
                server.enqueue(MockResponse().setBody(body))
                val chars = secret()
                val error = assertFailsWith<ProviderException> { provider.listModels(request(server), chars) }
                assertEquals(ErrorCode.INVALID_RESPONSE, error.fault.code)
                assertNull(error.inputTokens); assertNull(error.outputTokens); assertTrue(chars.all { it == '\u0000' })
            }
            assertEquals(invalid.size + 1, server.requestCount)
        }
    }

    @Test fun `metadata errors distinguish authentication rate limits and unsupported lists and never redirect or retry`() = runBlocking {
        MockWebServer().use { server ->
            val provider = OpenAiCompatibleProvider(true)
            val cases = listOf(401 to ErrorCode.PROVIDER_AUTH, 403 to ErrorCode.PROVIDER_AUTH,
                404 to ErrorCode.PROVIDER_UNAVAILABLE, 405 to ErrorCode.PROVIDER_UNAVAILABLE,
                429 to ErrorCode.PROVIDER_RATE_LIMIT, 503 to ErrorCode.PROVIDER_UNAVAILABLE, 302 to ErrorCode.PROVIDER_UNAVAILABLE)
            for ((status, expected) in cases) {
                val chars = secret(); val sentinel = String(chars)
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Location", server.url("/redirect"))
                    .setBody("""{"error":{"message":"$sentinel"},"usage":{"input_tokens":3}}"""))
                val error = assertFailsWith<ProviderException> { provider.listModels(request(server), chars) }
                assertEquals(expected, error.fault.code); assertFalse(error.fault.message.contains(sentinel))
                assertEquals(status in setOf(429, 503), error.fault.retryable)
                if (status in setOf(404, 405)) assertContains(error.fault.message, "手动填写")
                assertNull(error.inputTokens); assertNull(error.outputTokens)
                assertTrue(chars.all { it == '\u0000' })
            }
            assertEquals(cases.size, server.requestCount)
        }
    }

    @Test fun `invalid connection never sends and erases input`() = runBlocking {
        MockWebServer().use { server ->
            val base = request(server)
            val cases = listOf(base.copy(protocol = "other"), base.copy(baseUrl = base.baseUrl + "?api_key=hidden"),
                base.copy(baseUrl = server.url("/v1").newBuilder().username("user").password(UUID.randomUUID().toString()).build().toString()), base.copy(timeoutSeconds = 0))
            for (draft in cases) {
                val chars = secret()
                assertFailsWith<BusinessException> { OpenAiCompatibleProvider(true).listModels(draft, chars) }
                assertTrue(chars.all { it == '\u0000' })
            }
            val chars = secret()
            assertFailsWith<BusinessException> { OpenAiCompatibleProvider().listModels(base, chars) }
            assertTrue(chars.all { it == '\u0000' }); assertEquals(0, server.requestCount)
        }
    }

    @Test fun `response bytes and model count are bounded without returning a partial catalog`() = runBlocking {
        MockWebServer().use { server ->
            val provider = OpenAiCompatibleProvider(true)
            val tooMany = """{"data":[${(1..10001).joinToString(",") { """{"id":"model-$it"}""" }}]}"""
            val tooLarge = """{"padding":"${"x".repeat(2 * 1024 * 1024)}","data":[]}"""
            for (response in listOf(MockResponse().setBody(tooMany), MockResponse().setBody(tooLarge), MockResponse().setChunkedBody(tooLarge, 8192))) {
                server.enqueue(response)
                val chars = secret()
                val error = assertFailsWith<ProviderException> { provider.listModels(request(server), chars) }
                assertEquals(ErrorCode.LIMIT_EXCEEDED, error.fault.code); assertTrue(chars.all { it == '\u0000' })
            }
        }
    }

    @Test fun `coroutine cancellation aborts the HTTP wait and never sends again`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val chars = secret()
            val pending = async { OpenAiCompatibleProvider(true).listModels(request(server), chars) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            withTimeout(5_000) { pending.cancelAndJoin() }
            assertTrue(pending.isCancelled); assertTrue(chars.all { it == '\u0000' }); assertEquals(1, server.requestCount)
        }
    }

    @Test fun `metadata timeout is retryable and does not claim inference outcome unknown`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val chars = secret()
            val error = assertFailsWith<ProviderException> { OpenAiCompatibleProvider(true).listModels(request(server).copy(timeoutSeconds = 5), chars) }
            assertEquals(ErrorCode.PROVIDER_TIMEOUT, error.fault.code); assertTrue(error.fault.retryable)
            assertFalse(error.fault.message.contains("未知")); assertTrue(chars.all { it == '\u0000' }); assertEquals(1, server.requestCount)
        }
    }

    @Test fun `broken response body is a network fault and not a local storage failure`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"some-model"}]}""").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val error = assertFailsWith<ProviderException> { OpenAiCompatibleProvider(true).listModels(request(server), secret()) }
            assertEquals(ErrorCode.PROVIDER_UNAVAILABLE, error.fault.code); assertTrue(error.fault.retryable); assertEquals(1, server.requestCount)
        }
    }
}
