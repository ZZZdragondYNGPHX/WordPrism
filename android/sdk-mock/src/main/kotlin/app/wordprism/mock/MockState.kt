package app.wordprism.mock

import app.wordprism.sdk.*
import kotlinx.serialization.Serializable

/** Whole mock database, persisted as one JSON file so interrupted sessions and tasks survive process death. */
@Serializable
internal data class MockDb(
    val schema: Int = 1,
    val seq: Long = 0,
    val words: Map<String, MWord> = emptyMap(),
    val groups: Map<String, MGroup> = emptyMap(),
    val recommendations: Map<String, List<Recommendation>> = emptyMap(),
    val operations: Map<String, Operation> = emptyMap(),
    /** client request id -> operation id (idempotency). */
    val requestIndex: Map<String, String> = emptyMap(),
    val intakes: Map<String, MIntake> = emptyMap(),
    val undo: Map<String, MUndo> = emptyMap(),
    val days: Map<String, MDay> = emptyMap(),
    val sessions: Map<String, MSession> = emptyMap(),
    val answers: List<MAnswer> = emptyList(),
    val settings: MSettings = MSettings(),
    val changeSets: List<ChangeSet> = emptyList(),
    val presets: Map<String, MPreset> = emptyMap(),
    val bindings: Map<String, String?> = emptyMap(),
    val callPolicy: CallPolicy = CallPolicy(null, false, 20, 200, 1, null),
    val locks: Set<String> = emptySet(),
    val history: MHistory = MHistory(),
    val lastAnalysisCoveredAnswers: Int = 0,
    val disputes: Int = 0,
)

@Serializable
internal data class MWord(
    val id: String,
    val lemma: String,
    val pos: String,
    val glossZh: String,
    val glossEn: String,
    val collocations: List<String>,
    val example: String,
    val exampleZh: String,
    val note: String? = null,
    val version: Long = 1,
    val source: ContentSource,
    val status: String = StudyStatus.NotStarted.value,
    val createdAt: Long,
)

@Serializable
internal data class MGroup(
    val id: String,
    val title: String,
    val relation: String,
    val purpose: String,
    val memberIds: List<String>,
    val joinReasons: Map<String, String> = emptyMap(),
    val version: Long = 1,
    val source: ContentSource,
    val createdAt: Long,
)

@Serializable
internal data class MIntake(val opId: String, val input: String, val outcome: IntakeOutcome? = null, val choice: IntakeChoice? = null, val words: List<String> = emptyList())

/** What an undoable operation changed, so undo can revert exactly that. */
@Serializable
internal data class MUndo(
    val createdWordIds: List<String> = emptyList(),
    val createdGroupIds: List<String> = emptyList(),
    val addedMemberships: List<Pair<String, String>> = emptyList(), // groupId to wordId
    val removedMemberships: List<Pair<String, String>> = emptyList(),
    val wordBefore: MWord? = null,
    val groupTitleBefore: Pair<String, String>? = null,
    val done: Boolean = false,
)

@Serializable
internal data class MDay(
    val id: String,
    val date: String,
    val status: String,
    val effectiveSeconds: Long = 0,
    val waitingSeconds: Long = 0,
    val plannedItems: Int,
    val completedItems: Int = 0,
    val planVersion: Int,
    val openSessionId: String? = null,
    val autoAnalysisOpId: String? = null,
    val budgetPromptAnswered: Boolean = false,
)

@Serializable
internal data class MSession(
    val id: String,
    val dayId: String,
    val status: String,
    val activities: List<MActivity>,
    val index: Int = 0,
    val feedback: Map<String, Feedback> = emptyMap(), // activityId -> latest feedback
    val drafts: Map<String, String> = emptyMap(),
    val hinted: Set<String> = emptySet(),
    val revisions: Map<String, Int> = emptyMap(),
    val shownAt: Long = 0,
    val policyVersion: Int,
)

/** Internal activity spec including the answer key (never exposed to the UI). */
@Serializable
internal data class MActivity(
    val view: ActivityView,
    val correctOptionId: String? = null,
    val accepted: List<String> = emptyList(),
    val targetLemma: String? = null,
    val explanation: String? = null,
    val hint: String? = null,
)

@Serializable
internal data class MAnswer(
    val id: String,
    val at: Long,
    val activityKind: String,
    val target: String?,
    val chosen: String?,
    val correct: Boolean?,
    val hinted: Boolean,
    val prompt: String,
)

@Serializable
internal data class MSettings(
    val mode: String = AnalysisMode.DailyAfterDayEnd.value,
    val budgetMinutes: Int = 30,
    val newPerDay: Int = 6,
    val reviewShareRecall: Float = 0.2f,
    val readingShare: Float = 0.15f,
    val productionShare: Float = 0.1f,
    val targetRetention: Float = 0.9f,
    val policyVersion: Int = 3,
    val planVersion: Int = 1,
    val focus: List<String> = listOf("adapt → adopt"),
    val adjustedBy: String? = null,
    val lastAnalysisOpId: String? = null,
    val activeAnalysisOpId: String? = null,
)

@Serializable
internal data class MPreset(
    val id: String,
    val name: String,
    val version: Int,
    val adapterId: String,
    val baseUrl: String,
    val model: String,
    val maxOutputTokens: Int?,
    val timeoutSeconds: Int,
    /** Mock stand-in for Keystore-backed storage: only stores presence + last 4 chars. */
    val credentialHint: String?,
    val enabled: Boolean = true,
    val lastTest: ConnectionTestResult? = null,
)

/** Seeded synthetic history so the overview has something to show on first run. */
@Serializable
internal data class MHistory(
    val dailyMinutes: List<Int> = listOf(22, 0, 27, 31, 18, 25, 0, 29, 24, 26, 12, 0, 28, 21),
    val answers: Int = 412,
    val unaidedSuccess: Pair<Int, Int> = 241 to 318,
    val delayedSuccess: Pair<Int, Int> = 88 to 121,
    val hinted: Pair<Int, Int> = 31 to 47,
    val recognition: Pair<Int, Int> = 190 to 221,
    val recall: Pair<Int, Int> = 41 to 66,
    val reading: Pair<Int, Int> = 17 to 22,
    val production: Pair<Int, Int> = 6 to 9,
    val confusions: List<Triple<String, String, Pair<Int, Int>>> = listOf(
        Triple("adopt", "adapt", 7 to 19), Triple("liner", "linear", 4 to 15),
        Triple("effect", "affect", 3 to 12), Triple("principle", "principal", 2 to 4),
    ),
)
