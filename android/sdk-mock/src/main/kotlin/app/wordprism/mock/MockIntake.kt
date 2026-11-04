package app.wordprism.mock

import app.wordprism.sdk.*
import kotlinx.coroutines.flow.*

/**
 * Mock of the intake pipeline. Real grouping is retrieval + constrained model output + validation
 * in the Codex core; here a handful of deterministic rules produce each outcome the UI must handle.
 */
internal class MockIntake(private val c: MockCore) : IntakeApi {

    init { c.handlers[OperationKind.Intake] = ::run }

    private val wordRegex = Regex("[A-Za-z][A-Za-z'-]{1,30}")
    private val confusionHints = listOf("混", "分不清", "搞错", "弄错", "像", "相似", "confus")

    override fun requirement() = c.observeRequirement(FeatureId.WordIntake)

    override suspend fun submit(input: IntakeRequest, request: ClientRequestId): SdkResult<OperationId> {
        val words = wordRegex.findAll(input.text).map { it.value.lowercase() }.distinct().toList()
        if (words.isEmpty()) return SdkResult.Err(SdkError(ErrorCode.Validation, "没有识别到英文单词。试试“linear 和 liner 总是搞混”。", false, mapOf("text" to "没有识别到英文单词")))
        if (words.size > 8) return SdkResult.Err(SdkError(ErrorCode.Validation, "一次最多添加 8 个词，可以分几次输入", false, mapOf("text" to "一次最多 8 个词")))
        val r = c.tx { d ->
            c.existing(d, request)?.let { return@tx d to SdkResult.Ok(OperationId(it)) }
            c.blockingError(c.requirement(d, FeatureId.WordIntake))?.let { return@tx d to SdkResult.Err(it) }
            val op = c.newOperation(d, OperationKind.Intake, "AI 添加：${words.joinToString(" / ")}", FeatureId.WordIntake)
            val n = with(c) { d.withOp(op).remember(request, op.id.value) }
                .copy(intakes = d.intakes + (op.id.value to MIntake(op.id.value, input.text, words = words)))
            n to SdkResult.Ok(op.id)
        }
        r.onOk { c.launch(it.value) }
        return r
    }

    override fun observe(id: OperationId) = c.db.map { d ->
        val op = d.operations[id.value] ?: return@map null
        val rec = d.intakes[id.value] ?: return@map null
        IntakeState(op, rec.input, rec.outcome, rec.choice.takeIf { op.status == OperationStatus.NeedsInput })
    }.distinctUntilChanged()

    override suspend fun resolve(id: OperationId, optionIds: List<String>, request: ClientRequestId): SdkResult<OperationId> {
        if (optionIds.isEmpty()) return SdkResult.Err(SdkError(ErrorCode.Validation, "请选择一项", false))
        val r = c.tx { d ->
            val op = d.operations[id.value] ?: return@tx d to SdkResult.Err(SdkError(ErrorCode.NotFound, "任务不存在", false))
            if (op.status != OperationStatus.NeedsInput) return@tx d to SdkResult.Ok(id) // already resolved: idempotent
            val rec = d.intakes.getValue(id.value)
            val (n, summary) = apply(d, id.value, rec, optionIds)
            with(c) { n.updateOp(id.value) { it.copy(status = OperationStatus.Succeeded, actions = undoActions(n, id.value), resultSummary = summary) } } to SdkResult.Ok(id)
        }
        return r
    }

    private suspend fun run(opId: String) {
        if (!c.aiStep(opId, UsageFigure(1, 1400, 620))) return
        c.commit(opId) { d ->
            val rec = d.intakes.getValue(opId)
            val choice = decideChoice(d, rec)
            if (choice != null) {
                val n = with(c) { d.copy(intakes = d.intakes + (opId to rec.copy(choice = choice))).updateOp(opId) { it.copy(status = OperationStatus.NeedsInput, actions = setOf(OperationAction.Resolve, OperationAction.Cancel)) } }
                n to Unit
            } else {
                val (n, summary) = apply(d, opId, rec, emptyList())
                with(c) { n.updateOp(opId) { it.copy(status = OperationStatus.Succeeded, actions = undoActions(n, opId), resultSummary = summary) } } to Unit
            }
        }
    }

