package app.wordprism.ui.study

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.wordprism.MainActivity
import app.wordprism.sdk.*
import app.wordprism.ui.theme.WordPrismTheme
import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class TutorPanelUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun teachingReplyAndFollowupStayReadableAndRepeatedSendIsDisabled() {
        val submitted = mutableListOf<String>()
        val busy = mutableStateOf(false)
        val message = TutorMessage(
            Operation(OperationId("tutor-example"), OperationKind("tutor_session"), "随身助教", OperationStatus.Succeeded,
                createdAt = 0L, updatedAt = 0L), "coach", SessionId("session"),
            answerId = "answer", answerVersion = 1,
            message = "你这次注意到了前面的 lin，但词尾仍然容易混。linear 描述“线性的”，liner 指衬里等物品。先把词尾和词性连起来记。",
            checkQuestion = "如果描述一条线性关系，你会选哪个词？")
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent { WordPrismTheme {
                TutorSheet("example", listOf(message), canAsk = true, assisted = false, submitting = busy.value,
                    onAsk = { text, saved -> submitted += text; busy.value = true; saved() },
                    onCancel = {}, onRetry = {}, openApi = {}, onClose = {})
            } }
        }
        compose.onNodeWithText(message.message!!).assertIsDisplayed()
        compose.onNodeWithText(message.checkQuestion!!).assertExists()
        compose.onNodeWithText("换个讲法").performClick()
        compose.onNodeWithContentDescription("发送给助教").performClick()
        compose.onNodeWithContentDescription("正在提交…").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("请换一种更直观的方式讲解"), submitted) }
        compose.onNodeWithContentDescription("问助教，或说说你的理解").performTextInput("我想再看一个例子")
        compose.onNodeWithText("我想再看一个例子").assertExists()
        compose.runOnIdle { busy.value = false }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("发送给助教").assertIsDisplayed()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.externalCacheDir, "tutor-example.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
}
