package app.wordprism.codex

import app.wordprism.sdk.*
import dev.wordprism.contract.Command
import dev.wordprism.contract.Query
import dev.wordprism.contract.Ref
import dev.wordprism.contract.View
import dev.wordprism.contract.TimerMode
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.wordprism.contract.Id as CId
import dev.wordprism.contract.ActivityKind as CKind
import dev.wordprism.contract.ActivityCard as CCard
import dev.wordprism.contract.AnswerInput as CInput
import dev.wordprism.contract.Feature as CFeature
import dev.wordprism.contract.GradeSource as CGrade
import dev.wordprism.contract.Verdict as CVerdict
import dev.wordprism.contract.SessionStatus as CSessionStatus

internal fun kindLabel(kind: CKind): String = when (kind) {
    CKind.CHOICE -> "辨析选词"; CKind.RECALL -> "主动回忆"; CKind.SPELLING -> "拼写练习"
    CKind.PASSAGE -> "短文理解"; CKind.WRITING -> "造句"; CKind.REWRITE -> "改写"
    CKind.FAMILIARITY -> "判断基础"; CKind.CONTRAST -> "看清差异"
}
internal fun View.Day.toSdk(): LearningDay {
    val s = summary
    val labels = mapOf("disabled" to "自动分析已关闭", "will_run_after_day_end" to "今天结束后自动分析一次", "queued" to "分析已排队",
        "waiting_network" to "分析等待联网", "running" to "正在分析", "done" to "今日分析已完成", "failed" to "分析失败，可在任务记录重试",
        "skipped_no_new_data" to "没有尚未分析的新作答", "needs_configuration" to "分析等待配置 API", "restored_pending" to "恢复的分析需要重新授权", "cancelled" to "分析已取消")
    return LearningDay(LearningDayId(day.id.value), LocalDay(day.date), day.zoneId, DayStatus(s.state), s.budgetMinutes, studyMs / 1000, s.waitingMs / 1000,
        BudgetState(s.budgetState.name.lowercase()), plan?.targetIds?.size ?: 0, s.completedTargets, plan?.policyVersion?.toInt() ?: s.policy.version.toInt(),
        s.openSessionId?.value?.let(::SessionId), s.canEnd,
        DailyAnalysisStatus(if (s.automaticEnabled) AnalysisMode.DailyAfterDayEnd else AnalysisMode.Off, s.analysisState, labels[s.analysisState] ?: s.analysisState, automatic?.id?.value?.let(::OperationId)))
}

