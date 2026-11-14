package app.wordprism.mock

import app.wordprism.sdk.*
import kotlinx.coroutines.flow.*

/**
 * Mock learning engine. Scoring, budget state, plan and day-end rules live HERE (backend side),
 * never in the UI. The real engine (FSRS-based scheduling, versioned policies) replaces this.
 */
internal class MockStudy(private val c: MockCore, private val analysis: () -> MockInsights) : StudyApi {

    init { c.handlers[OperationKind.WritingFeedback] = ::runWritingFeedback }

    // ------------------------------------------------------------------ day & plan

    private fun ensureDay(d: MockDb): Pair<MockDb, MDay> {
        val today = c.today()
        d.days[today]?.let { return d to it }
        val day = MDay("d-$today", today, DayStatus.NotStarted.value, plannedItems = 0, planVersion = d.settings.planVersion)
        return d.copy(days = d.days + (today to day)) to day
    }

    private fun budgetState(d: MockDb, day: MDay): BudgetState {
        val budget = d.settings.budgetMinutes * 60L
        return when {
            day.effectiveSeconds >= budget -> BudgetState.Reached
            day.effectiveSeconds >= budget - 5 * 60 -> BudgetState.Near
            else -> BudgetState.Within
        }
    }

    internal fun dayView(d: MockDb, day: MDay): LearningDay {
        val a = analysis().dailyStatus(d, day)
        return LearningDay(
            LearningDayId(day.id), LocalDay(day.date), c.zone.id, DayStatus(day.status), d.settings.budgetMinutes,
            day.effectiveSeconds, day.waitingSeconds, budgetState(d, day), day.plannedItems, day.completedItems, day.planVersion,
            day.openSessionId?.takeIf { d.sessions[it]?.status != SessionStatus.Completed.value }?.let(::SessionId),
            canEndDay = day.status != DayStatus.Ended.value, dailyAnalysis = a,
        )
    }

    override fun observeLearningDay(): Flow<LearningDay> = combine(c.db, c.online) { d, _ ->
        val day = d.days[c.today()] ?: MDay("d-${c.today()}", c.today(), DayStatus.NotStarted.value, plannedItems = 0, planVersion = d.settings.planVersion)
        dayView(d, day)
    }.distinctUntilChanged()

    override fun observePlan(): Flow<LearningPlan> = c.db.map { d ->
        val s = d.settings
        val due = d.words.values.count { it.status == StudyStatus.Due.value }
        val fresh = minOf(s.newPerDay, d.words.values.count { it.status == StudyStatus.NotStarted.value })
        val minutes = minOf(s.budgetMinutes, (due * 1.6 + fresh * 2.4 + 6).toInt())
        val choiceShare = 1f - s.reviewShareRecall - s.readingShare - s.productionShare
        LearningPlan(
            s.planVersion, s.policyVersion, due, fresh, minutes,
            listOf(
                MixShare("choice", "辨认与语境选词", choiceShare), MixShare("recall", "无选项回忆", s.reviewShareRecall),
                MixShare("reading", "AI 短文理解", s.readingShare), MixShare("production", "造句与改写", s.productionShare),
            ),
            s.focus.map { f -> FocusItem(f, "近期反复混淆", d.groups.values.firstOrNull { g -> g.title.contains(f.substringBefore(" ")) }?.id?.let(::GroupId)) },
            s.adjustedBy,
            if (d.answers.size + d.history.answers < 200) "个人数据较少，按初始估计安排" else "按你近 14 天的实际用时估计",
            if (due > 12) "到期 $due 项，今天优先复习，其余顺延" else null,
            libraryWords = d.words.size,
        )
    }.distinctUntilChanged()

    // ------------------------------------------------------------------ session build

