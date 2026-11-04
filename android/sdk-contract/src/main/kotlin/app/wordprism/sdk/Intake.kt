package app.wordprism.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data class IntakeRequest(val text: String)

/** Live view of an intake operation plus its outcome once known. */
@Serializable
data class IntakeState(
    val operation: Operation,
    val input: String,
    val outcome: IntakeOutcome? = null,
    val pendingChoice: IntakeChoice? = null,
)

@Serializable
data class IntakeOutcome(
    val headline: String,
    val effects: List<IntakeEffect>,
    val groupId: GroupId?,
    val undoable: Boolean,
)

/** One actually-persisted change. */
@Serializable
data class IntakeEffect(
    val kind: IntakeEffectKind,
    val label: String,
    val reason: String?,
    val groupId: GroupId? = null,
    val wordId: WordId? = null,
)

@JvmInline
@Serializable
value class IntakeEffectKind(val value: String) {
    companion object {
        val CreatedGroup = IntakeEffectKind("created_group")
        val JoinedGroup = IntakeEffectKind("joined_group")
        val ReusedGroup = IntakeEffectKind("reused_group")
        val CreatedWord = IntakeEffectKind("created_word")
        val ReusedWord = IntakeEffectKind("reused_word")
        val SavedUngrouped = IntakeEffectKind("saved_ungrouped")
    }
}

/** Backend asks the user to decide (ambiguous group, possible misspelling). */
@Serializable
data class IntakeChoice(
    val prompt: String,
    val allowMultiple: Boolean,
    val options: List<IntakeOption>,
)

@Serializable
data class IntakeOption(val id: String, val label: String, val detail: String?, val kind: String /* group | new_group | spelling | keep */)

interface IntakeApi {
    fun requirement(): Flow<ApiRequirement>
    /** Idempotent per [request]: resubmitting returns the same operation. */
    suspend fun submit(input: IntakeRequest, request: ClientRequestId): SdkResult<OperationId>
    fun observe(id: OperationId): Flow<IntakeState?>
    suspend fun resolve(id: OperationId, optionIds: List<String>, request: ClientRequestId): SdkResult<OperationId>
}
