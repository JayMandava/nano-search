@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ai.nanosearch.launcher

import ai.nanosearch.launcher.ui.NanoTheme
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Choose which apps stay out of the app drawer and search. They are still installed and still work; this only hides them from view. */
class HiddenAppsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NanoTheme { HiddenAppsScreen(onBack = { finish() }) } }
    }
}

/** One app on the screen: its package (what is hidden), the launcher entry its icon is drawn from, and its name. */
private class AppRow(val pkg: String, val component: ComponentName, val label: String)

private fun appRows(c: Context): List<AppRow> =
    Indexers.apps(c, includeHidden = true)
        .mapNotNull { item -> ComponentName.unflattenFromString(item.key)?.let { AppRow(it.packageName, it, item.title) } }
        .distinctBy { it.pkg } // an app with several launcher entries is one row: hiding it hides them all
        .filter { it.pkg != c.packageName }
        .sortedBy { it.label.lowercase() }

/**
 * The icon for a row. [AppIcons.get] hands out one shared drawable per app, the same object the drawer and the dock are showing, so it must only be read:
 * setting its size to draw it here made those icons shrink. A scaled copy of its picture is made instead.
 */
private fun iconOf(c: Context, row: AppRow): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val d = AppIcons.get(c, row.component)
    val size = (40 * c.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    val picture = (d as? android.graphics.drawable.BitmapDrawable)?.bitmap
    val bmp = if (picture != null) Bitmap.createScaledBitmap(picture, size, size, true) else {
        val copy = (d.constantState?.newDrawable()?.mutate() ?: return@runCatching null)
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { copy.setBounds(0, 0, size, size); copy.draw(Canvas(it)) }
    }
    bmp.asImageBitmap()
}.getOrNull()

@Composable
private fun HiddenAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val rows = remember { appRows(context) }
    var hidden by remember { mutableStateOf(AppSettings.hiddenApps(context)) }
    var filter by remember { mutableStateOf("") }
    val shown = remember(filter) { rows.filter { filter.isBlank() || it.label.contains(filter.trim(), ignoreCase = true) } }
    // The icons are made once, off the main thread, in list order (so the first rows are ready first), and kept here for as long as the screen is open. Making one while its row
    // scrolled into view, and again each time the row came back, held the main thread long enough to make the list stutter.
    val icons = remember { mutableStateMapOf<String, androidx.compose.ui.graphics.ImageBitmap>() }
    LaunchedEffect(rows) {
        withContext(Dispatchers.Default) {
            for (row in rows) iconOf(context, row)?.let { icons[row.pkg] = it }
        }
    }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Hidden apps") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Text(
                        "A hidden app stays installed and keeps working. It just does not show in the app drawer, in search, in recents or favourites, or in Frames. Switch it off here to see it again.",
                        Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item {
                OutlinedTextField(value = filter, onValueChange = { filter = it }, singleLine = true, label = { Text("Find an app") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            items(shown, key = { it.pkg }) { row ->
                val isHidden = row.pkg in hidden
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        val icon = icons[row.pkg]
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { if (icon != null) Image(icon, contentDescription = null, Modifier.size(40.dp)) }
                    },
                    headlineContent = { Text(row.label) },
                    supportingContent = if (isHidden) ({ Text("Hidden") }) else null,
                    trailingContent = {
                        Switch(checked = isHidden, onCheckedChange = { on ->
                            AppSettings.setAppHidden(context, row.pkg, on)
                            hidden = AppSettings.hiddenApps(context)
                        })
                    },
                )
            }
        }
    }
}
