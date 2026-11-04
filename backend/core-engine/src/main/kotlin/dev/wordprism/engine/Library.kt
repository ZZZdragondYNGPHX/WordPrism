package dev.wordprism.engine

import dev.wordprism.contract.*
import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.json.*

internal fun normalize(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
internal fun checkRef(ref: Ref, actual: Long) = requireBusiness(ref.version == actual, "内容已被修改，请重新读取", ErrorCode.VERSION_CONFLICT)

internal fun Database.library(search: String = "", after: Id? = null, limit: Int = 50, groupId: Id? = null, wordId: Id? = null, now: Long = System.currentTimeMillis()): View.Library {
    requireBusiness(limit in 1..200 || groupId != null && limit == 10000, "分页数量须为 1–200")
    val allowed = groupId?.let { gid -> memberships.values.filter { it.groupId == gid && it.active }.mapNotNull { senses[it.senseId]?.wordId }.toSet() }
    val order = groupId?.let { groupWords(it).mapIndexed { i, w -> w.id to i }.toMap() }
    val matching = words.values.filter { (!it.archived || wordId == it.id) && (wordId == null || it.id == wordId) && (allowed == null || it.id in allowed) && (after == null || it.id.value > after.value) &&
        (search.isBlank() || normalize(search) in it.normalized || search in it.notes || it.tags.any { tag -> search in tag }) }
        .sortedWith(compareBy<Word> { order?.get(it.id) ?: 0 }.thenBy { it.id.value }).take(limit + 1)
    val selected = matching.take(limit); val wordIds = selected.map { it.id }.toSet()
    val selectedSenses = senses.values.filter { it.wordId in wordIds }.sortedBy { it.id.value }; val senseIds = selectedSenses.map { it.id }.toSet()
    val links = memberships.values.filter { it.senseId in senseIds && it.active }
    val selectedTargets = targets.values.filter { it.senseId in senseIds }
    val selectedGroups = groups.values.filter { !it.archived && (groupId == null || it.id == groupId) && (wordId == null || links.any { link -> link.groupId == it.id }) }
    return View.Library(selected, selectedSenses, selectedGroups, links,
        selectedTargets, selectedTargets.mapNotNull { schedules[it.id] }, selected.lastOrNull()?.id?.takeIf { matching.size > limit },
        selected.associate { word -> word.id to studySummary(selectedTargets.filter { senses[it.senseId]?.wordId == word.id }, now) },
        selectedGroups.associate { group -> group.id to studySummary(groupStudyTargets(group.id), now) },
        if (groupId == null) emptyList() else alignWords(selected), activities.values.any { it.disputed && targets[it.targetId]?.senseId in senseIds })
}

private val reversibleBuckets = setOf("words", "senses", "groups", "memberships", "targets", "bindings", "drafts")
internal fun Database.captureLibrary(): Map<Pair<String,String>, JsonElement> = StateCodec.rows(this).filter { it.bucket in reversibleBuckets }
    .associate { (it.bucket to it.key) to Contract.json.parseToJsonElement(it.payload) }

internal fun Database.recordLibraryUndo(operationId: Id, before: Map<Pair<String,String>, JsonElement>) {
    undos[operationId] = captureLibrary().mapNotNull { (key, after) ->
        if (before[key] == after) null else UndoEntry(key.first, key.second, before[key], after)
    }
}

internal fun Database.saveWords(command: Command.SaveWords, source: Provenance, now: Long): List<Id> {
    requireBusiness(command.words.isNotEmpty() && command.words.size <= 200, "一次添加 1–200 个词")
    command.words.forEach { seed ->
        requireBusiness(seed.spelling.isNotBlank() && seed.spelling.length <= 100 && seed.meaning.isNotBlank() && seed.meaning.length <= 4000, "词形或释义无效")
        requireBusiness(seed.acceptedForms.size <= 30 && seed.examples.size <= 20 && seed.examples.all { it.length <= 4000 }, "例句或答案范围过大")
    }
    requireBusiness(words.size + command.words.count { seed -> words.values.none { it.normalized == normalize(seed.spelling) } } <= 10000, "当前资料库最多支持 10000 个词", ErrorCode.LIMIT_EXCEEDED)
    val senseIds = command.words.map { seed ->
        val normalized = normalize(seed.spelling)
        val existing = words.values.find { it.normalized == normalized }
        val word = existing?.let { if (it.archived) it.copy(archived = false, version = it.version + 1) else it }
            ?: Word(id(), spelling = seed.spelling.trim(), normalized = normalized, source = source)
        words[word.id] = word
        val sense = senses.values.find { it.wordId == word.id && normalize(it.meaning) == normalize(seed.meaning) && normalize(it.partOfSpeech) == normalize(seed.partOfSpeech) }
            ?: Sense(id(), word.id, partOfSpeech = seed.partOfSpeech, meaning = seed.meaning,
                acceptedForms = (listOf(seed.spelling) + seed.acceptedForms).distinct(), examples = seed.examples, source = source).also { senses[it.id] = it }
        listOf(Ability.RECALL, Ability.SPELLING, Ability.EXPRESSION).forEach { ability ->
            val target = ensureTarget(sense.id, ability, null, now)
            if (word.archived != existing?.archived) targets[target.id] = target.copy(suspended = false)
        }
        sense.id
    }
    val targetGroup = command.existingGroup?.let { ref -> groups[ref.id].found("词组").also { checkRef(ref, it.version) } }
        ?: command.group?.let { seed ->
            requireBusiness(seed.title.isNotBlank() && seed.title.length <= 200 && seed.purpose.isNotBlank() && seed.purpose.length <= 4000, "词组主题无效")
            groups.values.find { group -> !group.archived && group.relation == seed.relation &&
                memberships.values.filter { it.groupId == group.id && it.active }.map { it.senseId }.toSet() == senseIds.toSet() }
                ?: Group(id(), title = seed.title, relation = seed.relation, purpose = seed.purpose).also { groups[it.id] = it }
        }
    if (targetGroup != null) {
        requireBusiness(!targetGroup.archived, "词组已归档", ErrorCode.VERSION_CONFLICT)
        var changed = false
        senseIds.forEach { sid ->
            val existing = memberships.values.find { it.groupId == targetGroup.id && it.senseId == sid }
            if (existing?.active != true) {
                val member = existing?.copy(active = true, version = existing.version + 1, source = source)
                    ?: Membership(id(), targetGroup.id, sid, reason = targetGroup.purpose, source = source, position = nextMemberPosition(targetGroup.id))
                memberships[member.id] = member; changed = true
            }
            val target = ensureTarget(sid, Ability.RECOGNITION, targetGroup.id, now)
            targets[target.id] = target.copy(suspended = false)
        }
        if (changed) {
            val automaticTitle = groupWords(targetGroup.id).joinToString(" / ") { it.spelling }
            groups[targetGroup.id] = targetGroup.copy(version = targetGroup.version + 1,
                title = if (!targetGroup.titleEdited && targetGroup.title.contains(" / ") && automaticTitle.length <= 200) automaticTitle else targetGroup.title)
        }
    }
    return senseIds + listOfNotNull(targetGroup?.id)
}

internal fun Database.ensureTarget(senseId: Id, ability: Ability, groupId: Id?, now: Long): LearningTarget {
    return targets.values.find { it.senseId == senseId && it.ability == ability && it.groupId == groupId }
        ?: LearningTarget(id(), senseId, ability, groupId).also { targets[it.id] = it; schedules[it.id] = Schedule(it.id, dueAtMs = now, policyVersion = policy.version) }
}

internal fun Database.undoLibrary(operation: Operation) {
    val entries = undos[operation.id].found("撤销记录")
    val current = captureLibrary()
    entries.forEach { entry -> requireBusiness(current[entry.bucket to entry.key] == entry.after, "撤销涉及的内容已被后续修改", ErrorCode.VERSION_CONFLICT) }
    entries.filter { it.bucket == "bindings" && it.before != null }.forEach { entry ->
        val previous = Contract.json.decodeFromJsonElement<Binding>(entry.before!!)
        requireBusiness(presets[previous.presetId]?.enabled == true, "原预设已删除或停用，无法恢复旧绑定", ErrorCode.VERSION_CONFLICT)
    }
    // Reverse dependency order; never remove an entity referenced by actual learning history.
    entries.filter { it.bucket == "memberships" }.forEach { entry ->
        val member = memberships[Id(entry.key)].found("成员")
        memberships[member.id] = entry.before?.let { Contract.json.decodeFromJsonElement<Membership>(it).copy(version = member.version + 1) }
            ?: member.copy(active = false, version = member.version + 1)
    }
    entries.filter { it.bucket == "groups" }.forEach { entry ->
        val group = groups[Id(entry.key)].found("词组")
        groups[group.id] = entry.before?.let { Contract.json.decodeFromJsonElement<Group>(it).copy(version = group.version + 1) }
            ?: group.copy(archived = true, version = group.version + 1)
    }
    entries.filter { it.bucket == "words" }.forEach { entry ->
        val word = words[Id(entry.key)].found("词条")
        val laterReference = memberships.values.any { it.active && senses[it.senseId]?.wordId == word.id }
        words[word.id] = entry.before?.let { Contract.json.decodeFromJsonElement<Word>(it).copy(version = word.version + 1) }
            ?: word.copy(archived = !laterReference, version = word.version + 1)
    }
    entries.filter { it.bucket == "senses" && it.before != null }.forEach { entry ->
        val sense = senses[Id(entry.key)].found("义项")
        senses[sense.id] = Contract.json.decodeFromJsonElement<Sense>(entry.before!!).copy(version = sense.version + 1)
    }
    entries.filter { it.bucket == "targets" }.forEach { entry ->
        val target = targets[Id(entry.key)].found("学习目标")
        if (entry.before != null) targets[target.id] = Contract.json.decodeFromJsonElement(entry.before)
        else if (words[senses[target.senseId]?.wordId]?.archived == true || target.groupId?.let { groups[it]?.archived } == true) targets[target.id] = target.copy(suspended = true)
    }
    entries.filter { it.bucket == "bindings" }.forEach { entry ->
        val feature = Feature.valueOf(entry.key)
        if (entry.before == null) bindings.remove(feature) else bindings[feature] = Contract.json.decodeFromJsonElement(entry.before)
    }
    entries.filter { it.bucket == "drafts" }.forEach { entry ->
        if (entry.before == null) drafts.remove(entry.key) else drafts[entry.key] = Contract.json.decodeFromJsonElement(entry.before)
    }
}

internal fun Database.nextMemberPosition(groupId: Id): Long = (memberships.values.filter { it.groupId == groupId }.maxOfOrNull { it.position } ?: -1) + 1
internal fun Database.groupWords(groupId: Id): List<Word> = memberships.values.filter { it.groupId == groupId && it.active }
    .sortedWith(compareBy<Membership> { it.position }.thenBy { it.id.value })
    .mapNotNull { senses[it.senseId]?.wordId?.let(words::get) }.filter { !it.archived }.distinctBy { it.id }
