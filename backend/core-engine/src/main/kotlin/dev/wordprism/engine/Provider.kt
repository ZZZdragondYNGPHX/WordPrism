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
    suspend fun listModels(request: ModelCatalogRequest, secret: CharArray): List<ApiModel>
    fun cancel(operationId: Id)
}

internal fun validateConnection(baseUrl: String, protocol: String, timeoutSeconds: Int, allowLocalHttp: Boolean = false): HttpUrl {
    val url = baseUrl.toHttpUrlOrNull() ?: fail(ErrorCode.VALIDATION, "API 地址无效")
    requireBusiness(url.isHttps || allowLocalHttp && url.host in setOf("localhost", "127.0.0.1", "::1"), "API 必须使用 HTTPS")
    requireBusiness(url.toUri().rawUserInfo == null && url.query == null && url.fragment == null, "地址不能包含凭证、查询或片段")
    requireBusiness(ApiProtocols.supported.any { it.id == protocol }, "暂不支持此协议", ErrorCode.UNSUPPORTED_VERSION)
    requireBusiness(timeoutSeconds in 5..180, "超时范围为 5–180 秒")
    // Users often paste a complete endpoint from provider docs. Strip only a known terminal
    // endpoint, preserving the origin and custom path prefix for both inference and /models.
    val path = url.encodedPath.trimEnd('/')
    val endpoint = listOf("/chat/completions", "/responses", "/models").firstOrNull { path.endsWith(it) }
    val rootPath = endpoint?.let { path.removeSuffix(it) } ?: path
    return url.newBuilder().encodedPath(rootPath.ifEmpty { "/" }).build()
}

internal fun validatePreset(preset: ApiPreset, allowLocalHttp: Boolean = false): HttpUrl {
    val root = validateConnection(preset.baseUrl, preset.protocol, preset.timeoutSeconds, allowLocalHttp)
    requireBusiness(preset.name.isNotBlank() && preset.name.length <= 120 && preset.model.isNotBlank() && preset.model.length <= 200, "预设名称或模型无效")
    requireBusiness(preset.maxOutputTokens in 64..16384 && (preset.temperature?.let { it.isFinite() && it in 0f..2f } != false), "预设参数超出支持范围")
    requireBusiness(preset.reasoningEffort == null || preset.reasoningEffort in ApiProtocols.supported.first { it.id == preset.protocol }.reasoningEfforts,
        "此协议不支持该思维程度")
    return root
}

class OpenAiCompatibleProvider(private val allowLocalHttp: Boolean = false) : LlmProvider {
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    private val calls = ConcurrentHashMap<Id, Call>()
    override suspend fun generate(request: ProviderRequest, secret: CharArray): ProviderResponse {
        try {
            val root = validatePreset(request.preset, allowLocalHttp)
            val preset = request.preset
            val body = encode(request).toString()
            requireBusiness(body.toByteArray(Charsets.UTF_8).size <= 256 * 1024, "本次上下文过大", ErrorCode.LIMIT_EXCEEDED)
            val path = when (preset.protocol) {
                ApiProtocols.OPENAI_RESPONSES -> "/responses"
                else -> "/chat/completions"
            }
            val http = okhttp3.Request.Builder().url(root.toString().trimEnd('/') + path)
                .header("Authorization", "Bearer " + String(secret)).post(body.toRequestBody("application/json".toMediaType())).build()
            secret.fill('\u0000')
            return exchange(http, preset.timeoutSeconds, request.operationId) { parse(it, preset.protocol) }
        } finally { secret.fill('\u0000') }
    }

    override suspend fun listModels(request: ModelCatalogRequest, secret: CharArray): List<ApiModel> = try {
        val root = validateConnection(request.baseUrl, request.protocol, request.timeoutSeconds, allowLocalHttp)
        val http = okhttp3.Request.Builder().url(root.toString().trimEnd('/') + "/models")
            .header("Authorization", "Bearer " + String(secret)).header("Accept", "application/json").get().build()
        secret.fill('\u0000')
        exchange(http, request.timeoutSeconds, metadata = true, parse = ::parseModels)
    } finally { secret.fill('\u0000') }

