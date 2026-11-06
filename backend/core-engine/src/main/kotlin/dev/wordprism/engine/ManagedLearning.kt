package dev.wordprism.engine

import dev.wordprism.contract.*

internal fun UserSettings.analysisEnabled() = automaticAnalysis || learningMode == LearningMode.AI_MANAGED
internal fun UserSettings.generationEnabled() = automaticGeneration || learningMode == LearningMode.AI_MANAGED

/** Converges durable operations after a business event. No network, timers or UI state live here. */
internal fun CoreEngine.reconcileManaged(db: Database, now: Long) {
    if (db.settings.learningMode != LearningMode.AI_MANAGED) return
    db.operations.values.toList().filter { it.feature == Feature.TUTORING && it.status in pendingStatuses + OperationStatus.RUNNING }.forEach { op ->
        val command = db.jobs[op.id]?.command as? Command.TutorSession ?: return@forEach
        if (command.moment != TutorMoment.QUESTION && op.actor == Actor.AI) {
            db.operations[op.id] = op.copy(status = OperationStatus.CANCELLED,
                fault = Fault(ErrorCode.DISABLED, "助教已改为结束后手动调用，请点击本轮总结"), updatedAtMs = now)
            return@forEach
        }
        val target = command.answer?.let { db.answers[it.id]?.targetId }?.let(db.targets::get)
        if (target != null && !db.activeTarget(target) || db.sessions[command.sessionId]?.targetIds.orEmpty().none { db.targets[it]?.let(db::activeTarget) == true }) {
            db.operations[op.id] = op.copy(status = OperationStatus.CANCELLED, fault = Fault(ErrorCode.DISABLED, "相关学习内容已归档或撤销"), updatedAtMs = now)
        }
    }
    db.days.values.filter { day -> day.closesAtMs <= now && day.endedAtMs == null && db.answers.values.any { db.answerDayId(it) == day.id } }.toList().forEach {
        db.finishDay(it.id, DayEndReason.USER_FINISHED, now)
    }
    val today = db.day(now)
    val budgetAvailable = db.settings.dailyRequestLimit?.let { limit ->
        db.usage.values.count { it.startedAtMs in today.opensAtMs until today.closesAtMs } < limit
    } != false
    if (db.settings.defaultPresetId == null) {
        db.presets.values.firstOrNull { it.enabled && hasCredential(db, it.id) }?.let {
            db.settings = db.settings.copy(version = db.settings.version + 1, defaultPresetId = it.id)
        }
    }
    // A saved credential/configuration can unblock an unattempted request, or a known rejection.
    // Unknown network outcomes always remain explicit retries to avoid duplicate paid requests.
    db.operations.values.toList().filter { it.managed && it.automationEpoch == db.settings.automationEpoch &&
        (it.status == OperationStatus.NEEDS_CONFIGURATION || it.status == OperationStatus.FAILED &&
            (it.fault?.code in setOf(ErrorCode.PROVIDER_AUTH, ErrorCode.CREDENTIAL_MISSING, ErrorCode.PRESET_MISSING) ||
                it.fault?.code == ErrorCode.BUDGET_EXCEEDED && budgetAvailable))
    }.forEach { op ->
        val preset = op.feature?.let { db.callPreview(it).preset }
        val credentialChanged = db.jobs[op.id]?.let { job ->
            job.managedCredentialRef != db.credentialRefs[preset?.id]
        } == true
        if (preset != null && hasCredential(db, preset.id) &&
            (op.attempt == 0 || op.preset != preset || credentialChanged || op.fault?.code == ErrorCode.BUDGET_EXCEEDED && budgetAvailable)) {
            db.operations[op.id] = op.copy(status = OperationStatus.QUEUED, preset = preset, fault = null, retryable = false, updatedAtMs = now)
        }
    }
    if (managedConfigurationFault(db) != null || !budgetAvailable) return
    db.prepareManagedContent(now)
}

internal fun CoreEngine.managedConfigurationFault(db: Database): Fault? {
    val features = buildList {
        add(Feature.WORD_INTAKE); add(Feature.WRITING_FEEDBACK); add(Feature.LEARNING_ANALYSIS)
        if ("tutor_session" !in db.settings.lockedCapabilities) add(Feature.TUTORING)
        if ("explain_sense" !in db.settings.lockedCapabilities) add(Feature.EXPLANATION)
        if ("generate_activity" !in db.settings.lockedCapabilities) add(Feature.PRACTICE_GENERATION)
    }
    return features.firstNotNullOfOrNull { feature ->
        val preview = db.callPreview(feature)
        preview.fault ?: preview.preset?.takeIf { !hasCredential(db, it.id) }?.let {
            Fault(ErrorCode.CREDENTIAL_MISSING, "请为 ${it.name} 填写 API 密钥")
        }
    }
}

