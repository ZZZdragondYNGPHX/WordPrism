package app.wordprism.ui.study

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wordprism.sdk.*
import app.wordprism.ui.common.*
import app.wordprism.ui.theme.Wp
import app.wordprism.ui.theme.WpType

@Composable
fun StudyScreen(sessionId: String, onExit: () -> Unit, openApi: () -> Unit, openFeedback: () -> Unit) {
    val vm = wpViewModel(key = "study-$sessionId") { sdk, s -> StudyViewModel(sdk, s, SessionId(sessionId)) }
    CollectMessages(vm, LocalSnackbar.current)
    val state by vm.session.collectAsStateWithLifecycle()
    val day by vm.day.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val hint by vm.hint.collectAsStateWithLifecycle()
    val review by vm.pendingReview.collectAsStateWithLifecycle()
    val managed by vm.managed.collectAsStateWithLifecycle()
    val tutoring by vm.tutoring.collectAsStateWithLifecycle()
    val askingTutor by vm.askingTutor.collectAsStateWithLifecycle()
    var showTutor by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var disputeFor by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmEndDay by rememberSaveable { mutableStateOf(false) }
    val c = Wp.colors
    LifecycleResumeEffect(sessionId) {
        vm.presence(true)
        onPauseOrDispose { vm.presence(false) }
    }

    // Leaving is a pause, not "end of day": the session stays resumable.
    BackHandler { vm.pause(onExit) }

    val s = state
    val contextualTutor = tutoring.filter { item ->
        if (s?.feedback != null) item.answerId == s.feedback!!.answerId && item.answerVersion == s.feedback!!.answerVersion
        else item.answerId == null && (item.presentationId == s?.current?.id?.value || item.moment == "welcome" &&
            (s?.current is ActivityView.LearnWord || s?.current is ActivityView.Contrast || s?.current is ActivityView.Familiarity))
    }
    if (showTutor && s != null) TutorSheet(
        contextKey = "${s.current?.id?.value}:${s.feedback?.answerVersion}",
        messages = if (s.current == null) tutoring.filter { it.moment == "recap" } else contextualTutor,
        canAsk = s.current != null,
        assisted = s.feedback == null,
        submitting = askingTutor,
        onAsk = { question, saved -> s.current?.let { vm.askTutor(it.id, question, saved) } },
        onCancel = vm::cancelTutor,
        onRetry = vm::retryTutor,
        openApi = openApi,
        onClose = { showTutor = false },
    )

    Column(
        Modifier.fillMaxSize()
            .background(c.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .imePadding(),
    ) {
        // ---------------------------------------------------------------- Cupertino Study HUD Top Bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.pause(onExit) }) {
                Icon(Icons.Outlined.Close, contentDescription = "暂停并退出（进度已保存）", tint = c.ink)
            }
            if (s != null) {
                ProgressSegments(s.done, s.total, Modifier.weight(1f).padding(horizontal = 8.dp))
                Text(
                    "${minOf(s.done + if (s.feedback == null) 1 else 0, s.total)}/${s.total}",
                    style = MaterialTheme.typography.labelLarge.merge(WpType.tabular),
                    color = c.muted,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (managed?.mode == LearningMode.AiManaged) {
                Spacer(Modifier.width(8.dp))
                CupertinoPill(
                    text = "问助教",
                    icon = Icons.Outlined.AutoAwesome,
                    tint = c.ai,
                    onClick = { showTutor = true },
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作", tint = c.ink)
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    val cur = s?.current
                    if (cur != null && s.feedback == null) {
                        DropdownMenuItem(
                            text = { Text("跳过这题") },
                            onClick = { menu = false; vm.skip(cur.id, null) },
                            leadingIcon = { Icon(Icons.Outlined.SkipNext, null) },
                        )
                    }
                    if (cur?.allowDispute == true) {
                        DropdownMenuItem(
                            text = { Text("这题有问题") },
                            onClick = { menu = false; disputeFor = cur.id.value },
                            leadingIcon = { Icon(Icons.Outlined.Flag, null) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("结束今天") },
                        onClick = { menu = false; confirmEndDay = true },
                        leadingIcon = { Icon(Icons.Outlined.Bedtime, null) },
                    )
                }
            }
        }
        day?.let { if (it.budgetState != BudgetState.Within) BudgetNote(it) }

        when {
            s == null -> Loading()
            s.status == SessionStatus.Completed || s.current == null -> SessionDone(
                s = s,
                day = day,
                onExit = { vm.pause(onExit) },
                openFeedback = openFeedback,
                tutor = { if (managed?.mode == LearningMode.AiManaged && s.done > 0) TutorReviewButton(s.id, openApi) },
                onEndDay = { confirmEndDay = true },
            )
            else -> {
                val act = s.current!!
                key(act.id) {
                    val shown = act
                    val slotFeedback = s.feedback

                    val lipColor = when (slotFeedback?.verdict) {
                        Verdict.Correct, Verdict.AcceptedAlternative -> c.positive
                        Verdict.Incorrect -> c.negative
                        Verdict.PendingReview -> c.ai
                        else -> if (shown is ActivityView.Production) c.ai else c.action
                    }

                    Column(
                        Modifier.weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = Wp.gutter)
                            .padding(top = 6.dp, bottom = 20.dp),
                    ) {
                        // Keep frequent answer and keyboard interactions immediate.
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            color = c.surface,
                            border = BorderStroke(0.5.dp, if (slotFeedback != null) lipColor.copy(alpha = 0.45f) else c.hairline),
                            shadowElevation = if (c.isDark) 0.dp else 4.dp,
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Box(Modifier.fillMaxWidth().height(4.dp).background(lipColor))
                                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CupertinoPill(
                                            text = shown.kindLabel,
                                            tint = if (shown is ActivityView.Production) c.ai else c.action,
                                        )
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            if (slotFeedback == null) "学习卡 · 正面" else "已作答 · 解析面",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = c.muted,
                                        )
                                    }
                                    Spacer(Modifier.height(16.dp))
                                    ActivityBody(shown, slotFeedback, busy, vm, openApi)
                                }
                            }
                        }

                        if (managed?.mode == LearningMode.AiManaged && (slotFeedback != null || shown is ActivityView.LearnWord || shown is ActivityView.Contrast)) {
                            TutorInline(contextualTutor.firstOrNull()) { showTutor = true }
                        }
                        if (hint?.first == shown.id && slotFeedback == null) HintCard(hint!!.second)
                        if (shown.hintAvailable && slotFeedback == null && hint?.first != shown.id) {
                            TextButton(onClick = { vm.requestHint(shown.id) }, modifier = Modifier.padding(top = 8.dp)) {
                                Icon(Icons.Outlined.Lightbulb, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("看提示")
                            }
                        }
                    }
                }
                val fb = s.feedback
                if (fb != null) {
                    FeedbackPanel(
                        fb = fb,
                        act = act,
                        review = review,
                        busy = busy,
                        onContinue = vm::advance,
                        onHard = { vm.markHard(fb.answerId, it) },
                        onRevise = { vm.revise(act.id) },
                        onRetryReview = vm::retryReview,
                        openApi = openApi,
                        reinforcementRemaining = s.reinforcementRemaining,
                    )
                }
            }
        }
    }

    if (s?.budgetPromptPending == true && day != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("今天的 ${day!!.budgetMinutes} 分钟预算用完了") },
            text = { Text("记忆质量比数量重要。可以在这里停下，没学完的内容会按计划顺延；也可以再学一会儿，用时会照实记录。") },
            confirmButton = { TextButton(onClick = { vm.budgetChoice(false, onExit) }) { Text("停在这里") } },
            dismissButton = { TextButton(onClick = { vm.budgetChoice(true) {} }) { Text("再学一会儿") } },
        )
    }

    disputeFor?.let { aid ->
        DisputeDialog(onDismiss = { disputeFor = null }) { reason -> disputeFor = null; vm.dispute(ActivityId(aid), reason) }
    }

    if (confirmEndDay) {
        AlertDialog(
            onDismissRequest = { confirmEndDay = false },
            title = { Text("结束今天的学习？") },
            text = { Text("这一轮会暂停并保存。结束今天后，每日分析（如果开启）会运行一次。") },
            confirmButton = { TextButton(onClick = { confirmEndDay = false; vm.endDay(onExit) }) { Text("结束今天") } },
            dismissButton = { TextButton(onClick = { confirmEndDay = false }) { Text("取消") } },
        )
    }
}

