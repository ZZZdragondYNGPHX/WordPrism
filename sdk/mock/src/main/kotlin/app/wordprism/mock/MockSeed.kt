package app.wordprism.mock

import app.wordprism.sdk.*
import java.time.Instant
import java.time.ZoneId

internal object MockSeed {
    private val aiSource = ContentSource("ai_generated", presetName = "日常整理", presetVersion = 2, model = "mock-model-small")

    fun word(lemma: String, now: Long, status: StudyStatus = StudyStatus.Learning, source: ContentSource = aiSource): MWord {
        val e = MockLexicon.entryOrPlaceholder(lemma)
        return MWord(
            id = "w-$lemma", lemma = lemma, pos = e.pos, glossZh = e.glossZh, glossEn = e.glossEn,
            collocations = e.collocations, example = e.example, exampleZh = e.exampleZh,
            source = source, status = status.value, createdAt = now,
        )
    }

    fun create(now: Long, zone: ZoneId): MockDb {
        val day = 86_400_000L
        val words = listOf(
            word("adapt", now - 20 * day, StudyStatus.Due), word("adopt", now - 20 * day, StudyStatus.Due),
            word("adept", now - 12 * day, StudyStatus.Learning),
            word("affect", now - 15 * day, StudyStatus.Stable), word("effect", now - 15 * day, StudyStatus.Learning),
            word("principal", now - 9 * day, StudyStatus.Learning), word("principle", now - 9 * day, StudyStatus.Due),
            word("stationary", now - 3 * day, StudyStatus.NotStarted), word("stationery", now - 3 * day, StudyStatus.NotStarted),
            word("economic", now - 2 * day, StudyStatus.NotStarted, ContentSource("imported")),
        ).associateBy { it.id }
        fun group(id: String, title: String, rel: RelationKind, purpose: String, members: List<String>, created: Long, reasons: Map<String, String> = emptyMap()) =
            MGroup(id, title, rel.value, purpose, members.map { "w-$it" }, reasons.mapKeys { "w-${it.key}" }, 1, aiSource, created)
        val groups = listOf(
            group("g-adapt", "adapt / adopt / adept", RelationKind.FormSimilar, "分清“适应、采用、熟练”", listOf("adapt", "adopt", "adept"), now - 20 * day,
                mapOf("adept" to "你提到它和 adapt 拼写容易混")),
            group("g-affect", "affect / effect", RelationKind.FormSimilar, "动词“影响”与名词“效果”", listOf("affect", "effect"), now - 15 * day),
            group("g-principal", "principal / principle", RelationKind.SoundAlike, "读音相同：校长、主要的 vs 原则", listOf("principal", "principle"), now - 9 * day),
            group("g-station", "stationary / stationery", RelationKind.FormSimilar, "静止的 vs 文具", listOf("stationary", "stationery"), now - 3 * day),
        ).associateBy { it.id }

        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
        val presets = listOf(
            MPreset("p-daily", "日常整理", 2, "openai_compatible", "https://api.example.com/v1", "mock-model-small", 1200, 30, "••••a91c",
                lastTest = ConnectionTestResult(true, now - 2 * day, "连接正常（模拟）", 640)),
            MPreset("p-writing", "写作批改", 1, "anthropic_messages", "https://api.example.org", "mock-model-large", 2000, 60, null),
        ).associateBy { it.id }
        val recs = mapOf(
            "g-adapt" to listOf(Recommendation("r-adaptation", "adaptation", "适应；改编本", "与 adapt 同词族，常在阅读中出现", requiresApi = true, alreadyInLibrary = false)),
            "g-affect" to listOf(Recommendation("r-effective", "effective", "有效的", "effect 的形容词，常与 affective 混", requiresApi = true, alreadyInLibrary = false)),
        )
        return MockDb(
            words = words, groups = groups, presets = presets, recommendations = recs,
            bindings = mapOf(
                FeatureId.WordIntake.value to "p-daily", FeatureId.WordRecommendation.value to "p-daily",
                FeatureId.PracticeGeneration.value to "p-daily", FeatureId.LearningAnalysis.value to "p-daily",
                FeatureId.WritingFeedback.value to "p-writing",
            ),
            callPolicy = CallPolicy(PresetId("p-daily"), false, 20, 200, 1, null),
            settings = MSettings(),
            days = emptyMap(),
            changeSets = listOf(
                ChangeSet(
                    ChangeSetId("cs-seed"), OperationId("op-seed"), "auto", now - day, "snap-seed", now - day,
                    "减少新学，把 adapt → adopt 设为重点",
                    listOf("近 7 天 adopt 被误选为 adapt 7 次（19 次机会）", "昨天新学 6 个只完成 3 个"),
                    listOf(
                        ParamChange("plan.new_per_day", "每日新学上限", "8", "6", "明日起", false),
                        ParamChange("plan.focus", "重点辨析", "无", "adapt → adopt", "明日计划", false),
                    ),
                    ChangeStatus.Applied, "今天的计划", UndoAvailability(true, "撤销后今天已完成的作答会保留"),
                ),
            ),
        ).let { it.copy(settings = it.settings.copy(adjustedBy = "AI 调整：减少新学，重点练 adapt → adopt", lastAnalysisOpId = null)) }
            .let { d -> d.copy(days = mapOf(today to MDay("d-$today", today, DayStatus.NotStarted.value, plannedItems = 0, planVersion = d.settings.planVersion))) }
    }
}