    private fun buildActivities(d: MockDb): List<MActivity> {
        val out = mutableListOf<MActivity>()
        var n = 0
        fun id() = ActivityId("a-${++n}")
        val groups = d.groups.values.filter { it.memberIds.size >= 2 }.sortedWith(compareByDescending<MGroup> { g -> g.memberIds.count { d.words[it]?.status == StudyStatus.Due.value } }.thenBy { it.createdAt }).take(3)
        val fresh = d.groups.values.firstOrNull { g -> g.memberIds.all { d.words[it]?.status == StudyStatus.NotStarted.value } && g.memberIds.size >= 2 }
        if (fresh != null) {
            val a = d.words.getValue(fresh.memberIds[0]); val b = d.words.getValue(fresh.memberIds[1])
            out += MActivity(ActivityView.Familiarity(id(), "判断基础", a.lemma, b.lemma), targetLemma = a.lemma)
            out += MActivity(ActivityView.Contrast(id(), "看清差异", fresh.detail(d)))
        }
        groups.forEach { g ->
            val members = g.memberIds.mapNotNull { d.words[it] }
            val a = members[0]; val b = members[1]
            val opts = members.sortedBy { (it.lemma.hashCode() + n) % 7 }.map { ChoiceOption(it.id, it.lemma) }
            out += MActivity(
                ActivityView.Choice(id(), "按释义辨认", "选出符合释义的词", "${a.glossZh}\n${a.glossEn}", null, opts, hintAvailable = true),
                correctOptionId = a.id, targetLemma = a.lemma, explanation = "${a.lemma}（${a.pos}）${a.glossZh}", hint = "看词性：这里需要 ${a.pos}",
            )
            val stem = b.example.replace(Regex("\\b${b.lemma}\\w*\\b", RegexOption.IGNORE_CASE), "____")
            out += MActivity(
                ActivityView.Choice(id(), "语境选词", "选出最适合填空的词", stem, b.exampleZh, opts.reversed(), hintAvailable = true),
                correctOptionId = b.id, targetLemma = b.lemma, explanation = "${b.collocations.firstOrNull() ?: b.lemma}：${b.glossZh}", hint = "看搭配：${b.collocations.firstOrNull() ?: "无"}",
            )
        }
        groups.firstOrNull()?.let { g ->
            val w = d.words.getValue(g.memberIds[1])
            out += MActivity(
                ActivityView.Recall(id(), "无选项回忆", "根据中文提示写出单词", w.example.replace(Regex("\\b${w.lemma}\\w*\\b", RegexOption.IGNORE_CASE), "____"), w.glossZh, w.lemma.length, null, hintAvailable = true),
                accepted = listOf(w.lemma, w.lemma + "s", w.lemma + "ed"), targetLemma = w.lemma, explanation = "${w.lemma}：${w.glossZh}", hint = "首字母是 ${w.lemma.first()}，共 ${w.lemma.length} 个字母",
            )
        }
        groups.getOrNull(1)?.let { g ->
            val a = d.words.getValue(g.memberIds[0]); val b = d.words.getValue(g.memberIds[1])
            val passage = "Mira's town had changed fast. ${a.example} Later, people noticed something else: ${b.example.replaceFirstChar { it.lowercase() }} " +
                "Nobody planned it, but the town learned to live with both."
            out += MActivity(
                ActivityView.Reading(id(), "短文理解", passage, "文中的 “${a.lemma}” 最接近哪个意思？",
                    listOf(ChoiceOption("r1", a.glossZh), ChoiceOption("r2", b.glossZh), ChoiceOption("r3", "与上下文无关的意思")),
                    ContentSource("ai_generated", c.presetFor(d, FeatureId.PracticeGeneration)?.name, 2, "mock-model-small"), hintAvailable = true),
                correctOptionId = "r1", targetLemma = a.lemma, explanation = "这里是 ${a.lemma}，${a.glossZh}；${b.lemma} 是 ${b.glossZh}", hint = "回到原句，看它修饰或连接的是什么",
            )
        }
        groups.firstOrNull()?.let { g ->
            val w = d.words.getValue(g.memberIds[1])
            out += MActivity(
                ActivityView.Production(id(), "造句", "用目标词写一句完整的英文", "例如：${w.exampleZh}", null, listOf(w.lemma),
                    null, c.requirement(d, FeatureId.WritingFeedback), hintAvailable = true),
                targetLemma = w.lemma, hint = "常见搭配：${w.collocations.joinToString()}",
            )
        }
        return out
    }

