package dev.wordprism.engine

import dev.wordprism.contract.*
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class ModuleManifest(
    val name: String, val schemaVersion: Int, val path: String, val sha256: String,
    val required: Boolean = true, val dependencies: List<String> = emptyList(),
)
@Serializable data class PackageManifest(
    val format: String = "wordprism", val formatVersion: Int = PackageFormat.FORMAT_VERSION, val packageId: Id,
    val createdAtMs: Long, val libraryId: Id, val purpose: PackagePurpose,
    val producerVersion: String = "0.4.0", val modules: List<ModuleManifest>,
    val requiredCapabilities: List<String> = listOf("fsrs-6"),
)

object PackageFormat {
    const val FORMAT_VERSION = 1
    const val MODULE_VERSION = 2
    val moduleFields: Map<String, Set<String>> = linkedMapOf(
    "library" to setOf("words", "senses", "groups", "memberships", "targets", "activities", "activityVersions", "recommendations", "drafts"),
    "learning" to setOf("answers", "answerVersions", "presentations", "exposures", "feedback", "difficultyMarks", "sessions", "timeSpans", "days", "plans", "sequence"),
    "memory" to setOf("schedules", "settings", "policy", "policyVersions", "evaluations"),
    "analysis" to setOf("snapshots", "changes", "lastAnalyzedSequence", "lastAutoExecutionAtMs", "lastAutoExecutionDate"),
    "api" to setOf("presets", "presetVersions", "bindings", "usage"),
        "operations" to setOf("operations", "jobs", "requests", "undos", "importedPackages"),
    )
}
private val moduleFields get() = PackageFormat.moduleFields
private const val MAX_COMPRESSED = 32 * 1024 * 1024
private const val MAX_EXPANDED = 64 * 1024 * 1024
private val packageJson = Json(Contract.json) { ignoreUnknownKeys = true }

