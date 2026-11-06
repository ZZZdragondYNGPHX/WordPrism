package app.wordprism.ui.today

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import app.wordprism.MainActivity
import app.wordprism.WordPrismApp
import app.wordprism.sdk.LearningMode
import app.wordprism.sdk.ClientRequestId
import dev.wordprism.android.AndroidWordPrism
import dev.wordprism.contract.Command
import dev.wordprism.contract.GroupSeed
import dev.wordprism.contract.Relation
import dev.wordprism.contract.Request
import dev.wordprism.contract.WordSeed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Run against an isolated applicationId; never modify a user's installed learning library. */
class ManagedLearningUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun modeSwitchConfigurationStudyAndRecreationUseTheSameRealLibrary() {
        assumeTrue(compose.activity.packageName.endsWith(".managedtest"))
        val app = compose.activity.application as WordPrismApp
        runBlocking {
            app.sdk.adaptation.setLearningMode(LearningMode.Standard, ClientRequestId(UUID.randomUUID().toString()))
            val core = AndroidWordPrism.open(app)
            assertNull(core.execute(Request(UUID.randomUUID().toString(), Command.SaveWords(
                listOf(WordSeed("linear", "线性的", "adjective"), WordSeed("liner", "衬里", "noun")),
                GroupSeed("linear / liner", Relation.LOOK_ALIKE, "区分形似词")))).fault)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("普通模式").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("普通模式").performClick()
        compose.onNodeWithText("AI 托管模式").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("今天的背诵").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("添加新单词组").assertIsDisplayed()
        compose.onNodeWithText("词库").assertDoesNotExist()
        compose.onNodeWithText("配置 API").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("API 密钥").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("API 密钥").assertExists()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        val studyAction = hasText("开始背诵") or hasText("继续背诵")
        compose.waitUntil(10_000) { compose.onAllNodes(studyAction).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(studyAction).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("暂停并退出（进度已保存）").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("问助教").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("问助教").performClick()
        compose.onNodeWithContentDescription("问助教，或说说你的理解").performTextInput("请只给一个提示")
        compose.onNodeWithContentDescription("发送给助教").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("你：请只给一个提示").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(runBlocking { app.sdk.study.observeTutor().first() }.any { it.question == "请只给一个提示" })
        compose.onNodeWithContentDescription("关闭助教").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("关闭助教").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("认识").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("认识").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("暂停并退出（进度已保存）").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("继续背诵").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(runBlocking { app.sdk.study.observeTutor().first() }.none { it.moment != "question" })
        compose.onNodeWithText("本轮助教总结").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("关闭助教").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, runBlocking { app.sdk.study.observeTutor().first() }.count { it.moment == "recap" })
        compose.onNodeWithContentDescription("关闭助教").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("关闭助教").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("本轮助教总结").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("关闭助教").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, runBlocking { app.sdk.study.observeTutor().first() }.count { it.moment == "recap" })
        compose.onNodeWithContentDescription("关闭助教").performClick()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("AI 托管模式").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(LearningMode.AiManaged, runBlocking { app.sdk.adaptation.observeManagedLearning().first().mode })
        compose.onNodeWithText("AI 托管模式").performClick()
        compose.onNodeWithText("普通模式").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("词库").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("词库").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("linear", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("linear", substring = true).onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("liner", substring = true).onFirst().assertIsDisplayed()
    }
}