    // ------------------------------------------------------------------ session API

    override suspend fun startOrResume(request: ClientRequestId, continuePastBudget: Boolean): SdkResult<SessionId> = c.tx { d0 ->
        val (d, day) = ensureDay(d0)
        day.openSessionId?.let { sid ->
            val s = d.sessions[sid]
            if (s != null && s.status != SessionStatus.Completed.value) {
                val n = d.copy(sessions = d.sessions + (sid to s.copy(status = SessionStatus.Active.value, shownAt = c.clock())))
                return@tx n to SdkResult.Ok(SessionId(sid))
            }
        }
        val acts = buildActivities(d)
        if (acts.isEmpty()) return@tx d to SdkResult.Err(SdkError(ErrorCode.Validation, "词库里还没有可以学习的词组，先用 AI 添加几个易混词吧", false))
        val s = MSession(c.newId("s"), day.id, SessionStatus.Active.value, acts, shownAt = c.clock(), policyVersion = d.settings.policyVersion)
        val nd = day.copy(
            status = if (day.status == DayStatus.Ended.value) DayStatus.Ended.value else DayStatus.InProgress.value,
            openSessionId = s.id, plannedItems = day.plannedItems + acts.size,
        )
        d.copy(sessions = d.sessions + (s.id to s), days = d.days + (day.date to nd)) to SdkResult.Ok(SessionId(s.id))
    }

    private fun view(d: MockDb, s: MSession): SessionState {
        val day = d.days.values.first { it.id == s.dayId }
        val cur = s.activities.getOrNull(s.index)
        val bs = budgetState(d, day)
        val curView = cur?.view?.let { v ->
            when (v) {
                is ActivityView.Recall -> v.copy(draft = s.drafts[v.id.value])
                is ActivityView.Production -> v.copy(draft = s.drafts[v.id.value], requirement = c.requirement(d, FeatureId.WritingFeedback))
                else -> v
            }
        }
        return SessionState(
            SessionId(s.id), SessionStatus(s.status), s.index.coerceAtMost(s.activities.size), s.activities.size, curView,
            cur?.let { s.feedback[it.view.id.value] }, bs,
            budgetPromptPending = bs == BudgetState.Reached && !day.budgetPromptAnswered && cur != null && s.feedback[cur.view.id.value] == null,
            s.policyVersion,
        )
    }

    override fun observeSession(id: SessionId) = combine(c.db, c.online) { d, _ -> d.sessions[id.value]?.let { view(d, it) } }.distinctUntilChanged()

