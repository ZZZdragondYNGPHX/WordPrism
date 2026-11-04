package app.wordprism.mock

import app.wordprism.sdk.*
import kotlinx.coroutines.flow.*

internal fun MWord.summary(d: MockDb) = WordSummary(
    WordId(id), lemma, pos, glossZh, d.groups.values.filter { id in it.memberIds }.map { GroupId(it.id) },
    StudyStatus(status), needsGrouping = d.groups.values.none { id in it.memberIds },
)

internal fun MGroup.summary(d: MockDb): GroupSummary {
    val members = memberIds.mapNotNull { d.words[it] }
    val due = members.count { it.status == StudyStatus.Due.value }
    val status = when {
        due > 0 -> StudyStatus.Due
        members.all { it.status == StudyStatus.NotStarted.value } -> StudyStatus.NotStarted
        members.all { it.status == StudyStatus.Stable.value } -> StudyStatus.Stable
        else -> StudyStatus.Learning
    }
    return GroupSummary(GroupId(id), title, RelationKind(relation), purpose, members.map { MemberRef(WordId(it.id), it.lemma) }, status, due, version)
}

internal fun MGroup.detail(d: MockDb): GroupDetail {
    val members = memberIds.mapNotNull { d.words[it] }
    val aligned = MockLexicon.alignAndHighlight(members.map { it.lemma })
    val diff = when (members.size) {
        0, 1 -> null
        else -> members.joinToString("；") { "${it.lemma} ${it.pos} ${it.glossZh.substringBefore('；')}" }
    }
    return GroupDetail(
        GroupId(id), title, RelationKind(relation), purpose, diff,
        members.mapIndexed { i, w ->
            GroupMember(
                WordId(w.id), w.lemma,
                listOf(Sense(SenseId(w.id + "-s1"), w.pos, w.glossZh, w.glossEn)),
                aligned[i].second.map { IntRangeDto(it.first, it.last + 1) }, aligned[i].first,
                w.collocations, listOf(Example(w.example, w.source, w.exampleZh)), w.note, StudyStatus(w.status), joinReasons[w.id],
            )
        },
        source, version, hasDisputedContent = false,
    )
}

internal class MockLibrary(private val c: MockCore) : LibraryApi {

    init { c.handlers[OperationKind.Recommendation] = ::runRecommend; c.handlers[OperationKind.AcceptRecommendation] = ::runAccept }

    override fun observeLibrary(query: LibraryQuery): Flow<LibraryPage> = c.db.map { d ->
        val q = query.text.trim().lowercase()
        fun wordHit(w: MWord) = q.isEmpty() || w.lemma.contains(q) || w.glossZh.contains(q)
        val groups = d.groups.values
            .filter { g -> query.relation == null || g.relation == query.relation?.value }
            .filter { g -> q.isEmpty() || g.title.lowercase().contains(q) || g.memberIds.any { id -> d.words[id]?.let(::wordHit) == true } }
            .sortedByDescending { it.createdAt }
        val ungrouped = d.words.values.filter { w -> d.groups.values.none { w.id in it.memberIds } && wordHit(w) }
        LibraryPage(
            if (query.onlyNeedsGrouping) emptyList() else groups.map { it.summary(d) },
            if (query.relation != null) emptyList() else ungrouped.sortedBy { it.lemma }.map { it.summary(d) },
            d.words.size, d.groups.size,
        )
    }.distinctUntilChanged()

    override fun observeGroup(id: GroupId) = c.db.map { d -> d.groups[id.value]?.detail(d) }.distinctUntilChanged()

    override fun observeWord(id: WordId) = c.db.map { d ->
        d.words[id.value]?.let { w ->
            WordDetail(WordId(w.id), w.lemma, listOf(Sense(SenseId(w.id + "-s1"), w.pos, w.glossZh, w.glossEn)), w.note,
                d.groups.values.filter { w.id in it.memberIds }.map { it.summary(d) }, StudyStatus(w.status), w.version)
        }
    }.distinctUntilChanged()

