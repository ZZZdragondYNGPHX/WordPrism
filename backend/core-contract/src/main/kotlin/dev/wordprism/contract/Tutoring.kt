package dev.wordprism.contract

import kotlinx.serialization.Serializable

@Serializable enum class TutorMoment { WELCOME, COACH, QUESTION, RECAP }

/** An interpretation of cited learning evidence, never a replacement for recorded answers. */
@Serializable data class TutorReport(
    val message: String,
    val checkQuestion: String? = null,
    val observation: String? = null,
    val evidence: List<Ref> = emptyList(),
    val nextPractice: ActivityKind? = null,
    val nextTargets: List<Id> = emptyList(),
)

@Serializable data class TutorEntry(
    val operation: Operation,
    val moment: TutorMoment,
    val sessionId: Id,
    val presentationId: Id? = null,
    val answer: Ref? = null,
    val question: String? = null,
    val report: TutorReport? = null,
)
