package dev.wordprism.contract

import kotlinx.serialization.Serializable

/** Linguistic parts, never arbitrary spelling chunks invented for a mnemonic. */
@Serializable enum class WordPartKind { PREFIX, ROOT, SUFFIX, STEM }
@Serializable data class WordPart(
    val form: String, val kind: WordPartKind, val meaning: String, val note: String = "",
)

/** AI statements are not dictionary verification. Uncertain origins may be omitted entirely. */
@Serializable data class EtymologyNote(val explanation: String, val uncertainty: String = "")
@Serializable data class Mnemonic(val story: String, val cue: String = "")

/** The same saved learning material is read by the user and by practice generation. */
@Serializable data class ExplanationContent(
    val explanation: String,
    val englishDefinition: String? = null,
    val wordParts: List<WordPart> = emptyList(),
    val etymology: EtymologyNote? = null,
    val mnemonic: Mnemonic? = null,
    val usageNotes: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
)

/** Versioned supplement to a stable sense; it never replaces the definition or real answers. */
@Serializable data class SenseExplanation(
    val version: Long, val basedOnSenseVersion: Long, val content: ExplanationContent,
    val recommendationIds: List<Id> = emptyList(), val source: Provenance,
)
