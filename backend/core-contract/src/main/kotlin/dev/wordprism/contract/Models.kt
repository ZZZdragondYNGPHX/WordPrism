package dev.wordprism.contract

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Sole source for local SDK and JSON bridge types. Times are UTC epoch milliseconds. */
object Contract {
    const val VERSION = 1
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; classDiscriminator = "type" }
}

@Serializable @JvmInline value class Id(val value: String)
@Serializable data class Ref(val id: Id, val version: Long)
@Serializable enum class ErrorCode {
    VALIDATION, NOT_FOUND, VERSION_CONFLICT, IDEMPOTENCY_CONFLICT, PRESET_MISSING,
    CREDENTIAL_MISSING, OFFLINE, BUDGET_EXCEEDED, DISABLED, CANCELLED, PROVIDER_TIMEOUT,
    PROVIDER_RATE_LIMIT, PROVIDER_AUTH, PROVIDER_UNAVAILABLE, INVALID_RESPONSE,
    TRUNCATED_RESPONSE, OUTCOME_UNKNOWN, UNSUPPORTED_VERSION, INVALID_PACKAGE,
    LIMIT_EXCEEDED, UNKNOWN_CAPABILITY, INSUFFICIENT_DATA, UNSUPPORTED_ALGORITHM, STORAGE
}
@Serializable data class Fault(val code: ErrorCode, val message: String, val retryable: Boolean = false)
@Serializable enum class OperationStatus {
    QUEUED, WAITING_NETWORK, NEEDS_CONFIGURATION, RUNNING, NEEDS_INPUT,
    SUCCEEDED, FAILED, CANCELLED, SUPERSEDED, RESTORED_PENDING
}
@Serializable enum class Feature { WORD_INTAKE, WORD_RECOMMENDATION, EXPLANATION, PRACTICE_GENERATION, WRITING_FEEDBACK, LEARNING_ANALYSIS }
@Serializable enum class Actor { USER, AI, SYSTEM }
@Serializable enum class Relation { LOOK_ALIKE, SYNONYM, WORD_FAMILY, SOUND_ALIKE, PERSONAL }
@Serializable enum class Ability { RECOGNITION, RECALL, SPELLING, READING, EXPRESSION }
@Serializable enum class ActivityKind { CHOICE, RECALL, SPELLING, PASSAGE, WRITING, REWRITE, FAMILIARITY, CONTRAST }
@Serializable enum class GradeSource { OBJECTIVE, USER, AI, UNGRADED }
@Serializable enum class Verdict { CORRECT, INCORRECT, UNGRADED, DISPUTED, SKIPPED }
@Serializable enum class Rating { AGAIN, HARD, GOOD, EASY }
@Serializable enum class SessionStatus { ACTIVE, PAUSED, FINISHED }
@Serializable enum class DayEndReason { USER_FINISHED, PLAN_COMPLETED, BUDGET_REACHED }
@Serializable enum class TimerMode { STUDY, WAITING, BACKGROUND, PAUSED }

