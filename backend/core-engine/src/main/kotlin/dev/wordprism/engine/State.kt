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
    /** Durable preparation checkpoint. A failed/cancelled step is not silently resubmitted. */
    val managedKey: String? = null,
    val managedCredentialRef: Id? = null,
    val tutorReport: TutorReport? = null,
)
@Serializable data class StoredPreview(val preview: PackagePreview, val manifest: PackageManifest, val modules: Map<String, JsonElement>)
@Serializable data class ArchivedModule(val version: Int, val required: Boolean, val dependencies: List<String>, val data: JsonElement)
@Serializable data class TimeSpan(val id: Id, val sessionId: Id, val dayId: Id, val mode: TimerMode, val fromMs: Long, val toMs: Long,
    val targetId: Id? = null)

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
    /** Encode once per transaction; changing the revision needs only one scalar row. */
    fun rowsForCommit(db: Database, before: Map<Pair<String, String>, String>): List<Row>? {
        val after = rows(db)
        if (after.size == before.size && after.all { before[it.bucket to it.key] == it.payload }) return null
        db.revision++
        return after.map { if (it.bucket == "_root" && it.key == "revision") it.copy(payload = db.revision.toString()) else it }
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
    /** Stored rows are immutable values; only scalar properties and top-level maps are writable. */
    fun fork(db: Database): Database = db.copy(
        words = db.words.toMutableMap(),
        senses = db.senses.toMutableMap(),
        groups = db.groups.toMutableMap(),
        memberships = db.memberships.toMutableMap(),
        targets = db.targets.toMutableMap(),
        schedules = db.schedules.toMutableMap(),
        activities = db.activities.toMutableMap(),
        activityVersions = db.activityVersions.toMutableMap(),
        answers = db.answers.toMutableMap(),
        answerVersions = db.answerVersions.toMutableMap(),
        presentations = db.presentations.toMutableMap(),
        exposures = db.exposures.toMutableMap(),
        feedback = db.feedback.toMutableMap(),
        difficultyMarks = db.difficultyMarks.toMutableMap(),
        sessions = db.sessions.toMutableMap(),
        timeSpans = db.timeSpans.toMutableMap(),
        days = db.days.toMutableMap(),
        plans = db.plans.toMutableMap(),
        drafts = db.drafts.toMutableMap(),
        policyVersions = db.policyVersions.toMutableMap(),
        changes = db.changes.toMutableMap(),
        snapshots = db.snapshots.toMutableMap(),
        evaluations = db.evaluations.toMutableMap(),
        presets = db.presets.toMutableMap(),
        credentialRefs = db.credentialRefs.toMutableMap(),
        presetVersions = db.presetVersions.toMutableMap(),
        bindings = db.bindings.toMutableMap(),
        usage = db.usage.toMutableMap(),
        recommendations = db.recommendations.toMutableMap(),
        operations = db.operations.toMutableMap(),
        jobs = db.jobs.toMutableMap(),
        requests = db.requests.toMutableMap(),
        undos = db.undos.toMutableMap(),
        previews = db.previews.toMutableMap(),
        importedPackages = db.importedPackages.toMutableMap(),
        archivedModules = db.archivedModules.toMutableMap(),
    )

    data class Changes(val writes: List<Row>, val deletes: List<Pair<String, String>>)
    /** Serialize changed records only; large histories and undo payloads stay untouched. */
    fun changes(before: Database, after: Database): Changes? {
        val deletes = mutableListOf<Pair<String, String>>()
        fun key(value: Any?): String = when (value) { is Id -> value.value; is Enum<*> -> value.name; else -> value.toString() }
        fun <K, V> delta(bucket: String, old: Map<K, V>, next: Map<K, V>): MutableMap<K, V> {
            old.keys.filter { it !in next }.forEach { deletes += bucket to key(it) }
            return next.filter { (k, v) -> old[k] != v || k !in old }.toMutableMap()
        }
        val sparse = after.copy(
            words = delta("words", before.words, after.words),
            senses = delta("senses", before.senses, after.senses),
            groups = delta("groups", before.groups, after.groups),
            memberships = delta("memberships", before.memberships, after.memberships),
            targets = delta("targets", before.targets, after.targets),
            schedules = delta("schedules", before.schedules, after.schedules),
            activities = delta("activities", before.activities, after.activities),
            activityVersions = delta("activityVersions", before.activityVersions, after.activityVersions),
            answers = delta("answers", before.answers, after.answers),
            answerVersions = delta("answerVersions", before.answerVersions, after.answerVersions),
            presentations = delta("presentations", before.presentations, after.presentations),
            exposures = delta("exposures", before.exposures, after.exposures),
            feedback = delta("feedback", before.feedback, after.feedback),
            difficultyMarks = delta("difficultyMarks", before.difficultyMarks, after.difficultyMarks),
            sessions = delta("sessions", before.sessions, after.sessions),
            timeSpans = delta("timeSpans", before.timeSpans, after.timeSpans),
            days = delta("days", before.days, after.days),
            plans = delta("plans", before.plans, after.plans),
            drafts = delta("drafts", before.drafts, after.drafts),
            policyVersions = delta("policyVersions", before.policyVersions, after.policyVersions),
            changes = delta("changes", before.changes, after.changes),
            snapshots = delta("snapshots", before.snapshots, after.snapshots),
            evaluations = delta("evaluations", before.evaluations, after.evaluations),
            presets = delta("presets", before.presets, after.presets),
            credentialRefs = delta("credentialRefs", before.credentialRefs, after.credentialRefs),
            presetVersions = delta("presetVersions", before.presetVersions, after.presetVersions),
            bindings = delta("bindings", before.bindings, after.bindings),
            usage = delta("usage", before.usage, after.usage),
            recommendations = delta("recommendations", before.recommendations, after.recommendations),
            operations = delta("operations", before.operations, after.operations),
            jobs = delta("jobs", before.jobs, after.jobs),
            requests = delta("requests", before.requests, after.requests),
            undos = delta("undos", before.undos, after.undos),
            previews = delta("previews", before.previews, after.previews),
            importedPackages = delta("importedPackages", before.importedPackages, after.importedPackages),
            archivedModules = delta("archivedModules", before.archivedModules, after.archivedModules),
        )
        val previousRoot = rows(before.copy(
            words = linkedMapOf(),
            senses = linkedMapOf(),
            groups = linkedMapOf(),
            memberships = linkedMapOf(),
            targets = linkedMapOf(),
            schedules = linkedMapOf(),
            activities = linkedMapOf(),
            activityVersions = linkedMapOf(),
            answers = linkedMapOf(),
            answerVersions = linkedMapOf(),
            presentations = linkedMapOf(),
            exposures = linkedMapOf(),
            feedback = linkedMapOf(),
            difficultyMarks = linkedMapOf(),
            sessions = linkedMapOf(),
            timeSpans = linkedMapOf(),
            days = linkedMapOf(),
            plans = linkedMapOf(),
            drafts = linkedMapOf(),
            policyVersions = linkedMapOf(),
            changes = linkedMapOf(),
            snapshots = linkedMapOf(),
            evaluations = linkedMapOf(),
            presets = linkedMapOf(),
            credentialRefs = linkedMapOf(),
            presetVersions = linkedMapOf(),
            bindings = linkedMapOf(),
            usage = linkedMapOf(),
            recommendations = linkedMapOf(),
            operations = linkedMapOf(),
            jobs = linkedMapOf(),
            requests = linkedMapOf(),
            undos = linkedMapOf(),
            previews = linkedMapOf(),
            importedPackages = linkedMapOf(),
            archivedModules = linkedMapOf(),
        )).associate { it.key to it.payload }
        val writes = rows(sparse).filter { it.bucket != "_root" || previousRoot[it.key] != it.payload }.toMutableList()
        if (writes.isEmpty() && deletes.isEmpty()) return null
        after.revision = before.revision + 1
        writes.removeAll { it.bucket == "_root" && it.key == "revision" }
        writes += Row("_root", "revision", after.revision.toString())
        return Changes(writes, deletes)
    }

    fun clone(db: Database): Database = Contract.json.decodeFromString(Contract.json.encodeToString(db))
}

