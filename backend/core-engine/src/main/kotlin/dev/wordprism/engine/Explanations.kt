package dev.wordprism.engine

import dev.wordprism.contract.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.*

/** Providers sometimes express an absent optional note/list as null. Apply only declared defaults;
 * required fields, unknown keys/enums and wrong types remain strict. Local requests/packages are unchanged. */
internal fun decodeExplanationProposal(value: JsonElement): ExplanationProposal = Contract.json.decodeFromJsonElement(
    ExplanationProposal.serializer(), generatedDefaults(value, ExplanationProposal.serializer().descriptor))

@OptIn(ExperimentalSerializationApi::class)
private fun generatedDefaults(value: JsonElement, descriptor: SerialDescriptor): JsonElement = when {
    value is JsonObject && descriptor.kind == StructureKind.CLASS -> JsonObject(value.mapNotNull { (key, item) ->
        val index = descriptor.getElementIndex(key)
        if (index < 0) key to item else {
            val field = descriptor.getElementDescriptor(index)
            if (item == JsonNull && descriptor.isElementOptional(index) && !field.isNullable) null
            else key to generatedDefaults(item, field)
        }
    }.toMap())
    value is JsonArray && descriptor.kind == StructureKind.LIST -> JsonArray(value.map { generatedDefaults(it, descriptor.getElementDescriptor(0)) })
    else -> value
}

internal fun validateExplanationContent(content: ExplanationContent, code: ErrorCode = ErrorCode.INVALID_RESPONSE) {
    fun valid(ok: Boolean) = requireBusiness(ok, "讲解字段为空、过长或数量超限", code)
    valid(content.explanation.isNotBlank() && content.explanation.length <= 4000)
    content.englishDefinition?.let { valid(it.isNotBlank() && it.length <= 1600) }
    valid(content.wordParts.size <= 8 && content.wordParts.all {
        it.form.isNotBlank() && it.form.length <= 100 && it.meaning.isNotBlank() && it.meaning.length <= 400 && it.note.length <= 800
    })
    content.etymology?.let { valid(it.explanation.isNotBlank() && it.explanation.length <= 4000 && it.uncertainty.length <= 1200) }
    content.mnemonic?.let { valid(it.story.isNotBlank() && it.story.length <= 4000 && it.cue.length <= 400) }
    valid(content.usageNotes.size <= 8 && content.usageNotes.all { it.isNotBlank() && it.length <= 1000 })
    valid(content.examples.size <= 12 && content.examples.all { it.isNotBlank() && it.length <= 1000 })
    val length = content.explanation.length + (content.englishDefinition?.length ?: 0) +
        content.wordParts.sumOf { it.form.length + it.meaning.length + it.note.length } +
        (content.etymology?.let { it.explanation.length + it.uncertainty.length } ?: 0) +
        (content.mnemonic?.let { it.story.length + it.cue.length } ?: 0) +
        content.usageNotes.sumOf { it.length } + content.examples.sumOf { it.length }
    valid(length <= 24000)
}

/** Project legacy drafts without rewriting rows used by old idempotency/undo records. */
internal fun Database.explanation(sense: Sense): SenseExplanation? = sense.explanation ?: run {
    val draft = drafts["explanation:${sense.id.value}"] ?: return@run null
    if (draft.text.isBlank()) return@run null
    val operation = operations.values.asSequence().filter {
        (jobs[it.id]?.command as? Command.ExplainSense)?.sense?.id == sense.id &&
            it.status == OperationStatus.SUCCEEDED && it.undoneBy == null
    }.latestOperation()
    SenseExplanation(draft.version, sense.version, ExplanationContent(draft.text), source =
        Provenance(if (operation == null) "legacy" else "ai", operation?.id, operation?.preset?.id, operation?.preset?.version, operation?.preset?.model, draft.atMs))
}

internal fun Database.recommendationAvailable(rec: Recommendation): Boolean =
    rec.originSenseId?.let { senseId -> senses[senseId]?.let { sense ->
        !words[sense.wordId].found("词条").archived && sense.explanation?.let { it.basedOnSenseVersion == sense.version && rec.id in it.recommendationIds } == true
    } == true } ?: true

internal fun Database.recommendationView(rec: Recommendation): Recommendation {
    val word = words.values.firstOrNull { !it.archived && it.normalized == normalize(rec.spelling) }
    return rec.copy(content = recommendationContent(rec), inLibrary = word != null, libraryWordId = word?.id)
}

internal fun validRecommendationContent(seed: WordSeed): Boolean = seed.spelling.isNotBlank() && seed.spelling.length <= 100 &&
    seed.meaning.isNotBlank() && seed.meaning.length <= 4000 && seed.partOfSpeech.length <= 100 &&
    seed.acceptedForms.size <= 30 && seed.acceptedForms.all { it.isNotBlank() && it.length <= 4000 } &&
    seed.examples.size <= 20 && seed.examples.all { it.isNotBlank() && it.length <= 4000 }

internal fun Database.explanationView(senseId: Id): View.Explanation {
    val sense = senses[senseId].found("义项"); val saved = explanation(sense)
    val family = saved?.recommendationIds.orEmpty().mapNotNull(recommendations::get)
    val candidates = family.filter { rec ->
        recommendationAvailable(rec) && (rec.acceptedOperationId == null || operations[rec.acceptedOperationId]?.undoneBy != null)
    }.map(::recommendationView)
    val operation = operations.values.asSequence().filter { (jobs[it.id]?.command as? Command.ExplainSense)?.sense?.id == senseId }.latestOperation()
    return View.Explanation(Ref(sense.id, sense.version), saved, saved != null && saved.basedOnSenseVersion != sense.version, candidates, operation, family.map(::recommendationView))
}
