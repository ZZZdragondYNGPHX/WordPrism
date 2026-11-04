package app.wordprism.sdk

import kotlinx.serialization.Serializable

/** Display projection of the core's typed ExplanationContent; no provider JSON reaches the UI. */
@Serializable data class ExplanationSection(val id: String, val title: String, val text: String, val note: String? = null)

@Serializable data class ExplanationDetail(
    val senseId: SenseId, val senseVersion: Long, val version: Long? = null,
    val sections: List<ExplanationSection> = emptyList(), val source: ContentSource? = null,
    val stale: Boolean = false, val recommendations: List<Recommendation> = emptyList(), val operation: Operation? = null,
    val family: List<Recommendation> = emptyList(),
)
