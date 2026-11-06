package app.wordprism.ui.today

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.wordprism.MainActivity
import app.wordprism.sdk.FocusItem
import app.wordprism.sdk.GroupId
import app.wordprism.ui.theme.WordPrismTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class FocusRowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun longExplanationDoesNotSqueezeWordsOnNarrowScreenWithLargeFont() {
        val explanation = "并列辨析拼写相近、含义不同的词：complement 表示补充、补足或相配；compliment 表示赞美、恭维。"
        var opened: String? = null
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                WordPrismTheme {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                        Surface(Modifier.width(320.dp)) {
                            Column(Modifier.padding(20.dp)) {
                                FocusRow(FocusItem("complement / compliment", explanation, GroupId("comparison"))) { opened = it }
                            }
                        }
                    }
                }
            }
        }
        val words = compose.onNodeWithText("complement  /  compliment", useUnmergedTree = true)
        val reason = compose.onNodeWithText(explanation, useUnmergedTree = true)
        words.assertIsDisplayed(); reason.assertIsDisplayed()
        val wordBounds = words.getUnclippedBoundsInRoot()
        val reasonBounds = reason.getUnclippedBoundsInRoot()
        assertTrue("Word pair should use at most a few readable lines", wordBounds.bottom - wordBounds.top < 120.dp)
        assertTrue("Explanation belongs below the words", reasonBounds.top >= wordBounds.bottom)
        compose.onNodeWithText(explanation).performClick()
        compose.runOnIdle { assertEquals("comparison", opened) }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(compose.activity.filesDir, "daily-planning-evidence").also { it.mkdirs() }
        File(directory, "focus-large-font.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
