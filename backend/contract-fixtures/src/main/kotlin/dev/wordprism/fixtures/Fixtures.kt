package dev.wordprism.fixtures

import dev.wordprism.contract.*
import java.io.File
import kotlinx.serialization.encodeToString

/** Generated fixtures round-trip through the same serializer consumed by Android and the bridge. */
fun main(args: Array<String>) {
    val output = File(args.firstOrNull() ?: "../fixtures").also { it.mkdirs() }
    val at = 1_793_664_000_000L
    val operation = Operation(Id("fixture-operation"), "submit_intake", OperationStatus.QUEUED, at, at, Feature.WORD_INTAKE, input = "linear / liner")
    val samples = linkedMapOf<String, Reply>(
        "empty-library" to Reply(1, View.Library(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())),
        "success" to Reply(2, View.OperationResult(operation.copy(status = OperationStatus.SUCCEEDED, undoable = true, resultIds = listOf(Id("fixture-word")))))
    )
    mapOf("generating" to OperationStatus.RUNNING, "offline" to OperationStatus.WAITING_NETWORK, "ambiguous" to OperationStatus.NEEDS_INPUT,
        "cancelled" to OperationStatus.CANCELLED, "restored-pending" to OperationStatus.RESTORED_PENDING).forEach { (name, status) ->
        samples[name] = Reply(2, View.OperationResult(operation.copy(status = status,
            choices = if (status == OperationStatus.NEEDS_INPUT) listOf(IntakeChoice(Id("fixture-group"), "存在两个合理归属")) else emptyList())))
    }
    listOf(ErrorCode.PROVIDER_TIMEOUT, ErrorCode.PRESET_MISSING, ErrorCode.VERSION_CONFLICT).forEach { code ->
        samples[code.name.lowercase()] = Reply(2, fault = Fault(code, "契约样例：${code.name}"))
    }
    samples["no-adjustment"] = Reply(5, View.OperationResult(operation.copy(kind = "analyze_learning", feature = Feature.LEARNING_ANALYSIS, status = OperationStatus.SUCCEEDED)))
    samples["spelling-choice"] = Reply(5, View.OperationResult(operation.copy(status = OperationStatus.NEEDS_INPUT, input = "lienar",
        spellingChoices = listOf(SpellingChoice("lienar", "linear", "合成示例：请确认原始拼写")))))
    samples["business-effects"] = Reply(6, View.OperationEffects(listOf(BusinessEffect(operation.id, "update_word", "fixture-word", "linear · 笔记", "notes",
        kotlinx.serialization.json.JsonPrimitive(""), kotlinx.serialization.json.JsonPrimitive("强调与 liner 的区别")))))
    val timeoutPreset = ApiPreset(Id("fixture-timeout-preset"), name = "重试配置示例", baseUrl = "https://example.invalid/v1",
        model = "example-model", protocol = ApiProtocols.OPENAI_RESPONSES, timeoutSeconds = 30)
    samples["failed-operation"] = Reply(5, View.OperationResult(operation.copy(status = OperationStatus.FAILED, attempt = 1,
        preset = timeoutPreset, retryable = true, fault = Fault(ErrorCode.PROVIDER_TIMEOUT, "请求超时（上限 30 秒）；服务商是否完成未知，请明确选择重试"))))
    samples["retry-uses-saved-preset"] = Reply(6, View.OperationResult(operation.copy(attempt = 1,
        preset = timeoutPreset.copy(version = 2, timeoutSeconds = 180, maxOutputTokens = 8000, reasoningEffort = ReasoningEffort.MAX))))
    val word = Word(Id("fixture-word"), spelling = "linear", normalized = "linear")
    val sense = Sense(Id("fixture-sense"), word.id, meaning = "线性的", acceptedForms = listOf("linear"))
    val target = LearningTarget(Id("fixture-target"), sense.id, Ability.RECALL)
    val explanationOperation = operation.copy(kind = "explain_sense", feature = Feature.EXPLANATION, status = OperationStatus.SUCCEEDED, undoable = true, resultIds = listOf(sense.id))
    val family = Recommendation(Id("fixture-family"), "linearity", "合成示例：派生关系", content = WordSeed("linearity", "线性", "noun"),
        source = Provenance("synthetic_example", explanationOperation.id, atMs = at), originSenseId = sense.id)
    val explanation = SenseExplanation(2, 2, ExplanationContent("合成讲解：把词形与具体语境联系起来。", "arranged in a line",
        wordParts = listOf(WordPart("line", WordPartKind.STEM, "线", "合成示例字段")),
        etymology = EtymologyNote("这里展示词源说明", "合成示例，未经词典核验"),
        mnemonic = Mnemonic("想象一条直线贯穿图纸。", "这只是记忆联想"),
        usageNotes = listOf("结合修饰的名词理解词义。"), examples = listOf("The graph shows a linear relationship.")),
        listOf(family.id), Provenance("synthetic_example", explanationOperation.id, atMs = at))
    samples["explanation-empty"] = Reply(10, View.Explanation(Ref(sense.id, 1), null, false, emptyList()))
    samples["explanation-saved"] = Reply(11, View.Explanation(Ref(sense.id, 2), explanation, false, listOf(family), explanationOperation, listOf(family)))
    samples["explanation-stale"] = Reply(12, View.Explanation(Ref(sense.id, 3), explanation, true, emptyList(), explanationOperation, listOf(family)))
    samples["explanation-cancelled"] = Reply(13, View.Explanation(Ref(sense.id, 1), null, false, emptyList(), explanationOperation.copy(status = OperationStatus.CANCELLED, undoable = false)))
    samples["populated-library"] = Reply(3, View.Library(listOf(word), listOf(sense), emptyList(), emptyList(), listOf(target), listOf(Schedule(target.id, dueAtMs = at))))
    samples["active-session"] = Reply(4, View.SessionState(Session(Id("fixture-session"), Id("fixture-day"), Id("fixture-plan"), 1, targetIds = listOf(target.id), currentActivityId = Id("fixture-activity"), currentPresentationId = Id("fixture-presentation")),
        ActivityCard(Id("fixture-activity"), 1, target.id, ActivityKind.RECALL, "回忆表达‘线性的’的单词", emptyList(), "fixture-context")))
    samples["learning-day"] = Reply(4, View.Day(LearningDay(Id("fixture-day"), "2026-11-03", "UTC", at, at + 86_400_000,
        planId = Id("fixture-plan")), LearningPlan(Id("fixture-plan"), Id("fixture-day"), 1, listOf(target.id), 1, 0, 0, 50_000,
        "initial_estimate", 1_800_000, at, newWordCount = 1), 0, null, summary = DaySummary(canEnd = true,
            automaticEnabled = true, analysisState = "will_run_after_day_end", plannedNewWords = 1)))
    val change = PolicyChange(Id("fixture-change"), operation.id, Id("fixture-snapshot"), Actor.AI, TrainingPolicy(), TrainingPolicy(version = 2, newTargetLimit = 5), "延迟回忆表现需要更多复习", emptyList(), at)
    samples["adjustment-applied"] = Reply(5, View.PolicyChanges(listOf(change)))
    samples["undo-preserves-later-answers"] = Reply(8, View.PolicyChanges(listOf(change.copy(undoneBy = Id("fixture-undo-after-answer")))))
    samples["migration-preview"] = Reply(8, View.ImportPreview(PackagePreview(Id("fixture-preview"), Id("fixture-package"), PackagePurpose.BACKUP,
        "0".repeat(64), 8, 2, 0, emptyList(), listOf("library:1->2"), emptyList(), true)))
    samples["api-protocols"] = Reply(8, View.Protocols(ApiProtocols.supported))
    samples["models"] = Reply(8, View.Models(listOf(ApiModel("example-model-a", "synthetic-provider"), ApiModel("example-model-b"))))
    samples["empty-models"] = Reply(8, View.Models(emptyList()))
    samples["models-unavailable"] = Reply(8, fault = Fault(ErrorCode.PROVIDER_UNAVAILABLE, "服务商未提供 /models 列表，请手动填写模型标识"))
    val responsesPreset = ApiPreset(Id("fixture-responses-preset"), name = "Responses 示例", baseUrl = "https://api.openai.com/v1",
        model = "your-responses-model", protocol = ApiProtocols.OPENAI_RESPONSES, maxOutputTokens = 4096)
    samples["responses-preset"] = Reply(9, View.Presets(listOf(PresetView(responsesPreset, hasCredential = false)),
        listOf(Binding(Feature.WORD_INTAKE, responsesPreset.id))))
    val reasoningPreset = responsesPreset.copy(id = Id("fixture-reasoning-preset"), name = "思维程度示例", reasoningEffort = ReasoningEffort.HIGH)
    samples["reasoning-preset"] = Reply(10, View.Presets(listOf(PresetView(reasoningPreset, hasCredential = false)), emptyList()))
    val writingAnswer = Answer(Id("fixture-writing-answer"), 1, Id("fixture-session"), Id("fixture-activity"), 1,
        target.id, input = AnswerInput("The graph is linear.", requestFeedback = true), atMs = at,
        verdict = Verdict.UNGRADED, gradeSource = GradeSource.UNGRADED, affectsSchedule = false, policyVersion = 1, delayed = false, newContext = true)
    val writingFeedback = Feedback(Id("fixture-writing-feedback"), writingAnswer.id, 1, "句意清楚。", GradeSource.AI, "good", "is linear",
        Provenance("synthetic_example", atMs = at), listOf(
            WritingFeedbackDimension("target_word", "目标词用法贴合语境。", "is linear"),
            WritingFeedbackDimension("grammar", "句子结构完整。"),
            WritingFeedbackDimension("naturalness", "表达自然。"),
            WritingFeedbackDimension("intent", "表达了线性关系。")), "The relationship is linear.", "用 relationship 突出关系。")
    samples["writing-feedback"] = Reply(11, View.Feedbacks(writingAnswer, listOf(writingFeedback)))
    samples.forEach { (name, value) ->
        val json = Contract.json.encodeToString(value)
        check(Contract.json.decodeFromString<Reply>(json) == value)
        File(output, "$name.json").writeText(json + "\n")
    }
    File(output, "submit-intake.request.json").writeText(Contract.json.encodeToString(Request("fixture-intake-key", Command.SubmitIntake("linear / liner"))) + "\n")
    File(output, "save-responses-preset.request.json").writeText(Contract.json.encodeToString(Request("fixture-responses-key", Command.SavePreset(responsesPreset))) + "\n")
    File(output, "save-reasoning-preset.request.json").writeText(Contract.json.encodeToString(Request("fixture-reasoning-key", Command.SavePreset(reasoningPreset))) + "\n")
    File(output, "save-unlimited-preset.request.json").writeText(Contract.json.encodeToString(Request("fixture-unlimited-key",
        Command.SavePreset(responsesPreset.copy(timeoutSeconds = null, maxOutputTokens = null)))) + "\n")
    File(output, "reset-reasoning-preset.request.json").writeText(Contract.json.encodeToString(Request("fixture-reasoning-default-key",
        Command.SavePreset(reasoningPreset.copy(reasoningEffort = null), expectedVersion = 1, preserveAdvancedSettings = true))) + "\n")
    File(output, "retry-operation.request.json").writeText(Contract.json.encodeToString(Request("fixture-retry-key", Command.RetryOperation(operation.id))) + "\n")
    File(output, "explain-sense.request.json").writeText(Contract.json.encodeToString(Request("fixture-explanation-key", Command.ExplainSense(Ref(sense.id, 1)))) + "\n")
    File(output, "accept-word-family.request.json").writeText(Contract.json.encodeToString(Request("fixture-family-key", Command.AcceptRecommendations(listOf(family.id), false))) + "\n")
    val catalogRequests = mapOf(
        "unsaved-models" to ModelCatalogRequest("https://api.openai.com/v1", ApiProtocols.OPENAI_RESPONSES),
        "saved-models" to ModelCatalogRequest("https://api.openai.com/v1", ApiProtocols.OPENAI_RESPONSES, credentialPreset = Ref(responsesPreset.id, responsesPreset.version)),
    )
    catalogRequests.forEach { (name, request) ->
        val json = Contract.json.encodeToString(request)
        check(Contract.json.decodeFromString<ModelCatalogRequest>(json) == request)
        File(output, "$name.catalog-request.json").writeText(json + "\n")
    }
    writeSchemas(File(output.parentFile, "schemas"))
    if ("--skip-packages" !in args) kotlinx.coroutines.runBlocking { writeReferencePackages(File(output.parentFile, "packages")) }
    if ("--explanation-package" in args) kotlinx.coroutines.runBlocking { writeExplanationPackage(File(output.parentFile, "packages")) }
    println("Validated ${samples.size} typed response fixtures, eight command requests and ${catalogRequests.size} model catalog requests in ${output.absolutePath}")
}