// ==================================================================== activity bodies

@Composable
private fun ActivityBody(act: ActivityView, fb: Feedback?, busy: Boolean, vm: StudyViewModel, openApi: () -> Unit) {
    when (act) {
        is ActivityView.Familiarity -> Familiarity(act, fb, busy) { vm.submit(act.id, Answer.Familiarity(it)) }
        is ActivityView.LearnWord -> LearnWord(act, fb, busy) { vm.submit(act.id, Answer.Acknowledge) }
        is ActivityView.Contrast -> Contrast(act, fb, busy) { vm.submit(act.id, Answer.Acknowledge) }
        is ActivityView.Choice -> {
            Text(act.instruction, style = MaterialTheme.typography.bodyMedium, color = Wp.colors.muted)
            Spacer(Modifier.height(10.dp))
            Stem(act.stem)
            var showZh by rememberSaveable(act.id.value) { mutableStateOf(false) }
            if (act.stemTranslationZh != null) {
                TextButton(onClick = { showZh = !showZh }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (showZh) "隐藏中文" else "看中文意思")
                }
                if (showZh) Text(act.stemTranslationZh!!, style = MaterialTheme.typography.bodyMedium, color = Wp.colors.muted)
            }
            Spacer(Modifier.height(16.dp))
            Options(act.options, fb, busy, wordStyle = true) { vm.submit(act.id, Answer.Choice(it)) }
        }
        is ActivityView.Reading -> {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Wp.colors.sunk.copy(alpha = if (Wp.colors.isDark) 0.65f else 0.45f),
                border = BorderStroke(0.5.dp, Wp.colors.hairline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(highlightTargets(act.passage, act.question), style = WpType.english, color = Wp.colors.ink)
                    SourceNote(act.source, Modifier.padding(top = 8.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(act.question, style = MaterialTheme.typography.titleMedium, color = Wp.colors.ink)
            Spacer(Modifier.height(12.dp))
            Options(act.options, fb, busy, wordStyle = false) { vm.submit(act.id, Answer.Choice(it)) }
        }
        is ActivityView.Recall -> Recall(act, fb, busy, vm)
        is ActivityView.Production -> Production(act, fb, busy, vm, openApi)
        is ActivityView.Unknown -> {
            Text(act.fallbackText, style = MaterialTheme.typography.bodyLarge)
            Text("当前版本还不支持这种题型，可以跳过。", style = MaterialTheme.typography.bodySmall, color = Wp.colors.muted, modifier = Modifier.padding(top = 8.dp))
            Button(onClick = { vm.skip(act.id, "unsupported") }, enabled = !busy, shape = CircleShape, modifier = Modifier.padding(top = 16.dp)) { Text("跳过") }
        }
    }
}

@Composable
private fun Familiarity(act: ActivityView.Familiarity, fb: Feedback?, busy: Boolean, onAnswer: (Boolean) -> Unit) {
    Text(act.lemma, style = WpType.word.copy(fontSize = WpType.word.fontSize * 1.35f), color = Wp.colors.ink)
    Spacer(Modifier.height(8.dp))
    Text("你认识这个词吗？", style = MaterialTheme.typography.bodyLarge, color = Wp.colors.muted)
    Spacer(Modifier.height(24.dp))
    val enabled = fb == null && !busy
    OutlinedButton(
        onClick = { onAnswer(false) },
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
    ) { Text("还不认识，先学基本义") }
    Spacer(Modifier.height(10.dp))
    Button(
        onClick = { onAnswer(true) },
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
    ) {
        Text(if (act.partner != null) "认识，但容易和 ${act.partner} 混" else "认识")
    }
}

@Composable
internal fun LearnWord(act: ActivityView.LearnWord, fb: Feedback?, busy: Boolean, onDone: () -> Unit) {
    Text(act.lemma, style = WpType.word, color = Wp.colors.ink)
    Text(act.partOfSpeech, style = MaterialTheme.typography.labelLarge, color = Wp.colors.action, modifier = Modifier.padding(top = 6.dp))
    Text(act.meaning, style = MaterialTheme.typography.headlineSmall, color = Wp.colors.ink, modifier = Modifier.padding(top = 6.dp))
    if (act.examples.isNotEmpty()) {
        SectionTitle("放进句子里")
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Wp.colors.sunk.copy(alpha = if (Wp.colors.isDark) 0.65f else 0.45f),
            border = BorderStroke(0.5.dp, Wp.colors.hairline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                act.examples.take(2).forEach {
                    Text(it, style = WpType.english, color = Wp.colors.ink)
                }
            }
        }
    }
    StudySupport(act.support)
    Text("先理解意思，再试着回忆。刚看过答案的练习不会算作独立记忆成功。", style = MaterialTheme.typography.bodySmall, color = Wp.colors.muted, modifier = Modifier.padding(vertical = 18.dp))
    if (fb == null) Button(
        onClick = onDone,
        enabled = !busy,
        shape = CircleShape,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) { Text("理解了，试着回忆") }
}

@Composable
private fun Contrast(act: ActivityView.Contrast, fb: Feedback?, busy: Boolean, onDone: () -> Unit) {
    val g = act.group
    var revealed by remember(act.id) { mutableStateOf(false) }
    LaunchedEffect(act.id) { revealed = true }
    g.purpose?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Wp.colors.ink) }
    Spacer(Modifier.height(16.dp))
    PrismStrip(g.members.map { it.prismRow() }, revealed = revealed, showGloss = false)
    Spacer(Modifier.height(16.dp))
    g.members.forEachIndexed { i, m ->
        Surface(
            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
            shape = RoundedCornerShape(16.dp),
            color = Wp.colors.sunk.copy(alpha = if (Wp.colors.isDark) 0.55f else 0.35f),
            border = BorderStroke(0.5.dp, Wp.colors.hairline),
        ) {
            Row(Modifier.padding(12.dp)) {
                Box(Modifier.padding(top = 7.dp).size(8.dp).clip(CircleShape).background(Wp.colors.member(i)))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontFamily = WpType.wordSmall.fontFamily, fontWeight = FontWeight.SemiBold, color = Wp.colors.member(i))) { append(m.lemma) }
                            m.senses.firstOrNull()?.let { append("  ${it.partOfSpeech} ${it.glossZh}") }
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    m.examples.firstOrNull()?.let {
                        Text(it.text, style = WpType.english.copy(fontSize = WpType.english.fontSize * 0.88f), color = Wp.colors.muted, modifier = Modifier.padding(top = 4.dp))
                    }
                    if (m.collocations.isNotEmpty()) {
                        Text("常见搭配：" + m.collocations.joinToString("，"), style = MaterialTheme.typography.bodySmall, color = Wp.colors.muted, modifier = Modifier.padding(top = 2.dp))
                    }
                    m.senses.forEach { sense -> StudySupport(act.support[sense.id.value].orEmpty()) }
                }
            }
        }
    }
    SourceNote(g.source, Modifier.padding(top = 6.dp))
    Spacer(Modifier.height(18.dp))
    if (fb == null) Button(
        onClick = onDone,
        enabled = !busy,
        shape = CircleShape,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) { Text("看清了，开始练习") }
}

