package app.wordprism.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@JvmInline @Serializable value class SessionId(val value: String)
@JvmInline @Serializable value class ActivityId(val value: String)
@JvmInline @Serializable value class LearningDayId(val value: String)

// ---------------------------------------------------------------- learning day & plan

@Serializable
data class LearningDay(
    val id: LearningDayId,
    val date: LocalDay,
    val timezone: String,
    val status: DayStatus,
    val budgetMinutes: Int,
    /** Accumulated effective learning time across all sessions today (backend-timed). */
    val effectiveSeconds: Long,
    /** Waiting for generation / background time, shown separately. */
    val waitingSeconds: Long,
    val budgetState: BudgetState,
    val plannedItems: Int,
    val completedItems: Int,
    val planVersion: Int,
    val openSessionId: SessionId?,
    val canEndDay: Boolean,
    val dailyAnalysis: DailyAnalysisStatus,
)

@JvmInline
@Serializable
value class DayStatus(val value: String) {
    companion object {
        val NotStarted = DayStatus("not_started")
        val InProgress = DayStatus("in_progress")
        val PlanCompleted = DayStatus("plan_completed")
        val Ended = DayStatus("ended")
    }
}

/** Backend decides budget proximity; the UI only presents it. */
@JvmInline
@Serializable
value class BudgetState(val value: String) {
    companion object {
        val Within = BudgetState("within")
        val Near = BudgetState("near")
        val Reached = BudgetState("reached")
    }
}

@Serializable
data class DailyAnalysisStatus(
    val mode: AnalysisMode,
    val state: String, // "not_due" | "will_run_after_day_end" | "queued" | "waiting_network" | "running" | "done" | "skipped_no_new_data" | "failed" | "disabled"
    val label: String,
    val operationId: OperationId? = null,
)

@Serializable
data class LearningPlan(
    val planVersion: Int,
    val policyVersion: Int,
    val reviewsDue: Int,
    val newPlanned: Int,
    val estimatedMinutes: Int,
    val mix: List<MixShare>,
    val focus: List<FocusItem>,
    /** E.g. "AI 调整：减少新学，加强 adapt → adopt 辨析". Null if plan is default. */
    val adjustedBy: String?,
    val estimateBasis: String,
    val backlogNote: String?,
    val libraryWords: Int = 0,
)

@Serializable
data class MixShare(val activityKind: String, val label: String, val share: Float)

@Serializable
data class FocusItem(val label: String, val reason: String, val groupId: GroupId?)

// ---------------------------------------------------------------- sessions & activities

@Serializable
data class SessionState(
    val id: SessionId,
    val status: SessionStatus,
    val done: Int,
    val total: Int,
    val current: ActivityView?,
    /** Feedback for the most recently submitted answer of [current], if any. */
    val feedback: Feedback?,
    val budgetState: BudgetState,
    val budgetPromptPending: Boolean,
    val policyVersion: Int,
    val reinforcementRemaining: Int = 0,
)

@JvmInline
@Serializable
value class SessionStatus(val value: String) {
    companion object {
        val Active = SessionStatus("active")
        val Paused = SessionStatus("paused")
        val Completed = SessionStatus("completed")
    }
}

/** What the learner sees. Unknown kinds render [fallbackText] and offer skip. */
@Serializable
sealed interface ActivityView {
    val id: ActivityId
    val kindLabel: String
    val hintAvailable: Boolean
    val allowDispute: Boolean

    @Serializable @SerialName("learn_word")
    data class LearnWord(
        override val id: ActivityId,
        override val kindLabel: String,
        val lemma: String,
        val partOfSpeech: String,
        val meaning: String,
        val examples: List<String>,
        override val hintAvailable: Boolean = false,
        override val allowDispute: Boolean = false,
        val support: List<ExplanationSection> = emptyList(),
    ) : ActivityView