    override suspend fun submitAnswer(session: SessionId, activity: ActivityId, answer: Answer, request: ClientRequestId): SdkResult<Feedback> {
        var launchOp: String? = null
        val r = c.tx { d ->
            val s = d.sessions[session.value] ?: return@tx d to notFound()
            val act = s.activities.firstOrNull { it.view.id == activity } ?: return@tx d to notFound()
            // Idempotency: a resubmission (double tap, retry after timeout) returns the stored result.
            // Revising an open answer goes through reviseAnswer(), which clears the current feedback first.
            s.feedback[activity.value]?.let { prev -> return@tx d to SdkResult.Ok(prev) }
            val hinted = activity.value in s.hinted
            val now = c.clock()
            val fb: Feedback = score(d, act, answer, request.value, hinted, (s.revisions[activity.value] ?: 0) + 1)
            var n = d
            if (fb.verdict == Verdict.PendingReview) {
                var op = c.newOperation(d, OperationKind.WritingFeedback, "造句反馈：${act.targetLemma}", FeatureId.WritingFeedback)
                    .copy(resultSummary = "${s.id}|${activity.value}|${(answer as Answer.Text).text}")
                // The sentence is always saved; if the preset can't run, the feedback task fails visibly and can be retried later.
                c.blockingError(c.requirement(d, FeatureId.WritingFeedback))?.let { e ->
                    op = op.copy(status = OperationStatus.Failed, error = e.copy(retryable = true, message = e.message + "。句子已保存，配置后可重试"), actions = setOf(OperationAction.Retry))
                }
                launchOp = op.id.value
                n = with(c) { n.withOp(op) }
            }
            val fb2 = if (launchOp != null) fb.copy(pendingOperationId = OperationId(launchOp!!)) else fb
            val day = n.days.values.first { it.id == s.dayId }
            val firstTime = s.feedback[activity.value] == null
            val spent = ((now - s.shownAt) / 1000).coerceIn(3, 90)
            val nd = day.copy(effectiveSeconds = day.effectiveSeconds + spent, completedItems = day.completedItems + if (firstTime) 1 else 0)
            val record = MAnswer(request.value, now, act.view.kindLabel, act.targetLemma, (answer as? Answer.Choice)?.optionId?.let { o -> d.words[o]?.lemma ?: o } ?: (answer as? Answer.Text)?.text, fb2.verdict.let { it == Verdict.Correct || it == Verdict.AcceptedAlternative }.takeIf { fb2.affectsSchedule }, hinted, act.view.kindLabel)
            n = n.copy(
                sessions = n.sessions + (s.id to s.copy(feedback = s.feedback + (activity.value to fb2), drafts = s.drafts - activity.value)),
                days = n.days + (nd.date to nd),
                answers = if (act.view is ActivityView.Contrast) n.answers else n.answers + record,
            )
            n to SdkResult.Ok(fb2)
        }
        launchOp?.let { c.launch(it) }
        return r
    }

    private fun score(d: MockDb, act: MActivity, answer: Answer, answerId: String, hinted: Boolean, version: Int): Feedback {
        fun fb(verdict: Verdict, headline: String, schedule: Boolean, note: String?, extra: (Feedback) -> Feedback = { it }) =
            extra(Feedback(answerId, version, (answer as? Answer.Text)?.text, verdict, headline, act.explanation, affectsSchedule = schedule, scheduleNote = note))
        return when (val v = act.view) {
            is ActivityView.Contrast, is ActivityView.LearnWord -> fb(Verdict.Recorded, "已看过基本义", false, null)
            is ActivityView.Familiarity -> {
                val knows = (answer as? Answer.Familiarity)?.knowsIt == true
                fb(Verdict.Recorded, if (knows) "好的，直接进入辨析" else "先看基本义，再做辨析", false, "只用于安排学习路径，不计入对错")
            }
            is ActivityView.Choice, is ActivityView.Reading -> {
                val chosen = (answer as? Answer.Choice)?.optionId
                val ok = chosen == act.correctOptionId
                val chosenWord = chosen?.let { d.words[it] }
                val reading = v is ActivityView.Reading
                val scheduleNote = when {
                    hinted -> "用过提示，记为练习，不按无提示成功计入"
                    reading -> "只更新 ${act.targetLemma} 的阅读理解目标"
                    else -> "已更新 ${act.targetLemma} 的复习安排"
                }
                fb(
                    when { !ok -> Verdict.Incorrect; hinted -> Verdict.Practice; else -> Verdict.Correct },
                    if (ok) "答对了" else "正确答案是 ${act.targetLemma}", true, scheduleNote,
                ) { it.copy(correctOptionId = act.correctOptionId, chosenOptionId = chosen, canMarkHard = ok && !hinted,
                    confusionNote = if (!ok && chosenWord != null) "你选了 ${chosenWord.lemma}：它是 ${chosenWord.pos} ${chosenWord.glossZh}，放在这里不合适" else null) }
            }
            is ActivityView.Recall -> {
                val text = (answer as? Answer.Text)?.text?.trim()?.lowercase().orEmpty()
                val ok = text in act.accepted.map { it.lowercase() }
                val near = !ok && act.targetLemma != null && MockLexicon.editDistance(text, act.targetLemma) == 1
                fb(
                    when { !ok -> Verdict.Incorrect; hinted -> Verdict.Practice; else -> Verdict.Correct },
                    when { ok -> "答对了"; near -> "差一个字母：应为 ${act.targetLemma}"; else -> "应为 ${act.targetLemma}" },
                    true, if (hinted) "用过提示，记为练习" else "已更新 ${act.targetLemma} 的回忆目标",
                ) { it.copy(acceptedAnswers = act.accepted.take(1), canMarkHard = ok && !hinted) }
            }
            is ActivityView.Production -> fb(Verdict.PendingReview, "已保存你的句子", false, "AI 反馈是辅助参考，不直接计入复习对错") { it.copy(canRevise = true, explanation = null) }
            is ActivityView.Unknown -> fb(Verdict.Recorded, "已跳过不支持的题型", false, null)
        }
    }