@Composable
private fun StudySupport(sections: List<ExplanationSection>) {
    if (sections.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    Text("AI 学习讲解", style = MaterialTheme.typography.labelLarge, color = Wp.colors.ai)
    sections.firstOrNull { it.id == "explanation" }?.let {
        Text(it.text, style = MaterialTheme.typography.bodyLarge, color = Wp.colors.ink, modifier = Modifier.padding(top = 6.dp))
    }
    val details = sections.filter { it.id != "explanation" }
    if (details.isNotEmpty()) SectionDisclosure("词源、助记与用法", "按需展开，讲解已保存在本机") {
        details.forEach { section ->
            Text(section.title, style = MaterialTheme.typography.titleSmall, color = Wp.colors.ink, modifier = Modifier.padding(top = 10.dp))
            Text(section.text, style = MaterialTheme.typography.bodyMedium, color = Wp.colors.ink)
            section.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Wp.colors.muted) }
        }
    }
}

/** Stem with `____` drawn as a visible blank (and read as “空格” by TalkBack). */
@Composable
private fun Stem(stem: String) {
    val c = Wp.colors
    val parts = stem.split("____")
    Text(
        buildAnnotatedString {
            parts.forEachIndexed { i, p ->
                append(p)
                if (i < parts.lastIndex) withStyle(SpanStyle(color = c.action, fontWeight = FontWeight.Bold)) { append(" ______ ") }
            }
        },
        style = WpType.english.copy(fontSize = WpType.english.fontSize * 1.1f),
        color = c.ink,
        modifier = Modifier.semantics { contentDescription = stem.replace("____", "空格") },
    )
}

