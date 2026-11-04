package dev.wordprism.engine

import dev.wordprism.contract.*
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

data class ProviderRequest(val operationId: Id, val preset: ApiPreset, val system: String, val data: JsonElement)
data class ProviderResponse(val content: JsonElement, val inputTokens: Long? = null, val outputTokens: Long? = null)
class ProviderException(val fault: Fault, val inputTokens: Long? = null, val outputTokens: Long? = null) : IOException(fault.message)
interface LlmProvider {
    suspend fun generate(request: ProviderRequest, secret: CharArray): ProviderResponse
    fun cancel(operationId: Id)
}

internal fun validatePreset(preset: ApiPreset, allowLocalHttp: Boolean = false) {
    val url = preset.baseUrl.toHttpUrlOrNull() ?: fail(ErrorCode.VALIDATION, "API 地址无效")
    requireBusiness(url.isHttps || allowLocalHttp && url.host in setOf("localhost", "127.0.0.1", "::1"), "API 必须使用 HTTPS")
    requireBusiness(url.toUri().rawUserInfo == null && url.query == null && url.fragment == null, "地址不能包含凭证、查询或片段")
    requireBusiness(preset.protocol == "openai_chat_completions", "暂不支持此协议", ErrorCode.UNSUPPORTED_VERSION)
    requireBusiness(preset.name.isNotBlank() && preset.name.length <= 120 && preset.model.isNotBlank() && preset.model.length <= 200, "预设名称或模型无效")
    requireBusiness(preset.timeoutSeconds in 5..180 && preset.maxOutputTokens in 64..16384 && (preset.temperature?.let { it.isFinite() && it in 0f..2f } != false), "预设参数超出支持范围")
}

class OpenAiCompatibleProvider(private val allowLocalHttp: Boolean = false) : LlmProvider {
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    private val calls = ConcurrentHashMap<Id, Call>()
    override suspend fun generate(request: ProviderRequest, secret: CharArray): ProviderResponse {
        validatePreset(request.preset, allowLocalHttp)
        val preset = request.preset
        val body = buildJsonObject {
            put("model", preset.model); put("max_tokens", preset.maxOutputTokens); put("stream", false)
            preset.temperature?.let { put("temperature", it) }
            if (preset.jsonMode) putJsonObject("response_format") { put("type", "json_object") }
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", request.system) }
                addJsonObject { put("role", "user"); put("content", request.data.toString()) }
            }
        }.toString()
        requireBusiness(body.toByteArray().size <= 256 * 1024, "本次上下文过大", ErrorCode.LIMIT_EXCEEDED)
        val http = okhttp3.Request.Builder().url(preset.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer " + String(secret)).post(body.toRequestBody("application/json".toMediaType())).build()
        secret.fill('\u0000')
        val call = client.newBuilder().callTimeout(preset.timeoutSeconds.toLong(), TimeUnit.SECONDS).build().newCall(http)
        calls[request.operationId] = call
        try {
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        val fault = if (e is InterruptedIOException) Fault(ErrorCode.PROVIDER_TIMEOUT, "请求超时；服务商是否完成未知，请明确选择重试")
                            else Fault(ErrorCode.OUTCOME_UNKNOWN, "连接中断；服务商是否完成未知，请明确选择重试")
                        if (continuation.isActive) continuation.resumeWithException(ProviderException(fault))
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching { response.use { parse(it) } }
                        if (continuation.isActive) result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                    }
                })
            }
        } finally { calls.remove(request.operationId, call); secret.fill('\u0000') }
    }
    private fun parse(response: Response): ProviderResponse {
        val body = response.body ?: throw ProviderException(Fault(ErrorCode.INVALID_RESPONSE, "响应没有内容"))
        if (body.contentLength() > 2 * 1024 * 1024) throw ProviderException(Fault(ErrorCode.LIMIT_EXCEEDED, "响应超过 2 MiB"))
        val source = body.source(); val bytes = source.readAtMost(2 * 1024 * 1024 + 1)
        if (bytes.size > 2 * 1024 * 1024) throw ProviderException(Fault(ErrorCode.LIMIT_EXCEEDED, "响应超过 2 MiB"))
        val raw = runCatching { Contract.json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }.getOrNull()
        val usage = raw?.get("usage") as? JsonObject
        val input = usage?.get("prompt_tokens")?.jsonPrimitive?.longOrNull?.takeIf { it >= 0 }
        val output = usage?.get("completion_tokens")?.jsonPrimitive?.longOrNull?.takeIf { it >= 0 }
        fun problem(code: ErrorCode, text: String, retry: Boolean = false): Nothing = throw ProviderException(Fault(code, text, retry), input, output)
        if (!response.isSuccessful) when (response.code) {
            401, 403 -> problem(ErrorCode.PROVIDER_AUTH, "服务商拒绝认证，请检查凭证")
            429 -> problem(ErrorCode.PROVIDER_RATE_LIMIT, "服务商限流，可稍后重试", true)
            in 500..599 -> problem(ErrorCode.PROVIDER_UNAVAILABLE, "服务商暂不可用", true)
            else -> problem(ErrorCode.PROVIDER_UNAVAILABLE, "服务商返回 HTTP ${response.code}")
        }
        val choice = (raw?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject ?: problem(ErrorCode.INVALID_RESPONSE, "响应缺少候选内容")
        if (choice["finish_reason"]?.jsonPrimitive?.content == "length") problem(ErrorCode.TRUNCATED_RESPONSE, "生成内容被截断")
        val message = choice["message"] as? JsonObject
        val content = message?.get("content")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: problem(ErrorCode.INVALID_RESPONSE, "模型未返回文本")
        val parsed = runCatching { Contract.json.parseToJsonElement(content) }.getOrNull() ?: problem(ErrorCode.INVALID_RESPONSE, "模型未返回有效 JSON")
        return ProviderResponse(parsed, input, output)
    }
    override fun cancel(operationId: Id) { calls[operationId]?.cancel() }
}

private fun okio.BufferedSource.readAtMost(limit: Int): ByteArray {
    val sink = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (sink.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - sink.size()))
        if (count == -1) break
        sink.write(buffer, 0, count)
    }
    return sink.toByteArray()
}

@Serializable internal data class RecommendationSeed(val spelling: String, val reason: String, val groupId: Id? = null, val content: WordSeed? = null)
@Serializable internal data class SpellingCorrection(val original: String, val suggested: WordSeed, val reason: String)
@Serializable internal data class IntakeProposal(
    val words: List<WordSeed>, val groupId: Id? = null, val newGroup: GroupSeed? = null,
    val candidates: List<IntakeChoice> = emptyList(), val reason: String,
    val recommendations: List<RecommendationSeed> = emptyList(),
    val corrections: List<SpellingCorrection> = emptyList(),
)
@Serializable internal data class RecommendationProposal(val recommendations: List<RecommendationSeed>)
@Serializable internal data class ActivityProposal(
    val prompt: String, val choices: List<Choice> = emptyList(), val acceptedAnswers: List<String> = emptyList(), val explanation: String,
    val passage: String? = null, val question: String? = null, val sourceSentence: String? = null,
)
@Serializable internal data class FeedbackProposal(val explanation: String, val targetUsage: String, val quotedAnswer: String)
@Serializable internal data class ExplanationProposal(val explanation: String, val examples: List<String>)
@Serializable internal data class AnalysisProposal(val patch: PolicyPatch, val actions: List<Command> = emptyList())