    private suspend fun runWritingFeedback(opId: String) {
        if (!c.aiStep(opId, UsageFigure(1, 700, 260))) return
        c.commit(opId) { d ->
            val (sid, aid, text) = (d.operations.getValue(opId).resultSummary ?: "||").split("|", limit = 3)
            val s = d.sessions[sid] ?: return@commit d to Unit
            val act = s.activities.firstOrNull { it.view.id.value == aid } ?: return@commit d to Unit
            val prev = s.feedback[aid] ?: return@commit d to Unit
            if (prev.pendingOperationId?.value != opId) return@commit d to Unit // answer was revised: old review must not attach
            val target = act.targetLemma.orEmpty()
            val lower = text.lowercase()
            val partner = d.groups.values.firstOrNull { g -> g.memberIds.any { d.words[it]?.lemma == target } }
                ?.memberIds?.mapNotNull { d.words[it]?.lemma }?.firstOrNull { it != target && Regex("\\b$it\\b").containsMatchIn(lower) }
            val review = when {
                Regex("\\b$target\\w*\\b").containsMatchIn(lower) && text.trim().split(Regex("\\s+")).size >= 5 -> AiReview("good", "用法正确",
                    listOf(ReviewPoint(target, "$target 在这里的用法与“${MockLexicon.get(target)?.glossZh ?: "目标义"}”一致", true)), null, aiSrc(d))
                Regex("\\b$target\\w*\\b").containsMatchIn(lower) -> AiReview("acceptable_unnatural", "可以，但句子偏短",
                    listOf(ReviewPoint(target, "目标词用对了", true), ReviewPoint(null, "补充主语或宾语会更自然", false)), MockLexicon.get(target)?.example, aiSrc(d))
                partner != null -> AiReview("misused", "用错了词",
                    listOf(ReviewPoint(partner, "这里写成了 $partner，题目要求使用 $target", true)), MockLexicon.get(target)?.example, aiSrc(d))
                else -> AiReview("misused", "没有用到目标词", listOf(ReviewPoint(null, "句子里没有找到 $target", true)), MockLexicon.get(target)?.example, aiSrc(d))
            }
            val richReview = review.copy(
                summary = "这是模拟反馈，展示分项评价与优化句的样式。",
                dimensions = listOf(
                    ReviewDimension("目标词与搭配", review.points.firstOrNull()?.comment.orEmpty()),
                    ReviewDimension("语法与结构", "检查主谓一致和句子结构。"),
                    ReviewDimension("自然度与语气", "可以结合上下文调整表达。"),
                    ReviewDimension("意图表达", "对照题目意图检查信息是否完整。"),
                ),
                suggestion = review.suggestion ?: text,
                optimizationNotes = "这是用于界面演示的模拟建议。",
                points = emptyList(),
            )
            val fb = prev.copy(verdict = Verdict.Reviewed, headline = "AI 表达反馈", aiReview = richReview, pendingOperationId = null)
            with(c) {
                d.copy(sessions = d.sessions + (sid to s.copy(feedback = s.feedback + (aid to fb))), writingFeedbackByOperation = d.writingFeedbackByOperation + (opId to fb))
                    .updateOp(opId) { it.copy(status = OperationStatus.Succeeded, actions = emptySet(), resultSummary = review.usageLabel) }
            } to Unit
        }
    }