private fun highlightTargets(passage: String, question: String) = buildAnnotatedString {
    val target = Regex("“(.+?)”").find(question)?.groupValues?.get(1)
    if (target == null) { append(passage); return@buildAnnotatedString }
    val idx = passage.indexOf(target, ignoreCase = true)
    if (idx < 0) { append(passage); return@buildAnnotatedString }
    append(passage.substring(0, idx))
    withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = Color(0x22007AFF))) { append(passage.substring(idx, idx + target.length)) }
    append(passage.substring(idx + target.length))
}

@Composable
internal fun Options(options: List<ChoiceOption>, fb: Feedback?, busy: Boolean, wordStyle: Boolean, onPick: (String) -> Unit) {
    val c = Wp.colors
    val keyLabels = listOf("A", "B", "C", "D", "E", "F")
    Column(Modifier.selectableGroup()) {
        options.forEachIndexed { index, o ->
            val graded = fb?.answerCorrect != null || fb?.verdict in listOf(Verdict.Correct, Verdict.AcceptedAlternative, Verdict.Incorrect, Verdict.Practice)
            val isCorrect = graded && (o.id in fb?.correctOptionIds.orEmpty() || fb?.correctOptionId == o.id)
            val isChosenWrong = graded && fb?.chosenOptionId == o.id && !isCorrect
            val border = when {
                isCorrect -> c.positive
                isChosenWrong -> c.negative
                else -> c.hairline
            }
            val bg = when {
                isCorrect -> c.positive.copy(alpha = if (c.isDark) 0.14f else 0.08f)
                isChosenWrong -> c.negative.copy(alpha = if (c.isDark) 0.14f else 0.08f)
                else -> c.sunk.copy(alpha = if (c.isDark) 0.55f else 0.35f)
            }
            val fade = fb != null && !isCorrect && !isChosenWrong
            Row(
                Modifier.fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(bg)
                    .border(if (isCorrect || isChosenWrong) 1.5.dp else 0.5.dp, border, RoundedCornerShape(16.dp))
                    .selectable(selected = fb?.chosenOptionId == o.id, enabled = fb == null && !busy, role = Role.RadioButton) { onPick(o.id) }
                    .heightIn(min = 54.dp)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .semantics {
                        if (isCorrect) stateDescription = "正确答案"
                        if (isChosenWrong) stateDescription = "你的选择，错误"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Decorative keycap cleared from semantics so merged Row text strictly preserves o.text
                Box(
                    Modifier.clearAndSetSemantics {}
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                isCorrect -> c.positive.copy(alpha = 0.2f)
                                isChosenWrong -> c.negative.copy(alpha = 0.2f)
                                else -> c.surface
                            },
                        )
                        .border(0.5.dp, c.hairline, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        keyLabels.getOrElse(index) { "${index + 1}" },
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            isCorrect -> c.positive
                            isChosenWrong -> c.negative
                            else -> c.muted
                        },
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    o.text,
                    style = if (wordStyle) WpType.wordSmall else MaterialTheme.typography.bodyLarge,
                    color = if (fade) c.muted else c.ink,
                    modifier = Modifier.weight(1f),
                )
                if (isCorrect) Icon(Icons.Outlined.CheckCircle, null, tint = c.positive)
                if (isChosenWrong) Icon(Icons.Outlined.Cancel, null, tint = c.negative)
            }
        }
    }
}

