@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ai.nanosearch.launcher.ui

import ai.nanosearch.launcher.AppSettings
import android.os.Build
import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
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
        AppSettings.dynamicColor(context) && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
}

/** Material 3 Expressive: springy motion, colours from the wallpaper (or the Nano Search violet), light, dark or system. */
@Composable
fun NanoTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(colorScheme = nanoColorScheme(LocalContext.current), motionScheme = MotionScheme.expressive(), content = content)
}
