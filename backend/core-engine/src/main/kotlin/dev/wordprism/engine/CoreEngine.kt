package dev.wordprism.engine

import dev.wordprism.contract.*
import dev.wordprism.memory.MemoryKernel
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class CoreEngine(
    val store: StateStore, internal val credentials: CredentialStore, internal val memory: MemoryKernel,
    private val clock: Clock = Clock.systemUTC(), private val provider: LlmProvider = OpenAiCompatibleProvider(),
    private val allowLocalHttp: Boolean = false,
) : WordPrism {
    val tasks = TaskRunner(this, provider)
    internal val runningFits = ConcurrentHashMap<Id, Long>()
    private val exports = Mutex()
    internal fun now() = clock.millis()
    internal fun hasCredential(db: Database, presetId: Id): Boolean = db.credentialRefs[presetId]?.let { credentials.has(it) } == true

    /** Call once when opening a process, before scheduling workers; never on each screen recreation. */
    suspend fun recover(): Reply = guarded {
        store.transaction { db ->
            db.sessions.replaceAll { _, session -> session.copy(timerMode = TimerMode.PAUSED, timerAtMs = null, status = if (session.status == SessionStatus.ACTIVE) SessionStatus.PAUSED else session.status) }
            db.operations.values.filter { it.status == OperationStatus.RUNNING }.forEach { op ->
                db.operations[op.id] = op.copy(status = if (op.feature == null) OperationStatus.QUEUED else OperationStatus.FAILED,
                    fault = if (op.feature == null) null else Fault(ErrorCode.OUTCOME_UNKNOWN, "进程中断后服务商结果未知；不会自动重复请求"), retryable = op.feature != null, updatedAtMs = now())
                db.jobs[op.id]?.let { db.jobs[op.id] = it.copy(lease = null) }
            }
            db.usage.replaceAll { _, usage -> if (usage.outcome == "in_flight") usage.copy(outcome = "process_interrupted_unknown", endedAtMs = now()) else usage }
            db.day(now()); View.Ok()
        }
    }

    override suspend fun execute(request: Request): Reply = guarded {
        requireBusiness(request.contractVersion == Contract.VERSION, "契约版本不支持", ErrorCode.UNSUPPORTED_VERSION)
        requireBusiness(request.key.length in 1..160, "请求身份长度必须为 1–160")
        val fingerprint = sha256(Contract.json.encodeToString(request.command).toByteArray())
        val cancelAfterCommit = mutableSetOf<Id>()
        val credentialsAfterCommit = mutableSetOf<Id>()
        val (value, applied) = store.transaction { db ->
            db.requests[request.key]?.let { saved ->
                requireBusiness(saved.fingerprint == fingerprint, "请求身份已用于不同内容", ErrorCode.IDEMPOTENCY_CONFLICT)
                return@transaction db.refresh(saved.view) to false
            }
            if (request.command is Command.RestoreBackup) {
                cancelAfterCommit += db.operations.values.filter { it.status !in terminalStatuses }.map { it.id }
                credentialsAfterCommit += db.credentialRefs.values
            }
            (request.command as? Command.DeletePreset)?.let { command -> db.credentialRefs[command.preset.id]?.let { credentialsAfterCommit += it } }
            val view = applyCommand(db, request.command, Actor.USER, now())
            db.requests[request.key] = SavedRequest(fingerprint, view)
            view to true
        }
        if (applied) {
            (request.command as? Command.CancelOperation)?.let { cancelAfterCommit += it.id }
            if (request.command is Command.SetSettings || request.command is Command.RestoreBackup || request.command is Command.UndoOperation) {
                cancelAfterCommit += store.read().operations.values.filter { it.status == OperationStatus.CANCELLED || it.status == OperationStatus.RESTORED_PENDING }.map { it.id }
            }
            cancelAfterCommit.forEach(tasks::cancel)
            credentialsAfterCommit.forEach(credentials::delete)
        }
        value
    }

    override suspend fun query(query: Query): Reply = guarded {
        if (query is Query.GetLearningDay && query.id == null) {
            return@guarded store.transaction { db ->
                val day = db.day(now())
                if (day.planId?.let { db.plans[it]?.plannerVersion } != PLANNER_VERSION) db.plan(day, now())
                db.dayView(db.days[day.id]!!)
            }
        }
        val db = store.read()
        when (query) {
            is Query.Library -> db.library(query.search, query.afterId, query.limit, now = now())
            is Query.LibrarySummary -> View.LibrarySummary(db.libraryOverview(query, now()))
            is Query.GetWord -> { db.words[query.id].found("词条"); db.library(wordId = query.id, now = now()) }
            is Query.GetExplanation -> db.explanationView(query.senseId)
            is Query.GetGroup -> { db.groups[query.id].found("词组"); db.library(groupId = query.id, limit = 10000, now = now()) }
            is Query.GetSession -> db.sessionView(db.sessions[query.id].found("会话"))
            is Query.GetActivity -> db.activities[query.id].found("题目").let { View.ActivityDetails(db.card(it), it.source) }
            is Query.GetPresentation -> {
                val answer = db.presentations[query.id]?.let(db.answers::get)
                val session = (answer?.sessionId?.let(db.sessions::get) ?: db.sessions.values.find { it.currentPresentationId == query.id }).found("题目展示")
                val activity = (answer?.let { db.activityVersions["${it.activityId.value}:${it.activityVersion}"] } ?: session.currentActivityId?.let(db.activities::get)).found("题目版本")
                View.Presentation(query.id, session.id, db.card(activity), answer?.let(db::answerView))
            }
            is Query.GetLearningDay -> db.dayView(db.days[query.id].found("学习日"))
            is Query.Overview -> View.Snapshot(db.snapshot(now(), query.fromMs))
            is Query.Snapshot -> View.Snapshot(db.snapshots[query.id].found("快照"))
            is Query.Evidence -> {
                requireBusiness(query.limit in 1..200, "证据分页数量须为 1–200")
                val answers = db.answers.values.filter { it.sequence > query.afterSequence && (query.targetId == null || it.targetId == query.targetId) }.sortedBy { it.sequence }.take(query.limit + 1)
                val page = answers.take(query.limit)
                View.Evidence(page, page.mapNotNull { db.activityVersions["${it.activityId.value}:${it.activityVersion}"] }.distinctBy { it.id to it.version }, page.lastOrNull()?.sequence?.takeIf { answers.size > query.limit })
            }
            is Query.GetFeedback -> (if (query.answerVersion == null) db.answers[query.answerId] else db.answerVersions["${query.answerId.value}:${query.answerVersion}"]).found("答案版本").let { a -> View.Feedbacks(a, db.feedback.values.filter { it.answerId == a.id && it.answerVersion == a.version }, db.answerView(a)) }
            is Query.GetOperationFeedback -> {
                val op = db.operations[query.operationId].found("操作")
                val feedback = op.resultIds.firstNotNullOfOrNull { db.feedback[it] }.found("反馈")
                val answer = db.answerVersions["${feedback.answerId.value}:${feedback.answerVersion}"].found("答案版本")
                View.Feedbacks(answer, listOf(feedback), db.answerView(answer).copy(feedback = listOf(feedback)))
            }
            is Query.GetOperation -> View.OperationResult(db.operations[query.id].found("操作"))
            is Query.OperationEffects -> { db.operations[query.id].found("操作"); View.OperationEffects(db.businessEffects(query.id)) }
            is Query.Operations -> { requireBusiness(query.limit in 1..200, "分页数量须为 1–200"); View.Operations(db.operations.values.sortedByDescending { it.createdAtMs }.take(query.limit)) }
            is Query.Recommendations -> View.Recommendations(db.recommendations.values.filter { (query.groupId == null || it.groupId == query.groupId) && db.recommendationAvailable(it) && (it.acceptedOperationId == null || db.operations[it.acceptedOperationId]?.undoneBy != null) }
                .map(db::recommendationView))
            Query.Settings -> View.Settings(db.settings, db.policy, db.bindings.values.toList())
            Query.Capabilities -> View.Capabilities(db.capabilities())
            Query.PolicyChanges -> View.PolicyChanges(db.changes.values.toList(), db.changes.values.filter { change -> change.undoneBy == null &&
                db.policy.copy(version = change.after.version) == change.after && db.changes.values.none { it.after.version > change.after.version && it.undoneBy == null } }.map { it.id }.toSet())
            Query.Presets -> View.Presets(db.presets.values.map { PresetView(it, hasCredential(db, it.id), db.connectionTest(it)) }, db.bindings.values.toList())
            Query.CallPolicy -> View.CallPolicy(db.callPolicy())
            Query.Protocols -> View.Protocols(ApiProtocols.supported)
            is Query.GetPackagePreview -> View.ImportPreview(db.previews[query.token].found("导入预览").preview)
            is Query.ExportScope -> View.ExportScope(query.purpose,
                if (query.purpose == PackagePurpose.BACKUP) listOf("完整词库、义项与组关系", "词源、助记讲解与词族候选", "真实作答、内容版本与会话", "记忆状态、策略与拟合评估", "分析、调整、撤销与任务记录", "API 非敏感配置、功能绑定与用量")
                else listOf("选中词条及其完整关联词组", "义项、目标与已保存练习内容", "词源、助记与待选择的词族候选"),
                if (query.purpose == PackagePurpose.BACKUP) listOf("API 凭证", "设备专用凭证引用") else listOf("个人学习历史与记忆状态", "API 配置、凭证和用量", "分析、任务与自动执行授权"),
                "wordprism-${query.purpose.name.lowercase()}-${java.time.Instant.ofEpochMilli(now()).atZone(ZoneId.of(db.settings.zoneId)).toLocalDate()}.wordprism")
            is Query.ConfusionEvidence -> {
                requireBusiness(query.limit in 1..200, "证据数量须为 1–200")
                val selected = db.answers.values.filter { a -> a.valid && a.confusedWith != null &&
                    db.words[db.senses[db.targets[a.targetId]?.senseId]?.wordId]?.spelling == query.expected &&
                    db.words[db.senses[a.confusedWith]?.wordId]?.spelling == query.selected }.sortedByDescending { it.sequence }.take(query.limit)
                View.Evidence(selected, selected.mapNotNull { db.activityVersions["${it.activityId.value}:${it.activityVersion}"] }.distinctBy { it.id to it.version }, null)
            }
            is Query.PreviewCall -> {
                val requires = query.recommendationIds.isEmpty() || query.recommendationIds.any { db.recommendations[it].found("推荐").let { rec -> db.recommendationContent(rec) == null } }
                val preview = db.callPreview(query.feature, requires)
                val day = db.days.values.find { now() in it.opensAtMs until it.closesAtMs }
                val fault = preview.fault ?: if (requires && preview.preset?.let { !hasCredential(db, it.id) } == true) Fault(ErrorCode.CREDENTIAL_MISSING, "请设置 API 凭证")
                    else if (requires && db.settings.dailyRequestLimit?.let { limit -> db.usage.values.count { u -> day != null && u.startedAtMs in day.opensAtMs until day.closesAtMs } >= limit } == true) Fault(ErrorCode.BUDGET_EXCEEDED, "今日请求额度已用完") else null
                View.Preview(preview.copy(fault = fault))
            }
            is Query.GetUsage -> View.Usages(db.usage.values.filter { it.startedAtMs >= query.fromMs && (query.feature == null || it.feature == query.feature) && (query.presetId == null || it.preset.id == query.presetId) })
            is Query.GetDraft -> View.DraftResult(db.drafts[query.key])
            is Query.GetEvaluation -> View.EvaluationResult(db.evaluations[query.id].found("评估"))
        }
    }
    override fun observe(): Flow<Long> = store.revisions()
    override suspend fun previewPackage(input: InputStream): Reply = guarded { Packages(this).preview(input) }
    override suspend fun exportPackage(purpose: PackagePurpose, output: OutputStream, wordIds: Set<Id>, identity: ExportIdentity?): Reply = guarded {
        exports.withLock { Packages(this).export(purpose, output, wordIds, identity) }
    }
    override suspend fun setCredential(presetId: Id, secret: CharArray): Reply = try {
        guarded {
            store.read().presets[presetId].found("预设")
            requireBusiness(secret.size in 1..4096 && secret.none { it == '\r' || it == '\n' }, "凭证格式无效")
            val reference = id(); var linked = false
            try {
                credentials.write(reference, secret)
                val previous = store.transaction { db -> db.presets[presetId].found("预设"); db.credentialRefs.put(presetId, reference) }
                linked = true; previous?.let(credentials::delete)
            } finally { if (!linked) credentials.delete(reference) }
            View.Ok(presetId)
        }
    } finally { secret.fill('\u0000') }
    override suspend fun deleteCredential(presetId: Id): Reply = guarded {
        val reference = store.transaction { it.credentialRefs.remove(presetId) }; reference?.let(credentials::delete); View.Ok(presetId)
    }
    override suspend fun listModels(request: ModelCatalogRequest, secret: CharArray?): Reply = try {
        guarded {
            requireBusiness(request.contractVersion == Contract.VERSION, "契约版本不支持", ErrorCode.UNSUPPORTED_VERSION)
            val root = validateConnection(request.baseUrl, request.protocol, request.timeoutSeconds, allowLocalHttp)
            var credentialReference: Id? = null
            val saved = request.credentialPreset.takeIf { secret == null }
            val resolved = secret ?: run {
                val ref = saved ?: fail(ErrorCode.CREDENTIAL_MISSING, "请填写 API 密钥后拉取模型")
                val db = store.read()
                val preset = db.presets[ref.id].found("预设"); checkRef(ref, preset.version)
                val savedRoot = validateConnection(preset.baseUrl, preset.protocol, preset.timeoutSeconds, allowLocalHttp)
                requireBusiness(root.toString().trimEnd('/') == savedRoot.toString().trimEnd('/'),
                    "服务地址已改变，请填写用于新地址的 API 密钥后拉取模型", ErrorCode.CREDENTIAL_MISSING)
                credentialReference = db.credentialRefs[ref.id]
                credentialReference?.let(credentials::read) ?: fail(ErrorCode.CREDENTIAL_MISSING, "请填写 API 密钥后拉取模型")
            }
            try {
                requireBusiness(resolved.size in 1..4096 && resolved.none { it == '\r' || it == '\n' }, "凭证格式无效")
                val models = provider.listModels(request, resolved)
                currentCoroutineContext().ensureActive()
                if (saved != null) {
                    val current = store.read()
                    requireBusiness(current.presets[saved.id]?.version == saved.version && current.credentialRefs[saved.id] == credentialReference,
                        "预设或凭证已变化，请重新拉取模型", ErrorCode.VERSION_CONFLICT)
                }
                View.Models(models)
            } finally { resolved.fill('\u0000') }
        }
    } finally { secret?.fill('\u0000') }

    private suspend fun guarded(action: suspend () -> View): Reply = withContext(Dispatchers.IO) {
        try { val value = action(); Reply(store.read().revision, value) }
        catch (e: CancellationException) { throw e }
        catch (e: BusinessException) { Reply(runCatching { store.read().revision }.getOrDefault(0), fault = e.fault) }
        catch (e: ProviderException) { Reply(runCatching { store.read().revision }.getOrDefault(0), fault = e.fault) }
        catch (_: java.io.IOException) { Reply(0, fault = Fault(ErrorCode.STORAGE, "本机数据读写失败；请保留同一请求身份重试", true)) }
        catch (_: kotlinx.serialization.SerializationException) { Reply(0, fault = Fault(ErrorCode.VALIDATION, "数据不符合当前契约")) }
        catch (_: IllegalArgumentException) { Reply(0, fault = Fault(ErrorCode.VALIDATION, "参数无效")) }
    }

    internal fun applyCommand(db: Database, command: Command, actor: Actor, now: Long): View {
        if (actor == Actor.AI) {
            val capability = Contract.json.encodeToJsonElement<Command>(command).jsonObject.getValue("type").jsonPrimitive.content
            requireBusiness(capability in aiActionTypes, "AI 不能修改用户授权或真实历史", ErrorCode.DISABLED)
            requireBusiness(capability !in db.settings.lockedCapabilities, "此业务动作已由用户锁定", ErrorCode.DISABLED)
        }
        return when (command) {
            is Command.SaveWords -> db.localOperation("save_words", now) { op -> db.saveWords(command, Provenance("user", op, atMs = now), now) }
            is Command.UpdateWord -> db.localOperation("update_word", now) {
                val word = db.words[command.word.id].found("词条"); checkRef(command.word, word.version)
                requireBusiness(command.notes.length <= 16000 && command.tags.size <= 30 && command.tags.all { it.length <= 80 }, "备注或标签过长")
                db.words[word.id] = word.copy(version = word.version + 1, notes = command.notes, tags = command.tags, archived = command.archived); listOf(word.id)
            }
            is Command.UpdateSense -> db.localOperation("update_sense", now) {
                val sense = db.senses[command.sense.id].found("义项"); checkRef(command.sense, sense.version)
                requireBusiness(command.meaning.isNotBlank() && command.meaning.length <= 4000 && command.acceptedForms.size <= 30 && command.examples.size <= 20, "义项内容无效")
                db.senses[sense.id] = sense.copy(version = sense.version + 1, meaning = command.meaning, acceptedForms = command.acceptedForms, examples = command.examples)
                db.words[sense.wordId]?.let { db.words[it.id] = it.copy(version = it.version + 1) }; listOf(sense.id)
            }
            is Command.EditWord -> db.localOperation("edit_word", now) {
                val word = db.words[command.word.id].found("词条"); checkRef(command.word, word.version)
                requireBusiness(command.notes?.let { it.length <= 16000 } != false, "备注过长")
                command.meaning?.let { meaning ->
                    val senseRef = command.sense
                    val sense = if (senseRef != null) db.senses[senseRef.id].found("义项").also { checkRef(senseRef, it.version) }
                        else db.senses.values.singleOrNull { it.wordId == word.id }.found("唯一义项；多义词需指定义项版本")
                    requireBusiness(sense.wordId == word.id && meaning.isNotBlank() && meaning.length <= 4000, "释义无效")
                    db.senses[sense.id] = sense.copy(version = sense.version + 1, meaning = meaning)
                }
                db.words[word.id] = word.copy(version = word.version + 1, notes = command.notes ?: word.notes); listOf(word.id)
            }
            is Command.RenameGroup -> db.localOperation("rename_group", now) {
                val group = db.groups[command.group.id].found("词组"); checkRef(command.group, group.version)
                requireBusiness(command.title.isNotBlank() && command.title.length <= 200, "词组名称须为 1–200 字符")
                db.groups[group.id] = group.copy(title = command.title.trim(), version = group.version + 1, titleEdited = true); listOf(group.id)
            }
            is Command.EditMemberships -> db.localOperation("edit_memberships", now) { operationId ->
                val group = db.groups[command.group.id].found("词组"); checkRef(command.group, group.version)
                requireBusiness(command.addWords.size + command.removeWords.size in 1..200 && command.addWords.toSet().intersect(command.removeWords.toSet()).isEmpty(), "成员修改范围无效")
                requireBusiness(command.reason.isNotBlank() && command.reason.length <= 4000, "归组需要依据")
                val source = Provenance(actor.name.lowercase(), operationId, atMs = now)
                val changed = mutableListOf<Id>()
                (command.addWords.map { it to true } + command.removeWords.map { it to false }).forEach { (wordId, active) ->
                    db.words[wordId].found("词条")
                    val senses = db.senses.values.filter { it.wordId == wordId }
                    // Adding an ambiguous multi-sense word needs the sense-specific public command.
                    requireBusiness(!active || senses.size == 1, "此词有多个义项，请按义项选择归组", ErrorCode.VERSION_CONFLICT)
                    senses.forEach { sense ->
                        val old = db.memberships.values.find { it.groupId == group.id && it.senseId == sense.id }
                        if (active || old != null) {
                            val m = old?.copy(active = active, version = old.version + 1, reason = command.reason, source = source)
                                ?: Membership(id(), group.id, sense.id, reason = command.reason, source = source, position = db.nextMemberPosition(group.id))
                            db.memberships[m.id] = m; changed += m.id
                            val target = db.ensureTarget(sense.id, Ability.RECOGNITION, group.id, now); db.targets[target.id] = target.copy(suspended = !active)
                        }
                    }
                }
                db.groups[group.id] = group.copy(version = group.version + 1); changed
            }
            is Command.ChangeMembership -> db.localOperation("change_membership", now) { op ->
                val group = db.groups[command.group.id].found("词组"); checkRef(command.group, group.version); db.senses[command.senseId].found("义项")
                requireBusiness(command.reason.isNotBlank() && command.reason.length <= 4000, "归组需要有效依据")
                val existing = db.memberships.values.find { it.groupId == group.id && it.senseId == command.senseId }
                val member = existing?.copy(version = existing.version + 1, active = command.active, reason = command.reason, source = Provenance(actor.name.lowercase(), op, atMs = now))
                    ?: Membership(id(), group.id, command.senseId, reason = command.reason, active = command.active, source = Provenance(actor.name.lowercase(), op, atMs = now), position = db.nextMemberPosition(group.id))
                db.memberships[member.id] = member; db.groups[group.id] = group.copy(version = group.version + 1)
                val target = db.ensureTarget(command.senseId, Ability.RECOGNITION, group.id, now)
                db.targets[target.id] = target.copy(suspended = !command.active); listOf(member.id)
            }
            is Command.SaveDraft -> {
                val old = db.drafts[command.key]; requireBusiness((old?.version ?: 0) == command.expectedVersion, "草稿已变化", ErrorCode.VERSION_CONFLICT)
                requireBusiness(command.key.length in 1..200 && command.text.length <= 16000, "草稿过长")
                val draft = Draft(command.key, command.text, (old?.version ?: 0) + 1, now); db.drafts[command.key] = draft; View.DraftResult(draft)
            }
            is Command.SubmitIntake -> {
                requireBusiness(command.input.isNotBlank() && command.input.length <= 8000, "输入须为 1–8000 字符")
                View.OperationResult(db.queue(command, Feature.WORD_INTAKE, now))
            }
            is Command.ResolveIntake -> {
                val op = db.operations[command.operationId].found("添加任务"); val job = db.jobs[op.id].found("添加任务")
                requireBusiness(op.status == OperationStatus.NEEDS_INPUT, "任务当前不需要选择", ErrorCode.VERSION_CONFLICT)
                requireBusiness(op.spellingChoices.isEmpty(), "请先处理拼写选择", ErrorCode.VERSION_CONFLICT)
                requireBusiness(command.disposition in setOf("groups", "ungrouped", "new_group"), "不支持此选择")
                requireBusiness(if (command.disposition == "groups") command.groups.isNotEmpty() && command.groups.all { group -> op.choices.any { it.groupId == group.id } } else command.groups.isEmpty(), "选择不属于已返回的候选")
                command.groups.forEach { ref -> checkRef(ref, db.groups[ref.id].found("词组").version) }
                val proposal = Contract.json.decodeFromJsonElement<IntakeProposal>(job.response.found("暂存结果"))
                db.completeIntakeChoice(op, proposal, command.groups, command.disposition == "ungrouped", now)
            }
            is Command.ResolveSpelling -> {
                val op = db.operations[command.operationId].found("添加任务"); val job = db.jobs[op.id].found("添加任务")
                requireBusiness(op.status == OperationStatus.NEEDS_INPUT && op.spellingChoices.any { it.original == command.original }, "此拼写选择已变化", ErrorCode.VERSION_CONFLICT)
                val proposal = Contract.json.decodeFromJsonElement<IntakeProposal>(job.response.found("暂存结果"))
                val correction = proposal.corrections.singleOrNull { it.original == command.original }.found("拼写建议")
                val updated = proposal.copy(words = proposal.words.map { if (command.useSuggested && normalize(it.spelling) == normalize(command.original)) correction.suggested else it },
                    corrections = proposal.corrections.filterNot { it.original == command.original },
                    newGroup = proposal.newGroup?.let { if (command.useSuggested) it.copy(title = it.title.replace(command.original, correction.suggested.spelling)) else it })
                val pending = op.copy(spellingChoices = op.spellingChoices.filterNot { it.original == command.original }, updatedAtMs = now)
                db.jobs[op.id] = job.copy(response = Contract.json.encodeToJsonElement(updated)); db.operations[op.id] = pending
                if (pending.spellingChoices.isNotEmpty() || pending.choices.isNotEmpty()) View.OperationResult(pending)
                else {
                    val explicit = (job.command as? Command.SubmitIntake)?.group
                    val group = explicit ?: updated.groupId?.let { Ref(it, job.expectedEntities["group:${it.value}"].found("候选组版本")) }
                    db.completeIntakeChoice(pending, updated, listOfNotNull(group), false, now)
                }
            }
            is Command.RequestRecommendations -> View.OperationResult(db.queue(command, Feature.WORD_RECOMMENDATION, now))
            is Command.AcceptRecommendations -> acceptRecommendations(db, command, now)
            is Command.GenerateActivity -> {
                View.OperationResult(db.queue(command, Feature.PRACTICE_GENERATION, now, actor == Actor.AI))
            }
            is Command.ExplainSense -> {
                if (actor == Actor.AI) requireBusiness(db.settings.automaticGeneration, "未授权自动补充", ErrorCode.DISABLED)
                checkRef(command.sense, db.senses[command.sense.id].found("义项").version)
                View.OperationResult(db.operations.values.asSequence().filter { it.actor == actor && db.jobs[it.id]?.command == command &&
                    (it.status in pendingStatuses || it.status == OperationStatus.RUNNING) }.latestOperation()
                    ?: db.queue(command, Feature.EXPLANATION, now, actor == Actor.AI))
            }
            is Command.EvaluateResponse -> {
                val answer = db.answers[command.answer.id].found("答案"); checkRef(command.answer, answer.version)
                requireBusiness(db.activities[answer.activityId]?.kind in setOf(ActivityKind.WRITING, ActivityKind.REWRITE), "只有开放表达需要此反馈")
                View.OperationResult(db.operations.values.asSequence().filter { db.jobs[it.id]?.command == command && it.status !in setOf(OperationStatus.CANCELLED, OperationStatus.SUPERSEDED) }.latestOperation()
                    ?: db.queue(command, Feature.WRITING_FEEDBACK, now))
            }
            is Command.StartSession -> db.startSession(now, command.continuePastBudget, command.includeIntroductions)
            is Command.NextActivity -> db.nextActivity(command.sessionId, now)
            is Command.RequestHint -> {
                val session = db.sessions[command.sessionId].found("会话")
                requireBusiness(session.currentPresentationId == command.presentationId && session.currentActivityId != null, "题目展示已变化", ErrorCode.VERSION_CONFLICT)
                val policy = db.policyVersions[session.policyVersion].found("会话策略")
                requireBusiness(policy.hintPolicy == "on_request", "当前策略要求独立作答后查看反馈", ErrorCode.DISABLED)
                val activity = db.activities[session.currentActivityId].found("题目")
                val old = db.exposures[command.presentationId] ?: Exposure()
                val exposure = old.copy(hintUsed = true, answerRevealed = old.answerRevealed || command.revealAnswer)
                db.exposures[command.presentationId] = exposure
                val text = if (command.revealAnswer) activity.acceptedAnswers.joinToString(" / ").ifBlank { activity.explanation }
                    else activity.acceptedAnswers.firstOrNull()?.take(1)?.let { "首字母：$it" } ?: "请按题目给出的语境表达目标义项。"
                View.Hint(text, exposure)
            }
            is Command.SubmitAnswer -> db.submitAnswer(command, now, memory)
            is Command.ReviseAnswer -> {
                val answer = db.answers[command.answer.id].found("答案"); checkRef(command.answer, answer.version)
                requireBusiness(answer.gradeSource == GradeSource.UNGRADED && !answer.input.skipped && command.text.length <= 16000, "只有开放表达可以修订；已评分记录不可重写")
                val revised = answer.copy(version = answer.version + 1, input = answer.input.copy(text = command.text))
                requireBusiness(command.text.isNotBlank(), "请填写答案")
                db.answers[answer.id] = revised; db.answerVersions["${answer.id.value}:${revised.version}"] = revised
                db.sessions[answer.sessionId]?.let { db.sessions[it.id] = it.copy(revisingAnswerId = null) }
                if (answer.input.requestFeedback) db.queue(Command.EvaluateResponse(Ref(revised.id, revised.version)), Feature.WRITING_FEEDBACK, now)
                db.answerView(revised)
            }
            is Command.BeginAnswerRevision -> {
                val session = db.sessions[command.sessionId].found("会话"); val answer = db.answers[command.answer.id].found("答案"); checkRef(command.answer, answer.version)
                requireBusiness(session.lastAnswerId == answer.id && session.awaitingAdvance && db.answerView(answer).canRevise, "此答案当前不可修订", ErrorCode.VERSION_CONFLICT)
                val revised = session.copy(revisingAnswerId = answer.id); db.sessions[session.id] = revised; db.sessionView(revised)
            }
            is Command.MarkDifficulty -> {
                val answer = db.answers[command.answer.id].found("答案"); checkRef(command.answer, answer.version)
                requireBusiness(db.answerView(answer).canMarkDifficult, "此答案不可标记难度", ErrorCode.VALIDATION)
                val mark = DifficultyMark(id(), answer.id, answer.version, command.difficult, now, db.revision + 1)
                db.difficultyMarks[mark.id] = mark; db.replay(memory); db.answerView(answer)
            }
            is Command.SetTimer -> {
                val session = db.sessions[command.sessionId].found("会话")
                requireBusiness(session.status != SessionStatus.FINISHED, "已完成会话不再计时")
                if (session.status == SessionStatus.PAUSED && command.mode in setOf(TimerMode.STUDY, TimerMode.WAITING)) {
                    val day = db.day(now)
                    requireBusiness(session.currentActivityId != null || session.awaitingAdvance || session.extraBudgetDayId == day.id || db.studyTime(day.id) < db.settings.budgetMinutes * 60_000L,
                        "已达到当日预算，请选择是否继续", ErrorCode.BUDGET_EXCEEDED)
                    db.sessions[session.id] = session.copy(status = SessionStatus.ACTIVE)
                }
                db.sessionView(db.tick(command.sessionId, command.mode, now))
            }
            is Command.FinishSession -> {
                val old = db.sessions[command.sessionId].found("会话")
                val session = if (old.status == SessionStatus.FINISHED) old else db.tick(command.sessionId, TimerMode.PAUSED, now).copy(status = if (command.pause) SessionStatus.PAUSED else SessionStatus.FINISHED)
                db.sessions[session.id] = session; db.sessionView(session)
            }
            is Command.FinishLearningDay -> db.finishDay(command.dayId, DayEndReason.USER_FINISHED, now)
            is Command.DisputeActivity -> dispute(db, command, now)
            is Command.AnalyzeLearning -> View.OperationResult(db.analyze(now, false, null, command.force))
            is Command.SetSettings -> setSettings(db, command, now)
            is Command.ApplyPolicy -> db.localOperation("apply_policy", now) { op -> listOfNotNull(db.applyPatch(command.patch, command.expectedVersion, actor, op, null, now, memory, command.evaluationId)?.id) }
            is Command.FitMemory -> View.OperationResult(db.queue(command, null, now, actor == Actor.AI))
            is Command.EvaluateParameters -> { memory.validate(command.weights); View.OperationResult(db.queue(command, null, now)) }
            is Command.SavePreset -> {
                validatePreset(command.preset, allowLocalHttp)
                val old = db.presets[command.preset.id]; requireBusiness((old?.version ?: 0) == command.expectedVersion, "预设已修改", ErrorCode.VERSION_CONFLICT)
                val preset = command.preset.copy(version = (old?.version ?: 0) + 1).let {
                    if (command.preserveAdvancedSettings && old != null) it.copy(enabled = old.enabled, jsonMode = old.jsonMode, temperature = old.temperature) else it
                }
                db.presets[preset.id] = preset; db.presetVersions["${preset.id.value}:${preset.version}"] = preset
                View.Presets(listOf(PresetView(preset, hasCredential(db, preset.id))), db.bindings.values.toList())
            }
            is Command.DeletePreset -> {
                val preset = db.presets[command.preset.id].found("预设"); checkRef(command.preset, preset.version)
                db.presets.remove(preset.id)
                db.bindings.entries.removeAll { it.value.presetId == preset.id }
                db.settings = db.settings.copy(version = db.settings.version + 1,
                    defaultPresetId = db.settings.defaultPresetId?.takeUnless { it == preset.id }, fallbackPresetId = db.settings.fallbackPresetId?.takeUnless { it == preset.id })
                db.credentialRefs.remove(preset.id)
                View.Ok(preset.id)
            }
            is Command.BindPreset -> {
                val preset = db.presets[command.binding.presetId].found("预设"); requireBusiness(preset.enabled, "预设已停用")
                if (actor == Actor.AI) requireBusiness(db.bindings[command.binding.feature]?.userLocked == false && !command.binding.userLocked, "此功能分配由用户锁定", ErrorCode.DISABLED)
                db.localOperation("bind_preset", now) { db.bindings[command.binding.feature] = command.binding; listOf(command.binding.presetId) }
            }
            is Command.UnbindPreset -> { db.bindings.remove(command.feature); View.Ok() }
            is Command.SetCallPolicy -> setCallPolicy(db, command.policy, now)
            is Command.TestConnection -> { val preset = db.presets[command.preset.id].found("预设"); checkRef(command.preset, preset.version); View.OperationResult(db.queue(command, Feature.WORD_INTAKE, now, fixedPreset = preset)) }
            is Command.CancelOperation -> {
                val operation = db.operations[command.id].found("操作")
                val result = if (operation.status !in terminalStatuses) operation.copy(status = OperationStatus.CANCELLED,
                    fault = null, retryable = false, updatedAtMs = now) else operation
                db.operations[operation.id] = result; View.OperationResult(result)
            }
            is Command.RetryOperation -> {
                val operation = db.operations[command.id].found("操作")
                requireBusiness(operation.status in setOf(OperationStatus.FAILED, OperationStatus.NEEDS_CONFIGURATION, OperationStatus.RESTORED_PENDING), "当前任务不可重试")
                requireBusiness(!operation.automatic || db.settings.automaticAnalysis || operation.feature != Feature.LEARNING_ANALYSIS, "请先启用自动分析，或单独发起手动分析", ErrorCode.DISABLED)
                // This explicit user action captures the current version of the same saved preset.
                // Earlier attempts keep their immutable Usage snapshots; queued/running jobs are unchanged.
                val result = operation.copy(status = OperationStatus.QUEUED, automationEpoch = db.settings.automationEpoch, fault = null, updatedAtMs = now,
                    preset = operation.preset?.let { db.presets[it.id] }?.takeIf { it.enabled }, retryable = false)
                db.operations[operation.id] = result
                db.jobs[operation.id]?.let { db.jobs[operation.id] = it.copy(notBeforeMs = 0, retryCycleStartAttempt = operation.attempt) }
                View.OperationResult(result)
            }
            is Command.UndoOperation -> undo(db, command.id, now)
            is Command.CommitImport -> Packages(this).commit(db, command.token, false, now)
            is Command.RestoreBackup -> Packages(this).commit(db, command.token, true, now)
        }
    }

    private fun acceptRecommendations(db: Database, command: Command.AcceptRecommendations, now: Long): View {
        requireBusiness(command.ids.isNotEmpty() && command.ids.size <= 50 && command.ids.distinct().size == command.ids.size, "请选择 1–50 个不同推荐")
        val selected = command.ids.map { db.recommendations[it].found("推荐") }
        val pending = selected.filter { it.acceptedOperationId == null || db.operations[it.acceptedOperationId]?.undoneBy != null }
        if (pending.isEmpty()) return View.OperationResult(db.operations[selected.first().acceptedOperationId].found("已加入操作"))
        requireBusiness(pending.all(db::recommendationAvailable), "词族讲解已撤销或更新，请重新读取候选", ErrorCode.VERSION_CONFLICT)
        val reserved = db.operations.values.filter { it.status !in terminalStatuses && it.status != OperationStatus.RESTORED_PENDING }.filter { operation ->
            (db.jobs[operation.id]?.command as? Command.AcceptRecommendations)?.ids?.any { recommendation -> pending.any { it.id == recommendation } } == true
        }
        if (reserved.isNotEmpty()) {
            val existing = reserved.singleOrNull()
            val request = existing?.let { db.jobs[it.id]?.command as? Command.AcceptRecommendations }
            requireBusiness(request != null && request.ids.toSet() == pending.map { it.id }.toSet(), "部分推荐正在加入，请先完成或取消该任务", ErrorCode.VERSION_CONFLICT)
            return View.OperationResult(existing!!)
        }
        if (pending.any { db.recommendationContent(it) == null }) {
            requireBusiness(command.allowApi, "选中内容需要调用 API，请先显示调用预览并由用户点击加入", ErrorCode.DISABLED)
            return View.OperationResult(db.queue(command.copy(ids = pending.map { it.id }), Feature.WORD_INTAKE, now))
        }
        return db.localOperation("accept_recommendations", now) { op ->
            val results = pending.groupBy { it.groupId }.flatMap { (groupId, items) ->
                db.saveWords(Command.SaveWords(items.map { db.recommendationContent(it)!! }, existingGroup = groupId?.let { Ref(it, db.groups[it].found("词组").version) }), Provenance("recommendation", op, atMs = now), now)
            }
            pending.forEach { db.recommendations[it.id] = it.copy(acceptedOperationId = op) }; results
        }
    }

    private fun setSettings(db: Database, command: Command.SetSettings, now: Long): View {
        requireBusiness(db.settings.version == command.expectedVersion, "设置已修改", ErrorCode.VERSION_CONFLICT)
        val value = command.settings
        ZoneId.of(value.zoneId)
        requireBusiness(value.budgetMinutes in 1..240 && value.lockedCapabilities.all { locked -> locked == "preGenerateLimit" || db.capabilities().any { it.id == locked } }, "用户设置无效")
        validateCallSettings(db, value)
        val previous = db.settings
        val analysisDisabled = previous.automaticAnalysis && !value.automaticAnalysis
        val newlyLocked = value.lockedCapabilities - previous.lockedCapabilities
        db.settings = value.copy(version = previous.version + 1, automationEpoch = previous.automationEpoch)
        db.operations.values.filter { op ->
            val parent = op.parentOperationId?.let { db.operations[it] }
            val stoppedAnalysis = analysisDisabled && (op.feature == Feature.LEARNING_ANALYSIS || parent?.feature == Feature.LEARNING_ANALYSIS && parent.automatic)
            val stoppedExplanation = op.feature == Feature.EXPLANATION &&
                (previous.automaticGeneration && !value.automaticGeneration || "explain_sense" in newlyLocked)
            val stoppedPractice = op.feature == Feature.PRACTICE_GENERATION && "generate_activity" in newlyLocked
            op.automatic && op.status !in terminalStatuses && (stoppedAnalysis || stoppedExplanation || stoppedPractice)
        }.forEach { op ->
            db.operations[op.id] = op.copy(status = OperationStatus.CANCELLED, fault = Fault(ErrorCode.DISABLED, "自动调用授权已变化"), updatedAtMs = now)
        }
        if (db.policy.plannedMinutes > value.budgetMinutes) {
            db.policy = db.policy.copy(version = db.policy.version + 1, plannedMinutes = value.budgetMinutes)
            db.policyVersions[db.policy.version] = db.policy
        }
        db.invalidatePlans(now)
        return View.Settings(db.settings, db.policy, db.bindings.values.toList())
    }

    private fun setCallPolicy(db: Database, value: CallPolicy, now: Long): View {
        requireBusiness(db.settings.version == value.settingsVersion && db.policy.version == value.policyVersion, "调用设置已变化", ErrorCode.VERSION_CONFLICT)
        setSettings(db, Command.SetSettings(db.settings.copy(defaultPresetId = value.defaultPresetId, fallbackPresetId = value.fallbackPresetId,
            dailyRequestLimit = value.dailyRequestLimit, maxRetries = value.maxRetries), value.settingsVersion), now)
        return View.CallPolicy(db.callPolicy())
    }

    private fun dispute(db: Database, command: Command.DisputeActivity, now: Long): View {
        val activity = db.activities[command.activity.id].found("题目"); checkRef(command.activity, activity.version)
        requireBusiness(command.reason.isNotBlank() && command.reason.length <= 4000, "争议需要依据")
        val op = Operation(id(), "dispute_activity", OperationStatus.SUCCEEDED, now, now, resultIds = listOf(activity.id), undoable = true)
        db.undos[op.id] = listOf(UndoEntry("activities", activity.id.value, Contract.json.encodeToJsonElement(activity), Contract.json.encodeToJsonElement(activity.copy(disputed = true))))
        db.activities[activity.id] = activity.copy(disputed = true)
        db.answers.replaceAll { _, answer -> if (answer.activityId == activity.id) answer.copy(valid = false) else answer }
        db.replay(memory)
        db.operations[op.id] = op; db.drafts["dispute:${op.id.value}"] = Draft("dispute:${op.id.value}", command.reason, 1, now)
        return View.OperationResult(op)
    }

    private fun undo(db: Database, operationId: Id, now: Long): View {
        val original = db.operations[operationId].found("操作")
        original.undoneBy?.let { return View.OperationResult(db.operations[it].found("撤销")) }
        requireBusiness(original.status == OperationStatus.SUCCEEDED && original.undoable, "此操作不可撤销", ErrorCode.VERSION_CONFLICT)
        val undoId = id()
        // Undo separately recorded child actions before reverting the parent's policy.
        original.resultIds.mapNotNull { db.operations[it] }.filter { it.parentOperationId == original.id }.asReversed().forEach { child ->
            if (child.status !in terminalStatuses) db.operations[child.id] = child.copy(status = OperationStatus.CANCELLED, updatedAtMs = now)
            else if (child.undoable && child.undoneBy == null) undo(db, child.id, now)
        }
        val changes = db.changes.values.filter { it.operationId == original.id && it.undoneBy == null }
        changes.sortedByDescending { it.after.version }.forEach { change ->
            requireBusiness(db.policy.copy(version = change.after.version) == change.after && db.changes.values.none { it.after.version > change.after.version && it.undoneBy == null }, "此策略之后已有其他明确修改，不能覆盖", ErrorCode.VERSION_CONFLICT)
            db.policy = change.before.copy(version = db.policy.version + 1); db.policyVersions[db.policy.version] = db.policy
            db.changes[change.id] = change.copy(undoneBy = undoId)
            db.replay(memory, db.policy)
        }
        if (db.undos[original.id]?.isNotEmpty() == true) {
            if (original.kind == "dispute_activity") {
                val entry = db.undos[original.id]!!.single(); val current = db.activities[Id(entry.key)].found("题目")
                requireBusiness(Contract.json.encodeToJsonElement(current) == entry.after, "题目已被修改", ErrorCode.VERSION_CONFLICT)
                db.activities[current.id] = Contract.json.decodeFromJsonElement(entry.before!!)
                db.answers.replaceAll { _, answer -> if (answer.activityId == current.id) answer.copy(valid = true) else answer }; db.replay(memory)
            } else db.undoLibrary(original)
        }
        val result = Operation(undoId, "undo", OperationStatus.SUCCEEDED, now, now, resultIds = listOf(original.id), summary = "已撤销；后续真实作答及其历史仍保留")
        db.invalidatePlans(now)
        db.operations[original.id] = original.copy(undoneBy = undoId, undoable = false, updatedAtMs = now); db.operations[undoId] = result
        return View.OperationResult(result)
    }

    internal fun replaceContents(target: Database, source: Database) {
        target.schemaVersion = source.schemaVersion; target.libraryId = source.libraryId; target.sequence = source.sequence
        target.settings = source.settings; target.policy = source.policy; target.lastAnalyzedSequence = source.lastAnalyzedSequence
        target.lastAutoExecutionAtMs = source.lastAutoExecutionAtMs; target.lastAutoExecutionDate = source.lastAutoExecutionDate
        fun <K,V> replace(to: MutableMap<K,V>, from: Map<K,V>) { to.clear(); to.putAll(from) }
        replace(target.words, source.words); replace(target.senses, source.senses); replace(target.groups, source.groups)
        replace(target.memberships, source.memberships); replace(target.targets, source.targets); replace(target.schedules, source.schedules)
        replace(target.activities, source.activities); replace(target.activityVersions, source.activityVersions); replace(target.answers, source.answers)
        replace(target.answerVersions, source.answerVersions); replace(target.presentations, source.presentations); replace(target.exposures, source.exposures); replace(target.feedback, source.feedback); replace(target.difficultyMarks, source.difficultyMarks)
        replace(target.sessions, source.sessions); replace(target.timeSpans, source.timeSpans); replace(target.days, source.days); replace(target.plans, source.plans); replace(target.drafts, source.drafts)
        replace(target.policyVersions, source.policyVersions); replace(target.changes, source.changes); replace(target.snapshots, source.snapshots)
        replace(target.evaluations, source.evaluations); replace(target.presets, source.presets); replace(target.credentialRefs, source.credentialRefs); replace(target.presetVersions, source.presetVersions)
        replace(target.bindings, source.bindings); replace(target.usage, source.usage); replace(target.recommendations, source.recommendations)
        replace(target.operations, source.operations); replace(target.jobs, source.jobs); replace(target.requests, source.requests); replace(target.undos, source.undos)
        replace(target.previews, source.previews); replace(target.importedPackages, source.importedPackages); replace(target.archivedModules, source.archivedModules)
    }
}