internal fun Activity.card() = ActivityCard(id, version, targetId, kind, prompt, choices, contextKey,
    passage = passage, question = question, sourceSentence = sourceSentence, source = source)
internal fun Database.card(activity: Activity): ActivityCard {
    val target = targets[activity.targetId]; val sense = senses[target?.senseId]; val word = words[sense?.wordId]
    return activity.card().copy(groupId = target?.groupId,
        targetWords = if (activity.kind in setOf(ActivityKind.WRITING, ActivityKind.REWRITE, ActivityKind.FAMILIARITY)) activity.targetWords.ifEmpty { listOfNotNull(word?.spelling) } else emptyList(),
        cue = activity.cue ?: sense?.meaning, hintAllowed = activity.kind !in introductionKinds,
        introduction = if (activity.kind == ActivityKind.CONTRAST && sense != null && word != null)
            SenseIntroduction(word.spelling, sense.partOfSpeech, sense.meaning, sense.examples,
                if (settings.learningMode == LearningMode.AI_MANAGED) explanation(sense)?.takeIf { it.basedOnSenseVersion == sense.version }?.content else null) else null)
}
internal fun Database.version(key: String): Long? {
    val parts = key.split(':', limit = 2); val identity = Id(parts[1])
    return when (parts[0]) { "group" -> groups[identity]?.version; "sense" -> senses[identity]?.version; "answer" -> answers[identity]?.version; else -> null }
}
