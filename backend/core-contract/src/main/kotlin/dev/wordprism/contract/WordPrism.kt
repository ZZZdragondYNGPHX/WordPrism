package dev.wordprism.contract

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class Request(val key: String, val command: Command, val contractVersion: Int = Contract.VERSION)
@Serializable data class Reply(val revision: Long, val value: View? = null, val fault: Fault? = null) {
    init { require((value == null) != (fault == null)) { "Reply requires exactly one of value or fault" } }
}

/** Keys are persistent idempotency keys. Reusing a key with different content is an error. */
@Serializable sealed interface Command {
    @Serializable @SerialName("save_words") data class SaveWords(
        val words: List<WordSeed>, val group: GroupSeed? = null, val existingGroup: Ref? = null,
    ) : Command
    @Serializable @SerialName("update_word") data class UpdateWord(
        val word: Ref, val notes: String, val tags: Set<String>, val archived: Boolean = false,
    ) : Command
    @Serializable @SerialName("update_sense") data class UpdateSense(
        val sense: Ref, val meaning: String, val acceptedForms: List<String>, val examples: List<String>,
    ) : Command
    /** One atomic edit. A null sense selects only an unambiguous single sense, guarded by the word version. */
    @Serializable @SerialName("edit_word") data class EditWord(val word: Ref, val notes: String?, val sense: Ref? = null, val meaning: String? = null) : Command
    @Serializable @SerialName("rename_group") data class RenameGroup(val group: Ref, val title: String) : Command
    @Serializable @SerialName("edit_memberships") data class EditMemberships(val group: Ref, val addWords: List<Id>, val removeWords: List<Id>, val reason: String) : Command
    @Serializable @SerialName("change_membership") data class ChangeMembership(
        val group: Ref, val senseId: Id, val active: Boolean, val reason: String,
    ) : Command
    @Serializable @SerialName("save_draft") data class SaveDraft(val key: String, val text: String, val expectedVersion: Long = 0) : Command
    @Serializable @SerialName("submit_intake") data class SubmitIntake(val input: String, val group: Ref? = null) : Command
    @Serializable @SerialName("resolve_intake") data class ResolveIntake(val operationId: Id, val groups: List<Ref> = emptyList(), val disposition: String = "groups") : Command
    @Serializable @SerialName("resolve_spelling") data class ResolveSpelling(val operationId: Id, val original: String, val useSuggested: Boolean) : Command
    @Serializable @SerialName("request_recommendations") data class RequestRecommendations(val group: Ref) : Command
    @Serializable @SerialName("accept_recommendations") data class AcceptRecommendations(val ids: List<Id>, val allowApi: Boolean) : Command
    @Serializable @SerialName("generate_activity") data class GenerateActivity(val targetId: Id, val kind: ActivityKind) : Command
    @Serializable @SerialName("explain_sense") data class ExplainSense(val sense: Ref) : Command
    @Serializable @SerialName("evaluate_response") data class EvaluateResponse(val answer: Ref) : Command
    @Serializable @SerialName("tutor_session") data class TutorSession(
        val sessionId: Id, val moment: TutorMoment, val presentationId: Id? = null,
        val answer: Ref? = null, val question: String? = null,
    ) : Command
    @Serializable @SerialName("start_session") data class StartSession(val continuePastBudget: Boolean = false, val includeIntroductions: Boolean = false) : Command
    @Serializable @SerialName("next_activity") data class NextActivity(val sessionId: Id) : Command
    @Serializable @SerialName("request_hint") data class RequestHint(val sessionId: Id, val presentationId: Id, val revealAnswer: Boolean = false) : Command
    @Serializable @SerialName("submit_answer") data class SubmitAnswer(
        val sessionId: Id, val presentationId: Id, val activity: Ref, val answer: AnswerInput,
    ) : Command
    @Serializable @SerialName("revise_answer") data class ReviseAnswer(val answer: Ref, val text: String) : Command
    @Serializable @SerialName("begin_answer_revision") data class BeginAnswerRevision(val sessionId: Id, val answer: Ref) : Command
    @Serializable @SerialName("mark_difficulty") data class MarkDifficulty(val answer: Ref, val difficult: Boolean) : Command
    @Serializable @SerialName("set_timer") data class SetTimer(val sessionId: Id, val mode: TimerMode) : Command
    @Serializable @SerialName("finish_session") data class FinishSession(val sessionId: Id, val pause: Boolean = false) : Command
    @Serializable @SerialName("finish_learning_day") data class FinishLearningDay(val dayId: Id) : Command
    @Serializable @SerialName("dispute_activity") data class DisputeActivity(val activity: Ref, val reason: String) : Command
    @Serializable @SerialName("analyze_learning") data class AnalyzeLearning(val force: Boolean = false) : Command
    @Serializable @SerialName("set_settings") data class SetSettings(val settings: UserSettings, val expectedVersion: Long) : Command
    @Serializable @SerialName("set_learning_mode") data class SetLearningMode(val mode: LearningMode, val expectedVersion: Long) : Command
    @Serializable @SerialName("apply_policy") data class ApplyPolicy(
        val patch: PolicyPatch, val expectedVersion: Long, val evaluationId: Id? = null,
    ) : Command
    @Serializable @SerialName("fit_memory") data class FitMemory(val applyIfBetter: Boolean = false) : Command
    @Serializable @SerialName("evaluate_parameters") data class EvaluateParameters(val weights: List<Float>) : Command
    /** UI forms that do not expose enabled/jsonMode/temperature preserve them transactionally.
     * reasoningEffort is always submitted, including null to return to the provider default. */
    @Serializable @SerialName("save_preset") data class SavePreset(val preset: ApiPreset, val expectedVersion: Long = 0, val preserveAdvancedSettings: Boolean = false) : Command
    @Serializable @SerialName("delete_preset") data class DeletePreset(val preset: Ref) : Command
    @Serializable @SerialName("bind_preset") data class BindPreset(val binding: Binding) : Command
    @Serializable @SerialName("unbind_preset") data class UnbindPreset(val feature: Feature) : Command
    @Serializable @SerialName("set_call_policy") data class SetCallPolicy(val policy: CallPolicy) : Command
    @Serializable @SerialName("test_connection") data class TestConnection(val preset: Ref) : Command
    @Serializable @SerialName("cancel_operation") data class CancelOperation(val id: Id) : Command
    /** Explicit retry captures the latest saved version of the same enabled preset; prior attempts stay unchanged. */
    @Serializable @SerialName("retry_operation") data class RetryOperation(val id: Id) : Command
    @Serializable @SerialName("undo_operation") data class UndoOperation(val id: Id) : Command
    @Serializable @SerialName("commit_import") data class CommitImport(val token: Id) : Command
    @Serializable @SerialName("restore_backup") data class RestoreBackup(val token: Id) : Command
}

