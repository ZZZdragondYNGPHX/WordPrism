package app.wordprism.codex

import app.wordprism.sdk.*
import dev.wordprism.contract.Command
import dev.wordprism.contract.Query
import dev.wordprism.contract.Ref
import dev.wordprism.contract.View
import kotlinx.coroutines.flow.*
import dev.wordprism.contract.Id as CId
import dev.wordprism.contract.Feature as CFeature
import dev.wordprism.contract.Relation as CRelation
import dev.wordprism.contract.StudySummary as CStudy
import dev.wordprism.contract.Sense as CSense

internal fun CRelation.toSdk() = when (this) {
    CRelation.LOOK_ALIKE -> RelationKind.FormSimilar; CRelation.SYNONYM -> RelationKind.Synonym
    CRelation.WORD_FAMILY -> RelationKind.WordFamily; CRelation.SOUND_ALIKE -> RelationKind.SoundAlike; CRelation.PERSONAL -> RelationKind.Personal
}
internal fun RelationKind.toCore() = CRelation.entries.firstOrNull { it.toSdk() == this }
internal fun CStudy?.toSdk() = StudyStatus(this?.state?.name?.lowercase() ?: "not_started")
internal fun CSense.toSdk() = Sense(SenseId(id.value), partOfSpeech, meaning, version = version)
internal fun dev.wordprism.contract.GroupOverview.toSdk() = GroupSummary(GroupId(group.id.value), group.title, group.relation.toSdk(), group.purpose,
    members.map { MemberRef(WordId(it.id.value), it.spelling) }, study.toSdk(), study.dueCount, group.version)

internal fun View.Library.group(id: GroupId): GroupDetail? {
    val g = groups.firstOrNull { it.id.value == id.value } ?: return null
    val links = memberships.filter { it.groupId == g.id && it.active }
    val members = words.mapNotNull { word ->
        val ss = senses.filter { sense -> sense.wordId == word.id && links.any { it.senseId == sense.id } }
        if (ss.isEmpty()) null else {
            val alignment = alignments.firstOrNull { it.wordId == word.id }
            GroupMember(WordId(word.id.value), word.spelling, ss.map { it.toSdk() }, alignment?.different.orEmpty().map { IntRangeDto(it.start, it.endExclusive) },
                alignment?.aligned, emptyList(), ss.flatMap { s -> s.examples.map { Example(it, s.source.toSdk()) } }, word.notes.ifBlank { null },
                wordStudy[word.id].toSdk(), links.filter { l -> ss.any { it.id == l.senseId } }.map { it.reason }.distinct().joinToString("；"))
        }
    }
    return GroupDetail(id, g.title, g.relation.toSdk(), g.purpose, null, members, links.firstOrNull()?.source?.toSdk() ?: ContentSource("user"), g.version, hasDisputedContent)
}

