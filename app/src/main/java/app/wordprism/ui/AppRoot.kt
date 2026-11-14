package app.wordprism.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.wordprism.sdk.LearningDay
import app.wordprism.sdk.LearningMode
import app.wordprism.sdk.OperationStatus
import app.wordprism.ui.common.LocalSnackbar
import app.wordprism.ui.common.OfflineBanner
import app.wordprism.ui.common.appSdk
import app.wordprism.ui.dev.DevPanelScreen
import app.wordprism.ui.intake.IntakeScreen
import app.wordprism.ui.library.GroupScreen
import app.wordprism.ui.library.LibraryScreen
import app.wordprism.ui.library.WordScreen
import app.wordprism.ui.overview.AdjustmentsScreen
import app.wordprism.ui.overview.OverviewScreen
import app.wordprism.ui.settings.ApiRoutingScreen
import app.wordprism.ui.settings.DataScreen
import app.wordprism.ui.settings.PresetEditScreen
import app.wordprism.ui.settings.SettingsScreen
import app.wordprism.ui.study.StudyScreen
import app.wordprism.ui.tasks.TasksScreen
import app.wordprism.ui.theme.Wp
import app.wordprism.ui.theme.WpType
import app.wordprism.ui.today.LearningModeControl
import app.wordprism.ui.today.ManagedTodayScreen
import app.wordprism.ui.today.TodayScreen
import kotlinx.serialization.Serializable

@Serializable object TodayRoute
@Serializable object LibraryRoute
@Serializable object OverviewRoute
@Serializable object SettingsRoute
@Serializable data class StudyRoute(val sessionId: String)
@Serializable data class GroupRoute(val groupId: String)
@Serializable data class WordRoute(val wordId: String)
@Serializable data class IntakeRoute(val prefill: String = "")
@Serializable object AdjustmentsRoute
@Serializable data class PresetRoute(val presetId: String? = null)
@Serializable object ApiRoutingRoute
@Serializable object DataRoute
@Serializable data class TasksRoute(val feedbackOnly: Boolean = false)
@Serializable object DevRoute

private data class Tab(val route: Any, val label: String, val icon: ImageVector, val match: (androidx.navigation.NavDestination) -> Boolean)