    // This optional developer mock does not invent a second explanation engine.
    override fun observeExplanation(senseId: SenseId): Flow<ExplanationDetail?> = flowOf(ExplanationDetail(senseId, 1))
    override fun explanationRequirement() = c.observeRequirement(FeatureId.Explanation)
    override suspend fun requestExplanation(senseId: SenseId, expectedVersion: Long, request: ClientRequestId): SdkResult<OperationId> =
        explanationUnavailable()
    override fun acceptRequirement(ids: List<String>) = c.observeRequirement(FeatureId.WordIntake)
    override suspend fun acceptRecommendations(ids: List<String>, request: ClientRequestId): SdkResult<OperationId> = explanationUnavailable()
    private fun explanationUnavailable() = SdkResult.Err(SdkError(ErrorCode("unsupported_mock"), "开发模拟模式未提供词源与助记，请切换到本机核心", false))

    override suspend fun updateWord(id: WordId, expectedVersion: Long, patch: WordPatch, request: ClientRequestId): SdkResult<WriteReceipt> = c.tx { d ->
        val w = d.words[id.value] ?: return@tx d to notFound()
        if (w.version != expectedVersion) return@tx d to conflict()
        if (patch.glossZh?.isBlank() == true) return@tx d to SdkResult.Err(SdkError(ErrorCode.Validation, "释义不能为空", false, mapOf("glossZh" to "释义不能为空")))
        val updated = w.copy(note = patch.note?.ifBlank { null } ?: w.note.takeIf { patch.note == null }, glossZh = patch.glossZh ?: w.glossZh, version = w.version + 1, source = if (patch.glossZh != null) ContentSource("user") else w.source)
        val op = c.newOperation(d, OperationKind.Edit, "编辑 ${w.lemma}", null, OperationStatus.Succeeded).copy(actions = setOf(OperationAction.Undo))
        val n = with(c) { d.copy(words = d.words + (w.id to updated), undo = d.undo + (op.id.value to MUndo(wordBefore = w))).withOp(op) }
        n to SdkResult.Ok(WriteReceipt(updated.version, op.id, true, "已保存 ${w.lemma}"))
    }

    override suspend fun changeMembership(change: MembershipChange, expectedVersion: Long, request: ClientRequestId): SdkResult<WriteReceipt> = c.tx { d ->
        val g = d.groups[change.groupId.value] ?: return@tx d to notFound()
        if (g.version != expectedVersion) return@tx d to conflict()
        val remove = change.remove.map { it.value }.filter { it in g.memberIds }
        val add = change.add.map { it.value }.filter { it !in g.memberIds && it in d.words }
        if (g.memberIds.size - remove.size + add.size < 1) return@tx d to SdkResult.Err(SdkError(ErrorCode.Validation, "词组至少保留一个成员", false))
        val ng = g.copy(memberIds = g.memberIds - remove.toSet() + add, version = g.version + 1)
        val label = buildString {
            if (remove.isNotEmpty()) append("已从「${g.title}」移出 ${remove.joinToString { d.words[it]?.lemma ?: it }}")
            if (add.isNotEmpty()) append("已加入 ${add.joinToString { d.words[it]?.lemma ?: it }}")
        }
        val op = c.newOperation(d, OperationKind.Edit, label, null, OperationStatus.Succeeded).copy(actions = setOf(OperationAction.Undo), resultSummary = label)
        val undo = MUndo(addedMemberships = add.map { g.id to it }, removedMemberships = remove.map { g.id to it })
        val n = with(c) { d.copy(groups = d.groups + (g.id to ng), undo = d.undo + (op.id.value to undo)).withOp(op) }
        n to SdkResult.Ok(WriteReceipt(ng.version, op.id, true, label + "。词条和学习记录保留。"))
    }

    override suspend fun renameGroup(id: GroupId, title: String, expectedVersion: Long, request: ClientRequestId): SdkResult<WriteReceipt> = c.tx { d ->
        val g = d.groups[id.value] ?: return@tx d to notFound()
        if (g.version != expectedVersion) return@tx d to conflict()
        if (title.isBlank()) return@tx d to SdkResult.Err(SdkError(ErrorCode.Validation, "名称不能为空", false, mapOf("title" to "名称不能为空")))
        val op = c.newOperation(d, OperationKind.Edit, "重命名词组", null, OperationStatus.Succeeded).copy(actions = setOf(OperationAction.Undo))
        val ng = g.copy(title = title.trim(), version = g.version + 1)
        val n = with(c) { d.copy(groups = d.groups + (g.id to ng), undo = d.undo + (op.id.value to MUndo(groupTitleBefore = g.id to g.title))).withOp(op) }
        n to SdkResult.Ok(WriteReceipt(ng.version, op.id, true, "已重命名"))
    }

