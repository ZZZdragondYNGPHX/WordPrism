package dev.wordprism.engine

import dev.wordprism.contract.*
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

internal fun id() = Id(UUID.randomUUID().toString())
class BusinessException(val fault: Fault) : RuntimeException(fault.message)
class StorageException(cause: Throwable) : java.io.IOException("Local storage failure", cause)
internal fun fail(code: ErrorCode, message: String, retryable: Boolean = false): Nothing = throw BusinessException(Fault(code, message, retryable))
internal fun requireBusiness(condition: Boolean, message: String, code: ErrorCode = ErrorCode.VALIDATION) { if (!condition) fail(code, message) }
internal fun <T> T?.found(name: String): T = this ?: fail(ErrorCode.NOT_FOUND, "$name 不存在")

@Serializable data class SavedRequest(val fingerprint: String, val view: View)
@Serializable data class UndoEntry(val bucket: String, val key: String, val before: JsonElement?, val after: JsonElement)
@Serializable data class JobData(
    val command: Command, val expectedEntities: Map<String, Long> = emptyMap(),
    val response: JsonElement? = null, val lease: Id? = null, val fitToken: Long? = null,
    val notBeforeMs: Long = 0, val retryCycleStartAttempt: Int = 0,
)
@Serializable data class StoredPreview(val preview: PackagePreview, val manifest: PackageManifest, val modules: Map<String, JsonElement>)
@Serializable data class ArchivedModule(val version: Int, val required: Boolean, val dependencies: List<String>, val data: JsonElement)
@Serializable data class TimeSpan(val id: Id, val sessionId: Id, val dayId: Id, val mode: TimerMode, val fromMs: Long, val toMs: Long)

/** All writes happen within StateStore.transaction; a failed block must roll back entirely. */
@Serializable data class Database(
    var schemaVersion: Int = 2, var libraryId: Id = id(), var revision: Long = 0, var sequence: Long = 0,
    var settings: UserSettings = UserSettings(), var policy: TrainingPolicy = TrainingPolicy(),
    var lastAnalyzedSequence: Long = 0, var lastAutoExecutionAtMs: Long? = null,
    var lastAutoExecutionDate: String? = null,
    val words: MutableMap<Id, Word> = linkedMapOf(),
    val senses: MutableMap<Id, Sense> = linkedMapOf(),
    val groups: MutableMap<Id, Group> = linkedMapOf(),
    val memberships: MutableMap<Id, Membership> = linkedMapOf(),
    val targets: MutableMap<Id, LearningTarget> = linkedMapOf(),
    val schedules: MutableMap<Id, Schedule> = linkedMapOf(),
    val activities: MutableMap<Id, Activity> = linkedMapOf(),
    val activityVersions: MutableMap<String, Activity> = linkedMapOf(),
    val answers: MutableMap<Id, Answer> = linkedMapOf(),
    val answerVersions: MutableMap<String, Answer> = linkedMapOf(),
    val presentations: MutableMap<Id, Id> = linkedMapOf(),
    val exposures: MutableMap<Id, Exposure> = linkedMapOf(),
    val feedback: MutableMap<Id, Feedback> = linkedMapOf(),
    val difficultyMarks: MutableMap<Id, DifficultyMark> = linkedMapOf(),
    val sessions: MutableMap<Id, Session> = linkedMapOf(),
    val timeSpans: MutableMap<Id, TimeSpan> = linkedMapOf(),
    val days: MutableMap<Id, LearningDay> = linkedMapOf(),
    val plans: MutableMap<Id, LearningPlan> = linkedMapOf(),
    val drafts: MutableMap<String, Draft> = linkedMapOf(),
    val policyVersions: MutableMap<Long, TrainingPolicy> = linkedMapOf(1L to TrainingPolicy()),
    val changes: MutableMap<Id, PolicyChange> = linkedMapOf(),
    val snapshots: MutableMap<Id, LearningSnapshot> = linkedMapOf(),
    val evaluations: MutableMap<Id, Evaluation> = linkedMapOf(),
    val presets: MutableMap<Id, ApiPreset> = linkedMapOf(),
    /** Device-only references; intentionally absent from all portable package modules. */
    val credentialRefs: MutableMap<Id, Id> = linkedMapOf(),
    val presetVersions: MutableMap<String, ApiPreset> = linkedMapOf(),
    val bindings: MutableMap<Feature, Binding> = linkedMapOf(),
    val usage: MutableMap<Id, Usage> = linkedMapOf(),
    val recommendations: MutableMap<Id, Recommendation> = linkedMapOf(),
    val operations: MutableMap<Id, Operation> = linkedMapOf(),
    val jobs: MutableMap<Id, JobData> = linkedMapOf(),
    val requests: MutableMap<String, SavedRequest> = linkedMapOf(),
    val undos: MutableMap<Id, List<UndoEntry>> = linkedMapOf(),
    val previews: MutableMap<Id, StoredPreview> = linkedMapOf(),
    val importedPackages: MutableMap<Id, Id> = linkedMapOf(),
    val archivedModules: MutableMap<String, ArchivedModule> = linkedMapOf(),
)