    @Serializable @SerialName("contrast")
    data class Contrast(
        override val id: ActivityId,
        override val kindLabel: String,
        val group: GroupDetail,
        override val hintAvailable: Boolean = false,
        override val allowDispute: Boolean = false,
        val support: Map<String, List<ExplanationSection>> = emptyMap(),
    ) : ActivityView

    @Serializable @SerialName("familiarity")
    data class Familiarity(
        override val id: ActivityId,
        override val kindLabel: String,
        val lemma: String,
        val partner: String?,
        override val hintAvailable: Boolean = false,
        override val allowDispute: Boolean = false,
    ) : ActivityView

    @Serializable @SerialName("choice")
    data class Choice(
        override val id: ActivityId,
        override val kindLabel: String,
        val instruction: String,
        /** Sentence or definition; `____` marks a blank. */
        val stem: String,
        val stemTranslationZh: String?,
        val options: List<ChoiceOption>,
        override val hintAvailable: Boolean,
        override val allowDispute: Boolean = true,
    ) : ActivityView

    @Serializable @SerialName("recall")
    data class Recall(
        override val id: ActivityId,
        override val kindLabel: String,
        val instruction: String,
        val stem: String,
        val cueZh: String?,
        val letterCount: Int?,
        val draft: String?,
        override val hintAvailable: Boolean,
        override val allowDispute: Boolean = true,
    ) : ActivityView

    @Serializable @SerialName("reading")
    data class Reading(
        override val id: ActivityId,
        override val kindLabel: String,
        val passage: String,
        val question: String,
        val options: List<ChoiceOption>,
        val source: ContentSource,
        override val hintAvailable: Boolean,
        override val allowDispute: Boolean = true,
    ) : ActivityView

    /** Open production: sentence writing or rewrite. Feedback is AI-assisted and asynchronous. */
    @Serializable @SerialName("production")
    data class Production(
        override val id: ActivityId,
        override val kindLabel: String,
        val instruction: String,
        val intentZh: String?,
        val sourceSentence: String?,
        val targetWords: List<String>,
        val draft: String?,
        val requirement: ApiRequirement,
        override val hintAvailable: Boolean,
        override val allowDispute: Boolean = true,
    ) : ActivityView

    @Serializable @SerialName("unknown")
    data class Unknown(
        override val id: ActivityId,
        override val kindLabel: String,
        val fallbackText: String,
        override val hintAvailable: Boolean = false,
        override val allowDispute: Boolean = false,
    ) : ActivityView
}

@Serializable
data class ChoiceOption(val id: String, val text: String)

@Serializable
sealed interface Answer {
    @Serializable @SerialName("ack") data object Acknowledge : Answer
    @Serializable @SerialName("familiarity") data class Familiarity(val knowsIt: Boolean) : Answer
    @Serializable @SerialName("choice") data class Choice(val optionId: String) : Answer
    @Serializable @SerialName("text") data class Text(val text: String) : Answer
}

@Serializable
data class Feedback(
    val answerId: String,
    /** Answer version the feedback belongs to (open answers can be revised). */
    val answerVersion: Int,
    /** The text answer this feedback refers to, so the UI can show it after process death. */
    val answerText: String? = null,
    val verdict: Verdict,
    val headline: String,
    val explanation: String?,
    val correctOptionId: String? = null,
    val chosenOptionId: String? = null,
    val acceptedAnswers: List<String> = emptyList(),
    /** E.g. "你选了 liner，它指衬垫/班轮". */
    val confusionNote: String? = null,
    val affectsSchedule: Boolean,
    val scheduleNote: String?,
    val aiReview: AiReview? = null,
    val pendingOperationId: OperationId? = null,
    val canMarkHard: Boolean = false,
    val markedHard: Boolean = false,
    val canRevise: Boolean = false,
    val correctOptionIds: List<String> = emptyList(),
    /** Objective outcome remains visible even when the attempt is assisted practice. */
    val answerCorrect: Boolean? = null,
)