    override fun observeRecommendations(groupId: GroupId) = c.db.map { d ->
        val items = d.recommendations[groupId.value].orEmpty().map { r -> r.copy(alreadyInLibrary = d.words.values.any { it.lemma == r.lemma }) }
        RecommendationSet(groupId, items, null, ContentSource("ai_generated", presetName = c.presetFor(d, FeatureId.WordRecommendation)?.name))
    }.distinctUntilChanged()

    override fun recommendationRequirement() = c.observeRequirement(FeatureId.WordRecommendation)

    override suspend fun requestRecommendations(groupId: GroupId, request: ClientRequestId): SdkResult<OperationId> {
        val r = c.tx { d ->
            c.existing(d, request)?.let { return@tx d to SdkResult.Ok(OperationId(it)) }
            val g = d.groups[groupId.value] ?: return@tx d to notFound()
            c.blockingError(c.requirement(d, FeatureId.WordRecommendation))?.let { return@tx d to SdkResult.Err(it) }
            val op = c.newOperation(d, OperationKind.Recommendation, "为「${g.title}」寻找相似词", FeatureId.WordRecommendation)
                .copy(resultSummary = groupId.value)
            with(c) { d.withOp(op).remember(request, op.id.value) } to SdkResult.Ok(op.id)
        }
        r.onOk { c.launch(it.value) }
        return r
    }

    private suspend fun runRecommend(opId: String) {
        if (!c.aiStep(opId)) return
        c.commit(opId) { d ->
            val gid = d.operations.getValue(opId).resultSummary ?: ""
            val g = d.groups[gid]
            val existing = d.recommendations[gid].orEmpty().map { it.lemma }.toSet()
            val memberLemmas = g?.memberIds?.mapNotNull { d.words[it]?.lemma }.orEmpty()
            val candidates = memberLemmas.flatMap { MockLexicon.get(it)?.related.orEmpty() }
                .filter { it !in existing && it !in memberLemmas }.distinct().take(3)
                .ifEmpty { listOf(memberLemmas.firstOrNull()?.let { it + "s" } ?: "mock") }
            val recs = candidates.map { lemma ->
                val e = MockLexicon.get(lemma)
                Recommendation("r-$lemma", lemma, e?.glossZh, "与 ${memberLemmas.minByOrNull { MockLexicon.editDistance(it, lemma) }} 拼写接近（模拟推荐）", requiresApi = true, alreadyInLibrary = d.words.containsKey("w-$lemma"))
            }
            val n = with(c) {
                d.copy(recommendations = d.recommendations + (gid to (d.recommendations[gid].orEmpty() + recs)))
                    .updateOp(opId) { it.copy(status = OperationStatus.Succeeded, actions = emptySet(), resultSummary = "找到 ${recs.size} 个候选词") }
            }
            n to Unit
        }
    }

    override suspend fun acceptRecommendations(groupId: GroupId, ids: List<String>, request: ClientRequestId): SdkResult<OperationId> {
        val r = c.tx { d ->
            c.existing(d, request)?.let { return@tx d to SdkResult.Ok(OperationId(it)) }
            val g = d.groups[groupId.value] ?: return@tx d to notFound()
            val picked = d.recommendations[g.id].orEmpty().filter { it.id in ids }
            if (picked.isEmpty()) return@tx d to SdkResult.Err(SdkError(ErrorCode.Validation, "请选择要加入的词", false))
            val needsApi = picked.any { it.requiresApi && !d.words.containsKey("w-${it.lemma}") }
            if (needsApi) c.blockingError(c.requirement(d, FeatureId.Explanation))?.let { return@tx d to SdkResult.Err(it) }
            val op = c.newOperation(d, OperationKind.AcceptRecommendation, "加入 ${picked.joinToString { it.lemma }}", if (needsApi) FeatureId.Explanation else null)
                .copy(resultSummary = g.id + "|" + picked.joinToString(",") { it.id })
            with(c) { d.withOp(op).remember(request, op.id.value) } to SdkResult.Ok(op.id)
        }
        r.onOk { c.launch(it.value) }
        return r
    }