interface StateStore : AutoCloseable {
    suspend fun read(): Database
    suspend fun <T> transaction(block: (Database) -> T): T
    fun revisions(): Flow<Long>
}

interface CredentialStore {
    fun has(presetId: Id): Boolean
    fun read(presetId: Id): CharArray?
    fun write(presetId: Id, secret: CharArray)
    fun delete(presetId: Id)
}

/** Physical stores share the same row codec and migration, without duplicating business models. */
object StateCodec {
    data class Row(val bucket: String, val key: String, val payload: String)
    private val scalar = setOf("schemaVersion", "libraryId", "revision", "sequence", "settings", "policy", "lastAnalyzedSequence", "lastAutoExecutionAtMs", "lastAutoExecutionDate")
    fun rows(db: Database): List<Row> = Contract.json.encodeToJsonElement(db).jsonObject.flatMap { (bucket, value) ->
        if (bucket in scalar) listOf(Row("_root", bucket, value.toString()))
        else value.jsonObject.map { (key, item) -> Row(bucket, key, item.toString()) }
    }
    fun decode(rows: List<Row>): Database {
        if (rows.isEmpty()) return Database()
        val root = mutableMapOf<String, JsonElement>()
        rows.groupBy { it.bucket }.forEach { (bucket, values) ->
            if (bucket == "_root") values.forEach { root[it.key] = Contract.json.parseToJsonElement(it.payload) }
            else root[bucket] = JsonObject(values.associate { it.key to Contract.json.parseToJsonElement(it.payload) })
        }
        return decodeJson(JsonObject(root))
    }
    fun decodeJson(input: JsonObject): Database {
        val version = input["schemaVersion"]?.jsonPrimitive?.int ?: 1
        requireBusiness(version in 1..2, "不支持此数据库版本", ErrorCode.UNSUPPORTED_VERSION)
        val migrated = if (version == 1) JsonObject(input + ("schemaVersion" to JsonPrimitive(2))) else input
        return Contract.json.decodeFromJsonElement(migrated)
    }
    fun clone(db: Database): Database = Contract.json.decodeFromString(Contract.json.encodeToString(db))
}

internal fun Activity.card() = ActivityCard(id, version, targetId, kind, prompt, choices, contextKey,
    passage = passage, question = question, sourceSentence = sourceSentence, source = source)
internal fun Database.card(activity: Activity): ActivityCard {
    val target = targets[activity.targetId]; val sense = senses[target?.senseId]; val word = words[sense?.wordId]
    return activity.card().copy(groupId = target?.groupId,
        targetWords = if (activity.kind in setOf(ActivityKind.WRITING, ActivityKind.REWRITE, ActivityKind.FAMILIARITY)) activity.targetWords.ifEmpty { listOfNotNull(word?.spelling) } else emptyList(),
        cue = activity.cue ?: sense?.meaning, hintAllowed = activity.kind !in introductionKinds)
}
internal fun Database.version(key: String): Long? {
    val parts = key.split(':', limit = 2); val identity = Id(parts[1])
    return when (parts[0]) { "group" -> groups[identity]?.version; "sense" -> senses[identity]?.version; "answer" -> answers[identity]?.version; else -> null }
}