@Serializable data class Provenance(
    val kind: String = "user", val operationId: Id? = null, val presetId: Id? = null,
    val presetVersion: Long? = null, val model: String? = null, val atMs: Long = 0,
)
@Serializable data class Word(
    val id: Id, val version: Long = 1, val spelling: String, val normalized: String,
    val notes: String = "", val tags: Set<String> = emptySet(), val archived: Boolean = false,
    val source: Provenance = Provenance(),
)
@Serializable data class Sense(
    val id: Id, val wordId: Id, val version: Long = 1, val partOfSpeech: String = "",
    val meaning: String, val acceptedForms: List<String> = emptyList(),
    val examples: List<String> = emptyList(), val source: Provenance = Provenance(),
)
@Serializable data class Group(
    val id: Id, val version: Long = 1, val title: String, val relation: Relation,
    val purpose: String, val archived: Boolean = false, val titleEdited: Boolean = false,
)
@Serializable data class Membership(
    val id: Id, val groupId: Id, val senseId: Id, val version: Long = 1,
    val reason: String, val active: Boolean = true, val source: Provenance = Provenance(),
    val position: Long = 0,
)
@Serializable data class LearningTarget(
    val id: Id, val senseId: Id, val ability: Ability, val groupId: Id? = null,
    val suspended: Boolean = false, val priority: Int = 0,
)
@Serializable data class MemoryState(val stability: Float, val difficulty: Float)
@Serializable data class Schedule(
    val targetId: Id, val memory: MemoryState? = null, val dueAtMs: Long,
    val lastReviewAtMs: Long? = null, val reviews: Int = 0,
    val algorithm: String = "fsrs-6/fsrs-rs-6.6.2", val policyVersion: Long = 1,
)
@Serializable data class Choice(val id: String, val text: String, val senseId: Id? = null)
/** One activity tests one stable target, including a passage with one aggregated answer. */
@Serializable data class Activity(
    val id: Id, val version: Long = 1, val targetId: Id, val kind: ActivityKind,
    val prompt: String, val choices: List<Choice> = emptyList(),
    val acceptedAnswers: List<String> = emptyList(), val explanation: String = "",
    val contextKey: String, val disputed: Boolean = false, val source: Provenance = Provenance(),
    val senseVersion: Long = 1, val passage: String? = null, val question: String? = null,
    val sourceSentence: String? = null,
    val cue: String? = null, val targetWords: List<String> = emptyList(),
)
@Serializable data class ActivityCard(
    val id: Id, val version: Long, val targetId: Id, val kind: ActivityKind,
    val prompt: String, val choices: List<Choice>, val contextKey: String,
    val hintAllowed: Boolean = true, val groupId: Id? = null,
    val targetWords: List<String> = emptyList(), val cue: String? = null,
    val passage: String? = null, val question: String? = null, val sourceSentence: String? = null,
    val source: Provenance = Provenance(),
)
@Serializable data class Exposure(val hintUsed: Boolean = false, val answerRevealed: Boolean = false)
@Serializable data class AnswerInput(
    val text: String, val hintUsed: Boolean = false, val answerRevealed: Boolean = false,
    val difficult: Boolean = false, val skipped: Boolean = false, val selectedChoiceId: String? = null,
    val familiar: Boolean? = null, val requestFeedback: Boolean = false,
)
@Serializable data class Answer(
    val id: Id, val sequence: Long, val sessionId: Id, val activityId: Id, val activityVersion: Long,
    val targetId: Id, val version: Long = 1, val input: AnswerInput, val atMs: Long,
    val verdict: Verdict, val gradeSource: GradeSource, val rating: Rating? = null,
    val affectsSchedule: Boolean, val policyVersion: Long, val delayed: Boolean,
    val newContext: Boolean, val confusedWith: Id? = null, val valid: Boolean = true,
    val dayId: Id? = null,
)
@Serializable data class Feedback(
    val id: Id, val answerId: Id, val answerVersion: Long, val explanation: String,
    val source: GradeSource, val targetUsage: String = "", val quotedAnswer: String = "",
    val provenance: Provenance = Provenance(),
)
@Serializable data class Draft(val key: String, val text: String, val version: Long, val atMs: Long)
@Serializable data class Session(
    val id: Id, val dayId: Id, val planId: Id, val policyVersion: Long,
    val status: SessionStatus = SessionStatus.ACTIVE, val targetIds: List<Id>,
    val completedTargets: Set<Id> = emptySet(), val currentActivityId: Id? = null,
    val currentPresentationId: Id? = null, val timerMode: TimerMode = TimerMode.PAUSED,
    val timerAtMs: Long? = null, val studyMs: Long = 0, val waitingMs: Long = 0,
    val backgroundMs: Long = 0,
    val extraBudgetDayId: Id? = null,
    val lastAnswerId: Id? = null, val awaitingAdvance: Boolean = false, val revisingAnswerId: Id? = null,
    val includeIntroductions: Boolean = false, val exposedSenseIds: Set<Id> = emptySet(),
    val lastPresentationId: Id? = null,
)
@Serializable data class LearningPlan(
    val id: Id, val dayId: Id, val policyVersion: Long, val targetIds: List<Id>,
    val newCount: Int, val reviewCount: Int, val deferredCount: Int, val estimatedMs: Long,
    val estimateSource: String, val budgetMs: Long, val createdAtMs: Long,
    /** Distinct previously unstudied words at planning time; null only for legacy plans. */
    val newWordCount: Int? = null,
)
@Serializable data class LearningDay(
    val id: Id, val date: String, val zoneId: String, val opensAtMs: Long, val closesAtMs: Long,
    val planId: Id? = null, val endedAtMs: Long? = null, val endReason: DayEndReason? = null,
    val endSequence: Long = 0, val automaticOperationId: Id? = null,
)
@Serializable data class TrainingPolicy(
    val version: Long = 1, val newTargetLimit: Int = 12, val reviewTargetLimit: Int = 40,
    val sessionTargetLimit: Int = 10, val plannedMinutes: Int = 25, val retention: Float = 0.9f,
    val maxIntervalDays: Int = 3650, val weights: List<Float> = emptyList(),
    val mix: Map<ActivityKind, Int> = mapOf(ActivityKind.CHOICE to 25, ActivityKind.RECALL to 35,
        ActivityKind.SPELLING to 15, ActivityKind.PASSAGE to 15, ActivityKind.WRITING to 10),
    val focusTargets: Set<Id> = emptySet(), val suspendedTargets: Set<Id> = emptySet(),
    val hintPolicy: String = "on_request", val passageWords: Int = 80,
    val backgroundDifficulty: String = "simple", val reuseAfterDays: Int = 7,
    val preGenerateLimit: Int = 0,
)
@Serializable data class UserSettings(
    val version: Long = 1, val zoneId: String = "UTC", val budgetMinutes: Int = 30,
    val automaticAnalysis: Boolean = true, val automaticGeneration: Boolean = false,
    val automationEpoch: Long = 0, val lockedCapabilities: Set<String> = emptySet(),
    val automaticRequestsPerDay: Int = 2,
    val defaultPresetId: Id? = null, val fallbackPresetId: Id? = null,
    val dailyRequestLimit: Int? = null, val maxRetries: Int = 0, val preGenerationMaxPerDay: Int = 10,
)
@Serializable data class Metric(val successes: Int, val opportunities: Int, val observed: Boolean = true) {
    val lowSample: Boolean get() = opportunities < 20
}
@Serializable data class Confusion(val targetId: Id, val selectedSenseId: Id, val count: Int, val opportunities: Int)
@Serializable data class LearningSnapshot(
    val id: Id, val schemaVersion: Int = 1, val metricVersion: Int = 1, val asOfMs: Long,
    val fromMs: Long, val zoneId: String, val throughSequence: Long, val policyVersion: Long,
    val words: Int, val senses: Int, val groups: Int, val answers: Int, val distinctTargets: Int,
    val activeDays: Int, val studyMs: Long, val waitingMs: Long, val backgroundMs: Long,
    val dueTargets: Int, val byAbility: Map<Ability, Metric>, val delayedRecall: Metric,
    val newContext: Metric, val confusions: List<Confusion>, val apiRequests: Int,
    val knownInputTokens: Long, val knownOutputTokens: Long, val unknownUsageRequests: Int,
    val hintedAnswers: Int = 0, val skippedAnswers: Int = 0, val disputedAnswers: Int = 0,
    val aiFeedbackCount: Int = 0, val gradeSources: Map<GradeSource, Int> = emptyMap(),
    val timeSource: String = "recorded_intervals",
    val details: SnapshotDetails = SnapshotDetails(),
)
@Serializable data class Capability(
    val id: String, val description: String, val valueType: String, val current: JsonElement,
    val constraint: String, val version: Int = 1, val affectsSchedule: Boolean = false,
    val undoMode: String = "restore_policy_replay_history", val userLocked: Boolean = false,
)
@Serializable data class PolicyPatch(val values: Map<String, JsonElement>, val reason: String, val evidenceIds: List<Id> = emptyList())
@Serializable data class PolicyChange(
    val id: Id, val operationId: Id, val snapshotId: Id?, val actor: Actor,
    val before: TrainingPolicy, val after: TrainingPolicy, val reason: String,
    val evidenceIds: List<Id>, val atMs: Long, val effective: String = "next_session",
    val undoneBy: Id? = null, val evaluationId: Id? = null,
)
@Serializable data class Evaluation(
    val id: Id, val throughSequence: Long, val policyVersion: Long, val weights: List<Float>,
    val trainItems: Int, val validationItems: Int, val cutoffMs: Long,
    val baselineLogLoss: Float?, val candidateLogLoss: Float?, val baselineRmse: Float?,
    val candidateRmse: Float?, val accepted: Boolean, val note: String,
    val algorithm: String = "fsrs-6/fsrs-rs-6.6.2",
)
@Serializable data class ApiPreset(
    val id: Id, val version: Long = 1, val name: String, val baseUrl: String, val model: String,
    val protocol: String = "openai_chat_completions", val enabled: Boolean = true,
    val timeoutSeconds: Int = 45, val maxOutputTokens: Int = 2048,
    val jsonMode: Boolean = true, val temperature: Float? = null,
)
@Serializable data class PresetView(val preset: ApiPreset, val hasCredential: Boolean, val lastTest: ConnectionTest? = null)
@Serializable data class Binding(val feature: Feature, val presetId: Id, val userLocked: Boolean = true)
@Serializable data class CallPreview(val feature: Feature, val requiresApi: Boolean, val preset: ApiPreset?, val fault: Fault? = null)
@Serializable data class Usage(
    val id: Id, val operationId: Id, val attempt: Int, val feature: Feature, val preset: ApiPreset,
    val startedAtMs: Long, val endedAtMs: Long? = null, val inputTokens: Long? = null,
    val outputTokens: Long? = null, val outcome: String = "in_flight", val automatic: Boolean = false,
)
@Serializable data class Recommendation(
    val id: Id, val spelling: String, val reason: String, val groupId: Id? = null,
    val content: WordSeed? = null, val acceptedOperationId: Id? = null, val source: Provenance,
    val inLibrary: Boolean = false,
)
@Serializable data class WordSeed(
    val spelling: String, val meaning: String, val partOfSpeech: String = "",
    val acceptedForms: List<String> = emptyList(), val examples: List<String> = emptyList(),
)
@Serializable data class GroupSeed(val title: String, val relation: Relation, val purpose: String)
@Serializable data class IntakeChoice(val groupId: Id, val reason: String, val version: Long = 0)
@Serializable data class SpellingChoice(val original: String, val suggested: String, val reason: String)
@Serializable data class Operation(
    val id: Id, val kind: String, val status: OperationStatus, val createdAtMs: Long, val updatedAtMs: Long,
    val feature: Feature? = null, val automatic: Boolean = false, val triggerDayId: Id? = null,
    val executionDayId: Id? = null, val preset: ApiPreset? = null, val attempt: Int = 0,
    val snapshotId: Id? = null, val throughSequence: Long = 0, val policyVersion: Long = 1,
    val automationEpoch: Long = 0, val fault: Fault? = null, val resultIds: List<Id> = emptyList(),
    val choices: List<IntakeChoice> = emptyList(), val mergedInto: Id? = null,
    val undoable: Boolean = false, val undoneBy: Id? = null, val retryable: Boolean = false,
    val actor: Actor = Actor.USER, val parentOperationId: Id? = null, val summary: String = "",
    val input: String? = null, val effects: List<IntakeEffect> = emptyList(),
    val spellingChoices: List<SpellingChoice> = emptyList(),
)
@Serializable enum class PackagePurpose { CONTENT, BACKUP }
/** An opaque destination digest prevents replaying a completed export into a different file. */
@Serializable data class ExportIdentity(val key: String, val destinationSha256: String)
@Serializable data class PackagePreview(
    val token: Id, val packageId: Id, val purpose: PackagePurpose, val sha256: String,
    val basedOnRevision: Long, val added: Int, val duplicate: Int, val conflicts: List<Id>,
    val migrations: List<String>, val archivedModules: List<String>, val requiresCredentials: Boolean,
    val formatVersion: Int = 1, val producerVersion: String = "0.4.0", val createdAtMs: Long? = null,
    val modules: List<PackageModuleInfo> = emptyList(),
)