class CodexLibrary(private val b: CodexBridge) : LibraryApi {
    override fun observeLibrary(query: LibraryQuery): Flow<LibraryPage> = b.live {
        val data = (b.view(Query.LibrarySummary(query.text, query.relation?.toCore(), query.onlyNeedsGrouping)) as View.LibrarySummary).library
        LibraryPage(data.groups.map { it.toSdk() }, data.ungroupedWords.map { w ->
            val sense = data.senses.firstOrNull { it.wordId == w.id }
            WordSummary(WordId(w.id.value), w.spelling, sense?.partOfSpeech, sense?.meaning, emptyList(), data.wordStudy[w.id].toSdk(), true)
        }, data.totalWords, data.totalGroups)
    }
    override fun observeGroup(id: GroupId): Flow<GroupDetail?> = b.live(Query.GetGroup(CId(id.value))) { (it as? View.Library)?.group(id) }
    override fun observeWord(id: WordId): Flow<WordDetail?> = b.live {
        val reply = b.core.query(Query.GetWord(CId(id.value)))
        if (reply.fault?.code == dev.wordprism.contract.ErrorCode.NOT_FOUND) null else {
            reply.fault?.let { throw ReadFailure(it.toSdk()) }
            val v = reply.value as View.Library; val w = v.words.first()
            val groups = (b.view(Query.LibrarySummary()) as View.LibrarySummary).library.groups.filter { g -> g.members.any { it.id == w.id } }
            WordDetail(id, w.spelling, v.senses.map { it.toSdk() }, w.notes.ifBlank { null }, groups.map { it.toSdk() }, v.wordStudy[w.id].toSdk(), w.version)
        }
    }
    override suspend fun updateWord(id: WordId, expectedVersion: Long, patch: WordPatch, request: ClientRequestId): SdkResult<WriteReceipt> = safely {
        val result = b.exec(request, Command.EditWord(Ref(CId(id.value), expectedVersion), patch.note,
            patch.senseId?.let { Ref(CId(it.value), patch.senseVersion ?: return@safely invalid<WriteReceipt>("需要义项版本")) }, patch.glossZh)) { it as? View.OperationResult }
        if (result is SdkResult.Err) result else {
            val version = (b.view(Query.GetWord(CId(id.value))) as View.Library).words.first().version
            SdkResult.Ok(receipt((result as SdkResult.Ok).value, version))
        }
    }
    override suspend fun changeMembership(change: MembershipChange, expectedVersion: Long, request: ClientRequestId): SdkResult<WriteReceipt> = safely {
        val result = b.exec(request, Command.EditMemberships(Ref(CId(change.groupId.value), expectedVersion), change.add.map { CId(it.value) }, change.remove.map { CId(it.value) }, "用户修改归组")) { it as? View.OperationResult }
        if (result is SdkResult.Err) result else {
            val version = (b.view(Query.GetGroup(CId(change.groupId.value))) as View.Library).groups.first().version
            SdkResult.Ok(receipt((result as SdkResult.Ok).value, version))
        }
    }
    override suspend fun renameGroup(id: GroupId, title: String, expectedVersion: Long, request: ClientRequestId): SdkResult<WriteReceipt> = safely {
        val result = b.exec(request, Command.RenameGroup(Ref(CId(id.value), expectedVersion), title)) { it as? View.OperationResult }
        if (result is SdkResult.Err) result else {
            val version = (b.view(Query.GetGroup(CId(id.value))) as View.Library).groups.first().version
            SdkResult.Ok(receipt((result as SdkResult.Ok).value, version))
        }
    }
    private fun receipt(v: View.OperationResult, version: Long) = WriteReceipt(version, OperationId(v.operation.id.value), v.operation.undoable, v.operation.summary.ifBlank { "修改已保存" })
    override fun observeRecommendations(groupId: GroupId): Flow<RecommendationSet> = b.live {
        val data = (b.view(Query.Recommendations(CId(groupId.value))) as View.Recommendations).recommendations
        RecommendationSet(groupId, data.map { Recommendation(it.id.value, it.spelling, it.content?.meaning, it.reason, it.content == null, it.inLibrary) },
            data.maxOfOrNull { it.source.atMs }, data.firstOrNull()?.source?.toSdk())
    }
    override fun recommendationRequirement() = b.requirement(CFeature.WORD_RECOMMENDATION)
    override fun acceptRequirement(groupId: GroupId, ids: List<String>) = b.requirement(CFeature.WORD_INTAKE, ids.map(::CId))
    override suspend fun requestRecommendations(groupId: GroupId, request: ClientRequestId): SdkResult<OperationId> = safely {
        val group = (b.view(Query.GetGroup(CId(groupId.value))) as View.Library).groups.first()
        b.exec(request, Command.RequestRecommendations(Ref(group.id, group.version))) { (it as? View.OperationResult)?.operation?.id?.value?.let(::OperationId) }
    }
    override suspend fun acceptRecommendations(groupId: GroupId, ids: List<String>, request: ClientRequestId): SdkResult<OperationId> =
        b.exec(request, Command.AcceptRecommendations(ids.map(::CId), allowApi = true)) { (it as? View.OperationResult)?.operation?.id?.value?.let(::OperationId) }
}