@Composable
private fun Recall(act: ActivityView.Recall, fb: Feedback?, busy: Boolean, vm: StudyViewModel) {
    val c = Wp.colors
    Text(act.instruction, style = MaterialTheme.typography.bodyMedium, color = c.muted)
    Spacer(Modifier.height(10.dp))
    Stem(act.stem)
    act.cueZh?.takeUnless { act.stem.contains(it) }?.let {
        Text("意思：$it", style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.padding(top = 10.dp))
    }
    act.letterCount?.let {
        Text("共 $it 个字母", style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 2.dp))
    }
    Spacer(Modifier.height(18.dp))
    var value by rememberSaveable(act.id.value, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(act.draft.orEmpty(), TextRange(act.draft.orEmpty().length)))
    }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(act.id) { if (fb == null) runCatching { focus.requestFocus() } }
    fun submit(explicitClick: Boolean) {
        if (busy || fb != null || value.text.isBlank()) return
        // Candidate confirmation must not submit. An explicit button click commits the visible
        // text: Latin IMEs can retain composition even after their keyboard has been hidden.
        if (!explicitClick && value.composition != null) return
        val text = value.text
        if (explicitClick) { value = value.copy(composition = null); focusManager.clearFocus() }
        vm.submit(act.id, Answer.Text(text.trim()))
    }
    OutlinedTextField(
        value = if (fb?.answerText != null) TextFieldValue(fb.answerText!!) else value,
        onValueChange = { value = it; vm.draftChanged(act.id, it.text) },
        enabled = fb == null,
        singleLine = true,
        textStyle = WpType.wordSmall,
        shape = RoundedCornerShape(16.dp),
        label = { Text("写出单词") },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit(explicitClick = false) }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    if (fb == null) {
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { submit(explicitClick = true) },
            enabled = !busy && value.text.isNotBlank(),
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text("提交") }
    }
}

