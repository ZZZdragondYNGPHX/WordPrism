package dev.wordprism.contract

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Derived business facts for every client. Adapters only format these values. */
@Serializable enum class StudyState { NOT_STARTED, LEARNING, DUE, STABLE, PAUSED }
@Serializable enum class BudgetState { WITHIN, NEAR, REACHED }
@Serializable data class StudySummary(val state: StudyState, val dueCount: Int = 0)
@Serializable data class CharacterRange(val start: Int, val endExclusive: Int)
@Serializable data class WordAlignment(val wordId: Id, val aligned: String, val different: List<CharacterRange>)
@Serializable data class GroupOverview(val group: Group, val members: List<Word>, val study: StudySummary)
@Serializable data class LibraryOverview(
    val groups: List<GroupOverview>, val ungroupedWords: List<Word>, val senses: List<Sense>,
    val wordStudy: Map<Id, StudySummary>, val totalWords: Int, val totalGroups: Int,
)
@Serializable data class DaySummary(
    val budgetMinutes: Int = 30, val waitingMs: Long = 0, val budgetState: BudgetState = BudgetState.WITHIN,
    val completedTargets: Int = 0, val openSessionId: Id? = null, val canEnd: Boolean = false,
    val automaticEnabled: Boolean = false, val analysisState: String = "not_due",
    val policy: TrainingPolicy = TrainingPolicy(), val focusGroups: List<Id> = emptyList(),
    val state: String = "not_started",
    val plannedNewWords: Int = 0,
)
@Serializable data class DayEffort(val date: String, val studyMs: Long, val plannedMs: Long)
@Serializable data class LoadForecast(val date: String, val targets: Int, val estimatedMs: Long)
@Serializable data class ConfusionDetail(
    val targetId: Id, val selectedSenseId: Id, val expected: String, val selected: String,
    val count: Int, val opportunities: Int, val groupId: Id?, val lowSample: Boolean,
    val trend: String = "insufficient_data",
)
@Serializable data class SnapshotDetails(
    val addedWords: Int = 0, val acceptedRecommendations: Int = 0, val undoneOperations: Int = 0,
    val ungroupedWords: Int = 0, val newTargets: Int = 0, val interruptedSessions: Int = 0,
    val budgetMinutes: Int = 30, val daily: List<DayEffort> = emptyList(),
    val forecast: List<LoadForecast> = emptyList(), val overdueTargets: Int = 0,
    val confusions: List<ConfusionDetail> = emptyList(), val usage: List<UsageTotal> = emptyList(),
    val aiUsage: Map<String, Metric> = emptyMap(), val lowSampleThreshold: Int = 20,
    /** Target-word usage judged good/equally valid, kept separate from objective memory evidence. */
    val expressionUsage: Metric = Metric(0, 0),
)
@Serializable data class UsageTotal(val feature: Feature, val presetId: Id, val presetName: String, val requests: Int,
    val knownInputTokens: Long, val knownOutputTokens: Long, val unknownRequests: Int)
@Serializable data class ConnectionTest(val ok: Boolean, val atMs: Long, val message: String, val latencyMs: Long?)
@Serializable data class ProtocolInfo(val id: String, val label: String, val defaultBaseUrl: String?, val notes: String,
    val reasoningEfforts: List<ReasoningEffort> = emptyList())
@Serializable data class CallPolicy(
    val defaultPresetId: Id?, val fallbackPresetId: Id?, val dailyRequestLimit: Int?, val maxRetries: Int,
    val automaticGeneration: Boolean, val preGenerationMaxPerDay: Int,
    val settingsVersion: Long, val policyVersion: Long,
)
@Serializable data class IntakeEffect(
    val kind: String, val label: String, val reason: String, val groupId: Id? = null, val wordId: Id? = null,
)
@Serializable data class PackageModuleInfo(val name: String, val version: Int, val state: String)
@Serializable data class DifficultyMark(
    val id: Id, val answerId: Id, val answerVersion: Long, val difficult: Boolean, val atMs: Long, val revision: Long,
)
/** Exact persisted content/configuration differences, including actions of an analysis operation. */
@Serializable data class BusinessEffect(val operationId: Id, val capabilityId: String, val entityId: String, val label: String,
    val field: String, val before: JsonElement?, val after: JsonElement, val affectsSchedule: Boolean = false)
