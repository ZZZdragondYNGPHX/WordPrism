package app.wordprism.mock

import app.wordprism.sdk.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Mock `.wordprism` container: a ZIP with manifest.json + data.json. The format id is
 * "wordprism-mock" so a real build can refuse/migrate it explicitly. Not the official schema.
 */
@Serializable
internal data class MockManifest(
    val format: String = "wordprism-mock",
    val formatVersion: String = "0.1-mock",
    val producerVersion: String = "frontend-mock",
    val purpose: String,
    val createdAt: Long,
    val modules: List<Pair<String, String>>,
)

@Serializable
internal data class MockPayload(
    val words: List<MWord>,
    val groups: List<MGroup>,
    val answers: List<MAnswer> = emptyList(),
    val settings: MSettings? = null,
    val changeSets: List<ChangeSet> = emptyList(),
    val presets: List<MPreset> = emptyList(),
    val bindings: Map<String, String?> = emptyMap(),
)

internal class MockTransfer(private val c: MockCore) : DataTransferApi {

    private val previews = mutableMapOf<String, Pair<ImportPreview, MockPayload>>()

    init { c.handlers[OperationKind.Export] = ::runExport; c.handlers[OperationKind.Import] = ::runImport; c.handlers[OperationKind.Restore] = ::runImport }

    private val moduleLabels = mapOf("library" to "核心词库", "content" to "学习内容", "history" to "学习历史", "memory" to "记忆与计划",
        "adaptation" to "AI 调整记录", "api_config" to "API 配置（不含密钥）")

    override suspend fun exportScope(request: ExportRequest): SdkResult<ExportScopeInfo> {
        val full = request.purpose == PackagePurpose.FullBackup
        return SdkResult.Ok(ExportScopeInfo(request.purpose,
            if (full) moduleLabels.values.toList() else listOf("所选词组的词条、释义、例句与归组关系"),
            if (full) listOf("API 密钥（恢复后需重新填写）", "进行中的 AI 任务") else listOf("学习进度与历史", "AI 调整记录", "API 配置"),
            "wordprism-${if (full) "backup" else "words"}-${c.today()}.wordprism"))
    }

    override suspend fun export(request: ExportRequest, target: DocumentRef, client: ClientRequestId): SdkResult<OperationId> {
        val r = c.tx { d ->
            c.existing(d, client)?.let { return@tx d to SdkResult.Ok(OperationId(it)) }
            if (request.purpose == PackagePurpose.ContentPack && request.groupIds.isEmpty())
                return@tx d to SdkResult.Err(SdkError(ErrorCode.Validation, "请至少选择一个词组", false))
            val op = c.newOperation(d, OperationKind.Export, if (request.purpose == PackagePurpose.FullBackup) "导出完整备份" else "导出 ${request.groupIds.size} 个词组", null)
                .copy(resultSummary = c.json.encodeToString(ExportRequest.serializer(), request) + "\n" + target.uri)
            with(c) { d.withOp(op).remember(client, op.id.value) } to SdkResult.Ok(op.id)
        }
        r.onOk { c.launch(it.value) }
        return r
    }