internal class Packages(private val engine: CoreEngine) {
    suspend fun preview(input: InputStream): View {
        val bytes = readBounded(input, MAX_COMPRESSED)
        val entries = linkedMapOf<String, ByteArray>(); var expanded = 0
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    requireBusiness(!entry.isDirectory && Regex("manifest\\.json|modules/[a-z][a-z0-9_-]{0,63}\\.json").matches(entry.name) && entry.name !in entries, "包内路径无效或重复", ErrorCode.INVALID_PACKAGE)
                    requireBusiness(entries.size < 32, "模块数量超限", ErrorCode.LIMIT_EXCEEDED)
                    val data = readBounded(zip, minOf(MAX_EXPANDED - expanded, if (entry.name == "manifest.json") 512 * 1024 else MAX_COMPRESSED))
                    expanded += data.size; entries[entry.name] = data
                }
            }
        } catch (e: BusinessException) { throw e } catch (_: Exception) { fail(ErrorCode.INVALID_PACKAGE, "专属包无法读取") }
        val manifest = try { Contract.json.decodeFromString<PackageManifest>(entries["manifest.json"].found("包清单").toString(Charsets.UTF_8)) }
            catch (_: Exception) { fail(ErrorCode.INVALID_PACKAGE, "包清单无效") }
        requireBusiness(manifest.format == "wordprism" && manifest.formatVersion == 1, "不支持此容器版本", ErrorCode.UNSUPPORTED_VERSION)
        requireBusiness(manifest.modules.map { it.name }.distinct().size == manifest.modules.size && manifest.modules.map { it.path }.distinct().size == manifest.modules.size, "模块身份重复", ErrorCode.INVALID_PACKAGE)
        requireBusiness(manifest.requiredCapabilities.all { it == "fsrs-6" }, "需要当前版本不支持的能力", ErrorCode.UNSUPPORTED_ALGORITHM)
        val names = manifest.modules.map { it.name }.toSet()
        requireBusiness("library" in names && (manifest.purpose != PackagePurpose.BACKUP || names.containsAll(moduleFields.keys)), "缺少必需模块", ErrorCode.INVALID_PACKAGE)
        requireBusiness(entries.keys == manifest.modules.map { it.path }.toSet() + "manifest.json", "存在未登记资源", ErrorCode.INVALID_PACKAGE)
        val migrations = mutableListOf<String>(); val archived = mutableListOf<String>()
        val modules = manifest.modules.associate { module ->
            requireBusiness(module.dependencies.all { it in names }, "模块依赖缺失", ErrorCode.INVALID_PACKAGE)
            val data = entries[module.path].found("模块数据")
            requireBusiness(sha256(data) == module.sha256, "模块完整性校验失败", ErrorCode.INVALID_PACKAGE)
            requireBusiness(module.schemaVersion > 0, "模块版本无效", ErrorCode.INVALID_PACKAGE)
            if (module.name in moduleFields) {
                requireBusiness(module.schemaVersion <= 2, "核心模块版本过新", ErrorCode.UNSUPPORTED_VERSION)
                if (module.schemaVersion == 1) migrations += "${module.name}:1->2"
            } else {
                requireBusiness(!module.required, "存在不支持的必需模块", ErrorCode.UNSUPPORTED_VERSION); archived += module.name
            }
            val value = try { Contract.json.parseToJsonElement(data.toString(Charsets.UTF_8)) } catch (_: Exception) { fail(ErrorCode.INVALID_PACKAGE, "模块 JSON 无效") }
            requireBusiness(value is JsonObject, "模块必须是对象", ErrorCode.INVALID_PACKAGE)
            module.name to value
        }
        val staged = decode(manifest, modules)
        validateDatabase(staged, engine.memory)
        return engine.store.transaction { db ->
            val incoming = StateCodec.rows(staged).filter { it.bucket in moduleFields.getValue("library") }
            val existing = StateCodec.rows(db).associateBy { it.bucket to it.key }
            val conflicts = incoming.filter { existing[it.bucket to it.key]?.payload?.let { old -> old != it.payload } == true }.map { Id(it.key) }.distinct()
            val preview = PackagePreview(id(), manifest.packageId, manifest.purpose, sha256(bytes), db.revision + 1,
                staged.words.keys.count { it !in db.words }, staged.words.keys.count { it in db.words }, conflicts, migrations, archived, staged.presets.isNotEmpty(),
                manifest.formatVersion, manifest.producerVersion, manifest.createdAtMs,
                manifest.modules.map { m -> PackageModuleInfo(m.name, m.schemaVersion, if (m.name in archived) "unknown_optional" else if (m.schemaVersion < PackageFormat.MODULE_VERSION) "migrate" else "ok") })
            db.previews.clear(); db.previews[preview.token] = StoredPreview(preview, manifest, modules)
            View.ImportPreview(preview)
        }
    }

    suspend fun export(purpose: PackagePurpose, output: OutputStream, wordIds: Set<Id>, identity: ExportIdentity?): View {
        val db = engine.store.read()
        val fingerprint = identity?.let { key ->
            requireBusiness(key.key.length in 1..160 && Regex("[0-9a-f]{64}").matches(key.destinationSha256), "导出请求身份无效")
            sha256(buildJsonObject { put("type", "stream_export"); put("purpose", purpose.name); put("destination", key.destinationSha256)
                put("words", JsonArray(wordIds.sortedBy { it.value }.map { JsonPrimitive(it.value) })) }.toString().toByteArray())
        }
        identity?.let { key -> db.requests[key.key]?.let { prior ->
            requireBusiness(prior.fingerprint == fingerprint && prior.view is View.Exported, "请求身份已用于不同内容或导出目标", ErrorCode.IDEMPOTENCY_CONFLICT)
            return prior.view
        } }
        val selected = if (purpose == PackagePurpose.BACKUP) StateCodec.clone(db) else content(db, wordIds)
        selected.previews.clear()
        val all = Contract.json.encodeToJsonElement(selected).jsonObject
        val values = linkedMapOf<String, JsonElement>()
        val keys = if (purpose == PackagePurpose.BACKUP) moduleFields.keys else setOf("library")
        keys.forEach { name ->
            // Unknown optional fields in a known module are carried as opaque data, never executed.
            val preserved = if (purpose == PackagePurpose.BACKUP) db.archivedModules["fields_$name"]?.data else null
            val current = JsonObject(all.filterKeys { it in moduleFields.getValue(name) })
            values[name] = preserved?.let { mergeExtensions(current, it) } ?: current
        }
        if (purpose == PackagePurpose.BACKUP) db.archivedModules.filterKeys { !it.startsWith("fields_") }.forEach { (name, module) -> values[name] = module.data }
        val data = values.mapValues { it.value.toString().toByteArray() }
        requireBusiness(data.values.sumOf { it.size.toLong() } <= MAX_EXPANDED, "导出超过当前包大小限制", ErrorCode.LIMIT_EXCEEDED)
        val manifest = PackageManifest(packageId = id(), createdAtMs = engine.now(), libraryId = db.libraryId, purpose = purpose,
            modules = values.map { (name, _) ->
                val unknown = db.archivedModules[name]
                ModuleManifest(name, unknown?.version ?: PackageFormat.MODULE_VERSION, "modules/$name.json", sha256(data.getValue(name)), unknown?.required ?: true,
                    unknown?.dependencies ?: if (name == "library") emptyList() else listOf("library"))
            })
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            fun write(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            write("manifest.json", Contract.json.encodeToJsonElement(manifest).toString().toByteArray())
            data.forEach { (name, bytes) -> write("modules/$name.json", bytes) }
        }
        requireBusiness(buffer.size() <= MAX_COMPRESSED, "压缩包超过当前大小限制", ErrorCode.LIMIT_EXCEEDED)
        output.write(buffer.toByteArray()); output.flush()
        val receipt = View.Exported(manifest.packageId, buffer.size().toLong())
        engine.store.transaction { state ->
            state.operations[manifest.packageId] = Operation(manifest.packageId, "export", OperationStatus.SUCCEEDED, engine.now(), engine.now(),
                summary = if (purpose == PackagePurpose.BACKUP) "完整备份已写入所选文件；凭证不包含在备份中" else "词库内容包已写入所选文件")
            identity?.let { state.requests[it.key] = SavedRequest(fingerprint!!, receipt) }
        }
        return receipt
    }

    fun commit(db: Database, token: Id, restoring: Boolean, now: Long): View {
        val stored = db.previews[token].found("导入预览")
        db.importedPackages[stored.manifest.packageId]?.let { return View.OperationResult(db.operations[it].found("已完成导入")) }
        requireBusiness(stored.preview.basedOnRevision == db.revision, "资料库已变化，请重新预览", ErrorCode.VERSION_CONFLICT)
        requireBusiness(restoring == (stored.manifest.purpose == PackagePurpose.BACKUP), "完整恢复与追加内容包必须使用对应入口")
        val staged = decode(stored.manifest, stored.modules); validateDatabase(staged, engine.memory)
        val opId = id()
        if (restoring) {
            staged.settings = staged.settings.copy(automaticAnalysis = false, automaticGeneration = false, automationEpoch = maxOf(staged.settings.automationEpoch, db.settings.automationEpoch) + 1)
            staged.operations.replaceAll { _, op -> if (op.status !in terminalStatuses) op.copy(status = OperationStatus.RESTORED_PENDING, fault = Fault(ErrorCode.DISABLED, "备份恢复后需要重新授权任务")) else op }
            staged.jobs.replaceAll { _, job -> job.copy(lease = null, fitToken = null) }
            staged.sessions.replaceAll { _, session -> session.copy(timerMode = TimerMode.PAUSED, timerAtMs = null, status = if (session.status == SessionStatus.ACTIVE) SessionStatus.PAUSED else session.status) }
            staged.usage.replaceAll { _, usage -> if (usage.outcome == "in_flight") usage.copy(outcome = "restored_unknown", endedAtMs = now) else usage }
            staged.previews.clear(); engine.replaceContents(db, staged)
        } else {
            requireBusiness(stored.preview.conflicts.isEmpty(), "内容包存在冲突，请先在来源库修正后重新导出", ErrorCode.VERSION_CONFLICT)
            val before = db.captureLibrary()
            db.words.putAll(staged.words); db.senses.putAll(staged.senses); db.groups.putAll(staged.groups); db.memberships.putAll(staged.memberships)
            db.recommendations.putAll(staged.recommendations)
            staged.targets.forEach { (targetId, target) -> if (targetId !in db.targets) { db.targets[targetId] = target; db.schedules[targetId] = Schedule(targetId, dueAtMs = now, policyVersion = db.policy.version) } }
            db.activities.putAll(staged.activities); db.activityVersions.putAll(staged.activityVersions)
            db.archivedModules.putAll(staged.archivedModules); db.recordLibraryUndo(opId, before)
            db.invalidatePlans(now)
            validateDatabase(db, engine.memory)
        }
        val operation = Operation(opId, if (restoring) "restore_backup" else "commit_import", OperationStatus.SUCCEEDED, now, now,
            resultIds = staged.words.keys.toList(), undoable = !restoring && db.undos[opId]?.isNotEmpty() == true,
            summary = if (restoring) "备份已恢复；未完成请求与凭证需要重新配置" else "内容包已导入，已有学习历史保留")
        db.operations[opId] = operation; db.importedPackages[stored.manifest.packageId] = opId; db.previews.remove(token)
        return View.OperationResult(operation)
    }

    private fun decode(manifest: PackageManifest, modules: Map<String, JsonElement>): Database {
        val combined = mutableMapOf<String, JsonElement>("schemaVersion" to JsonPrimitive(2), "libraryId" to Contract.json.encodeToJsonElement(manifest.libraryId))
        val unknown = linkedMapOf<String, ArchivedModule>()
        manifest.modules.forEach { module ->
            val fields = modules.getValue(module.name).jsonObject
            requireBusiness("credentialRefs" !in fields, "专属包不能携带设备凭证引用", ErrorCode.INVALID_PACKAGE)
            if (module.name in moduleFields) {
                val known = moduleFields.getValue(module.name)
                combined.putAll(fields.filterKeys { it in known })
            } else unknown[module.name] = ArchivedModule(module.schemaVersion, module.required, module.dependencies, fields)
        }
        val result = try { packageJson.decodeFromJsonElement<Database>(JsonObject(combined)) } catch (_: Exception) { fail(ErrorCode.INVALID_PACKAGE, "模块数据不符合当前 schema") }
        val knownState = Contract.json.encodeToJsonElement(result).jsonObject
        manifest.modules.filter { it.name in moduleFields }.forEach { module ->
            val decoded = JsonObject(knownState.filterKeys { it in moduleFields.getValue(module.name) })
            extensions(modules.getValue(module.name), decoded)?.let { extra ->
                unknown["fields_${module.name}"] = ArchivedModule(module.schemaVersion, false, module.dependencies, extra)
            }
        }
        result.archivedModules.putAll(unknown)
        if (manifest.purpose == PackagePurpose.CONTENT) {
            requireBusiness(manifest.modules.none { it.name in moduleFields && it.name != "library" }, "内容包不能携带个人学习记录或 API 配置", ErrorCode.INVALID_PACKAGE)
            // Content may carry only the candidates explicitly linked by its saved supplements.
            // Acceptance is personal history and must be decided anew in the destination library.
            val candidates = result.senses.values.flatMap { it.explanation?.recommendationIds.orEmpty() }.toSet()
            result.recommendations.keys.retainAll(candidates)
            result.recommendations.replaceAll { _, rec -> rec.copy(acceptedOperationId = null, inLibrary = false, libraryWordId = null) }
            result.drafts.clear()
            result.targets.forEach { (id, _) -> result.schedules[id] = Schedule(id, dueAtMs = 0) }
        }
        return result
    }

    private fun content(db: Database, ids: Set<Id>): Database {
        requireBusiness(ids.all { it in db.words }, "选中的词不存在")
        val chosenWords = (if (ids.isEmpty()) db.words.values.filter { !it.archived }.map { it.id } else ids).toMutableSet()
        var groupIds: Set<Id>; var senseIds: Set<Id>
        do {
            val count = chosenWords.size
            senseIds = db.senses.values.filter { it.wordId in chosenWords }.map { it.id }.toSet()
            groupIds = db.memberships.values.filter { it.active && it.senseId in senseIds }.map { it.groupId }.toSet()
            db.memberships.values.filter { it.active && it.groupId in groupIds }.forEach { db.senses[it.senseId]?.wordId?.let(chosenWords::add) }
        } while (chosenWords.size != count)
        val out = Database(libraryId = db.libraryId)
        out.words.putAll(db.words.filterKeys { it in chosenWords })
        out.senses.putAll(db.senses.filterKeys { it in senseIds }.mapValues { (_, sense) -> sense.copy(explanation = db.explanation(sense)) })
        val candidates = out.senses.values.flatMap { it.explanation?.recommendationIds.orEmpty() }.toSet()
        out.recommendations.putAll(db.recommendations.filterKeys { it in candidates }.mapValues { (_, rec) -> rec.copy(acceptedOperationId = null, inLibrary = false, libraryWordId = null) })
        out.groups.putAll(db.groups.filterKeys { it in groupIds }); out.memberships.putAll(db.memberships.filterValues { it.groupId in groupIds && it.senseId in senseIds })
        out.targets.putAll(db.targets.filterValues { it.senseId in senseIds && (it.groupId == null || it.groupId in groupIds) })
        out.activities.putAll(db.activities.filterValues { it.targetId in out.targets })
        out.activityVersions.putAll(db.activityVersions.filterValues { it.targetId in out.targets })
        return out
    }
}

