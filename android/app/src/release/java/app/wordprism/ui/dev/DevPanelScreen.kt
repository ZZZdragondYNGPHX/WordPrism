package app.wordprism.ui.dev

import androidx.compose.runtime.Composable
import app.wordprism.ui.common.WpTopBar

/** Release has no mock implementation or demo data. The route is not exposed. */
@Composable
fun DevPanelScreen(onBack: () -> Unit) { WpTopBar("本机学习", onBack = onBack) }