@Composable
private fun Production(act: ActivityView.Production, fb: Feedback?, busy: Boolean, vm: StudyViewModel, openApi: () -> Unit) {
    val c = Wp.colors
    val focusManager = LocalFocusManager.current
    Text(act.instruction, style = MaterialTheme.typography.bodyMedium, color = c.muted)
    Spacer(Modifier.height(10.dp))
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = c.aiSoft,
        border = BorderStroke(0.5.dp, c.ai.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("目标词", style = MaterialTheme.typography.labelMedium, color = c.ai)
            MemberWords(act.targetWords, Modifier.fillMaxWidth(), style = WpType.word.copy(fontSize = 24.sp, lineHeight = 30.sp))
        }
    }
    act.intentZh?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.padding(top = 12.dp)) }
    act.sourceSentence?.let { Text(it, style = WpType.english, color = c.ink, modifier = Modifier.padding(top = 10.dp)) }
    Spacer(Modifier.height(16.dp))
    var value by rememberSaveable(act.id.value, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(act.draft.orEmpty())) }
    OutlinedTextField(
        value = if (fb?.answerText != null) TextFieldValue(fb.answerText!!) else value,
        onValueChange = { value = it; vm.draftChanged(act.id, it.text) },
        enabled = fb == null,
        minLines = 3,
        textStyle = WpType.english,
        shape = RoundedCornerShape(16.dp),
        label = { Text("你的句子") },
        supportingText = { Text(if (fb == null) "草稿会自动保存" else "已提交") },
        // Enter inserts a newline; only the button submits, so IME confirmation can't hand in the answer.
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
        modifier = Modifier.fillMaxWidth(),
    )
    if (fb == null) {
        ApiDisclosure(act.requirement, Modifier.padding(top = 4.dp), onConfigure = openApi)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val text = value.text
                value = value.copy(composition = null); focusManager.clearFocus()
                vm.submit(act.id, Answer.Text(text.trim()))
            },
            enabled = !busy && value.text.isNotBlank(),
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text("提交句子") }
    }
}

@Composable
private fun HintCard(h: Hint) {
    Panel(Modifier.padding(top = 14.dp), accentLipColor = Wp.colors.caution) {
        Text(h.text, style = MaterialTheme.typography.bodyLarge, color = Wp.colors.ink)
        Text(h.note, style = MaterialTheme.typography.bodySmall, color = Wp.colors.muted, modifier = Modifier.padding(top = 4.dp))
    }
}

// ==================================================================== feedback