internal fun CoreEngine.managedLearningState(db: Database): ManagedLearningState {
    val current = db.operations.values.filter { it.managed && it.automationEpoch == db.settings.automationEpoch && it.undoneBy == null }
    val pending = current.count { it.status in pendingStatuses || it.status == OperationStatus.RUNNING }
    val attention = current.count { it.status in setOf(OperationStatus.FAILED, OperationStatus.NEEDS_INPUT, OperationStatus.NEEDS_CONFIGURATION) }
    return ManagedLearningState(db.settings.learningMode, db.settings.defaultPresetId, managedConfigurationFault(db), pending, attention,
        when {
            pending > 0 -> "AI 正在整理词组、准备讲解与练习；已有内容可以先学"
            attention > 0 -> "部分内容暂未准备好，已有内容仍可学习"
            else -> "添加词组后自动备课，学习结束后自动调整"
        })
}

private fun Database.prepareManagedContent(now: Long) {
    val day = day(now)
    val currentPlan = day.planId?.let(plans::get)?.takeIf { it.policyVersion == policy.version && it.plannerVersion == PLANNER_VERSION }
        ?: plan(day, now)
    val latestIntake = operations.values.filter { it.kind in setOf("submit_intake", "save_words", "commit_import") &&
        it.status == OperationStatus.SUCCEEDED && it.undoneBy == null }.maxByOrNull { it.createdAtMs }
    val desired = (latestIntake?.resultIds.orEmpty().filter { it in senses } + currentPlan.targetIds.mapNotNull { targets[it]?.senseId })
        .distinct().filter { sid -> targets.values.any { it.senseId == sid && activeTarget(it) } }
    // Bound concurrent preparation, not the learner's total content. Completion schedules the next step.
    var slots = 2 - operations.values.count { it.managed && it.automatic && it.status in pendingStatuses + OperationStatus.RUNNING }
    if (slots <= 0) return
    fun enqueue(command: Command, feature: Feature, key: String, senseId: Id) {
        val parent = operations.values.filter { it.kind in setOf("submit_intake", "save_words", "commit_import") &&
            it.status == OperationStatus.SUCCEEDED && it.undoneBy == null && senseId in it.resultIds }.maxByOrNull { it.createdAtMs }
        val op = queue(command, feature, now, automatic = true)
        operations[op.id] = op.copy(parentOperationId = parent?.id)
        jobs[op.id] = jobs.getValue(op.id).copy(managedKey = key)
        parent?.let { operations[it.id] = it.copy(resultIds = it.resultIds + op.id) }
        slots--
    }
    for (sid in desired) {
        if (slots <= 0) break
        val sense = senses.getValue(sid)
        val key = "${settings.automationEpoch}:${sid.value}:${sense.version}"
        val explaining = jobs.entries.any { (oid, job) -> (job.command as? Command.ExplainSense)?.sense?.id == sid &&
            operations[oid]?.status in pendingStatuses + OperationStatus.RUNNING }
        if (explaining) continue // Explanation changes the sense version; generate only after it settles.
        if ("explain_sense" !in settings.lockedCapabilities && explanation(sense)?.basedOnSenseVersion != sense.version &&
            jobs.values.none { it.managedKey == "$key:explain" }) {
            enqueue(Command.ExplainSense(Ref(sid, sense.version)), Feature.EXPLANATION, "$key:explain", sid)
            continue
        }
        if ("generate_activity" in settings.lockedCapabilities) continue
        val anchor = targets.values.firstOrNull { it.senseId == sid && activeTarget(it) } ?: continue
        for (kind in policy.mix.filter { it.value > 0 && it.key !in introductionKinds }.keys) {
            if (slots <= 0) break
            if (kind == ActivityKind.CHOICE && memberships.values.none { it.senseId == sid && it.active }) continue
            if (activities.values.any { targets[it.targetId]?.senseId == sid && it.kind == kind && !it.disputed && reusable(it, policy, now) }) continue
            val latestPractice = answers.values.filter { it.valid && targets[it.targetId]?.senseId == sid }.maxOfOrNull { it.sequence } ?: 0
            val stepKey = "$key:${day.id.value}:${kind.name}:$latestPractice"
            if (jobs.values.any { it.managedKey == stepKey }) continue
            if (jobs.entries.any { (oid, job) -> (job.command as? Command.GenerateActivity)?.let {
                    targets[it.targetId]?.senseId == sid && it.kind == kind
                } == true && operations[oid]?.status in pendingStatuses + OperationStatus.RUNNING }) continue
            val target = targets.values.firstOrNull { it.senseId == sid && it.ability == kind.ability() && activeTarget(it) } ?: anchor
            enqueue(Command.GenerateActivity(target.id, kind), Feature.PRACTICE_GENERATION, stepKey, sid)
        }
    }
}