    private suspend fun runAccept(opId: String) {
        val op = c.db.value.operations[opId] ?: return
        if (op.feature != null && !c.aiStep(opId)) return
        c.commit(opId) { d ->
            val (gid, idList) = (op.resultSummary ?: "|").split("|", limit = 2)
            val g = d.groups[gid] ?: return@commit d to Unit
            val picked = d.recommendations[gid].orEmpty().filter { it.id in idList.split(",") }
            val now = c.clock()
            val created = picked.filter { !d.words.containsKey("w-${it.lemma}") }.map { MockSeed.word(it.lemma, now, StudyStatus.NotStarted) }
            val wordIds = picked.map { "w-${it.lemma}" }.filter { it !in g.memberIds }
            val ng = g.copy(memberIds = g.memberIds + wordIds, version = g.version + 1, joinReasons = g.joinReasons + picked.associate { "w-${it.lemma}" to it.reason })
            val summary = "已加入 ${picked.joinToString { it.lemma }}，新增 ${created.size} 个词条"
            val n = with(c) {
                d.copy(
                    words = d.words + created.associateBy { it.id }, groups = d.groups + (gid to ng),
                    recommendations = d.recommendations + (gid to d.recommendations[gid].orEmpty().filter { it !in picked }),
                    undo = d.undo + (opId to MUndo(createdWordIds = created.map { it.id }, addedMemberships = wordIds.map { gid to it })),
                ).updateOp(opId) { it.copy(status = OperationStatus.Succeeded, actions = setOf(OperationAction.Undo), resultSummary = summary) }
            }
            n to Unit
        }
    }

    private fun notFound() = SdkResult.Err(SdkError(ErrorCode.NotFound, "内容已不存在，可能已被撤销或删除", false))
    private fun conflict() = SdkResult.Err(SdkError(ErrorCode.VersionConflict, "内容已在别处更新，已载入最新版本，请再确认一次修改", true))
}

/** Reverts exactly what an operation changed. Shared by every undoable operation. */
internal suspend fun MockCore.undoOperation(id: OperationId, request: ClientRequestId): SdkResult<UndoOutcome> = tx { d ->
    val op = d.operations[id.value] ?: return@tx d to SdkResult.Err(SdkError(ErrorCode.NotFound, "操作不存在", false))
    val u = d.undo[id.value]
    if (u == null || u.done || OperationAction.Undo !in op.actions) {
        return@tx d to SdkResult.Err(SdkError(ErrorCode.NotUndoable, "这项操作已撤销或不能撤销", false))
    }
    val answeredLemmas = d.answers.mapNotNull { it.target }.toSet()
    var groups = d.groups
    u.addedMemberships.forEach { (g, w) -> groups[g]?.let { groups = groups + (g to it.copy(memberIds = it.memberIds - w, version = it.version + 1)) } }
    u.removedMemberships.forEach { (g, w) -> groups[g]?.let { groups = groups + (g to it.copy(memberIds = it.memberIds + w, version = it.version + 1)) } }
    u.groupTitleBefore?.let { (g, t) -> groups[g]?.let { groups = groups + (g to it.copy(title = t, version = it.version + 1)) } }
    val keptWords = u.createdWordIds.filter { d.words[it]?.lemma in answeredLemmas }
    val removedGroups = u.createdGroupIds.filter { gid -> groups[gid]?.memberIds?.all { it in u.createdWordIds } != false }
    groups = groups - removedGroups.toSet()
    var words = d.words - (u.createdWordIds - keptWords.toSet()).toSet()
    u.wordBefore?.let { before -> words[before.id]?.let { cur -> words = words + (before.id to before.copy(version = cur.version + 1)) } }
    val preserved = buildList {
        if (keptWords.isNotEmpty()) add("已有作答的 ${keptWords.joinToString { d.words[it]!!.lemma }} 保留为待整理词条")
        if (d.answers.isNotEmpty()) add("学习记录保持不变")
    }
    val undoOp = newOperation(d, OperationKind.Undo, "撤销：${op.title}", null, OperationStatus.Succeeded)
    val n = d.copy(words = words, groups = groups, undo = d.undo + (id.value to u.copy(done = true)))
        .withOp(undoOp).updateOp(id.value) { it.copy(status = OperationStatus.Undone, actions = emptySet()) }
    n to SdkResult.Ok(UndoOutcome(undoOp.id, "已撤销：${op.title}", preserved))
}