@Serializable sealed interface Query {
    @Serializable @SerialName("library") data class Library(val search: String = "", val afterId: Id? = null, val limit: Int = 50) : Query
    @Serializable @SerialName("library_overview") data class LibrarySummary(val search: String = "", val relation: Relation? = null, val ungroupedOnly: Boolean = false) : Query
    @Serializable @SerialName("word") data class GetWord(val id: Id) : Query
    @Serializable @SerialName("sense_explanation") data class GetExplanation(val senseId: Id) : Query
    @Serializable @SerialName("group") data class GetGroup(val id: Id) : Query
    @Serializable @SerialName("session") data class GetSession(val id: Id) : Query
    @Serializable @SerialName("activity") data class GetActivity(val id: Id) : Query
    @Serializable @SerialName("presentation") data class GetPresentation(val id: Id) : Query
    @Serializable @SerialName("learning_day") data class GetLearningDay(val id: Id? = null) : Query
    @Serializable @SerialName("overview") data class Overview(val fromMs: Long = 0) : Query
    @Serializable @SerialName("snapshot") data class Snapshot(val id: Id) : Query
    @Serializable @SerialName("evidence") data class Evidence(val afterSequence: Long = 0, val limit: Int = 50, val targetId: Id? = null) : Query
    @Serializable @SerialName("feedback") data class GetFeedback(val answerId: Id, val answerVersion: Long? = null) : Query
    @Serializable @SerialName("operation_feedback") data class GetOperationFeedback(val operationId: Id) : Query
    @Serializable @SerialName("operation") data class GetOperation(val id: Id) : Query
    @Serializable @SerialName("operation_effects") data class OperationEffects(val id: Id) : Query
    @Serializable @SerialName("operations") data class Operations(val limit: Int = 50) : Query
    @Serializable @SerialName("recommendations") data class Recommendations(val groupId: Id? = null) : Query
    @Serializable @SerialName("settings") data object Settings : Query
    @Serializable @SerialName("managed_learning") data object ManagedLearning : Query
    @Serializable @SerialName("tutoring") data class Tutoring(val sessionId: Id? = null) : Query
    @Serializable @SerialName("capabilities") data object Capabilities : Query
    @Serializable @SerialName("policy_changes") data object PolicyChanges : Query
    @Serializable @SerialName("presets") data object Presets : Query
    @Serializable @SerialName("call_policy") data object CallPolicy : Query
    @Serializable @SerialName("protocols") data object Protocols : Query
    @Serializable @SerialName("package_preview") data class GetPackagePreview(val token: Id) : Query
    @Serializable @SerialName("export_scope") data class ExportScope(val purpose: PackagePurpose) : Query
    @Serializable @SerialName("confusion_evidence") data class ConfusionEvidence(val expected: String, val selected: String, val limit: Int = 50) : Query
    @Serializable @SerialName("call_preview") data class PreviewCall(val feature: Feature, val recommendationIds: List<Id> = emptyList()) : Query
    @Serializable @SerialName("usage") data class GetUsage(val fromMs: Long = 0, val feature: Feature? = null, val presetId: Id? = null) : Query
    @Serializable @SerialName("draft") data class GetDraft(val key: String) : Query
    @Serializable @SerialName("evaluation") data class GetEvaluation(val id: Id) : Query
}

