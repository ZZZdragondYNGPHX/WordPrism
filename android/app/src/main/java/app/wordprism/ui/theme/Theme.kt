package app.wordprism.ui.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wordprism.R

/**
 * WordPrism visual system.
 *
 * Idea: the app separates words that look alike, the way a prism separates light.
 *  - Ground and ink are cool and quiet (frosted lilac-grey, indigo ink).
 *  - The spectrum is NOT decoration: it is reserved for member identity inside a confusion
 *    group (linear = vermilion, liner = amber, …), consistently across library, study and overview.
 *  - English is set in Atkinson Hyperlegible Next / Mono, a face designed so that similar letters
 *    are distinguishable; Chinese UI uses the system CJK sans.
 */
@Immutable
data class WpColors(
    val ground: Color,
    val surface: Color,
    val sunk: Color,
    val ink: Color,
    val muted: Color,
    val hairline: Color,
    val action: Color,
    val onAction: Color,
    val actionSoft: Color,
    val positive: Color,
    val negative: Color,
    val caution: Color,
    val spectrum: List<Color>,
    val isDark: Boolean,
) {
    fun member(index: Int): Color = spectrum[index.mod(spectrum.size)]
}

private val Light = WpColors(
    ground = Color(0xFFF1F0F6), surface = Color(0xFFFBFAFE), sunk = Color(0xFFE7E5F0),
    ink = Color(0xFF23244A), muted = Color(0xFF636283), hairline = Color(0xFFDCDAE6),
    action = Color(0xFF3A3FC8), onAction = Color.White, actionSoft = Color(0xFFE2E2FA),
    positive = Color(0xFF1E7A4C), negative = Color(0xFFB8361F), caution = Color(0xFF8A5A00),
    spectrum = listOf(Color(0xFFC93A22), Color(0xFF9A6100), Color(0xFF1C8150), Color(0xFF2360C4), Color(0xFF7840C0)),
    isDark = false,
)

private val Dark = WpColors(
    ground = Color(0xFF14141F), surface = Color(0xFF1C1C2A), sunk = Color(0xFF262638),
    ink = Color(0xFFE9E8F4), muted = Color(0xFFA6A5BF), hairline = Color(0xFF34344B),
    action = Color(0xFFA3A6FF), onAction = Color(0xFF14141F), actionSoft = Color(0xFF2C2D52),
    positive = Color(0xFF6BD6A0), negative = Color(0xFFFF8F7A), caution = Color(0xFFF2BE5C),
    spectrum = listOf(Color(0xFFFF8A73), Color(0xFFF2B84B), Color(0xFF5FD39A), Color(0xFF7AA8FF), Color(0xFFC3A0FF)),
    isDark = true,
)

val Atkinson = FontFamily(
    Font(R.font.atkinson_next, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.atkinson_next, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.atkinson_next, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(620))),
    Font(R.font.atkinson_next, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(720))),
)
val AtkinsonMono = FontFamily(
    Font(R.font.atkinson_mono, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.atkinson_mono, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(640))),
    Font(R.font.atkinson_mono, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(760))),
)

/** Type roles specific to this app on top of Material's scale. */
object WpType {
    /** An English headword (study prompts, group titles). */
    val word = TextStyle(fontFamily = Atkinson, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = 0.2.sp)
    val wordSmall = TextStyle(fontFamily = Atkinson, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 24.sp)
    /** English running text: examples, passages, options. */
    val english = TextStyle(fontFamily = Atkinson, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 28.sp)
    /** Letter-aligned comparison strip. */
    val prism = TextStyle(fontFamily = AtkinsonMono, fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 40.sp)
    val figure = TextStyle(fontFamily = Atkinson, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 38.sp, fontFeatureSettings = "tnum")
    val tabular = TextStyle(fontFamily = Atkinson, fontFeatureSettings = "tnum")
}

private fun typography(): Typography {
    val base = Typography()
    return base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 26.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 22.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp),
    )
}

val LocalWpColors = staticCompositionLocalOf { Light }
/** True when the user disabled system animations; motion should then be instant. */
val LocalReducedMotion = staticCompositionLocalOf { false }

object Wp {
    val colors: WpColors @Composable @ReadOnlyComposable get() = LocalWpColors.current
    val reducedMotion: Boolean @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
    val gutter = 20.dp
}

@Composable
fun WordPrismTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) Dark else Light
    val context = LocalContext.current
    val reduced = remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.action, onPrimary = c.onAction, primaryContainer = c.actionSoft, onPrimaryContainer = c.ink,
        secondary = c.ink, onSecondary = c.surface, secondaryContainer = c.sunk, onSecondaryContainer = c.ink,
        background = c.ground, onBackground = c.ink, surface = c.ground, onSurface = c.ink,
        surfaceVariant = c.sunk, onSurfaceVariant = c.muted, surfaceContainerLowest = c.surface,
        surfaceContainerLow = c.surface, surfaceContainer = c.surface, surfaceContainerHigh = c.surface, surfaceContainerHighest = c.sunk,
        outline = c.muted, outlineVariant = c.hairline, error = c.negative, onError = c.onAction,
        inverseSurface = c.ink, inverseOnSurface = c.ground, inversePrimary = c.actionSoft,
    )
    CompositionLocalProvider(LocalWpColors provides c, LocalReducedMotion provides reduced) {
        MaterialTheme(colorScheme = scheme, typography = typography(), shapes = Shapes(
            small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        ), content = content)
    }
}
