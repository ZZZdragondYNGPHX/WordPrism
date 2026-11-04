package app.wordprism.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@JvmInline @Serializable value class WordId(val value: String)
@JvmInline @Serializable value class GroupId(val value: String)
@JvmInline @Serializable value class SenseId(val value: String)

@JvmInline
@Serializable
value class RelationKind(val value: String) {
    companion object {
        val FormSimilar = RelationKind("form_similar")
        val Synonym = RelationKind("synonym")
        val WordFamily = RelationKind("word_family")
        val SoundAlike = RelationKind("sound_alike")
        val Personal = RelationKind("personal")
    }
}

/** Backend-provided learning status label for a word or group. */
@JvmInline
@Serializable
value class StudyStatus(val value: String) {
    companion object {
        val NotStarted = StudyStatus("not_started")
        val Learning = StudyStatus("learning")
        val Due = StudyStatus("due")
        val Stable = StudyStatus("stable")
        val Paused = StudyStatus("paused")
    }
}

@Serializable
data class WordSummary(
    val id: WordId,
    val lemma: String,
    val partOfSpeech: String?,
    val glossZh: String?,
    val groupIds: List<GroupId>,
    val status: StudyStatus,
    /** True when the word has no reliable group yet ("待整理"). */
    val needsGrouping: Boolean,
)

@Serializable
data class GroupSummary(
    val id: GroupId,
    val title: String,
    val relation: RelationKind,
    val purpose: String?,
    val members: List<MemberRef>,
    val status: StudyStatus,
    val dueCount: Int,
    val version: Long,
)

@Serializable
data class MemberRef(val wordId: WordId, val lemma: String)

@Serializable
data class LibraryQuery(val text: String = "", val relation: RelationKind? = null, val onlyNeedsGrouping: Boolean = false)

@Serializable
data class LibraryPage(
    val groups: List<GroupSummary>,
    val ungroupedWords: List<WordSummary>,
    val totalWords: Int,
    val totalGroups: Int,
)

@Serializable
data class Sense(val id: SenseId, val partOfSpeech: String, val glossZh: String, val glossEn: String? = null, val version: Long = 1,
    val examples: List<Example> = emptyList())

@Serializable
data class Example(val text: String, val source: ContentSource, val translationZh: String? = null)

/**
 * A group member as displayed on the contrast view.
 * @property formHighlights character index ranges (inclusive start, exclusive end) in [alignedForm], or [lemma] when unaligned,
 *   that differ from the other members — computed by the backend, rendered by the UI.
 * @property alignedForm lemma padded with spaces so members align letter-by-letter
 *   (backend may compute an alignment; mock uses a simple heuristic). Null = render unaligned.
 */
@Serializable
data class GroupMember(
    val wordId: WordId,
    val lemma: String,
    val senses: List<Sense>,
    val formHighlights: List<IntRangeDto>,
    val alignedForm: String? = null,
    val collocations: List<String>,
    val examples: List<Example>,
    val note: String?,
    val status: StudyStatus,
    val joinReason: String?,
)

@Serializable
data class IntRangeDto(val start: Int, val endExclusive: Int)

@Serializable
data class GroupDetail(
    val id: GroupId,
    val title: String,
    val relation: RelationKind,
    val purpose: String?,
    val oneLineDifference: String?,
    val members: List<GroupMember>,
    val source: ContentSource,
    val version: Long,
    val hasDisputedContent: Boolean,
)

@Serializable
data class WordDetail(
    val id: WordId,
    val lemma: String,
    val senses: List<Sense>,
    val note: String?,
    val groups: List<GroupSummary>,
    val status: StudyStatus,
    val version: Long,
)

@Serializable
data class WordPatch(val note: String? = null, val glossZh: String? = null, val senseId: SenseId? = null, val senseVersion: Long? = null)

@Serializable
data class MembershipChange(val groupId: GroupId, val add: List<WordId> = emptyList(), val remove: List<WordId> = emptyList())

@Serializable
data class Recommendation(
    val id: String,
    val lemma: String,
    val glossZh: String?,
    val reason: String,
    /** True if accepting needs a model call to generate content. */
    val requiresApi: Boolean,
    val alreadyInLibrary: Boolean,
    val wordId: WordId? = null,
)

@Serializable
data class RecommendationSet(
    val groupId: GroupId,
    val items: List<Recommendation>,
    val generatedAt: EpochMillis?,
    val source: ContentSource?,
)

interface LibraryApi {
    fun observeLibrary(query: LibraryQuery): Flow<LibraryPage>
    fun observeGroup(id: GroupId): Flow<GroupDetail?>
    fun observeWord(id: WordId): Flow<WordDetail?>
    /** Offline read of saved support and its durable task. Never initiates generation. */
    fun observeExplanation(senseId: SenseId): Flow<ExplanationDetail?>
    fun explanationRequirement(): Flow<ApiRequirement>
    suspend fun requestExplanation(senseId: SenseId, expectedVersion: Long, request: ClientRequestId): SdkResult<OperationId>
    suspend fun updateWord(id: WordId, expectedVersion: Long, patch: WordPatch, request: ClientRequestId): SdkResult<WriteReceipt>
    suspend fun changeMembership(change: MembershipChange, expectedVersion: Long, request: ClientRequestId): SdkResult<WriteReceipt>
    suspend fun renameGroup(id: GroupId, title: String, expectedVersion: Long, request: ClientRequestId): SdkResult<WriteReceipt>

    /** Already stored recommendations — never triggers a model call. */
    fun observeRecommendations(groupId: GroupId): Flow<RecommendationSet>
    fun recommendationRequirement(): Flow<ApiRequirement>
    fun acceptRequirement(groupId: GroupId, ids: List<String>): Flow<ApiRequirement> = recommendationRequirement()
    /** Shared acceptance path for candidates without a group, including word families. */
    fun acceptRequirement(ids: List<String>): Flow<ApiRequirement>
    /** User-initiated search for more candidates (calls `word_recommendation`). */
    suspend fun requestRecommendations(groupId: GroupId, request: ClientRequestId): SdkResult<OperationId>
    /** Adds selected candidates; may call the API for content. Result is undoable via [OperationsApi.undo]. */
    suspend fun acceptRecommendations(groupId: GroupId, ids: List<String>, request: ClientRequestId): SdkResult<OperationId>
    suspend fun acceptRecommendations(ids: List<String>, request: ClientRequestId): SdkResult<OperationId>
}
