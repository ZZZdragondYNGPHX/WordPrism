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
    requireBusiness(ApiProtocols.supported.any { it.id == preset.protocol }, "暂不支持此协议", ErrorCode.UNSUPPORTED_VERSION)
    requireBusiness(preset.name.isNotBlank() && preset.name.length <= 120 && preset.model.isNotBlank() && preset.model.length <= 200, "预设名称或模型无效")
    requireBusiness(preset.timeoutSeconds in 5..180 && preset.maxOutputTokens in 64..16384 && (preset.temperature?.let { it.isFinite() && it in 0f..2f } != false), "预设参数超出支持范围")
}

class OpenAiCompatibleProvider(private val allowLocalHttp: Boolean = false) : LlmProvider {
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    private val calls = ConcurrentHashMap<Id, Call>()
    override suspend fun generate(request: ProviderRequest, secret: CharArray): ProviderResponse {
        try {
            validatePreset(request.preset, allowLocalHttp)
            val preset = request.preset
            val body = encode(request).toString()
            requireBusiness(body.toByteArray(Charsets.UTF_8).size <= 256 * 1024, "本次上下文过大", ErrorCode.LIMIT_EXCEEDED)
            val path = when (preset.protocol) {
                ApiProtocols.OPENAI_RESPONSES -> "/responses"
                else -> "/chat/completions"
            }
            val http = okhttp3.Request.Builder().url(preset.baseUrl.trimEnd('/') + path)
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
                            val result = runCatching { response.use { parse(it, preset.protocol) } }
                            if (continuation.isActive) result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                        }
                    })
                }
            } finally { calls.remove(request.operationId, call) }
        } finally { secret.fill('\u0000') }
    }

    private fun encode(request: ProviderRequest): JsonObject = buildJsonObject {
        val preset = request.preset
        put("model", preset.model); put("stream", false)
        preset.temperature?.let { put("temperature", it) }
        when (preset.protocol) {
            ApiProtocols.OPENAI_RESPONSES -> {
                put("max_output_tokens", preset.maxOutputTokens)
                put("store", false)
                put("instructions", request.system)
                put("input", request.data.toString())
                if (preset.jsonMode) putJsonObject("text") { putJsonObject("format") { put("type", "json_object") } }
            }
            else -> {
                put("max_tokens", preset.maxOutputTokens)
                if (preset.jsonMode) putJsonObject("response_format") { put("type", "json_object") }
                putJsonArray("messages") {
                    addJsonObject { put("role", "system"); put("content", request.system) }
                    addJsonObject { put("role", "user"); put("content", request.data.toString()) }
                }
            }
        }
    }

    private fun parse(response: Response, protocol: String): ProviderResponse {
        val body = response.body ?: throw ProviderException(Fault(ErrorCode.INVALID_RESPONSE, "响应没有内容"))
        if (body.contentLength() > 2 * 1024 * 1024) throw ProviderException(Fault(ErrorCode.LIMIT_EXCEEDED, "响应超过 2 MiB"))
        val source = body.source(); val bytes = source.readAtMost(2 * 1024 * 1024 + 1)
        if (bytes.size > 2 * 1024 * 1024) throw ProviderException(Fault(ErrorCode.LIMIT_EXCEEDED, "响应超过 2 MiB"))
        val raw = runCatching { Contract.json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
        val usage = raw?.get("usage") as? JsonObject
        val native = protocol == ApiProtocols.OPENAI_RESPONSES
        val tokens = TokenUsage(usage?.tokens(if (native) "input_tokens" else "prompt_tokens"),
            usage?.tokens(if (native) "output_tokens" else "completion_tokens"))
        if (!response.isSuccessful) when (response.code) {
            401, 403 -> tokens.problem(ErrorCode.PROVIDER_AUTH, "服务商拒绝认证，请检查凭证")
            429 -> tokens.problem(ErrorCode.PROVIDER_RATE_LIMIT, "服务商限流，可稍后重试", true)
            in 500..599 -> tokens.problem(ErrorCode.PROVIDER_UNAVAILABLE, "服务商暂不可用", true)
            else -> tokens.problem(ErrorCode.PROVIDER_UNAVAILABLE, "服务商返回 HTTP ${response.code}")
        }
        if (raw == null) tokens.problem(ErrorCode.INVALID_RESPONSE, "服务商未返回有效 JSON 对象")
        val content = if (native) responsesText(raw, tokens) else chatText(raw, tokens)
        val parsed = runCatching { Contract.json.parseToJsonElement(content) as? JsonObject }.getOrNull()
            ?: tokens.problem(ErrorCode.INVALID_RESPONSE, "模型未返回有效 JSON 对象")
        return ProviderResponse(parsed, tokens.input, tokens.output)
    }

    private fun chatText(raw: JsonObject, tokens: TokenUsage): String {
        val choice = (raw["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: tokens.problem(ErrorCode.INVALID_RESPONSE, "响应缺少候选内容")
        when (choice.text("finish_reason")) {
            "length" -> tokens.problem(ErrorCode.TRUNCATED_RESPONSE, "生成内容被截断")
            "content_filter" -> tokens.problem(ErrorCode.INVALID_RESPONSE, "服务商过滤了本次内容")
        }
        val message = choice["message"] as? JsonObject
        if (!message?.text("refusal").isNullOrBlank()) tokens.problem(ErrorCode.INVALID_RESPONSE, "模型拒绝生成本次内容")
        return message?.text("content")?.takeIf { it.isNotBlank() } ?: tokens.problem(ErrorCode.INVALID_RESPONSE, "模型未返回文本")
    }

    private fun responsesText(raw: JsonObject, tokens: TokenUsage): String {
        when (raw.text("status")) {
            "completed" -> if (raw["error"] != null && raw["error"] != JsonNull) tokens.problem(ErrorCode.INVALID_RESPONSE, "响应同时包含完成状态和错误")
            "incomplete" -> when ((raw["incomplete_details"] as? JsonObject)?.text("reason")) {
                "max_output_tokens", "max_messages" -> tokens.problem(ErrorCode.TRUNCATED_RESPONSE, "生成内容被截断；输出上限包含推理 tokens")
                "content_filter" -> tokens.problem(ErrorCode.INVALID_RESPONSE, "服务商过滤了本次内容")
                else -> tokens.problem(ErrorCode.INVALID_RESPONSE, "服务商未完成本次内容")
            }
            "failed" -> when ((raw["error"] as? JsonObject)?.text("code")) {
                "rate_limit_exceeded" -> tokens.problem(ErrorCode.PROVIDER_RATE_LIMIT, "服务商限流，可稍后重试", true)
                "server_error" -> tokens.problem(ErrorCode.PROVIDER_UNAVAILABLE, "服务商暂不可用", true)
                "invalid_prompt" -> tokens.problem(ErrorCode.VALIDATION, "服务商拒绝了本次输入或参数，请检查模型配置")
                else -> tokens.problem(ErrorCode.PROVIDER_UNAVAILABLE, "服务商未能完成本次响应")
            }
            "queued", "in_progress" -> tokens.problem(ErrorCode.OUTCOME_UNKNOWN, "服务商仍在处理本次请求；结果未知，请明确选择重试")
            "cancelled" -> tokens.problem(ErrorCode.CANCELLED, "服务商取消了本次响应")
            else -> tokens.problem(ErrorCode.INVALID_RESPONSE, "响应缺少有效的完成状态")
        }
        val output = raw["output"] as? JsonArray ?: tokens.problem(ErrorCode.INVALID_RESPONSE, "响应缺少输出内容")
        val text = buildString {
            for (element in output) {
                val item = element as? JsonObject ?: tokens.problem(ErrorCode.INVALID_RESPONSE, "输出项格式无效")
                // Reasoning and tool items are not assistant text; output_text is an SDK helper, not the wire format.
                if (item.text("type") != "message") continue
                if (item.text("role") != "assistant" || item.text("status") != "completed") tokens.problem(ErrorCode.INVALID_RESPONSE, "响应包含未完成或无效的消息")
                val parts = item["content"] as? JsonArray ?: tokens.problem(ErrorCode.INVALID_RESPONSE, "消息缺少内容")
                for (elementPart in parts) {
                    val part = elementPart as? JsonObject ?: tokens.problem(ErrorCode.INVALID_RESPONSE, "消息内容格式无效")
                    when (part.text("type")) {
                        "refusal" -> tokens.problem(ErrorCode.INVALID_RESPONSE, "模型拒绝生成本次内容")
                        "output_text" -> append(part.text("text") ?: tokens.problem(ErrorCode.INVALID_RESPONSE, "消息文本格式无效"))
                        else -> tokens.problem(ErrorCode.INVALID_RESPONSE, "消息包含不支持的内容类型")
                    }
                }
            }
        }
        return text.takeIf { it.isNotBlank() } ?: tokens.problem(ErrorCode.INVALID_RESPONSE, "模型未返回文本")
    }
    override fun cancel(operationId: Id) { calls[operationId]?.cancel() }
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.tokens(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
private data class TokenUsage(val input: Long?, val output: Long?) {
    fun problem(code: ErrorCode, text: String, retry: Boolean = false): Nothing = throw ProviderException(Fault(code, text, retry), input, output)
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