    private fun aiSrc(d: MockDb) = ContentSource("ai_generated", c.presetFor(d, FeatureId.WritingFeedback)?.name, c.presetFor(d, FeatureId.WritingFeedback)?.version, "mock-model-large", c.clock())

    override suspend fun saveDraft(session: SessionId, activity: ActivityId, text: String): SdkResult<Unit> = c.tx { d ->
        val s = d.sessions[session.value] ?: return@tx d to notFound()
        if (s.drafts[activity.value] == text) return@tx d to SdkResult.Ok(Unit)
        d.copy(sessions = d.sessions + (s.id to s.copy(drafts = s.drafts + (activity.value to text)))) to SdkResult.Ok(Unit)
    }

    override suspend fun requestHint(session: SessionId, activity: ActivityId): SdkResult<Hint> = c.tx { d ->
        val s = d.sessions[session.value] ?: return@tx d to notFound()
        val act = s.activities.firstOrNull { it.view.id == activity } ?: return@tx d to notFound()
        val hint = act.hint ?: return@tx d to SdkResult.Err(SdkError(ErrorCode.Validation, "这道题没有提示", false))
        d.copy(sessions = d.sessions + (s.id to s.copy(hinted = s.hinted + activity.value))) to
            SdkResult.Ok(Hint(hint, "看过提示后答对，会记为练习而不是无提示成功"))
    }

    override suspend fun markHard(session: SessionId, answerId: String, hard: Boolean): SdkResult<Feedback> = c.tx { d ->
        val s = d.sessions[session.value] ?: return@tx d to notFound()
        val (aid, fb) = s.feedback.entries.firstOrNull { it.value.answerId == answerId }?.toPair() ?: return@tx d to notFound()
        val nf = fb.copy(markedHard = hard, scheduleNote = if (hard) "仍记为答对，复习间隔会稍短" else fb.scheduleNote?.takeUnless { it.startsWith("仍记为") } ?: "已更新复习安排")
        d.copy(sessions = d.sessions + (s.id to s.copy(feedback = s.feedback + (aid to nf)))) to SdkResult.Ok(nf)
    }

    override suspend fun reviseAnswer(session: SessionId, activity: ActivityId): SdkResult<Unit> {
        var cancelOp: String? = null
        val r = c.tx { d ->
            val s = d.sessions[session.value] ?: return@tx d to notFound()
            val fb = s.feedback[activity.value] ?: return@tx d to SdkResult.Ok(Unit)
            cancelOp = fb.pendingOperationId?.value
            // Keep the old feedback as history (mock: drop from current view), draft starts from nothing new.
            d.copy(sessions = d.sessions + (s.id to s.copy(feedback = s.feedback - activity.value, revisions = s.revisions + (activity.value to fb.answerVersion)))) to SdkResult.Ok(Unit)
        }
        cancelOp?.let { c.cancel(OperationId(it)) }
        return r
    }