@JvmInline
@Serializable
value class Verdict(val value: String) {
    companion object {
        val Correct = Verdict("correct")
        val Incorrect = Verdict("incorrect")
        val AcceptedAlternative = Verdict("accepted_alternative")
        val Recorded = Verdict("recorded")
        val PendingReview = Verdict("pending_review")
        val Reviewed = Verdict("reviewed")
        val Practice = Verdict("practice") // answered after hint/exposure
    }
}

@Serializable
data class AiReview(
    val usage: String, // "misused" | "acceptable_unnatural" | "good" | "equally_valid"
    val usageLabel: String,
    val points: List<ReviewPoint>,
    val suggestion: String?,
    val source: ContentSource,
    val summary: String? = null,
    val dimensions: List<ReviewDimension> = emptyList(),
    val optimizationNotes: String? = null,
)

@Serializable
data class ReviewDimension(val label: String, val comment: String, val quote: String? = null)

@Serializable
data class ReviewPoint(val quote: String?, val comment: String, val aboutTarget: Boolean)

@Serializable
data class Hint(val text: String, val note: String)

@Serializable data class TutorMessage(
    val operation: Operation, val moment: String, val sessionId: SessionId,
    val presentationId: String? = null, val answerId: String? = null, val answerVersion: Int? = null,
    val question: String? = null, val message: String? = null, val checkQuestion: String? = null,
)

interface StudyApi {
    fun observeTutorReviewSession(): Flow<SessionId?> = kotlinx.coroutines.flow.flowOf(null)
    suspend fun reviewWithTutor(session: SessionId, request: ClientRequestId): SdkResult<OperationId> =
        SdkResult.Err(SdkError(ErrorCode("unsupported"), "当前后端不支持助教总结", false))
    fun observeTutor(session: SessionId? = null): Flow<List<TutorMessage>> = kotlinx.coroutines.flow.flowOf(emptyList())
    suspend fun askTutor(session: SessionId, activity: ActivityId, question: String, request: ClientRequestId): SdkResult<OperationId> =
        SdkResult.Err(SdkError(ErrorCode("unsupported"), "当前后端不支持随身助教", false))
    /** Lifecycle/presence only. All timing and budget decisions belong to the local core. */
    suspend fun reportPresence(session: SessionId, visible: Boolean, waiting: Boolean = false): SdkResult<Unit> = SdkResult.Ok(Unit)
    fun observeLearningDay(): Flow<LearningDay>
    fun observePlan(): Flow<LearningPlan>
    /** Resumes today's open session if any, else starts one. Idempotent per [request]. */
    suspend fun startOrResume(request: ClientRequestId, continuePastBudget: Boolean = false): SdkResult<SessionId>
    fun observeSession(id: SessionId): Flow<SessionState?>
    /** Idempotent per [request]; double taps yield one scored answer. */
    suspend fun submitAnswer(session: SessionId, activity: ActivityId, answer: Answer, request: ClientRequestId): SdkResult<Feedback>
    suspend fun saveDraft(session: SessionId, activity: ActivityId, text: String): SdkResult<Unit>
    suspend fun requestHint(session: SessionId, activity: ActivityId): SdkResult<Hint>
    suspend fun markHard(session: SessionId, answerId: String, hard: Boolean): SdkResult<Feedback>
    suspend fun reviseAnswer(session: SessionId, activity: ActivityId): SdkResult<Unit>
    suspend fun advance(session: SessionId, request: ClientRequestId): SdkResult<SessionState>
    suspend fun skip(session: SessionId, activity: ActivityId, reason: String?): SdkResult<SessionState>
    suspend fun dispute(session: SessionId, activity: ActivityId, reason: String): SdkResult<Unit>
    /** Ends this sitting only; the learning day stays open. */
    suspend fun pause(session: SessionId): SdkResult<Unit>
    /** Respond to a budget prompt: keep going explicitly or stop here. */
    suspend fun answerBudgetPrompt(session: SessionId, continueLearning: Boolean): SdkResult<SessionState>
    /** Explicit "end today": records the day-end event and requests at most one daily analysis. */
    suspend fun finishLearningDay(request: ClientRequestId): SdkResult<LearningDay>
}
