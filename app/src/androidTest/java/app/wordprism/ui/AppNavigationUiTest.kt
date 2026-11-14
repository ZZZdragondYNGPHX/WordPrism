package app.wordprism.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import app.wordprism.MainActivity
import app.wordprism.WordPrismApp
import app.wordprism.sdk.ClientRequestId
import app.wordprism.sdk.LearningMode
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class AppNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun controlCenterBackAndSearchRecreationPreserveNavigation() {
        assumeTrue(compose.activity.packageName.endsWith(".managedtest"))
        val app = compose.activity.application as WordPrismApp
        runBlocking {
            app.sdk.adaptation.setLearningMode(LearningMode.Standard, ClientRequestId(UUID.randomUUID().toString()))
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("普通模式").fetchSemanticsNodes().isNotEmpty() }
        val hud = compose.onNodeWithContentDescription("控制中心，", substring = true)
        hud.performClick()
        compose.onNodeWithText("控制中心").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("控制中心").assertDoesNotExist()
        compose.onNodeWithText("普通模式").assertIsDisplayed()

        val libraryTab = hasText("词库") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onNode(libraryTab).performClick().assertIsSelected()
        compose.onNodeWithContentDescription("搜索单词、释义或词组").performTextInput("unmatched-query")
        compose.activityRule.scenario.recreate()
        compose.onNode(libraryTab).assertIsSelected()
        compose.onNodeWithContentDescription("搜索单词、释义或词组").assertTextEquals("unmatched-query")
        compose.onNodeWithContentDescription("清空搜索").performClick()
        compose.onNodeWithContentDescription("搜索单词、释义或词组").assertTextEquals("")
    }
}