class CodexIntake(private val b: CodexBridge) : IntakeApi {
    override fun requirement() = b.requirement(CFeature.WORD_INTAKE)
    override suspend fun submit(input: IntakeRequest, request: ClientRequestId) = b.exec(request, Command.SubmitIntake(input.text)) { (it as? View.OperationResult)?.operation?.id?.value?.let(::OperationId) }
    override fun observe(id: OperationId): Flow<IntakeState?> = b.live {
        val op = (b.optionalView(Query.GetOperation(CId(id.value))) as? View.OperationResult)?.operation ?: return@live null
        val groups = if (op.choices.isNotEmpty()) (b.view(Query.LibrarySummary()) as View.LibrarySummary).library.groups else emptyList()
        IntakeState(op.toSdk(), op.input.orEmpty(),
            if (op.status == dev.wordprism.contract.OperationStatus.SUCCEEDED) IntakeOutcome(op.summary.ifBlank { "内容已保存" },
                op.effects.map { IntakeEffect(IntakeEffectKind(it.kind), it.label, it.reason, it.groupId?.value?.let(::GroupId), it.wordId?.value?.let(::WordId)) },
                op.effects.firstNotNullOfOrNull { it.groupId }?.value?.let(::GroupId), op.undoable) else null,
            if (op.status == dev.wordprism.contract.OperationStatus.NEEDS_INPUT) op.spellingChoices.firstOrNull()?.let { choice ->
                val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(choice.original.toByteArray(Charsets.UTF_8))
                IntakeChoice("确认 ${choice.original} 的拼写", false, listOf(
                    IntakeOption("spelling:correct:$key", "改为 ${choice.suggested}", choice.reason, "spelling"),
                    IntakeOption("spelling:keep:$key", "保留 ${choice.original}", "保留原始拼写与原输入", "keep")))
            } ?: IntakeChoice("请选择归组方式（已有组可多选）", true,
                op.choices.map { choice ->
                    val group = groups.firstOrNull { it.group.id == choice.groupId }?.group
                    IntakeOption("group:${choice.groupId.value}:${choice.version.takeIf { it > 0 } ?: group?.version ?: 1}", group?.title ?: "候选词组", choice.reason, "group")
                } + listOf(IntakeOption("new_group", "另建词组", "按本次输入建立独立词组；单独选择此项", "new_group"),
                    IntakeOption("ungrouped", "先单独保存", "保留词条，稍后整理；单独选择此项", "keep"))) else null)
    }
    override suspend fun resolve(id: OperationId, optionIds: List<String>, request: ClientRequestId): SdkResult<OperationId> = safely {
        if (optionIds.isEmpty() || optionIds.size > 20 || optionIds.distinct().size != optionIds.size) return@safely invalid<OperationId>("请选择有效选项")
        val selected = optionIds.singleOrNull()
        if (selected?.startsWith("spelling:") == true) {
            val parts = selected.split(':')
            if (parts.size != 3 || parts[1] !in setOf("correct", "keep")) return@safely invalid<OperationId>("拼写选择身份无效")
            val original = runCatching { java.util.Base64.getUrlDecoder().decode(parts[2]).toString(Charsets.UTF_8) }.getOrNull()
                ?: return@safely invalid<OperationId>("拼写选择身份无效")
            return@safely b.exec(request, Command.ResolveSpelling(CId(id.value), original, parts[1] == "correct")) { (it as? View.OperationResult)?.operation?.id?.value?.let(::OperationId) }
        }
        val disposition = selected?.takeIf { it in setOf("new_group", "ungrouped") } ?: "groups"
        val refs = if (disposition == "groups") optionIds.map { option ->
            val parts = option.split(':')
            if (parts.size != 3 || parts[0] != "group" || parts[2].toLongOrNull() == null) return@safely invalid<OperationId>("已有组可多选；另建或单独保存需独立选择")
            Ref(CId(parts[1]), parts[2].toLong())
        } else emptyList()
        b.exec(request, Command.ResolveIntake(CId(id.value), refs, disposition)) { (it as? View.OperationResult)?.operation?.id?.value?.let(::OperationId) }
    }
}