@Serializable sealed interface View {
    @Serializable @SerialName("library") data class Library(
        val words: List<Word>, val senses: List<Sense>, val groups: List<Group>,
        val memberships: List<Membership>, val targets: List<LearningTarget>, val schedules: List<Schedule>, val nextId: Id? = null,
        val wordStudy: Map<Id, StudySummary> = emptyMap(), val groupStudy: Map<Id, StudySummary> = emptyMap(),
        val alignments: List<WordAlignment> = emptyList(),
        val hasDisputedContent: Boolean = false,
    ) : View
    @Serializable @SerialName("library_overview") data class LibrarySummary(val library: LibraryOverview) : View
    @Serializable @SerialName("session") data class SessionState(val session: Session, val activity: ActivityCard?,
        val feedback: AnswerResult? = null, val budgetState: BudgetState = BudgetState.WITHIN, val budgetPromptPending: Boolean = false) : View
    @Serializable @SerialName("activity") data class ActivityDetails(val activity: ActivityCard, val source: Provenance) : View
    @Serializable @SerialName("presentation") data class Presentation(val id: Id, val sessionId: Id, val activity: ActivityCard, val answer: AnswerResult? = null) : View
    @Serializable @SerialName("hint") data class Hint(val text: String, val exposure: Exposure) : View
    @Serializable @SerialName("day") data class Day(val day: LearningDay, val plan: LearningPlan?, val studyMs: Long, val automatic: Operation?, val summary: DaySummary = DaySummary()) : View
    @Serializable @SerialName("answer") data class AnswerResult(val answer: Answer, val feedback: List<Feedback>, val schedule: Schedule?,
        val acceptedAnswers: List<String> = emptyList(), val correctChoiceId: String? = null,
        val canMarkDifficult: Boolean = false, val markedDifficult: Boolean = false, val canRevise: Boolean = false,
        val pendingOperationId: Id? = null, val correctChoiceIds: List<String> = emptyList()) : View
    @Serializable @SerialName("feedback") data class Feedbacks(val answer: Answer, val feedback: List<Feedback>, val details: AnswerResult? = null) : View
    @Serializable @SerialName("snapshot") data class Snapshot(val snapshot: LearningSnapshot) : View
    @Serializable @SerialName("evidence") data class Evidence(val answers: List<Answer>, val activities: List<Activity>, val nextSequence: Long?) : View
    @Serializable @SerialName("operation") data class OperationResult(val operation: Operation) : View
    @Serializable @SerialName("operations") data class Operations(val operations: List<Operation>) : View
    @Serializable @SerialName("operation_effects") data class OperationEffects(val effects: List<BusinessEffect>) : View
    @Serializable @SerialName("recommendations") data class Recommendations(val recommendations: List<Recommendation>) : View
    @Serializable @SerialName("sense_explanation") data class Explanation(
        val sense: Ref, val explanation: SenseExplanation?, val stale: Boolean,
        val recommendations: List<Recommendation>, val operation: Operation? = null,
        /** Related words remain readable after acceptance; recommendations contains only eligible candidates. */
        val family: List<Recommendation> = emptyList(),
    ) : View
    @Serializable @SerialName("settings") data class Settings(val settings: UserSettings, val policy: TrainingPolicy, val bindings: List<Binding>) : View
    @Serializable @SerialName("managed_learning") data class ManagedLearning(val state: ManagedLearningState) : View
    @Serializable @SerialName("tutoring") data class Tutoring(val entries: List<TutorEntry>) : View
    @Serializable @SerialName("capabilities") data class Capabilities(val capabilities: List<Capability>) : View
    @Serializable @SerialName("policy_changes") data class PolicyChanges(val changes: List<PolicyChange>, val undoableIds: Set<Id> = emptySet()) : View
    @Serializable @SerialName("presets") data class Presets(val presets: List<PresetView>, val bindings: List<Binding>) : View
    @Serializable @SerialName("call_policy") data class CallPolicy(val policy: dev.wordprism.contract.CallPolicy) : View
    @Serializable @SerialName("protocols") data class Protocols(val protocols: List<ProtocolInfo>) : View
    @Serializable @SerialName("models") data class Models(val models: List<ApiModel>) : View
    @Serializable @SerialName("call_preview") data class Preview(val preview: CallPreview) : View
    @Serializable @SerialName("usage") data class Usages(val usage: List<Usage>) : View
    @Serializable @SerialName("draft") data class DraftResult(val draft: Draft?) : View
    @Serializable @SerialName("evaluation") data class EvaluationResult(val evaluation: Evaluation) : View
    @Serializable @SerialName("package_preview") data class ImportPreview(val preview: PackagePreview) : View
    @Serializable @SerialName("exported") data class Exported(val packageId: Id, val bytes: Long) : View
    @Serializable @SerialName("export_scope") data class ExportScope(val purpose: PackagePurpose, val included: List<String>, val excluded: List<String>, val suggestedFileName: String) : View
    @Serializable @SerialName("ok") data class Ok(val id: Id? = null) : View
}

interface WordPrism {
    suspend fun execute(request: Request): Reply
    suspend fun query(query: Query): Reply
    /** Emits durable revisions; re-query on emission. Unsubscribing never cancels a business task. */
    fun observe(): Flow<Long>
    suspend fun previewPackage(input: InputStream): Reply
    suspend fun exportPackage(purpose: PackagePurpose, output: OutputStream, wordIds: Set<Id> = emptySet(), identity: ExportIdentity? = null): Reply
    /** Secrets bypass serializable commands, snapshots and backups. Implementations erase the input. */
    suspend fun setCredential(presetId: Id, secret: CharArray): Reply
    suspend fun deleteCredential(presetId: Id): Reply
    /** Explicit, cancellable network metadata read. No durable task, usage or automatic retry.
     * An explicit secret overrides credentialPreset and is erased on every exit; null uses the saved credential.
     * Cancelling the coroutine aborts the request. The caller must discard results after editing the connection.
     */
    suspend fun listModels(request: ModelCatalogRequest, secret: CharArray? = null): Reply
}