    private suspend fun <T> exchange(http: okhttp3.Request, timeoutSeconds: Int, operationId: Id? = null,
        metadata: Boolean = false, parse: (Response) -> T): T {
        // OkHttp defaults each I/O stage to 10 seconds independently of callTimeout.
        // Apply the user's budget to every stage and retain one deadline for the whole request.
        val timeout = timeoutSeconds.toLong()
        val call = client.newBuilder().callTimeout(timeout, TimeUnit.SECONDS)
            .connectTimeout(timeout, TimeUnit.SECONDS).readTimeout(timeout, TimeUnit.SECONDS)
            .writeTimeout(timeout, TimeUnit.SECONDS).build().newCall(http)
        operationId?.let { calls[it] = call }
        fun failure(e: IOException): ProviderException {
            if (e is ProviderException) return e
            val fault = if (metadata) {
                if (e is InterruptedIOException) Fault(ErrorCode.PROVIDER_TIMEOUT, "拉取模型超时（上限 $timeoutSeconds 秒），请重试", true)
                else Fault(ErrorCode.PROVIDER_UNAVAILABLE, "无法拉取模型，请检查网络和服务地址后重试", true)
            } else {
                if (e is InterruptedIOException) Fault(ErrorCode.PROVIDER_TIMEOUT, "请求超时（上限 $timeoutSeconds 秒）；服务商是否完成未知，请明确选择重试")
                else Fault(ErrorCode.OUTCOME_UNKNOWN, "连接中断；服务商是否完成未知，请明确选择重试")
            }
            return ProviderException(fault)
        }
        try {
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(failure(e))
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching { response.use(parse) }
                        if (continuation.isActive) result.fold({ continuation.resume(it) }, {
                            continuation.resumeWithException(if (it is IOException) failure(it) else it)
                        })
                    }
                })
            }
        } finally { operationId?.let { calls.remove(it, call) } }
    }

    private fun encode(request: ProviderRequest): JsonObject = buildJsonObject {
        val preset = request.preset
        put("model", preset.model); put("stream", false)
        preset.temperature?.let { put("temperature", it) }
        when (preset.protocol) {
            ApiProtocols.OPENAI_RESPONSES -> {
                put("max_output_tokens", preset.maxOutputTokens)
                preset.reasoningEffort?.let { effort -> putJsonObject("reasoning") { put("effort", effort.name.lowercase()) } }
                put("store", false)
                put("instructions", request.system)
                // Responses validates JSON mode against input messages, not only instructions.
                put("input", if (preset.jsonMode) "Return a JSON object for the following task data:\n${request.data}" else request.data.toString())
                if (preset.jsonMode) putJsonObject("text") { putJsonObject("format") { put("type", "json_object") } }
            }
            else -> {
                // Explicit effort opts into the reasoning-capable limit; legacy presets keep their wire format.
                put(if (preset.reasoningEffort == null) "max_tokens" else "max_completion_tokens", preset.maxOutputTokens)
                preset.reasoningEffort?.let { put("reasoning_effort", it.name.lowercase()) }
                if (preset.jsonMode) putJsonObject("response_format") { put("type", "json_object") }
                putJsonArray("messages") {
                    addJsonObject { put("role", "system"); put("content", request.system) }
                    addJsonObject { put("role", "user"); put("content", request.data.toString()) }
                }
            }
        }
    }

    private fun readJson(response: Response): JsonObject? {
        val body = response.body ?: throw ProviderException(Fault(ErrorCode.INVALID_RESPONSE, "响应没有内容"))
        if (body.contentLength() > 2 * 1024 * 1024) throw ProviderException(Fault(ErrorCode.LIMIT_EXCEEDED, "响应超过 2 MiB"))
        val source = body.source(); val bytes = source.readAtMost(2 * 1024 * 1024 + 1)
        if (bytes.size > 2 * 1024 * 1024) throw ProviderException(Fault(ErrorCode.LIMIT_EXCEEDED, "响应超过 2 MiB"))
        return runCatching { Contract.json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
    }

    private fun parseModels(response: Response): List<ApiModel> {
        val raw = readJson(response)
        val errors = TokenUsage(null, null)
        if (response.code in setOf(404, 405)) errors.problem(ErrorCode.PROVIDER_UNAVAILABLE, "服务商未提供 /models 列表，请手动填写模型标识")
        checkStatus(response, errors, raw)
        val models = raw?.get("data") as? JsonArray ?: errors.problem(ErrorCode.INVALID_RESPONSE, "服务商未返回有效的模型列表")
        if (models.size > 10000) errors.problem(ErrorCode.LIMIT_EXCEEDED, "模型列表超过 10000 项，请手动填写模型标识")
        return models.map { element ->
            val item = element as? JsonObject ?: errors.problem(ErrorCode.INVALID_RESPONSE, "模型列表包含无效项")
            val id = item.text("id")?.takeIf { it.isNotBlank() && it.length <= 200 && it.none(Char::isISOControl) }
                ?: errors.problem(ErrorCode.INVALID_RESPONSE, "模型列表包含无效的模型标识")
            ApiModel(id, item.text("owned_by")?.takeIf { it.isNotBlank() && it.length <= 200 && it.none(Char::isISOControl) })
        }.distinctBy { it.id }.sortedBy { it.id }
    }

    private fun parse(response: Response, protocol: String): ProviderResponse {
        val raw = readJson(response)
        val usage = raw?.get("usage") as? JsonObject
        val native = protocol == ApiProtocols.OPENAI_RESPONSES
        val tokens = TokenUsage(usage?.tokens(if (native) "input_tokens" else "prompt_tokens"),
            usage?.tokens(if (native) "output_tokens" else "completion_tokens"))
        checkStatus(response, tokens, raw)
        if (raw == null) tokens.problem(ErrorCode.INVALID_RESPONSE, "服务商未返回有效 JSON 对象")
        val content = if (native) responsesText(raw, tokens) else chatText(raw, tokens)
        val parsed = runCatching { Contract.json.parseToJsonElement(content) as? JsonObject }.getOrNull()
            ?: tokens.problem(ErrorCode.INVALID_RESPONSE, "模型未返回有效 JSON 对象")
        return ProviderResponse(parsed, tokens.input, tokens.output)
    }

    private fun checkStatus(response: Response, tokens: TokenUsage, raw: JsonObject?) {
        // Provider messages can echo credentials or study text. Only recognized diagnostic fields
        // and locally worded causes are persisted or shown; arbitrary raw error strings are not.
        val error = raw?.get("error") as? JsonObject
        val parameter = error?.text("param")?.takeIf { it in diagnosticParameters }
        val code = error?.text("code")?.takeIf { it in diagnosticCodes }
        val type = error?.text("type")?.takeIf { it in diagnosticTypes }
        val details = listOfNotNull(parameter?.let { "参数 $it" }, code, type).joinToString("；")
        fun problem(localCode: ErrorCode, message: String, retryable: Boolean = false): Nothing =
            tokens.problem(localCode, message + if (details.isBlank()) "" else "（$details）", retryable)
        if (!response.isSuccessful) when (response.code) {
            400, 422 -> {
                val cause = when {
                    parameter == "input" && error?.text("message")?.let { it.contains("json", true) && it.contains("json_object", true) } == true ->
                        "JSON 模式要求 input 消息包含 JSON 输出指令，请更新应用后重试"
                    parameter in setOf("reasoning.effort", "reasoning_effort") -> "服务商拒绝所选思维程度，请调整并保存后重试"
                    code == "unsupported_parameter" || code == "unsupported_value" -> "模型不支持所选参数或取值，请检查预设配置"
                    else -> "服务商拒绝请求参数，请检查模型与预设配置"
                }
                problem(ErrorCode.VALIDATION, "$cause（HTTP ${response.code}）")
            }
            401 -> problem(ErrorCode.PROVIDER_AUTH, "认证失败（HTTP 401）：请确认使用该服务商的 API 密钥，并已保存")
            403 -> problem(ErrorCode.PROVIDER_AUTH, "访问被拒绝（HTTP 403）：请检查密钥权限、模型访问权限或服务地区限制")
            429 -> problem(ErrorCode.PROVIDER_RATE_LIMIT, "服务商限流，可稍后重试", true)
            404 -> problem(ErrorCode.PROVIDER_UNAVAILABLE, "接口或模型不存在（HTTP 404），请检查服务地址、协议和模型标识")
            in 500..599 -> problem(ErrorCode.PROVIDER_UNAVAILABLE, "服务商暂不可用", true)
            else -> problem(ErrorCode.PROVIDER_UNAVAILABLE, "服务商返回 HTTP ${response.code}")
        }
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
                "max_output_tokens", "max_messages" -> tokens.problem(ErrorCode.TRUNCATED_RESPONSE,
                    "生成内容被额度截断，未保存不完整内容；输出上限包含推理 tokens，请提高输出上限或降低思维程度，保存后重试")
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

private val diagnosticParameters = setOf("input", "instructions", "model", "reasoning", "reasoning.effort", "reasoning_effort",
    "text.format", "text.format.type", "response_format", "response_format.type", "temperature", "max_tokens", "max_completion_tokens", "max_output_tokens", "store", "stream")
private val diagnosticCodes = setOf("invalid_api_key", "insufficient_quota", "model_not_found", "unsupported_country_region_territory",
    "unsupported_parameter", "unsupported_value", "invalid_value", "context_length_exceeded", "invalid_request_error", "rate_limit_exceeded", "server_error")
private val diagnosticTypes = setOf("invalid_request_error", "authentication_error", "permission_error", "rate_limit_error", "server_error")

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
@Serializable internal data class ExplanationProposal(val content: ExplanationContent, val recommendations: List<RecommendationSeed> = emptyList())
@Serializable internal data class AnalysisProposal(val patch: PolicyPatch, val actions: List<Command> = emptyList())