private val tabs = listOf(
    Tab(TodayRoute, "今天", Icons.Outlined.WbTwilight) { it.hasRoute<TodayRoute>() },
    Tab(LibraryRoute, "词库", Icons.Outlined.CollectionsBookmark) { it.hasRoute<LibraryRoute>() },
    Tab(OverviewRoute, "总览", Icons.Outlined.Insights) { it.hasRoute<OverviewRoute>() },
    Tab(SettingsRoute, "设置", Icons.Outlined.Tune) { it.hasRoute<SettingsRoute>() },
)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val sdk = appSdk()
    val snackbar = remember { SnackbarHostState() }
    val conn by sdk.observeConnectivity().collectAsStateWithLifecycle(null)
    val managed by remember(sdk) { sdk.adaptation.observeManagedLearning() }.collectAsStateWithLifecycle(null)
    val day by remember(sdk) { sdk.study.observeLearningDay() }.collectAsStateWithLifecycle(null)
    val ops by remember(sdk) { sdk.operations.observeRecent() }.collectAsStateWithLifecycle(emptyList())
    val aiMode = managed?.mode == LearningMode.AiManaged
    val coreError by remember(sdk) { sdk.observeErrors() }.collectAsStateWithLifecycle(null)
    var hudExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(coreError) {
        val error = coreError
        if (error != null && snackbar.showSnackbar(
                error.message,
                actionLabel = "重试",
                withDismissAction = true,
                duration = SnackbarDuration.Indefinite,
            ) == SnackbarResult.ActionPerformed
        ) sdk.retryReads()
    }
    val entry by nav.currentBackStackEntryAsState()
    val dest = entry?.destination
    val onTab = dest != null && tabs.any { it.match(dest) }
    val fade = if (Wp.reducedMotion) 0 else 180

    LaunchedEffect(dest) { hudExpanded = false }
    BackHandler(enabled = hudExpanded) { hudExpanded = false }

    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        Scaffold(
            containerColor = Wp.colors.ground,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (onTab && !aiMode) {
                    SpotlightBottomDock(
                        currentDest = dest,
                        onSelectTab = { tab ->
                            hudExpanded = false
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onQuickIntake = {
                            hudExpanded = false
                            nav.navigate(IntakeRoute())
                        },
                    )
                }
            },
            contentWindowInsets = WindowInsets(0),
        ) { pad ->
            Column(
                Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
            ) {
                if (onTab) Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.safeDrawing).background(Wp.colors.ground))
                if (onTab) OfflineBanner(conn)
                if (onTab) {
                    val attentionStatuses = setOf(OperationStatus.Failed, OperationStatus.NeedsConfiguration, OperationStatus.NeedsInput, OperationStatus.RestoredPending)
                    val activeTasks = ops.count { it.status.isActive && it.status !in attentionStatuses }
                    val failedTasks = ops.count { it.status in attentionStatuses }
                    managed?.let { state ->
                        FlowRow(
                            Modifier.fillMaxWidth().padding(horizontal = Wp.gutter, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            LearningModeControl(state) {
                                hudExpanded = false
                                nav.navigate(TodayRoute) { popUpTo(nav.graph.findStartDestination().id); launchSingleTop = true }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                HudTriggerPill(
                                    day = day,
                                    activeTasks = activeTasks,
                                    failedTasks = failedTasks,
                                    expanded = hudExpanded,
                                    onToggle = { hudExpanded = !hudExpanded },
                                )
                                if (aiMode) {
                                    Spacer(Modifier.width(8.dp))
                                    if (dest?.hasRoute<TodayRoute>() == true) {
                                        Surface(
                                            shape = CircleShape,
                                            color = Wp.colors.surface,
                                            border = BorderStroke(0.5.dp, Wp.colors.hairline),
                                            modifier = Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) {
                                                hudExpanded = false
                                                nav.navigate(SettingsRoute) { launchSingleTop = true }
                                            },
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Outlined.Settings, contentDescription = "设置与更多", tint = Wp.colors.ink, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    } else {
                                        IconButton(onClick = {
                                            hudExpanded = false
                                            nav.navigate(TodayRoute) { popUpTo(nav.graph.findStartDestination().id); launchSingleTop = true }
                                        }) { Icon(Icons.Outlined.Home, contentDescription = "返回学习") }
                                    }
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = hudExpanded,
                        modifier = Modifier.heightIn(max = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * 0.5f })
                            .verticalScroll(rememberScrollState()),
                        enter = if (Wp.reducedMotion) fadeIn(tween(0)) else scaleIn(tween(180, easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)), initialScale = 0.98f, transformOrigin = TransformOrigin(0.5f, 0f)) + fadeIn(tween(180)),
                        exit = if (Wp.reducedMotion) fadeOut(tween(0)) else scaleOut(tween(140), targetScale = 0.98f, transformOrigin = TransformOrigin(0.5f, 0f)) + fadeOut(tween(140)),
                    ) {
                        ControlCenterHud(
                            day = day,
                            activeTasks = activeTasks,
                            failedTasks = failedTasks,
                            aiMode = aiMode,
                            onClose = { hudExpanded = false },
                            onOpenAdjustments = { hudExpanded = false; nav.navigate(AdjustmentsRoute) },
                            onOpenTasks = { hudExpanded = false; nav.navigate(TasksRoute()) },
                            onOpenIntake = { hudExpanded = false; nav.navigate(IntakeRoute()) },
                            onOpenOverview = { hudExpanded = false; nav.navigate(OverviewRoute) { launchSingleTop = true } },
                            onOpenSettings = { hudExpanded = false; nav.navigate(SettingsRoute) { launchSingleTop = true } },
                        )
                    }
                }

                NavHost(
                    nav, startDestination = TodayRoute, modifier = Modifier.weight(1f),
                    enterTransition = { fadeIn(tween(fade)) }, exitTransition = { fadeOut(tween(fade)) },
                ) {
                    composable<TodayRoute> {
                        if (managed == null) app.wordprism.ui.common.Loading()
                        else if (aiMode) ManagedTodayScreen(
                            managed!!,
                            openStudy = { nav.navigate(StudyRoute(it)) },
                            openIntake = { nav.navigate(IntakeRoute()) },
                            openPreset = { nav.navigate(PresetRoute(it)) },
                            openTasks = { nav.navigate(TasksRoute()) },
                            openAdjustments = { nav.navigate(AdjustmentsRoute) },
                        )
                        else TodayScreen(
                            openStudy = { nav.navigate(StudyRoute(it)) },
                            openIntake = { nav.navigate(IntakeRoute()) },
                            openAdjustments = { nav.navigate(AdjustmentsRoute) },
                            openTasks = { nav.navigate(TasksRoute()) },
                            openGroup = { nav.navigate(GroupRoute(it)) },
                            openApi = { nav.navigate(ApiRoutingRoute) },
                            openData = { nav.navigate(DataRoute) },
                            openFeedback = { nav.navigate(TasksRoute(true)) },
                        )
                    }
                    composable<LibraryRoute> {
                        LibraryScreen(
                            openGroup = { nav.navigate(GroupRoute(it)) },
                            openIntake = { nav.navigate(IntakeRoute(it)) },
                            openTasks = { nav.navigate(TasksRoute()) },
                            openWord = { nav.navigate(WordRoute(it)) },
                        )
                    }
                    composable<OverviewRoute> {
                        OverviewScreen(openAdjustments = { nav.navigate(AdjustmentsRoute) }, openGroup = { nav.navigate(GroupRoute(it)) })
                    }
                    composable<SettingsRoute> {
                        SettingsScreen(
                            openPreset = { nav.navigate(PresetRoute(it)) },
                            openRouting = { nav.navigate(ApiRoutingRoute) },
                            openData = { nav.navigate(DataRoute) },
                            openAdjustments = { nav.navigate(AdjustmentsRoute) },
                            openTasks = { nav.navigate(TasksRoute()) },
                            openDev = { nav.navigate(DevRoute) },
                        )
                    }
                    composable<StudyRoute> { e ->
                        StudyScreen(
                            e.toRoute<StudyRoute>().sessionId,
                            onExit = { nav.popBackStack() },
                            openApi = { nav.navigate(ApiRoutingRoute) },
                            openFeedback = { nav.navigate(TasksRoute(true)) },
                        )
                    }
                    composable<GroupRoute> { e ->
                        GroupScreen(
                            e.toRoute<GroupRoute>().groupId,
                            onBack = { nav.popBackStack() },
                            openApi = { nav.navigate(ApiRoutingRoute) },
                            openWord = { nav.navigate(WordRoute(it)) },
                        )
                    }
                    composable<WordRoute> { e ->
                        WordScreen(
                            e.toRoute<WordRoute>().wordId,
                            onBack = { nav.popBackStack() },
                            openApi = { nav.navigate(ApiRoutingRoute) },
                            openGroup = { nav.navigate(GroupRoute(it)) },
                            openWord = { nav.navigate(WordRoute(it)) },
                        )
                    }
                    composable<IntakeRoute> { e ->
                        IntakeScreen(
                            prefill = e.toRoute<IntakeRoute>().prefill,
                            onClose = { nav.popBackStack() },
                            openGroup = { nav.navigate(GroupRoute(it)) { popUpTo<IntakeRoute> { inclusive = true } } },
                            openApi = { nav.navigate(ApiRoutingRoute) },
                        )
                    }
                    composable<AdjustmentsRoute> { AdjustmentsScreen(onBack = { nav.popBackStack() }, openApi = { nav.navigate(ApiRoutingRoute) }) }
                    composable<PresetRoute> { e -> PresetEditScreen(e.toRoute<PresetRoute>().presetId, onBack = { nav.popBackStack() }) }
                    composable<ApiRoutingRoute> { ApiRoutingScreen(onBack = { nav.popBackStack() }, openPreset = { nav.navigate(PresetRoute(it)) }) }
                    composable<DataRoute> { DataScreen(onBack = { nav.popBackStack() }) }
                    composable<TasksRoute> { e -> TasksScreen(onBack = { nav.popBackStack() }, feedbackOnly = e.toRoute<TasksRoute>().feedbackOnly, openApi = { nav.navigate(ApiRoutingRoute) }) }
                    composable<DevRoute> { DevPanelScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }
}

@Composable
private fun HudTriggerPill(
    day: LearningDay?,
    activeTasks: Int,
    failedTasks: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val c = Wp.colors
    val minutes = ((day?.effectiveSeconds ?: 0L) / 60L).toInt()
    val budget = day?.budgetMinutes ?: 30
    val label = if (day != null) "${minutes}/${budget}′" else "学习进度"
    val dotColor = when {
        failedTasks > 0 -> c.negative
        activeTasks > 0 -> c.ai
        else -> c.positive
    }
    Surface(
        shape = CircleShape,
        color = if (expanded) c.actionSoft else c.surface,
        border = BorderStroke(0.5.dp, if (expanded) c.action.copy(alpha = 0.45f) else c.hairline),
        shadowElevation = if (c.isDark) 0.dp else 1.5.dp,
        modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onToggle)
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = "控制中心，已学 $minutes 分钟，预算 $budget 分钟，$activeTasks 项进行中，$failedTasks 项需要处理"
                stateDescription = if (expanded) "已展开" else "已收起"
            },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(dotColor, CircleShape))
            Spacer(Modifier.width(7.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium.merge(WpType.tabular),
                color = if (expanded) c.action else c.ink,
                fontWeight = FontWeight.SemiBold,
            )
            if (activeTasks > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "·$activeTasks",
                    style = MaterialTheme.typography.labelSmall.merge(WpType.tabular),
                    color = c.ai,
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                null,
                tint = if (expanded) c.action else c.muted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun ControlCenterHud(
    day: LearningDay?,
    activeTasks: Int,
    failedTasks: Int,
    aiMode: Boolean,
    onClose: () -> Unit,
    onOpenAdjustments: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenIntake: () -> Unit,
    onOpenOverview: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val c = Wp.colors
    val minutes = ((day?.effectiveSeconds ?: 0L) / 60L).toInt()
    val budget = day?.budgetMinutes ?: 30
    val frac = if (budget > 0) (minutes.toFloat() / budget.toFloat()).coerceIn(0f, 1f) else 0f
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Wp.gutter, vertical = 6.dp),
        shape = RoundedCornerShape(24.dp),
        color = c.surface,
        border = BorderStroke(0.5.dp, c.hairline),
        shadowElevation = if (c.isDark) 0.dp else 8.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("控制中心", style = MaterialTheme.typography.titleSmall, color = c.ink)
                    val progressText = if (day != null) {
                        "今日学习 $minutes / $budget 分钟 · 进度 ${day.completedItems}/${day.plannedItems}"
                    } else {
                        "快速调度、任务与参数概览"
                    }
                    Text(progressText, style = MaterialTheme.typography.bodySmall.merge(WpType.tabular), color = c.muted)
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "收起控制中心", tint = c.muted, modifier = Modifier.size(18.dp))
                }
            }

            if (day != null) {
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.sunk)) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(CircleShape).background(c.action))
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HudTile(
                    icon = Icons.Outlined.Tune,
                    tint = c.ai,
                    title = "AI 调整",
                    subtitle = "每日预算与记录",
                    onClick = onOpenAdjustments,
                    modifier = Modifier.weight(1f),
                )
                HudTile(
                    icon = Icons.Outlined.AutoAwesome,
                    tint = c.action,
                    title = "快捷添词",
                    subtitle = "输入单词或描述",
                    onClick = onOpenIntake,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HudTile(
                    icon = Icons.Outlined.HourglassEmpty,
                    tint = if (failedTasks > 0) c.negative else c.positive,
                    title = "后台任务",
                    subtitle = when {
                        failedTasks > 0 -> "$failedTasks 项需要处理"
                        activeTasks > 0 -> "$activeTasks 项进行中"
                        else -> "运行状态良好"
                    },
                    onClick = onOpenTasks,
                    modifier = Modifier.weight(1f),
                )
                HudTile(
                    icon = if (aiMode) Icons.Outlined.Settings else Icons.Outlined.Insights,
                    tint = c.muted,
                    title = if (aiMode) "API 与设置" else "数据总览",
                    subtitle = if (aiMode) "预设、模型与数据" else "记忆与易混统计",
                    onClick = if (aiMode) onOpenSettings else onOpenOverview,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HudTile(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Wp.colors
    Surface(
        modifier = modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = c.sunk.copy(alpha = if (c.isDark) 0.72f else 0.55f),
        border = BorderStroke(0.5.dp, c.hairline),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.labelLarge, color = c.ink, modifier = Modifier.weight(1f))
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun SpotlightBottomDock(
    currentDest: androidx.navigation.NavDestination?,
    onSelectTab: (Tab) -> Unit,
    onQuickIntake: () -> Unit,
) {
    val c = Wp.colors
    Box(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = c.dockGlass,
            border = BorderStroke(0.5.dp, c.hairline),
            shadowElevation = if (c.isDark) 0.dp else 10.dp,
            tonalElevation = 0.dp,
        ) {
            Row(
                Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    val selected = currentDest?.let(tab.match) == true
                    val tint = if (selected) c.action else c.muted
                    Column(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(22.dp))
                            .background(if (selected) c.actionSoft else Color.Transparent)
                            .selectable(selected = selected, role = Role.Tab) { onSelectTab(tab) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(tab.icon, null, tint = tint, modifier = Modifier.size(21.dp))
                        Spacer(Modifier.height(2.dp))
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = tint,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                Surface(
                    modifier = Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onQuickIntake)
                        .semantics { contentDescription = "快速添加易混词" },
                    shape = CircleShape,
                    color = c.ai,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = c.onAction, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
