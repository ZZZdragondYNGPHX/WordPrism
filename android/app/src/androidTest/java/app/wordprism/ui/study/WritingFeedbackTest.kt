package app.wordprism.ui.study

import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import app.wordprism.MainActivity
import app.wordprism.sdk.*
import app.wordprism.ui.theme.WordPrismTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic feedback exercises the real scrollable result panel on Android. */
class WritingFeedbackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun dimensionsAndOptimizedSentenceRemainReadableAndActionsReachable() {
        var continued = 0
        val review = AiReview("good", "用法良好", emptyList(), "The relationship is linear.", ContentSource("ai_generated", model = "synthetic-model"),
            summary = "句意清楚，目标词贴合语境。",
            dimensions = listOf(
                ReviewDimension("目标词与搭配", "linear 适合描述这种关系。", "is linear"),
                ReviewDimension("语法与结构", "主语与系动词搭配正确。"),
                ReviewDimension("自然度与语气", "句子自然直接。"),
                ReviewDimension("意图表达", "准确表达了线性关系。"),
            ), optimizationNotes = "用 relationship 突出关系。")
        val feedback = Feedback("answer", 1, "The graph is linear.", Verdict.Reviewed, "AI 表达反馈", null,
            affectsSchedule = false, scheduleNote = "不直接改变记忆评分", aiReview = review, canRevise = true)
        val activity = ActivityView.Production(ActivityId("activity"), "造句", "请造句", "线性关系", null,
            listOf("linear"), null, ApiRequirement(FeatureId.WritingFeedback, true, null, null, ApiReadiness.Ready), false)
        compose.activityRule.scenario.onActivity { activityHost ->
            activityHost.setContent {
                WordPrismTheme {
                    FeedbackPanel(feedback, activity, null, false, onContinue = { continued++ }, onHard = {},
                        onRevise = {}, onRetryReview = {}, openApi = {})
                }
            }
        }
        compose.onNodeWithText("AI 表达反馈").assertIsDisplayed()
        compose.onNodeWithText("目标词与搭配").assertIsDisplayed()
        saveScreenshot("writing-feedback-top")
        compose.onNodeWithText("意图表达").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("AI 优化版").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("The relationship is linear.").assertIsDisplayed()
        saveScreenshot("writing-feedback-optimized")
        compose.onNodeWithText("改一改再交").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("继续").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, continued) }
    }

    private fun saveScreenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(compose.activity.filesDir, "writing-feedback-evidence").also { it.mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
