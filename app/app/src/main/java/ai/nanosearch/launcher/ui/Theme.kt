@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ai.nanosearch.launcher.ui

import ai.nanosearch.launcher.AppSettings
import android.os.Build
import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Violet = Color(0xFF6B4EE6)
private val LightColors = lightColorScheme(primary = Violet, secondary = Color(0xFF5E5C71), tertiary = Color(0xFF00897B))
private val DarkColors = darkColorScheme(primary = Color(0xFFCBBEFF), secondary = Color(0xFFC9C3DC), tertiary = Color(0xFF6FD9C8))

// Classic: charcoal and white, one blue accent, no tint from the wallpaper.
private val Blue = Color(0xFF00A3E0)
private val ClassicDark = darkColorScheme(
    primary = Blue, onPrimary = Color.White, primaryContainer = Color(0xFF0B3D55), onPrimaryContainer = Color(0xFFBFE8FA),
    secondary = Color(0xFF9FB0BA), onSecondary = Color(0xFF10181C), secondaryContainer = Color(0xFF2A3338), onSecondaryContainer = Color(0xFFDDE6EA),
    tertiary = Color(0xFF7BC96F), onTertiary = Color(0xFF0C1A0A), tertiaryContainer = Color(0xFF26391F), onTertiaryContainer = Color(0xFFD3F0CB),
    background = Color(0xFF111213), onBackground = Color(0xFFF1F3F4), surface = Color(0xFF151617), onSurface = Color(0xFFF1F3F4),
    surfaceVariant = Color(0xFF2A2D2F), onSurfaceVariant = Color(0xFFA6ADB2), outline = Color(0xFF5B6368), outlineVariant = Color(0xFF34393C),
    surfaceContainerLowest = Color(0xFF0D0E0F), surfaceContainerLow = Color(0xFF171819), surfaceContainer = Color(0xFF1C1E1F),
    surfaceContainerHigh = Color(0xFF232627), surfaceContainerHighest = Color(0xFF2C3032),
)
private val ClassicLight = lightColorScheme(
    primary = Color(0xFF0088BF), onPrimary = Color.White, primaryContainer = Color(0xFFD3EEFA), onPrimaryContainer = Color(0xFF063447),
    secondary = Color(0xFF55646D), onSecondary = Color.White, secondaryContainer = Color(0xFFE3E9EC), onSecondaryContainer = Color(0xFF1B262C),
    tertiary = Color(0xFF3E8E35), onTertiary = Color.White, tertiaryContainer = Color(0xFFD9F0D5), onTertiaryContainer = Color(0xFF0B2A07),
    background = Color(0xFFF2F3F4), onBackground = Color(0xFF1B1D1F), surface = Color(0xFFF7F8F8), onSurface = Color(0xFF1B1D1F),
    surfaceVariant = Color(0xFFE2E5E7), onSurfaceVariant = Color(0xFF5A6268), outline = Color(0xFF8A9298), outlineVariant = Color(0xFFD0D4D7),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F2F3), surfaceContainer = Color(0xFFEBEDEE),
    surfaceContainerHigh = Color(0xFFE5E8E9), surfaceContainerHighest = Color(0xFFDFE2E4),
)

/** True when the user chose dark, or chose "system" and the system is dark. */
fun isDark(context: Context): Boolean = when (AppSettings.themeMode(context)) {
    "light" -> false
    "dark" -> true
    else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}

/** The colours for the user's choices: wallpaper colours (Material You) or the Nano Search violet, light or dark. */
fun nanoColorScheme(context: Context): ColorScheme {
    val dark = isDark(context)
    return when {
        AppSettings.isClassic(context) -> if (dark) ClassicDark else ClassicLight
        AppSettings.dynamicColor(context) && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
}

/** Classic text: Source Sans 3 at each weight. */
private fun classicTypography(context: Context): Typography {
    fun f(w: Int) = Font("SourceSans3.ttf", context.assets, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    val family = FontFamily(f(300), f(400), f(500), f(600), f(700))
    val base = Typography()
    fun TextStyle.sans() = copy(fontFamily = family)
    return Typography(
        displayLarge = base.displayLarge.sans(), displayMedium = base.displayMedium.sans(), displaySmall = base.displaySmall.sans(),
        headlineLarge = base.headlineLarge.sans(), headlineMedium = base.headlineMedium.sans(), headlineSmall = base.headlineSmall.sans(),
        titleLarge = base.titleLarge.sans(), titleMedium = base.titleMedium.sans(), titleSmall = base.titleSmall.sans(),
        bodyLarge = base.bodyLarge.sans(), bodyMedium = base.bodyMedium.sans(), bodySmall = base.bodySmall.sans(),
        labelLarge = base.labelLarge.sans(), labelMedium = base.labelMedium.sans(), labelSmall = base.labelSmall.sans(),
    )
}

/** Material 3 Expressive: springy motion, colours from the wallpaper (or the Nano Search violet), light, dark or system. In Classic, flat colours and its own typeface. */
@Composable
fun NanoTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    if (AppSettings.isClassic(context)) {
        MaterialExpressiveTheme(colorScheme = nanoColorScheme(context), typography = classicTypography(context), motionScheme = MotionScheme.expressive(), content = content)
    } else {
        MaterialExpressiveTheme(colorScheme = nanoColorScheme(context), motionScheme = MotionScheme.expressive(), content = content)
    }
}