class CodexStudy(private val b: CodexBridge) : StudyApi {
    private val draftMutex = Mutex()
    override fun observeLearningDay(): Flow<LearningDay> = b.live { (b.view(Query.GetLearningDay()) as View.Day).toSdk() }
    override fun observePlan(): Flow<LearningPlan> = b.live {
        val day = b.view(Query.GetLearningDay()) as View.Day; val p = day.plan; val policy = day.summary.policy
        val groups = (b.view(Query.LibrarySummary()) as View.LibrarySummary).library.groups
        val total = policy.mix.values.sum().coerceAtLeast(1)
        LearningPlan(p?.policyVersion?.toInt() ?: policy.version.toInt(), policy.version.toInt(), p?.reviewCount ?: 0, day.summary.plannedNewWords, ((p?.estimatedMs ?: 0) / 60_000).toInt(),
            policy.mix.filterValues { it > 0 }.map { (kind, amount) -> MixShare(kind.name.lowercase(), kindLabel(kind), amount.toFloat() / total) },
            day.summary.focusGroups.mapNotNull { gid -> groups.firstOrNull { it.group.id == gid }?.let { FocusItem(it.group.title, it.group.purpose, GroupId(gid.value)) } },
            null, if (p?.estimateSource == "personal_observation") "按实际学习用时估计" else "初始用时估计，随学习记录调整",
            p?.deferredCount?.takeIf { it > 0 }?.let { "$it 个目标顺延；其中可能有等待可用阅读内容的目标" })
    }
    private suspend fun session(id: SessionId) = b.view(Query.GetSession(CId(id.value))) as View.SessionState
    override suspend fun startOrResume(request: ClientRequestId): SdkResult<SessionId> = safely {
        val result = b.exec(request, Command.StartSession(includeIntroductions = true)) { it as? View.SessionState }
        if (result is SdkResult.Err) return@safely result
        val view = (result as SdkResult.Ok).value
        if (view.activity == null && view.session.status == CSessionStatus.ACTIVE) {
            val next = b.exec(ClientRequestId("${request.value}:next"), Command.NextActivity(view.session.id)) { it }
            if (next is SdkResult.Err) return@safely next
        }
        SdkResult.Ok(SessionId(view.session.id.value))
    }
    override fun observeSession(id: SessionId): Flow<SessionState?> = b.live(Query.GetSession(CId(id.value))) {
        (it as? View.SessionState)?.let { state -> mapSession(state) }
    }
    private suspend fun mapSession(v: View.SessionState): SessionState {
        val s = v.session
        return SessionState(SessionId(s.id.value), when (s.status) { CSessionStatus.ACTIVE -> SessionStatus.Active; CSessionStatus.PAUSED -> SessionStatus.Paused; CSessionStatus.FINISHED -> SessionStatus.Completed },
            s.completedTargets.size, s.targetIds.size, v.activity?.let { mapActivity(it, s) }, v.feedback?.let(::mapFeedback),
            BudgetState(v.budgetState.name.lowercase()), v.budgetPromptPending, s.policyVersion.toInt())
    }
    private suspend fun mapActivity(a: CCard, session: dev.wordprism.contract.Session): ActivityView {
        val id = ActivityId((session.currentPresentationId ?: session.lastPresentationId ?: a.id).value); val label = kindLabel(a.kind)
        val draft = (b.view(Query.GetDraft(draftKey(SessionId(session.id.value), id))) as View.DraftResult).draft?.text
            ?: session.revisingAnswerId?.let { ((b.view(Query.GetFeedback(it)) as View.Feedbacks).answer.input.text) }
        return when (a.kind) {
            CKind.FAMILIARITY -> ActivityView.Familiarity(id, label, a.targetWords.firstOrNull().orEmpty(), null)
            CKind.CONTRAST -> a.groupId?.let { gid -> (b.view(Query.GetGroup(gid)) as View.Library).group(GroupId(gid.value)) }
                ?.let { ActivityView.Contrast(id, label, it) } ?: ActivityView.Unknown(id, label, a.prompt)
            CKind.CHOICE -> ActivityView.Choice(id, label, "选择符合释义或语境的词", studyText(a.prompt, a.passage, a.question, a.sourceSentence), null,
                a.choices.map { ChoiceOption(it.id, it.text) }, a.hintAllowed)
            CKind.RECALL, CKind.SPELLING -> ActivityView.Recall(id, label, "独立写出对应的英文词",
                studyText(a.prompt, a.passage, a.question, a.sourceSentence), a.cue, null, draft, a.hintAllowed)
            CKind.PASSAGE -> if (a.choices.isEmpty()) ActivityView.Unknown(id, label, a.prompt) else ActivityView.Reading(id, label, a.passage ?: a.prompt,
                studyText(a.prompt.takeUnless { it == a.passage }, a.question, a.sourceSentence), a.choices.map { ChoiceOption(it.id, it.text) }, a.source.toSdk(), a.hintAllowed)
            CKind.WRITING, CKind.REWRITE -> ActivityView.Production(id, label, a.prompt, a.cue,
                studyText(a.passage, a.question, a.sourceSentence).ifBlank { null }, a.targetWords, draft,
                (b.view(Query.PreviewCall(CFeature.WRITING_FEEDBACK)) as View.Preview).preview.toSdk(b.online.value), a.hintAllowed)
        }
    }
    /** Model fields can carry essential context for any kind; keep them all in the existing UI slots. */
    private fun studyText(vararg parts: String?): String = parts.mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }.distinct().joinToString("\n\n")
    internal fun mapFeedback(v: View.AnswerResult): Feedback {
        val a = v.answer; val ai = v.feedback.lastOrNull { it.source == CGrade.AI }
        val practice = !a.affectsSchedule && (a.input.hintUsed || a.input.answerRevealed) && a.gradeSource == CGrade.OBJECTIVE
        val verdict = when {
            !a.valid || a.verdict == CVerdict.DISPUTED || a.input.skipped -> Verdict.Recorded
            ai != null -> Verdict.Reviewed
            a.gradeSource == CGrade.UNGRADED && v.pendingOperationId != null -> Verdict.PendingReview
            practice -> Verdict.Practice
            a.verdict == CVerdict.CORRECT -> Verdict.Correct; a.verdict == CVerdict.INCORRECT -> Verdict.Incorrect
            else -> Verdict.Recorded
        }
        val headline = when (verdict) {
            Verdict.Correct -> "回答正确"; Verdict.Incorrect -> "这次没有回忆准确"; Verdict.Practice -> "练习已记录"
            Verdict.PendingReview -> "句子已保存，等待 AI 反馈"; Verdict.Reviewed -> "AI 表达反馈"
            else -> if (a.input.skipped) "已跳过，不记为遗忘" else if (!a.valid) "已标记争议" else "已记录"
        }
        return Feedback(a.id.value, a.version.toInt(), a.input.text.ifBlank { null }, verdict, headline,
            v.feedback.lastOrNull { it.source != CGrade.AI }?.explanation, v.correctChoiceId, a.input.selectedChoiceId, v.acceptedAnswers,
            null, a.affectsSchedule && a.valid,
            when { !a.valid -> "此题不参与当前复习安排"; practice -> "使用了提示或看过答案，不计作独立回忆"; a.gradeSource != CGrade.OBJECTIVE -> "不直接改变记忆评分"; a.input.skipped -> "不记为遗忘"; else -> "已按本机记忆算法更新" },
            ai?.let { AiReview(it.targetUsage, when (it.targetUsage) { "good" -> "用法良好"; "misused" -> "目标词误用"; "acceptable_unnatural" -> "用法可接受，可更自然"; "equally_valid" -> "同样合理的表达"; else -> "AI 评价" },
                listOf(ReviewPoint(it.quotedAnswer, it.explanation, true)), null, it.provenance.toSdk()) },
            v.pendingOperationId?.value?.let(::OperationId), v.canMarkDifficult, v.markedDifficult, v.canRevise)
    }
    override suspend fun submitAnswer(session: SessionId, activity: ActivityId, answer: Answer, request: ClientRequestId): SdkResult<Feedback> = safely {
        val view = session(session)
        val presentation = b.view(Query.GetPresentation(CId(activity.value))) as View.Presentation
        if (presentation.sessionId.value != session.value) return@safely invalid<Feedback>("题目不属于此会话")
        val card = presentation.activity
        val command: Command = if (view.session.revisingAnswerId != null) {
            val original = (b.view(Query.GetFeedback(view.session.revisingAnswerId!!)) as View.Feedbacks).answer
            val text = (answer as? Answer.Text)?.text ?: return@safely invalid<Feedback>("修订需要文字答案")
            Command.ReviseAnswer(Ref(original.id, original.version), text)
        } else {
            // Keep the presentation identity through process death and duplicate taps.
            val input = when (answer) {
                is Answer.Text -> CInput(answer.text, requestFeedback = card.kind in setOf(CKind.WRITING, CKind.REWRITE))
                is Answer.Choice -> CInput("", selectedChoiceId = answer.optionId)
                is Answer.Familiarity -> CInput("", familiar = answer.knowsIt)
                Answer.Acknowledge -> CInput("")
            }
            Command.SubmitAnswer(CId(session.value), presentation.id, Ref(card.id, card.version), input)
        }
        b.exec(request, command) { (it as? View.AnswerResult)?.let(::mapFeedback) }
    }
    private fun draftKey(session: SessionId, activity: ActivityId) = "answer:${session.value}:${activity.value}"
    override suspend fun saveDraft(session: SessionId, activity: ActivityId, text: String): SdkResult<Unit> = safely { draftMutex.withLock {
        val key = draftKey(session, activity); val old = (b.view(Query.GetDraft(key)) as View.DraftResult).draft
        if (old?.text == text) SdkResult.Ok(Unit) else b.exec(requestId(), Command.SaveDraft(key, text, old?.version ?: 0)) { Unit }
    } }
    override suspend fun requestHint(session: SessionId, activity: ActivityId): SdkResult<Hint> = safely {
        val v = session(session)
        if (v.session.currentPresentationId?.value != activity.value) return@safely invalid<Hint>("题目已变化")
        b.exec(requestId(), Command.RequestHint(CId(session.value), v.session.currentPresentationId!!)) { (it as? View.Hint)?.let { Hint(it.text, "此次记为辅助练习，不计作独立回忆") } }
    }
    override suspend fun markHard(session: SessionId, answerId: String, hard: Boolean): SdkResult<Feedback> = safely {
        val answer = (b.view(Query.GetFeedback(CId(answerId))) as View.Feedbacks).answer
        b.exec(requestId(), Command.MarkDifficulty(Ref(answer.id, answer.version), hard)) { (it as? View.AnswerResult)?.let(::mapFeedback) }
    }
    override suspend fun reviseAnswer(session: SessionId, activity: ActivityId): SdkResult<Unit> = safely {
        val v = session(session); val answer = v.feedback?.answer ?: return@safely invalid<Unit>("当前没有可修订的答案")
        if (v.session.lastPresentationId?.value != activity.value) return@safely invalid<Unit>("题目已变化")
        b.exec(requestId(), Command.BeginAnswerRevision(CId(session.value), Ref(answer.id, answer.version))) { Unit }
    }
    override suspend fun advance(session: SessionId, request: ClientRequestId): SdkResult<SessionState> = safely {
        val r = b.exec(request, Command.NextActivity(CId(session.value))) { it as? View.SessionState }
        if (r is SdkResult.Err) r else SdkResult.Ok(mapSession((r as SdkResult.Ok).value))
    }
    override suspend fun skip(session: SessionId, activity: ActivityId, reason: String?): SdkResult<SessionState> = safely {
        val v = session(session); val card = v.activity ?: return@safely invalid<SessionState>("当前没有题目")
        if (v.session.currentPresentationId?.value != activity.value) return@safely invalid<SessionState>("题目已变化")
        val key = "skip:${v.session.currentPresentationId!!.value}"
        val result = b.exec(ClientRequestId(key), Command.SubmitAnswer(CId(session.value), v.session.currentPresentationId!!, Ref(card.id, card.version), CInput(reason.orEmpty(), skipped = true))) { it }
        if (result is SdkResult.Err) result else advance(session, ClientRequestId("$key:next"))
    }
    override suspend fun dispute(session: SessionId, activity: ActivityId, reason: String): SdkResult<Unit> = safely {
        val presentation = b.view(Query.GetPresentation(CId(activity.value))) as View.Presentation
        if (presentation.sessionId.value != session.value) return@safely invalid<Unit>("题目不属于此会话")
        val card = presentation.activity
        b.exec(requestId(), Command.DisputeActivity(Ref(card.id, card.version), reason)) { Unit }
    }
    override suspend fun pause(session: SessionId) = b.exec(requestId(), Command.FinishSession(CId(session.value), pause = true)) { Unit }
    override suspend fun answerBudgetPrompt(session: SessionId, continueLearning: Boolean): SdkResult<SessionState> = safely {
        val result = b.exec(requestId(), if (continueLearning) Command.StartSession(continuePastBudget = true, includeIntroductions = true) else Command.FinishSession(CId(session.value), pause = true)) { it as? View.SessionState }
        if (result is SdkResult.Err) result else {
            val view = (result as SdkResult.Ok).value
            if (continueLearning && view.activity == null && view.session.status == CSessionStatus.ACTIVE) advance(SessionId(view.session.id.value), requestId()) else SdkResult.Ok(mapSession(view))
        }
    }
    override suspend fun finishLearningDay(request: ClientRequestId): SdkResult<LearningDay> = safely {
        val day = b.view(Query.GetLearningDay()) as View.Day
        b.exec(request, Command.FinishLearningDay(day.day.id)) { (it as? View.Day)?.toSdk() }
    }
    override suspend fun reportPresence(session: SessionId, visible: Boolean, waiting: Boolean): SdkResult<Unit> =
        b.exec(requestId(), Command.SetTimer(CId(session.value), if (!visible) TimerMode.BACKGROUND else if (waiting) TimerMode.WAITING else TimerMode.STUDY)) { Unit }
}
