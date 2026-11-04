package dev.wordprism.memory

import dev.wordprism.contract.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable data class Review(val rating: Int, val delta_t: Int)
@Serializable data class History(val reviews: List<Review>)
@Serializable data class MemoryOutcome(val memory: MemoryState, val interval: Float)
@Serializable data class MemoryMetrics(val log_loss: Float, val rmse_bins: Float)

/** Implemented only by the packaged fsrs-rs JNI core in production. */
interface MemoryKernel {
    fun defaults(): List<Float>
    fun validate(weights: List<Float>)
    fun schedule(weights: List<Float>, state: MemoryState?, elapsedDays: Int, rating: Rating, retention: Float): MemoryOutcome
    fun fit(histories: List<History>, token: Long): List<Float>
    fun evaluate(weights: List<Float>, histories: List<History>): MemoryMetrics
    fun cancel(token: Long)
}

class NativeMemoryKernel : MemoryKernel {
    companion object { init { System.loadLibrary("wordprism_memory") } }
    private external fun invokeNative(request: String): String
    private external fun cancelNative(token: Long)

    private inline fun <reified T> invoke(kind: String, vararg fields: Pair<String, JsonElement>): T {
        val response = Contract.json.parseToJsonElement(invokeNative(buildJsonObject {
            put("kind", kind); fields.forEach { (k, v) -> put(k, v) }
        }.toString())).jsonObject
        response["error"]?.let { throw IllegalArgumentException(it.jsonPrimitive.content) }
        return Contract.json.decodeFromJsonElement(response.getValue("value"))
    }
    override fun defaults(): List<Float> = invoke("defaults")
    override fun validate(weights: List<Float>) { invoke<List<Float>>("validate", "weights" to Contract.json.encodeToJsonElement(weights)) }
    override fun schedule(weights: List<Float>, state: MemoryState?, elapsedDays: Int, rating: Rating, retention: Float): MemoryOutcome =
        invoke("schedule", "weights" to Contract.json.encodeToJsonElement(weights), "memory" to Contract.json.encodeToJsonElement(state),
            "elapsedDays" to JsonPrimitive(elapsedDays), "rating" to JsonPrimitive(rating.ordinal + 1), "retention" to JsonPrimitive(retention))
    override fun fit(histories: List<History>, token: Long): List<Float> = invoke("fit", "items" to Contract.json.encodeToJsonElement(histories), "token" to JsonPrimitive(token))
    override fun evaluate(weights: List<Float>, histories: List<History>): MemoryMetrics =
        invoke("evaluate", "weights" to Contract.json.encodeToJsonElement(weights), "items" to Contract.json.encodeToJsonElement(histories))
    override fun cancel(token: Long) = cancelNative(token)
}