    private fun undoActions(d: MockDb, opId: String) =
        if (d.intakes[opId]?.outcome?.undoable == true) setOf(OperationAction.Undo) else emptySet()

    private fun groupsContaining(d: MockDb, lemma: String) = d.groups.values.filter { g -> g.memberIds.any { d.words[it]?.lemma == lemma } }

    /** Returns a question for the user, or null if the mock can act directly. */
    private fun decideChoice(d: MockDb, rec: MIntake): IntakeChoice? {
        val words = rec.words
        val misspelt = words.firstOrNull { w -> MockLexicon.get(w) == null && d.words.values.none { it.lemma == w } && MockLexicon.closestKnown(w) != null }
        if (misspelt != null) {
            val s = MockLexicon.closestKnown(misspelt)!!
            return IntakeChoice("「$misspelt」可能拼错了", false, listOf(
                IntakeOption("spell:$misspelt:$s", "改为 $s", MockLexicon.get(s)?.glossZh, "spelling"),
                IntakeOption("keep", "保留 $misspelt", "按原样保存，不做替换", "keep"),
            ))
        }
        val related = words.flatMap { groupsContaining(d, it) }.distinctBy { it.id }
        if (words.size == 1 && related.isEmpty()) {
            val w = words.single()
            val candidates = d.groups.values.filter { g -> g.memberIds.any { id -> d.words[id]?.let { MockLexicon.editDistance(it.lemma, w) <= 2 } == true } }
            val userSaysConfused = confusionHints.any { rec.input.contains(it, ignoreCase = true) }
            if (candidates.size == 1 && userSaysConfused) return null // clear match + user-stated confusion: join directly
            if (candidates.isNotEmpty()) {
                return IntakeChoice("「$w」可能属于这些词组", true,
                    candidates.map { IntakeOption("group:${it.id}", it.title, "拼写接近：${it.purpose}", "group") } +
                        IntakeOption("keep", "先单独保存", "作为待整理词条，以后再归组", "keep"))
            }
        }
        if (related.size > 1) {
            return IntakeChoice("这些词同时出现在 ${related.size} 个词组里", true,
                related.map { IntakeOption("group:${it.id}", it.title, it.purpose, "group") } +
                    IntakeOption("new", "另建一个新词组", "把这次输入的词单独成组", "new_group"))
        }
        return null
    }

