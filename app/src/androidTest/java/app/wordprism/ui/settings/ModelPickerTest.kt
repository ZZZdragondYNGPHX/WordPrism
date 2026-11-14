package app.wordprism.ui.settings

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import app.wordprism.MainActivity
import app.wordprism.sdk.ApiModel
import app.wordprism.ui.theme.WordPrismTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real Compose on Android, with an explicitly synthetic catalog; no provider or learning-data writes. */
class ModelPickerTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val models = (1..60).map { ApiModel("synthetic-model-${it.toString().padStart(2, '0')}", "synthetic-provider") }

    @Test fun searchAndSelectionReturnTheExactProviderId() {
        var selected = "manual-model"
        val showing = mutableStateOf(true)
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                WordPrismTheme {
                    if (showing.value) ModelPickerDialog(models, selected, onSelect = { selected = it; showing.value = false }, onDismiss = { showing.value = false })
                }
            }
        }
        compose.onNodeWithText("60 / 60 个模型").assertIsDisplayed()
        compose.onNodeWithText("搜索模型").performTextInput("model-42")
        compose.onNodeWithText("1 / 60 个模型").assertIsDisplayed()
        compose.onNodeWithText("synthetic-model-42").assertIsDisplayed()
        saveScreenshot("model-picker-search")
        compose.onNodeWithText("synthetic-model-42").performClick()
        compose.runOnIdle { assertEquals("synthetic-model-42", selected) }
        compose.onNodeWithText("选择模型").assertDoesNotExist()
    }

    @Test fun emptySearchAndDismissKeepTheManualModel() {
        var selected = "manual-model"
        val showing = mutableStateOf(true)
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                WordPrismTheme {
                    if (showing.value) ModelPickerDialog(models, selected, onSelect = { selected = it }, onDismiss = { showing.value = false })
                }
            }
        }
        compose.onNodeWithText("搜索模型").performTextInput("no-such-model")
        compose.onNodeWithText("没有匹配的模型").assertIsDisplayed()
        saveScreenshot("model-picker-no-match")
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle { assertEquals("manual-model", selected) }
        compose.onNodeWithText("选择模型").assertDoesNotExist()
    }

    private fun saveScreenshot(name: String) {
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        val directory = File(compose.activity.filesDir, "model-picker-evidence").also { it.mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