@Composable
internal fun FeedbackPanel(
    fb: Feedback,
    act: ActivityView,
    review: Operation?,
    busy: Boolean,
    onContinue: () -> Unit,
    onHard: (Boolean) -> Unit,
    onRevise: () -> Unit,
    onRetryReview: (OperationId) -> Unit,
    openApi: () -> Unit,
    reinforcementRemaining: Int = 0,
) {
    val c = Wp.colors
    val (icon, tone) = when (fb.verdict) {
        Verdict.Correct, Verdict.AcceptedAlternative -> Icons.Outlined.CheckCircle to c.positive
        Verdict.Incorrect -> Icons.Outlined.Cancel to c.negative
        Verdict.PendingReview -> Icons.Outlined.HourglassTop to c.ai
        Verdict.Practice -> Icons.Outlined.School to c.caution
        Verdict.Reviewed -> Icons.Outlined.Info to c.ai
        else -> Icons.Outlined.Done to c.muted
    }
    Surface(
        modifier = Modifier.fillMaxWidth()
            .heightIn(max = 420.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = c.surface,
        border = BorderStroke(0.5.dp, tone.copy(alpha = 0.35f)),
        shadowElevation = if (c.isDark) 0.dp else 12.dp,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Wp.gutter, vertical = 14.dp)) {
            Box(
                Modifier.align(Alignment.CenterHorizontally)
                    .width(38.dp).height(4.dp).clip(CircleShape).background(c.hairline),
            )
            Spacer(Modifier.height(10.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = tone, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(fb.headline, style = MaterialTheme.typography.titleMedium, color = tone, modifier = Modifier.weight(1f))
                }
                fb.confusionNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.padding(top = 8.dp)) }
                fb.explanation?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.padding(top = 6.dp)) }
                val options = when (act) {
                    is ActivityView.Choice -> act.options
                    is ActivityView.Reading -> act.options
                    else -> emptyList()
                }
                if ((fb.verdict == Verdict.Incorrect || fb.answerCorrect == false) && fb.acceptedAnswers.isNotEmpty()) {
                    Text(
                        "可接受：" + fb.acceptedAnswers.map { answer -> options.firstOrNull { it.id == answer }?.text ?: answer }.distinct().joinToString("，"),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                if (fb.verdict == Verdict.PendingReview) {
                    Spacer(Modifier.height(10.dp))
                    if (review != null) {
                        OperationLine(review, onRetry = { onRetryReview(review.id) })
                        if (review.error?.code == ErrorCode.CredentialMissing || review.error?.code == ErrorCode.PresetMissing) {
                            TextButton(onClick = openApi) { Text("去配置 API 预设") }
                        }
                    } else Text("正在准备反馈", style = MaterialTheme.typography.bodySmall, color = c.muted)
                    Text(
                        "可以先继续下一题。回到首页，点击“表达反馈”即可查看结果或重试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.muted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                fb.aiReview?.let { AiReviewBlock(it) }

                fb.scheduleNote?.let {
                    Text(
                        (if (fb.affectsSchedule) "复习安排：" else "") + it,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.muted,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                if (fb.answerCorrect == false && reinforcementRemaining > 0) {
                    Text(
                        "本轮末尾还有 $reinforcementRemaining 项巩固，帮助你再回想一次。",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.action,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (fb.canMarkHard) {
                    FilterChip(
                        selected = fb.markedHard,
                        onClick = { onHard(!fb.markedHard) },
                        shape = CircleShape,
                        label = { Text("答对了，但有点难") },
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (fb.canRevise && act is ActivityView.Production) {
                    TextButton(onClick = onRevise) { Text("改一改再交") }
                }
            }
            Button(
                onClick = onContinue,
                enabled = !busy,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("继续") }
        }
    }
}

@Composable
internal fun AiReviewBlock(r: AiReview) {
    val c = Wp.colors
    val tone = when (r.usage) {
        "good", "equally_valid" -> c.positive
        "misused" -> c.negative
        else -> c.caution
    }
    Column(Modifier.padding(top = 10.dp)) {
        r.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.ink) }
        r.dimensions.forEach { dimension ->
            Column(Modifier.padding(top = 12.dp)) {
                Text(dimension.label, style = MaterialTheme.typography.labelLarge, color = c.ai)
                Text(
                    buildAnnotatedString {
                        dimension.quote?.let {
                            withStyle(SpanStyle(fontFamily = WpType.english.fontFamily, fontWeight = FontWeight.SemiBold)) { append("“$it” ") }
                        }
                        append(dimension.comment)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.ink,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        r.points.forEach { p ->
            Row(Modifier.padding(top = 8.dp)) {
                Box(Modifier.padding(top = 9.dp).size(5.dp).background(if (p.aboutTarget) tone else c.muted, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(
                    buildAnnotatedString {
                        p.quote?.let {
                            withStyle(SpanStyle(fontFamily = WpType.english.fontFamily, fontWeight = FontWeight.SemiBold)) { append("“$it” ") }
                        }
                        append(p.comment)
                        if (!p.aboutTarget) withStyle(SpanStyle(color = c.muted)) { append("（与目标词无关）") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.ink,
                )
            }
        }
        r.suggestion?.let {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                shape = RoundedCornerShape(16.dp),
                color = c.sunk.copy(alpha = if (c.isDark) 0.72f else 0.50f),
                border = BorderStroke(0.5.dp, c.hairline),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("AI 优化版", style = MaterialTheme.typography.labelLarge, color = c.ai)
                    Text(it, style = WpType.english.copy(fontSize = WpType.english.fontSize * 0.9f), color = c.ink, modifier = Modifier.padding(top = 5.dp))
                    r.optimizationNotes?.let { notes ->
                        Text(notes, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        SourceNote(r.source, Modifier.padding(top = 6.dp))
    }
}

// ==================================================================== chrome pieces

@Composable
private fun ProgressSegments(done: Int, total: Int, modifier: Modifier) {
    val c = Wp.colors
    val fraction = if (total > 0) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    Box(
        modifier.height(8.dp)
            .clip(CircleShape)
            .background(c.sunk)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(done.toFloat(), 0f..total.toFloat().coerceAtLeast(1f)) },
    ) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(CircleShape).background(c.action))
    }
}

@Composable
private fun BudgetNote(d: LearningDay) {
    val text = if (d.budgetState == BudgetState.Reached) "今天的预算已用完，可以随时停下" else "接近今天的 ${d.budgetMinutes} 分钟预算"
    Text(text, style = MaterialTheme.typography.bodySmall, color = Wp.colors.caution, modifier = Modifier.padding(horizontal = Wp.gutter, vertical = 2.dp))
}

@Composable
private fun SessionDone(
    s: SessionState,
    day: LearningDay?,
    onExit: () -> Unit,
    openFeedback: () -> Unit,
    tutor: @Composable () -> Unit,
    onEndDay: () -> Unit,
) {
    val c = Wp.colors
    Column(Modifier.fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(Wp.gutter)) {
        Spacer(Modifier.height(28.dp))
        CupertinoPill(text = "完成本轮", icon = Icons.Outlined.CheckCircle, tint = c.positive)
        Spacer(Modifier.height(14.dp))
        Text("这一轮完成了", style = MaterialTheme.typography.headlineSmall, color = c.ink)
        Text("共完成 ${s.done} 项练习，作答都已保存。", style = MaterialTheme.typography.bodyLarge, color = c.muted, modifier = Modifier.padding(top = 8.dp))
        day?.let {
            Spacer(Modifier.height(22.dp))
            Panel {
                KeyValue("今天累计", "${it.effectiveSeconds / 60} 分钟（预算 ${it.budgetMinutes}）")
                Hairline()
                KeyValue("今日完成", "${it.completedItems} 项")
                Hairline()
                Text(it.dailyAnalysis.label, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.padding(top = 10.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        tutor()
        TextButton(onClick = openFeedback, modifier = Modifier.fillMaxWidth()) { Text("查看表达反馈") }
        Button(onClick = onExit, shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("回到今天") }
        if (day?.canEndDay == true && day.status != DayStatus.PlanCompleted) {
            OutlinedButton(
                onClick = onEndDay,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 52.dp),
            ) { Text("结束今天") }
        }
    }
}

@Composable
private fun DisputeDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    val reasons = listOf("有不止一个合理答案", "释义或例句不对", "题目看不懂", "其他问题")
    var picked by rememberSaveable { mutableStateOf(reasons[0]) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("这题哪里有问题？") },
        text = {
            Column(Modifier.selectableGroup()) {
                reasons.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().selectable(picked == r, role = Role.RadioButton) { picked = r }.heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = picked == r, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(r)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(picked) }) { Text("提交") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
