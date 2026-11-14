package app.wordprism.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class OverviewWindow(val days: Int) { // 0 = all time
    companion object { val Week = OverviewWindow(7); val Month = OverviewWindow(30); val All = OverviewWindow(0) }
}

/** Same snapshot the analysis AI reads. Values with low opportunity counts carry [Ratio.lowSample]. */
@Serializable
data class LearningOverview(
    val snapshotId: String,
    val schemaVersion: Int,
    val metricsVersion: String,
    val asOf: EpochMillis,
    val window: OverviewWindow,
    val timezone: String,
    val coverage: String,
    val library: LibraryStats,
    val effort: EffortStats,
    val memory: List<RatioMetric>,
    val abilities: List<RatioMetric>,
    val confusions: List<ConfusionPair>,
    val load: LoadStats,
    val contentQuality: List<CountMetric>,
    val apiUsage: List<UsageRow>,
)

@Serializable
data class LibraryStats(val words: Int, val groups: Int, val addedInWindow: Int, val recommendationsAccepted: Int, val undone: Int, val needsGrouping: Int)

@Serializable
data class EffortStats(
    val activeDays: Int,
    val answers: Int,
    val distinctTargets: Int,
    val newLearned: Int,
    val effectiveMinutesTotal: Int,
    val budgetMinutes: Int,
    val daily: List<DayEffort>,
    val waitingMinutesTotal: Int,
    val interruptedSessions: Int,
)

@Serializable
data class DayEffort(val date: LocalDay, val effectiveMinutes: Int, val plannedMinutes: Int)

@Serializable
data class Ratio(val successes: Int, val opportunities: Int, val lowSample: Boolean)

@Serializable
data class RatioMetric(
    val id: String,
    val label: String,
    val ratio: Ratio,
    val basis: String, // "observed" | "predicted" | "user_report" | "ai_review"
    val basisLabel: String,
    val note: String? = null,
)

@Serializable
data class ConfusionPair(
    val from: String,
    val to: String,
    val count: Int,
    val opportunities: Int,
    val trend: String, // "improving" | "worse" | "flat" | "new"
    val trendLabel: String,
    val lowSample: Boolean,
    val groupId: GroupId?,
)

@Serializable
data class LoadStats(val dueNow: Int, val backlog: Int, val forecast: List<DayForecast>, val forecastBasis: String)

@Serializable
data class DayForecast(val date: LocalDay, val estimatedReviews: Int, val estimatedMinutes: Int)

@Serializable
data class CountMetric(val label: String, val count: Int, val note: String? = null)

@Serializable
data class UsageRow(val feature: FeatureId, val featureLabel: String, val presetName: String?, val usage: UsageFigure, val unknownUsage: Boolean)

@Serializable
data class EvidenceItem(val at: EpochMillis, val prompt: String, val answered: String, val expected: String, val activityLabel: String, val hintUsed: Boolean)

interface OverviewApi {
    /** Pure local computation: never triggers a model request. */
    fun observeOverview(window: OverviewWindow): Flow<LearningOverview>
    suspend fun queryConfusionEvidence(from: String, to: String, limit: Int): SdkResult<List<EvidenceItem>>
}
