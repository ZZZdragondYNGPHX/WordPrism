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
    samples["failed-operation"] = Reply(5, View.OperationResult(operation.copy(status = OperationStatus.FAILED, retryable = true, fault = Fault(ErrorCode.PROVIDER_TIMEOUT, "服务商结果未知；需明确重试"))))
    val word = Word(Id("fixture-word"), spelling = "linear", normalized = "linear")
    val sense = Sense(Id("fixture-sense"), word.id, meaning = "线性的", acceptedForms = listOf("linear"))
    val target = LearningTarget(Id("fixture-target"), sense.id, Ability.RECALL)
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
    samples.forEach { (name, value) ->
        val json = Contract.json.encodeToString(value)
        check(Contract.json.decodeFromString<Reply>(json) == value)
        File(output, "$name.json").writeText(json + "\n")
    }
    File(output, "submit-intake.request.json").writeText(Contract.json.encodeToString(Request("fixture-intake-key", Command.SubmitIntake("linear / liner"))) + "\n")
    File(output, "save-responses-preset.request.json").writeText(Contract.json.encodeToString(Request("fixture-responses-key", Command.SavePreset(responsesPreset))) + "\n")
    File(output, "save-reasoning-preset.request.json").writeText(Contract.json.encodeToString(Request("fixture-reasoning-key", Command.SavePreset(reasoningPreset))) + "\n")
    File(output, "reset-reasoning-preset.request.json").writeText(Contract.json.encodeToString(Request("fixture-reasoning-default-key",
        Command.SavePreset(reasoningPreset.copy(reasoningEffort = null), expectedVersion = 1, preserveAdvancedSettings = true))) + "\n")
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
    println("Validated ${samples.size} typed response fixtures, four command requests and ${catalogRequests.size} model catalog requests in ${output.absolutePath}")
}
