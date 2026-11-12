@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ai.nanosearch.launcher.ui

import ai.nanosearch.launcher.AppSettings
import ai.nanosearch.launcher.BuildConfig
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Settings retain Android controls and semantics, with an original OS7-inspired visual treatment. */
@Composable
fun os7SettingsEnabled(): Boolean = BuildConfig.OS7_STANDALONE || AppSettings.os7Mode(LocalContext.current)

@Composable
fun SettingsSurfaceTheme(colorScheme: ColorScheme? = null, content: @Composable () -> Unit) {
    NanoTheme(colorScheme) {
        if (!os7SettingsEnabled()) content() else {
            val colors = lightColorScheme(
                primary = Color(0xFF0067A8), onPrimary = Color.White,
                primaryContainer = Color(0xFFDCEAF4), onPrimaryContainer = Color(0xFF172532),
                secondaryContainer = Color(0xFFE4E7E9), onSecondaryContainer = Color(0xFF172532),
                background = Color(0xFFECEDEE), surface = Color(0xFFF9F9F8),
                onBackground = Color(0xFF171B1F), onSurface = Color(0xFF171B1F),
                onSurfaceVariant = Color(0xFF4C555C), outline = Color(0xFF8C979E),
                outlineVariant = Color(0xFFC3C8CC), surfaceContainerHighest = Color(0xFFE5E7E8),
                surfaceContainerHigh = Color(0xFFEEEFEF), surfaceContainer = Color(0xFFF4F4F3),
            )
            val base = MaterialTheme.typography
            MaterialTheme(
                colorScheme = colors,
                shapes = Shapes(RoundedCornerShape(2.dp), RoundedCornerShape(3.dp), RoundedCornerShape(4.dp), RoundedCornerShape(4.dp), RoundedCornerShape(6.dp)),
                typography = base.copy(
                    titleLarge = base.titleLarge.copy(fontFamily = FontFamily.SansSerif, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold),
                    titleMedium = base.titleMedium.copy(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    bodyLarge = base.bodyLarge.copy(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 21.sp),
                    bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 19.sp),
                    bodySmall = base.bodySmall.copy(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 17.sp),
                ), content = content,
            )
        }
    }
}

@Composable
fun SettingsTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    compact: Boolean = false,
) {
    if (!os7SettingsEnabled()) {
        if (compact) TopAppBar(title = title, modifier = modifier, navigationIcon = navigationIcon, actions = actions, scrollBehavior = scrollBehavior)
        else LargeTopAppBar(title = title, modifier = modifier, navigationIcon = navigationIcon, actions = actions, scrollBehavior = scrollBehavior)
    } else {
        TopAppBar(
            title = title, navigationIcon = navigationIcon, actions = actions,
            modifier = modifier.background(Brush.verticalGradient(listOf(Color(0xFF555E66), Color(0xFF252B31), Color(0xFF151A20)))),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = Color.White, navigationIconContentColor = Color.White, actionIconContentColor = Color.White),
        )
    }
}

@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
    colors: CardColors = CardDefaults.cardColors(),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val os7 = os7SettingsEnabled()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val selected = os7 && (pressed || focused)
    val scheme = MaterialTheme.colorScheme
    val cardColors = if (!os7) colors else CardDefaults.cardColors(
        containerColor = if (selected) Color(0xFF0067A8) else colors.containerColor,
        contentColor = if (selected) Color.White else scheme.onSurface,
    )
    val cardShape = if (os7) RoundedCornerShape(2.dp) else shape
    val border = if (os7) BorderStroke(1.dp, if (selected) Color(0xFF56B8F3) else scheme.outlineVariant) else null
    // ListItem reads its text colors from the theme, rather than LocalContentColor.
    val contentColors = if (selected) scheme.copy(onSurface = Color.White, onSurfaceVariant = Color.White) else scheme
    MaterialTheme(colorScheme = contentColors) {
        if (onClick == null) Card(modifier = if (os7) modifier.fillMaxWidth() else modifier, shape = cardShape, colors = cardColors, border = border, content = content)
        else Card(onClick = onClick, modifier = if (os7) modifier.fillMaxWidth() else modifier, shape = cardShape, colors = cardColors, border = border, interactionSource = interaction, content = content)
    }
}

/** Checkbox geometry matches classic Options without reducing the accessible touch target. */
@Composable
fun SettingsSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    if (os7SettingsEnabled()) Checkbox(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
    else Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
}

/** Keep explanatory copy out of the control's column, including at larger font sizes. */
@Composable
fun SettingsControlRow(
    title: String,
    modifier: Modifier = Modifier,
    description: (@Composable ColumnScope.() -> Unit)? = null,
    control: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            control()
        }
        if (description != null) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp), content = description)
        }
    }
}
