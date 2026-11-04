package app.wordprism

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.wordprism.ui.AppRoot
import app.wordprism.ui.theme.WordPrismTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            WordPrismTheme {
                AppRoot()
            }
        }
    }
}