    private fun apply(d0: MockDb, opId: String, rec: MIntake, options: List<String>): Pair<MockDb, String> {
        var d = d0
        val now = c.clock()
        var words = rec.words
        options.firstOrNull { it.startsWith("spell:") }?.let { o -> val (_, from, to) = o.split(":"); words = words.map { if (it == from) to else it } }
        val effects = mutableListOf<IntakeEffect>()
        val createdWords = mutableListOf<String>(); val createdGroups = mutableListOf<String>(); val added = mutableListOf<Pair<String, String>>()
        val wordIds = words.map { lemma ->
            d.words.values.firstOrNull { it.lemma == lemma }?.let { effects += IntakeEffect(IntakeEffectKind.ReusedWord, "沿用已有词条 $lemma", "保留原有释义和学习进度", wordId = WordId(it.id)); return@map it.id }
            val w = MockSeed.word(lemma, now, StudyStatus.NotStarted, ContentSource("ai_generated", c.presetFor(d, FeatureId.WordIntake)?.name, c.presetFor(d, FeatureId.WordIntake)?.version, "mock-model-small", now))
            d = d.copy(words = d.words + (w.id to w)); createdWords += w.id
            effects += IntakeEffect(IntakeEffectKind.CreatedWord, "新建词条 $lemma", MockLexicon.get(lemma)?.glossZh ?: "释义为模拟生成", wordId = WordId(w.id))
            w.id
        }
        val chosenGroups = options.filter { it.startsWith("group:") }.map { it.removePrefix("group:") }
        val keepAlone = "keep" in options && chosenGroups.isEmpty()
        val related = words.flatMap { groupsContaining(d, it) }.distinctBy { it.id }
        val exact = related.firstOrNull { g -> g.memberIds.toSet() == wordIds.toSet() }
        val targetGroups: List<MGroup> = when {
            chosenGroups.isNotEmpty() -> chosenGroups.mapNotNull { d.groups[it] }
            keepAlone || "new" in options -> emptyList()
            exact != null -> listOf(exact)
            related.size == 1 -> related
            wordIds.size == 1 -> d.groups.values.filter { g -> g.memberIds.any { id -> d.words[id]?.let { MockLexicon.editDistance(it.lemma, words[0]) <= 2 } == true } }.take(1)
            else -> emptyList()
        }
        var groupId: String? = null
        if (exact != null && chosenGroups.isEmpty() && "new" !in options) {
            effects.add(0, IntakeEffect(IntakeEffectKind.ReusedGroup, "词组「${exact.title}」已存在", "没有重复创建，可以直接学习或补充例句", GroupId(exact.id)))
            groupId = exact.id
        } else if (targetGroups.isNotEmpty()) {
            targetGroups.forEach { g ->
                val newMembers = wordIds.filter { it !in g.memberIds }
                val reason = "你提到它和 ${g.memberIds.mapNotNull { d.words[it]?.lemma }.joinToString(" / ")} 容易混"
                d = d.copy(groups = d.groups + (g.id to g.copy(memberIds = g.memberIds + newMembers, version = g.version + 1, joinReasons = g.joinReasons + newMembers.associateWith { reason })))
                added += newMembers.map { g.id to it }
                effects.add(0, IntakeEffect(IntakeEffectKind.JoinedGroup, "已加入「${g.title}」", reason, GroupId(g.id)))
                groupId = groupId ?: g.id
            }
        } else if (wordIds.size >= 2) {
            val lemmas = wordIds.mapNotNull { d.words[it]?.lemma }
            val formLike = lemmas.zipWithNext().all { (a, b) -> MockLexicon.editDistance(a, b) <= 3 }
            val g = MGroup(c.newId("g"), lemmas.joinToString(" / "), (if (formLike) RelationKind.FormSimilar else RelationKind.Personal).value,
                if (formLike) "分清拼写相近的 ${lemmas.joinToString("、")}" else "你常混用的一组词", wordIds, emptyMap(), 1,
                ContentSource("ai_generated", c.presetFor(d, FeatureId.WordIntake)?.name, c.presetFor(d, FeatureId.WordIntake)?.version, "mock-model-small", now), now)
            d = d.copy(groups = d.groups + (g.id to g)); createdGroups += g.id; groupId = g.id
            effects.add(0, IntakeEffect(IntakeEffectKind.CreatedGroup, "已创建词组「${g.title}」", if (formLike) "拼写相近，按形似辨析整理" else "按你的描述整理为个人易混组", GroupId(g.id)))
        } else {
            effects += IntakeEffect(IntakeEffectKind.SavedUngrouped, "${words.single()} 已保存为待整理词条", "暂时没有可靠的归组依据，可以单独学习，以后再归组", wordId = WordId(wordIds.single()))
        }
        val headline = effects.first().label
        val outcome = IntakeOutcome(headline, effects, groupId?.let(::GroupId), undoable = createdWords.isNotEmpty() || createdGroups.isNotEmpty() || added.isNotEmpty())
        d = d.copy(
            intakes = d.intakes + (opId to rec.copy(outcome = outcome, choice = null)),
            undo = d.undo + (opId to MUndo(createdWordIds = createdWords, createdGroupIds = createdGroups, addedMemberships = added)),
        )
        return d to headline
    }
}
