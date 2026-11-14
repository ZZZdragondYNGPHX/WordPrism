package app.wordprism.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * WordPrism visual system — Proposal A (Cupertino Pure · Spatial Deck & Spotlight).
 *
 * Idea: the app separates words that look alike, the way a prism separates light.
 *  - Ground and surface follow Apple HIG systemGroupedBackground / secondarySystemGroupedBackground
 *    (`#F2F2F7` / `#FFFFFF` in light; `#000000` / `#1C1C1E` in OLED dark).
 *  - Contrast-adjusted System Blue (`#005FCC` / `#409CFF`) anchors primary user actions, while System Indigo
 *    (`#504DC6` / `#A6A3FF`) identifies AI Tutor, AI scheduling, and adaptive intelligence.
 *  - The spectrum is reserved strictly for member identity inside a confusion group
 *    consistently across library, Wallet card stacks, optical PrismStrip, and overview.
 *  - English is set in Atkinson Hyperlegible Next / Mono so look-alike glyphs remain distinct;
 *    Chinese UI uses the system CJK sans.
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
    val ai: Color = if (isDark) Color(0xFFA6A3FF) else Color(0xFF504DC6),
    val aiSoft: Color = if (isDark) Color(0x2EA6A3FF) else Color(0x1F504DC6),
    val elevated: Color = surface,
    val dockGlass: Color = if (isDark) Color(0xEC1C1C1E) else Color(0xF0FFFFFF),
    val subtleShadow: Color = if (isDark) Color(0x52000000) else Color(0x12000000),
) {
    fun member(index: Int): Color = spectrum[index.mod(spectrum.size)]
}

private val Light = WpColors(
    ground = Color(0xFFF2F2F7),
    surface = Color(0xFFFFFFFF),
    sunk = Color(0xFFE5E5EA),
    ink = Color(0xFF1C1C1E),
    muted = Color(0xFF6E6E73),
    hairline = Color(0x1F3C3C43),
    action = Color(0xFF005FCC),
    onAction = Color.White,
    actionSoft = Color(0x1F007AFF),
    positive = Color(0xFF167230),
    negative = Color(0xFFB92319),
    caution = Color(0xFF965200),
    spectrum = listOf(
        Color(0xFFB92319),
        Color(0xFF965200),
        Color(0xFF167230),
        Color(0xFF005FCC),
        Color(0xFF8940B0),
    ),
    isDark = false,
    ai = Color(0xFF504DC6),
    aiSoft = Color(0x1F504DC6),
    elevated = Color(0xFFFFFFFF),
    dockGlass = Color(0xF0FFFFFF),
    subtleShadow = Color(0x12000000),
)

private val Dark = WpColors(
    ground = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    sunk = Color(0xFF2C2C2E),
    ink = Color(0xFFF5F5F7),
    muted = Color(0xFF98989D),
    hairline = Color(0x24FFFFFF),
    action = Color(0xFF409CFF),
    onAction = Color(0xFF001B36),
    actionSoft = Color(0x33409CFF),
    positive = Color(0xFF30D158),
    negative = Color(0xFFFF6961),
    caution = Color(0xFFFF9F0A),
    spectrum = listOf(
        Color(0xFFFF6961),
        Color(0xFFFFB340),
        Color(0xFF30D158),
        Color(0xFF409CFF),
        Color(0xFFBF5AF2),
    ),
    isDark = true,
    ai = Color(0xFFA6A3FF),
    aiSoft = Color(0x2EA6A3FF),
    elevated = Color(0xFF242426),
    dockGlass = Color(0xEE1C1C1E),
    subtleShadow = Color(0x52000000),
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
    val word = TextStyle(
        fontFamily = Atkinson,
        fontWeight = FontWeight.Bold,
        fontSize = 31.sp,
        lineHeight = 37.sp,
        letterSpacing = (-0.5).sp,
    )
    val wordSmall = TextStyle(
        fontFamily = Atkinson,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.2).sp,
    )
    /** English running text: examples, passages, options. */
    val english = TextStyle(
        fontFamily = Atkinson,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 27.sp,
    )
    /** Letter-aligned comparison strip inside the optical Prism chamber. */
    val prism = TextStyle(
        fontFamily = AtkinsonMono,
        fontWeight = FontWeight.Medium,
        fontSize = 29.sp,
        lineHeight = 39.sp,
    )
    val figure = TextStyle(
        fontFamily = Atkinson,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.8).sp,
        fontFeatureSettings = "tnum",
    )
    val tabular = TextStyle(
        fontFamily = Atkinson,
        fontFeatureSettings = "tnum",
    )
}

private fun typography(): Typography {
    val base = Typography()
    return base.copy(
        headlineMedium = base.headlineMedium.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            lineHeight = 34.sp,
            letterSpacing = (-0.6).sp,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            lineHeight = 31.sp,
            letterSpacing = (-0.5).sp,
        ),
        titleLarge = base.titleLarge.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            lineHeight = 27.sp,
            letterSpacing = (-0.3).sp,
        ),
        titleMedium = base.titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 23.sp,
            letterSpacing = (-0.15).sp,
        ),
        titleSmall = base.titleSmall.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 25.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
        labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium, fontSize = 11.sp),
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
    val resolver = context.contentResolver
    fun animationsDisabled() = runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)
    var reduced by remember(resolver) { mutableStateOf(animationsDisabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = animationsDisabled() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        reduced = animationsDisabled()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.action, onPrimary = c.onAction, primaryContainer = c.actionSoft, onPrimaryContainer = c.ink,
        secondary = c.ai, onSecondary = c.onAction, secondaryContainer = c.aiSoft, onSecondaryContainer = c.ink,
        background = c.ground, onBackground = c.ink, surface = c.ground, onSurface = c.ink,
        surfaceVariant = c.sunk, onSurfaceVariant = c.muted, surfaceContainerLowest = c.surface,
        surfaceContainerLow = c.surface, surfaceContainer = c.surface, surfaceContainerHigh = c.elevated, surfaceContainerHighest = c.sunk,
        outline = c.muted, outlineVariant = c.hairline, error = c.negative, onError = c.onAction,
        inverseSurface = c.ink, inverseOnSurface = c.ground, inversePrimary = c.actionSoft,
    )
    CompositionLocalProvider(LocalWpColors provides c, LocalReducedMotion provides reduced) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography(),
            shapes = Shapes(
                small = RoundedCornerShape(10.dp),
                medium = RoundedCornerShape(18.dp),
                large = RoundedCornerShape(24.dp),
                extraLarge = RoundedCornerShape(30.dp),
            ),
            content = content,
        )
    }
}