    override suspend fun advance(session: SessionId, request: ClientRequestId): SdkResult<SessionState> {
        var dayEnded = false
        val r = c.tx { d ->
            val s = d.sessions[session.value] ?: return@tx d to notFound()
            val cur = s.activities.getOrNull(s.index)
            if (cur != null && s.feedback[cur.view.id.value] == null) return@tx d to SdkResult.Ok(view(d, s)) // not answered: no-op
            if (d.requestIndex[request.value] != null) return@tx d to SdkResult.Ok(view(d, s))
            val ni = s.index + 1
            val done = ni >= s.activities.size
            val ns = s.copy(index = ni, status = if (done) SessionStatus.Completed.value else s.status, shownAt = c.clock())
            var n = d.copy(sessions = d.sessions + (s.id to ns), requestIndex = d.requestIndex + (request.value to "advance"))
            if (done) {
                val day = n.days.values.first { it.id == s.dayId }
                if (day.status != DayStatus.Ended.value) {
                    n = n.copy(days = n.days + (day.date to day.copy(status = DayStatus.PlanCompleted.value, openSessionId = null)))
                    dayEnded = true
                }
            }
            n to SdkResult.Ok(view(n, ns))
        }
        if (dayEnded) analysis().onDayEnded(c.today())
        return r
    }

    override suspend fun skip(session: SessionId, activity: ActivityId, reason: String?): SdkResult<SessionState> {
        c.tx { d ->
            val s = d.sessions[session.value] ?: return@tx d to Unit
            val fb = Feedback("skip-${activity.value}", 1, null, Verdict.Recorded, "已跳过", reason?.let { "原因：$it" }, affectsSchedule = false, scheduleNote = "跳过不计为遗忘")
            d.copy(sessions = d.sessions + (s.id to s.copy(feedback = s.feedback + (activity.value to fb)))) to Unit
        }
        return advance(session, ClientRequestId("skip-${session.value}-${activity.value}"))
    }

    override suspend fun dispute(session: SessionId, activity: ActivityId, reason: String): SdkResult<Unit> = c.tx { d ->
        d.copy(disputes = d.disputes + 1) to SdkResult.Ok(Unit)
    }

    override suspend fun pause(session: SessionId): SdkResult<Unit> = c.tx { d ->
        val s = d.sessions[session.value] ?: return@tx d to notFound()
        if (s.status == SessionStatus.Completed.value) return@tx d to SdkResult.Ok(Unit)
        d.copy(sessions = d.sessions + (s.id to s.copy(status = SessionStatus.Paused.value))) to SdkResult.Ok(Unit)
    }

    override suspend fun answerBudgetPrompt(session: SessionId, continueLearning: Boolean): SdkResult<SessionState> = c.tx { d ->
        val s = d.sessions[session.value] ?: return@tx d to notFound()
        val day = d.days.values.first { it.id == s.dayId }
        var n = d.copy(days = d.days + (day.date to day.copy(budgetPromptAnswered = true)))
        if (!continueLearning) n = n.copy(sessions = n.sessions + (s.id to s.copy(status = SessionStatus.Paused.value)))
        n to SdkResult.Ok(view(n, n.sessions.getValue(s.id)))
    }

    override suspend fun finishLearningDay(request: ClientRequestId): SdkResult<LearningDay> {
        c.tx { d0 ->
            val (d, day) = ensureDay(d0)
            if (day.status == DayStatus.Ended.value) return@tx d to Unit
            val sessions = day.openSessionId?.let { sid -> d.sessions[sid]?.let { mapOf(sid to it.copy(status = SessionStatus.Paused.value)) } }.orEmpty()
            d.copy(days = d.days + (day.date to day.copy(status = DayStatus.Ended.value)), sessions = d.sessions + sessions) to Unit
        }
        analysis().onDayEnded(c.today())
        val d = c.db.value
        return SdkResult.Ok(dayView(d, d.days.getValue(c.today())))
    }

    private fun notFound() = SdkResult.Err(SdkError(ErrorCode.NotFound, "学习会话不存在", false))
}
