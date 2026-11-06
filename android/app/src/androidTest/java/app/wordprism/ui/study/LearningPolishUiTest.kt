package app.wordprism.ui.study

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.wordprism.MainActivity
import app.wordprism.sdk.*
import app.wordprism.ui.theme.WordPrismTheme
import app.wordprism.ui.today.EmptyToday
import app.wordprism.ui.common.PrismRow
import app.wordprism.ui.common.PrismStrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LearningPolishUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun acceptedAlternativeIsNeverPaintedAsWrongAndDisputedAnswerHasNoGrade() {
        val options = listOf(ChoiceOption("a", "first"), ChoiceOption("b", "second"), ChoiceOption("c", "wrong"))
        val feedback = Feedback("answer", 1, null, Verdict.Correct, "回答正确", null,
            correctOptionId = "a", chosenOptionId = "b", affectsSchedule = true, scheduleNote = null,
            correctOptionIds = listOf("a", "b"), answerCorrect = true)
        compose.activityRule.scenario.onActivity { it.setContent { WordPrismTheme { Options(options, feedback, false, true) {} } } }
        compose.onNodeWithText("second").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "正确答案"))
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "你的选择，错误")).assertCountEquals(0)
        compose.activityRule.scenario.onActivity { it.setContent { WordPrismTheme { Options(options, feedback.copy(verdict = Verdict.Recorded, answerCorrect = null), false, true) {} } } }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)).assertCountEquals(0)
    }

    @Test fun firstRunOffersWorkingIntakeAndImportWithoutEmptyStudyAction() {
        var intake = 0; var imports = 0
        compose.activityRule.scenario.onActivity { it.setContent { WordPrismTheme { EmptyToday({ intake++ }, { imports++ }) } } }
        compose.onNodeWithText("添加我的第一组词").assertIsDisplayed().performClick()
        compose.onNodeWithText("导入已有词库").assertIsDisplayed().performClick()
        compose.onNodeWithText("开始今天的学习").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, intake); assertEquals(1, imports) }
    }

    @Test fun longFeedbackKeepsContinueVisibleWithNarrowLayoutAndLargeFont() {
        var continued = 0
        val feedback = Feedback("answer", 1, "linear", Verdict.Practice, "再看清这个区别", "解释。".repeat(160),
            affectsSchedule = false, scheduleNote = "辅助练习不计作独立回忆", answerCorrect = false)
        compose.activityRule.scenario.onActivity { it.setContent {
            WordPrismTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    Box(Modifier.width(320.dp)) {
                        FeedbackPanel(feedback, ActivityView.Unknown(ActivityId("a"), "练习", ""), null, false,
                            { continued++ }, {}, {}, {}, {})
                    }
                }
            }
        } }
        compose.onNodeWithText("继续").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, continued) }
    }

    @Test fun longWordsKeepSingleLineAndGlossMovesBelowOnNarrowScreen() {
        val word = "electroencephalographically"
        compose.activityRule.scenario.onActivity { it.setContent {
            WordPrismTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    Box(Modifier.width(320.dp).padding(20.dp)) {
                        PrismStrip(listOf(PrismRow(word, null, emptyList(), "脑电描记法的（合成布局样本）")))
                    }
                }
            }
        } }
        val layouts = mutableListOf<TextLayoutResult>()
        val wordNode = compose.onNodeWithText(word, useUnmergedTree = true)
        wordNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        val gloss = compose.onNodeWithText("脑电描记法的（合成布局样本）", useUnmergedTree = true)
        gloss.assertIsDisplayed()
        assertTrue(gloss.getUnclippedBoundsInRoot().top >= wordNode.getUnclippedBoundsInRoot().bottom)
        compose.onAllNodes(hasScrollAction(), useUnmergedTree = true).onFirst().performTouchInput { swipeLeft() }
        gloss.assertIsDisplayed()
    }
}