internal fun Database.localOperation(kind: String, now: Long, action: (Id) -> List<Id>): View.OperationResult {
    val opId = id(); val before = captureLibrary(); val resultIds = action(opId)
    recordLibraryUndo(opId, before)
    if (undos[opId]?.any { it.bucket in setOf("words", "senses", "targets", "memberships") } == true) invalidatePlans(now)
    val op = Operation(opId, kind, OperationStatus.SUCCEEDED, now, now, resultIds = resultIds,
        // Accepting an already-owned family word still changes the recommendation's state.
        // Undo uses its acceptedOperationId/undoneBy relationship even when no library row changed.
        undoable = kind == "accept_recommendations" || undos[opId]?.isNotEmpty() == true || changes.values.any { it.operationId == opId }, summary = "修改已保存",
        effects = if (kind in setOf("save_words", "accept_recommendations")) intakeEffects(before, resultIds, "本次选择已保存") else emptyList())
    operations[opId] = op; return View.OperationResult(op)
}
internal fun Database.invalidatePlans(now: Long) {
    // Sessions keep their original plan and policy; the next daily query builds a new plan.
    days.replaceAll { _, day -> if (day.closesAtMs > now) day.copy(planId = null) else day }
}
internal fun Database.recommendationContent(recommendation: Recommendation): WordSeed? {
    val word = words.values.find { it.normalized == normalize(recommendation.spelling) }
    val sense = word?.let { known -> senses.values.singleOrNull { it.wordId == known.id } }
    // A paraphrased AI gloss does not establish a second sense for an already-known word.
    return if (word != null && sense != null) WordSeed(word.spelling, sense.meaning, sense.partOfSpeech, sense.acceptedForms, sense.examples)
        else recommendation.content
}
private fun Database.refresh(view: View): View = when (view) {
    is View.OperationResult -> View.OperationResult(operations[view.operation.id] ?: view.operation)
    is View.SessionState -> sessions[view.session.id]?.let { sessionView(it) } ?: view
    is View.Day -> days[view.day.id]?.let { dayView(it) } ?: view
    is View.AnswerResult -> answers[view.answer.id]?.let { answerView(it) } ?: view
    else -> view
}

/** Thin framework bridge: no independent rules or serialization models. */
class JsonBridge(private val sdk: WordPrism) {
    suspend fun execute(json: String): String = respond { sdk.execute(Contract.json.decodeFromString(json)) }
    suspend fun query(json: String): String = respond { sdk.query(Contract.json.decodeFromString(json)) }
    suspend fun listModels(json: String, secret: CharArray? = null): String = try {
        respond { sdk.listModels(Contract.json.decodeFromString(json), secret) }
    } finally { secret?.fill('\u0000') }
    private suspend fun respond(action: suspend () -> Reply): String = Contract.json.encodeToString(try { action() }
        catch (_: kotlinx.serialization.SerializationException) { Reply(0, fault = Fault(ErrorCode.UNSUPPORTED_VERSION, "无法识别此契约消息")) })
}
