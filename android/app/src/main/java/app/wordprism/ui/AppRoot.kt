package app.wordprism.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
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
@Serializable object TasksRoute
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
    val coreError by remember(sdk) { sdk.observeErrors() }.collectAsStateWithLifecycle(null)
    LaunchedEffect(coreError) {
        val error = coreError
        if (error != null && snackbar.showSnackbar(error.message, actionLabel = "重试", withDismissAction = true,
                duration = SnackbarDuration.Indefinite) == SnackbarResult.ActionPerformed) sdk.retryReads()
    }
    val entry by nav.currentBackStackEntryAsState()
    val dest = entry?.destination
    val onTab = dest != null && tabs.any { it.match(dest) }
    val fade = if (Wp.reducedMotion) 0 else 180

    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        Scaffold(
            containerColor = Wp.colors.ground,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (onTab) NavigationBar(containerColor = Wp.colors.surface, tonalElevation = 0.dp()) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = dest?.let(tab.match) == true,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true; restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, null) }, label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = Wp.colors.actionSoft),
                        )
                    }
                }
            },
            contentWindowInsets = WindowInsets(0),
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                if (onTab) Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.safeDrawing).background(Wp.colors.ground))
                if (onTab) OfflineBanner(conn)
                NavHost(
                    nav, startDestination = TodayRoute, modifier = Modifier.weight(1f),
                    enterTransition = { fadeIn(tween(fade)) }, exitTransition = { fadeOut(tween(fade)) },
                ) {
                    composable<TodayRoute> {
                        TodayScreen(
                            openStudy = { nav.navigate(StudyRoute(it)) }, openIntake = { nav.navigate(IntakeRoute()) },
                            openAdjustments = { nav.navigate(AdjustmentsRoute) }, openTasks = { nav.navigate(TasksRoute) },
                            openGroup = { nav.navigate(GroupRoute(it)) }, openApi = { nav.navigate(ApiRoutingRoute) },
                        )
                    }
                    composable<LibraryRoute> {
                        LibraryScreen(openGroup = { nav.navigate(GroupRoute(it)) }, openIntake = { nav.navigate(IntakeRoute(it)) }, openTasks = { nav.navigate(TasksRoute) }, openWord = { nav.navigate(WordRoute(it)) })
                    }
                    composable<OverviewRoute> {
                        OverviewScreen(openAdjustments = { nav.navigate(AdjustmentsRoute) }, openGroup = { nav.navigate(GroupRoute(it)) })
                    }
                    composable<SettingsRoute> {
                        SettingsScreen(
                            openPreset = { nav.navigate(PresetRoute(it)) }, openRouting = { nav.navigate(ApiRoutingRoute) },
                            openData = { nav.navigate(DataRoute) }, openAdjustments = { nav.navigate(AdjustmentsRoute) },
                            openTasks = { nav.navigate(TasksRoute) }, openDev = { nav.navigate(DevRoute) },
                        )
                    }
                    composable<StudyRoute> { e ->
                        StudyScreen(e.toRoute<StudyRoute>().sessionId, onExit = { nav.popBackStack() }, openApi = { nav.navigate(ApiRoutingRoute) })
                    }
                    composable<GroupRoute> { e ->
                        GroupScreen(e.toRoute<GroupRoute>().groupId, onBack = { nav.popBackStack() }, openApi = { nav.navigate(ApiRoutingRoute) }, openWord = { nav.navigate(WordRoute(it)) })
                    }
                    composable<WordRoute> { e -> WordScreen(e.toRoute<WordRoute>().wordId, onBack = { nav.popBackStack() },
                        openApi = { nav.navigate(ApiRoutingRoute) }, openGroup = { nav.navigate(GroupRoute(it)) }, openWord = { nav.navigate(WordRoute(it)) }) }
                    composable<IntakeRoute> { e ->
                        IntakeScreen(
                            prefill = e.toRoute<IntakeRoute>().prefill, onClose = { nav.popBackStack() },
                            openGroup = { nav.navigate(GroupRoute(it)) { popUpTo<IntakeRoute> { inclusive = true } } },
                            openApi = { nav.navigate(ApiRoutingRoute) },
                        )
                    }
                    composable<AdjustmentsRoute> { AdjustmentsScreen(onBack = { nav.popBackStack() }, openApi = { nav.navigate(ApiRoutingRoute) }) }
                    composable<PresetRoute> { e -> PresetEditScreen(e.toRoute<PresetRoute>().presetId, onBack = { nav.popBackStack() }) }
                    composable<ApiRoutingRoute> { ApiRoutingScreen(onBack = { nav.popBackStack() }, openPreset = { nav.navigate(PresetRoute(it)) }) }
                    composable<DataRoute> { DataScreen(onBack = { nav.popBackStack() }) }
                    composable<TasksRoute> { TasksScreen(onBack = { nav.popBackStack() }) }
                    composable<DevRoute> { DevPanelScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())