    private suspend fun runExport(opId: String) {
        val (reqJson, uri) = c.db.value.operations.getValue(opId).resultSummary!!.split("\n", limit = 2)
        val req = c.json.decodeFromString(ExportRequest.serializer(), reqJson)
        c.setStatus(opId, OperationStatus.Running)
        val d = c.db.value
        val full = req.purpose == PackagePurpose.FullBackup
        val groups = if (full) d.groups.values.toList() else d.groups.values.filter { GroupId(it.id) in req.groupIds }
        val words = if (full) d.words.values.toList() else groups.flatMap { it.memberIds }.distinct().mapNotNull { d.words[it] }
        val payload = MockPayload(
            words.map { if (full) it else it.copy(status = StudyStatus.NotStarted.value) }, groups,
            if (full) d.answers else emptyList(), if (full) d.settings else null, if (full) d.changeSets else emptyList(),
            if (full) d.presets.values.map { it.copy(credentialHint = null, lastTest = null) } else emptyList(), if (full) d.bindings else emptyMap(),
        )
        val modules = (if (full) moduleLabels.keys.toList() else listOf("library", "content")).map { it to "1" }
        for (step in 1..3) {
            if (!c.isActive(opId)) return
            c.mutate { db -> with(c) { db.updateOp(opId) { it.copy(progress = OperationProgress(step, 3, listOf("整理数据", "写入文件", "校验")[step - 1])) } } }
            delay(400)
        }
        runCatching {
            c.documents.openOutput(uri).use { os ->
                ZipOutputStream(os).use { zip ->
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(c.json.encodeToString(MockManifest.serializer(), MockManifest(purpose = req.purpose.value, createdAt = c.clock(), modules = modules)).toByteArray())
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("data.json"))
                    zip.write(c.json.encodeToString(MockPayload.serializer(), payload).toByteArray())
                    zip.closeEntry()
                }
            }
        }.onFailure { return c.failOp(opId, SdkError(ErrorCode.Internal, "写入文件失败：${it.message}", true)) }
        c.commit(opId) { db -> with(c) { db.updateOp(opId) { it.copy(status = OperationStatus.Succeeded, actions = emptySet(), resultSummary = "已导出 ${words.size} 个词条、${groups.size} 个词组" + if (full) "、${payload.answers.size} 条作答" else "") } } to Unit }
    }

    override suspend fun previewImport(source: DocumentRef): SdkResult<ImportPreview> {
        val parsed = runCatching {
            var manifest: MockManifest? = null; var payload: MockPayload? = null
            c.documents.openInput(source.uri).use { ins ->
                ZipInputStream(ins).use { zip ->
                    var e = zip.nextEntry; var total = 0L
                    while (e != null) {
                        val bytes = zip.readBytes(); total += bytes.size
                        if (total > 50_000_000) error("数据包解压后超过 50 MB")
                        when (e.name) {
                            "manifest.json" -> manifest = c.json.decodeFromString(MockManifest.serializer(), bytes.decodeToString())
                            "data.json" -> payload = c.json.decodeFromString(MockPayload.serializer(), bytes.decodeToString())
                        }
                        e = zip.nextEntry
                    }
                }
            }
            manifest to payload
        }.getOrElse { return SdkResult.Err(SdkError(ErrorCode.UnsupportedFormat, "无法读取这个文件：它不是 WordPrism 数据包，或文件已损坏", false)) }
        val (m, p) = parsed
        if (m == null || p == null || !m.format.startsWith("wordprism")) return SdkResult.Err(SdkError(ErrorCode.UnsupportedFormat, "这个文件不是 WordPrism 数据包", false))
        val d = c.db.value
        val full = m.purpose == PackagePurpose.FullBackup.value
        val dupes = p.words.count { w -> d.words.values.any { it.lemma == w.lemma } }
        val conflicts = p.groups.count { g -> d.groups.values.any { it.title == g.title && it.id != g.id } }
        val modules = m.modules.map { (id, v) ->
            val known = id in moduleLabels
            ModuleStatus(id, moduleLabels[id] ?: id, v, if (known) "ok" else "unknown_optional", if (known) "可导入" else "当前版本不认识，会原样保留")
        }
        val preview = ImportPreview(
            c.newId("pv"), source, PackagePurpose(m.purpose), m.formatVersion, m.producerVersion, m.createdAt, modules,
            ImportCounts(if (full) p.words.size else p.words.size - dupes, if (full) 0 else dupes, if (full) 0 else conflicts, 0),
            if (full) listOfNotNull(
                p.presets.takeIf { it.isNotEmpty() }?.let { "${it.size} 个 API 预设需要重新填写密钥" },
                "每日自动分析恢复后保持关闭，需要你重新开启",
            ) else emptyList(),
            listOfNotNull("这是模拟格式（${m.formatVersion}），正式格式发布后需要迁移", if (conflicts > 0 && !full) "$conflicts 个词组与现有词组同名，会作为新词组加入" else null),
            canCommit = true, blockingReason = null, replacesLibrary = full,
        )
        previews[preview.previewId] = preview to p
        return SdkResult.Ok(preview)
    }

    override suspend fun commitImport(previewId: String, client: ClientRequestId): SdkResult<OperationId> {
        val (preview, _) = previews[previewId] ?: return SdkResult.Err(SdkError(ErrorCode.NotFound, "预览已过期，请重新选择文件", false))
        val r = c.tx { d ->
            c.existing(d, client)?.let { return@tx d to SdkResult.Ok(OperationId(it)) }
            val op = c.newOperation(d, if (preview.replacesLibrary) OperationKind.Restore else OperationKind.Import,
                if (preview.replacesLibrary) "恢复完整备份" else "导入词库内容", null).copy(resultSummary = previewId)
            with(c) { d.withOp(op).remember(client, op.id.value) } to SdkResult.Ok(op.id)
        }
        r.onOk { c.launch(it.value) }
        return r
    }

    private suspend fun runImport(opId: String) {
        val previewId = c.db.value.operations.getValue(opId).resultSummary
        val (preview, p) = previews[previewId] ?: return c.failOp(opId, SdkError(ErrorCode.NotFound, "应用重启后预览已失效，请重新选择文件。现有资料未改变。", false))
        c.setStatus(opId, OperationStatus.Running)
        for (step in 1..3) {
            if (!c.isActive(opId)) return
            c.mutate { db -> with(c) { db.updateOp(opId) { it.copy(progress = OperationProgress(step, 3, listOf("暂存数据", "检查引用", "切换资料库")[step - 1])) } } }
            delay(500)
        }
        c.commit(opId) { d ->
            if (preview.replacesLibrary) {
                val n = d.copy(
                    words = p.words.associateBy { it.id }, groups = p.groups.associateBy { it.id }, answers = p.answers,
                    settings = (p.settings ?: d.settings).copy(mode = AnalysisMode.Off.value, activeAnalysisOpId = null),
                    changeSets = p.changeSets, presets = p.presets.associateBy { it.id }, bindings = p.bindings, recommendations = emptyMap(),
                )
                with(c) { n.updateOp(opId) { it.copy(status = OperationStatus.Succeeded, actions = emptySet(), resultSummary = "已恢复 ${p.words.size} 个词条、${p.groups.size} 个词组和 ${p.answers.size} 条作答") } } to Unit
            } else {
                val byLemma = d.words.values.associateBy { it.lemma }
                val idMap = p.words.associate { w -> w.id to (byLemma[w.lemma]?.id ?: w.id) }
                val newWords = p.words.filter { byLemma[it.lemma] == null }.map { it.copy(source = ContentSource("imported")) }
                val newGroups = p.groups.filter { g -> d.groups.values.none { it.memberIds.toSet() == g.memberIds.map { idMap[it] }.toSet() } }
                    .map { g -> g.copy(id = if (d.groups.containsKey(g.id)) c.newId("g") else g.id, memberIds = g.memberIds.mapNotNull { idMap[it] }) }
                val n = d.copy(words = d.words + newWords.associateBy { it.id }, groups = d.groups + newGroups.associateBy { it.id },
                    undo = d.undo + (opId to MUndo(createdWordIds = newWords.map { it.id }, createdGroupIds = newGroups.map { it.id })))
                with(c) { n.updateOp(opId) { it.copy(status = OperationStatus.Succeeded, actions = setOf(OperationAction.Undo), resultSummary = "新增 ${newWords.size} 个词条、${newGroups.size} 个词组；跳过 ${p.words.size - newWords.size} 个重复词条") } } to Unit
            }
        }
    }
}
