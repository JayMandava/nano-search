package ai.nanosearch.launcher.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** The Material 3 colours the View-based launcher paints with: the same scheme the Compose screens use, so both always match. */
class Palette private constructor(
    val onSurface: Int, val onVariant: Int, val primary: Int, val onPrimary: Int,
    val primaryContainer: Int, val onPrimaryContainer: Int,
    val secondaryContainer: Int, val onSecondaryContainer: Int,
    val tertiaryContainer: Int, val onTertiaryContainer: Int,
    val errorContainer: Int, val onErrorContainer: Int,
    /** Search bar and dock float over the wallpaper, so they are slightly see-through. */
    val bar: Int, val dock: Int,
    /** The results panel and the drawer cover the wallpaper almost completely. */
    val panel: Int, val drawer: Int,
    val card: Int, val container: Int, val outline: Int, val error: Int, val scrim: Int,
    /** The Classic style: flat surfaces and small corners. */
    val classic: Boolean,
) {
    /** A corner radius in dp for the current style: Modern is as given, Classic is much squarer. */
    fun radius(dp: Int) = if (classic) maxOf(2, dp / 6) else dp

    companion object {
        private fun withAlpha(c: Color, a: Float) = c.copy(alpha = a).toArgb()

        fun of(context: Context): Palette {
            val s = nanoColorScheme(context)
            val classic = ai.nanosearch.launcher.AppSettings.isClassic(context)
            return Palette(
                s.onSurface.toArgb(), s.onSurfaceVariant.toArgb(), s.primary.toArgb(), s.onPrimary.toArgb(),
                s.primaryContainer.toArgb(), s.onPrimaryContainer.toArgb(),
                s.secondaryContainer.toArgb(), s.onSecondaryContainer.toArgb(),
                s.tertiaryContainer.toArgb(), s.onTertiaryContainer.toArgb(),
                s.errorContainer.toArgb(), s.onErrorContainer.toArgb(),
                bar = withAlpha(s.surfaceContainerHigh, if (classic) 0.97f else 0.92f), dock = withAlpha(s.surfaceContainer, if (classic) 0.97f else 0.78f),
                panel = withAlpha(s.surface, 0.98f), drawer = withAlpha(s.surface, if (classic) 0.42f else 0.96f),
                card = s.surfaceContainerHigh.toArgb(), container = s.surfaceContainerHighest.toArgb(), outline = withAlpha(s.onSurface, 0.12f),
                error = s.error.toArgb(), scrim = withAlpha(s.scrim, 0.45f), classic = classic,
            )
        }
    }
}