/** Preserve optional unknown fields, including fields on stable-ID entities, across edits and export. */
private fun extensions(raw: JsonElement, known: JsonElement): JsonElement? = when {
    raw is JsonObject && known is JsonObject -> raw.mapNotNull { (key, value) ->
        if (key !in known) key to value else extensions(value, known.getValue(key))?.let { key to it }
    }.toMap().takeIf { it.isNotEmpty() }?.let(::JsonObject)
    raw is JsonArray && known is JsonArray -> {
        if (raw.indices.any { i -> i >= known.size || extensions(raw[i], known[i]) != null }) raw else null
    }
    else -> null
}
private fun mergeExtensions(current: JsonElement, archived: JsonElement): JsonElement = when {
    current is JsonObject && archived is JsonObject -> JsonObject(archived + current.mapValues { (key, value) -> archived[key]?.let { mergeExtensions(value, it) } ?: value })
    current is JsonArray && archived is JsonArray -> JsonArray(current.mapIndexed { index, value ->
        val identity = (value as? JsonObject)?.get("id")
        val old = if (identity != null) archived.firstOrNull { (it as? JsonObject)?.get("id") == identity } else archived.getOrNull(index)
        if (old != null) mergeExtensions(value, old) else value
    })
    else -> current
}

private fun readBounded(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream(); val chunk = ByteArray(8192)
    while (true) {
        val count = input.read(chunk); if (count == -1) break
        requireBusiness(output.size().toLong() + count <= limit, "专属包数据超过当前限制", ErrorCode.LIMIT_EXCEEDED)
        output.write(chunk, 0, count)
    }
    return output.toByteArray()
}

fun validateDatabase(db: Database, memory: dev.wordprism.memory.MemoryKernel) {
    fun valid(condition: Boolean, text: String) = requireBusiness(condition, text, ErrorCode.INVALID_PACKAGE)
    valid(db.words.size <= 10000 && db.answers.size <= 100000 && db.activities.size <= 30000, "实体数量超限")
    valid(db.words.all { (id, word) -> id == word.id && word.version > 0 && word.spelling.length in 1..100 && word.normalized == normalize(word.spelling) &&
        word.notes.length <= 16000 && word.tags.size <= 30 && word.tags.all { it.length <= 80 } }, "词条身份或内容无效")
    valid(db.words.values.map { it.normalized }.distinct().size == db.words.size, "存在重复规范词形")
    valid(db.senses.all { (id, value) -> id == value.id && value.wordId in db.words && value.version > 0 && value.meaning.length in 1..4000 &&
        value.acceptedForms.size <= 31 && value.acceptedForms.all { it.length in 1..4000 } && value.examples.size <= 20 && value.examples.all { it.length <= 4000 } }, "义项引用或内容无效")
    db.senses.values.forEach { sense -> sense.explanation?.let { saved ->
        validateExplanationContent(saved.content, ErrorCode.INVALID_PACKAGE)
        valid(saved.version in 1..sense.version && saved.basedOnSenseVersion in 1..sense.version && saved.recommendationIds.size <= 6 &&
            saved.recommendationIds.distinct().size == saved.recommendationIds.size && saved.recommendationIds.all { db.recommendations[it]?.originSenseId == sense.id },
            "讲解版本或词族候选引用无效")
    } }
    valid(db.recommendations.all { (id, rec) -> id == rec.id && rec.spelling.isNotBlank() && rec.spelling.length <= 100 && rec.reason.length <= 4000 &&
        (rec.groupId == null || rec.groupId in db.groups) && (rec.originSenseId == null || rec.originSenseId in db.senses && rec.groupId == null) &&
        (rec.content?.let { validRecommendationContent(it) && normalize(it.spelling) == normalize(rec.spelling) } != false) }, "推荐引用或内容无效")
    valid(db.groups.all { (id, value) -> id == value.id && value.version > 0 && value.title.length in 1..200 && value.purpose.length in 1..4000 }, "词组身份无效")
    valid(db.memberships.all { (id, value) -> id == value.id && value.version > 0 && value.position >= 0 && value.groupId in db.groups && value.senseId in db.senses }, "成员引用无效")
    valid(db.memberships.values.map { it.groupId to it.senseId }.distinct().size == db.memberships.size, "成员关系重复")
    valid(db.targets.all { (id, value) -> id == value.id && value.senseId in db.senses && (value.groupId == null || value.groupId in db.groups) }, "目标引用无效")
    valid(db.targets.values.map { Triple(it.senseId,it.ability,it.groupId) }.distinct().size == db.targets.size, "学习目标重复")
    fun activityValid(value: Activity) = value.targetId in db.targets && value.version > 0 && value.senseVersion > 0 &&
        value.prompt.length in 1..16000 && value.explanation.length <= 16000 && listOfNotNull(value.passage, value.question, value.sourceSentence).all { it.length <= 16000 } &&
        value.choices.size <= 8 && value.choices.all { it.senseId == null || it.senseId in db.senses }
    valid(db.activities.all { (id, value) -> id == value.id && activityValid(value) }, "题目引用无效")
    valid(db.activityVersions.all { (key, value) -> key == "${value.id.value}:${value.version}" && value.id in db.activities && activityValid(value) }, "题目版本无效")
    valid(db.answers.all { (id, value) -> id == value.id && value.sessionId in db.sessions && value.targetId in db.targets &&
        value.activityId in db.activities && "${value.activityId.value}:${value.activityVersion}" in db.activityVersions && value.policyVersion in db.policyVersions }, "作答引用无效")
    valid(db.answers.values.all { it.version > 0 && it.sequence > 0 } && db.answers.values.map { it.sequence }.distinct().size == db.answers.size && db.sequence >= (db.answers.values.maxOfOrNull { it.sequence } ?: 0), "学习事件位置无效")
    valid(db.answerVersions.all { (key, value) -> key == "${value.id.value}:${value.version}" && value.id in db.answers && value.sessionId in db.sessions &&
        "${value.activityId.value}:${value.activityVersion}" in db.activityVersions && value.policyVersion in db.policyVersions }, "作答版本无效")
    valid(db.presentations.values.all { it in db.answers }, "题目展示引用无效")
    valid(db.sessions.values.all { it.dayId in db.days && it.planId in db.plans && it.targetIds.all { id -> id in db.targets } && it.policyVersion in db.policyVersions }, "会话引用无效")
    valid(db.plans.values.all { it.dayId in db.days && it.targetIds.all { id -> id in db.targets } && it.policyVersion in db.policyVersions }, "计划引用无效")
    valid(db.plans.values.all { it.newWordCount == null || it.newWordCount in 0..it.targetIds.size }, "计划新词数量无效")
    valid(db.days.all { (id, day) -> id == day.id && day.opensAtMs < day.closesAtMs && (day.planId == null || db.plans[day.planId]?.dayId == id) &&
        (day.automaticOperationId == null || day.automaticOperationId in db.operations) }, "学习日引用无效")
    valid(db.sessions.values.all { s -> (s.currentActivityId == null || s.currentActivityId in db.activities) &&
        (s.lastAnswerId == null || db.answers[s.lastAnswerId]?.sessionId == s.id) && s.completedTargets.all { it in s.targetIds } }, "会话进度无效")
    valid(db.timeSpans.all { (id, span) -> id == span.id && span.sessionId in db.sessions && span.dayId in db.days && span.fromMs <= span.toMs &&
        span.fromMs >= db.days.getValue(span.dayId).opensAtMs && span.toMs <= db.days.getValue(span.dayId).closesAtMs }, "计时间隔无效")
    valid(db.feedback.values.all { it.answerId in db.answers && "${it.answerId.value}:${it.answerVersion}" in db.answerVersions }, "反馈引用无效")
    valid(db.difficultyMarks.all { (id, mark) -> id == mark.id && "${mark.answerId.value}:${mark.answerVersion}" in db.answerVersions }, "难度标记引用无效")
    valid(db.bindings.values.all { it.presetId in db.presets } && db.jobs.keys.all { it in db.operations }, "配置或任务引用无效")
    valid(db.changes.values.all { it.operationId in db.operations && (it.snapshotId == null || it.snapshotId in db.snapshots) }, "调整历史引用无效")
    valid(db.operations.all { (id, op) -> id == op.id && (op.parentOperationId == null || op.parentOperationId in db.operations) &&
        (op.undoneBy == null || op.undoneBy in db.operations) && (op.snapshotId == null || op.snapshotId in db.snapshots) }, "操作记录引用无效")
    valid(db.schedules.all { (id, state) -> id in db.targets && state.targetId == id && state.algorithm == "fsrs-6/fsrs-rs-6.6.2" &&
        (state.memory?.let { it.stability.isFinite() && it.stability > 0 && it.difficulty in 1f..10f } != false) }, "不支持或损坏的记忆状态")
    valid(db.lastAnalyzedSequence in 0..db.sequence && db.settings.version > 0 && db.settings.budgetMinutes in 1..240 && db.settings.automaticRequestsPerDay in 0..100, "统计覆盖或用户预算无效")
    valid(db.policyVersions[db.policy.version] == db.policy && db.policyVersions.all { (version, policy) -> version == policy.version }, "当前策略版本无效")
    valid(db.presetVersions.all { (key, preset) -> key == "${preset.id.value}:${preset.version}" } &&
        db.presets.all { (id, preset) -> id == preset.id && db.presetVersions["${id.value}:${preset.version}"] == preset }, "预设版本无效")
    try {
        db.policyVersions.values.forEach { validateTrainingPolicy(db, it) }
        validateCallSettings(db, db.settings)
        java.time.ZoneId.of(db.settings.zoneId)
        db.presets.values.forEach { validatePreset(it) }
    } catch (_: BusinessException) { fail(ErrorCode.INVALID_PACKAGE, "备份中的策略或调用配置无效") }
    catch (_: java.time.DateTimeException) { fail(ErrorCode.INVALID_PACKAGE, "备份中的时区无效") }
    db.policyVersions.values.forEach { policy -> try { memory.validate(policy.weights) } catch (_: Exception) { fail(ErrorCode.UNSUPPORTED_ALGORITHM, "无法解释备份中的算法参数") } }
}
