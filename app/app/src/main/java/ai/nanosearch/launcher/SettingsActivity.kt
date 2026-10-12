@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ai.nanosearch.launcher

import ai.nanosearch.launcher.ui.NanoTheme
import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** Nano Search's own settings: default home, appearance and what the search may read. Models come next. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // A change of style does not restart the screen: the old look fades into the new one, and the scroll position stays where it was.
            val listState = rememberLazyListState()
            val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
            Crossfade(targetState = SettingsTheme.version, animationSpec = tween(260), label = "style") { _ ->
                NanoTheme { SettingsScreen(onBack = { finish() }, listState, scroll) }
            }
        }
    }
}

/** Bumped whenever a choice on this screen changes the look of the whole app; the screen then fades to the new look. */
internal object SettingsTheme {
    var version by mutableIntStateOf(0)
        private set
    fun bump() { version++ }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit, listState: LazyListState, scroll: androidx.compose.material3.TopAppBarScrollBehavior) {
    val context = LocalContext.current
    // Anything the user can change outside this screen (the system role, permissions) is re-read whenever we come back to it.
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { DefaultHomeCard(tick) }
            item { ModelsRow() }
            item { Header("Appearance") }
            item { StyleCard() }
            if (AppSettings.isSense(context)) item { WeatherCard() }
            item { AppearanceCard() }
            item { Header("Screen") }
            item { FullScreenCard() }
            item { Header("Hub and badges") }
            item { HubCard(tick) }
            item { Header("Search can read") }
            items(AppSettings.SOURCES, key = { it.kind }) { SourceRow(it, tick) }
            item { FilesRow(tick) }
            item { Header("Your data") }
            item { DataCard(tick) }
            item { SetupRow() }
            item { AdvancedRow() }
        }
    }
}

@Composable
private fun ModelsRow() {
    val context = LocalContext.current
    Card(shape = RoundedCornerShape(24.dp), onClick = { context.startActivity(Intent(context, ModelsActivity::class.java)) }) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            headlineContent = { Text("Models") },
            supportingContent = { Text("Choose, download or import the models that understand, answer, listen and see.") },
        )
    }
}

@Composable
internal fun Header(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))

private fun isDefaultHome(context: Context): Boolean {
    val rm = context.getSystemService(RoleManager::class.java)
    return rm.isRoleHeld(RoleManager.ROLE_HOME)
}

@Composable
internal fun DefaultHomeCard(tick: Int) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    val isDefault = remember(tick, refresh) { isDefaultHome(context) }
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = if (isDefault) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (isDefault) "Nano Search is your home app" else "Make Nano Search your home app", style = MaterialTheme.typography.titleLarge)
            Text(
                if (isDefault) "The home button and swipe-up gesture open this launcher." else "Pick it as the default so the home button opens your search-first home screen.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!isDefault) {
                Button(onClick = {
                    val rm = context.getSystemService(RoleManager::class.java)
                    // Android shows its own "Set as default home app" sheet; if it refuses, fall back to the full list.
                    if (rm.isRoleAvailable(RoleManager.ROLE_HOME)) launcher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
                    else context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                }) { Text("Set as default") }
            } else {
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }, contentPadding = PaddingValues(0.dp)) { Text("Change in system settings") }
            }
        }
    }
}

/** Hides the status bar and the navigation bar on the home screen. */
@Composable
private fun FullScreenCard() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(AppSettings.fullScreen(context)) }
    Card(shape = RoundedCornerShape(24.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text("Full screen", style = MaterialTheme.typography.bodyLarge)
                Text("Hide the status bar and the navigation bar. Swipe from the top for the notification shade, or from the bottom for the navigation bar; they hide again by themselves.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = on, onCheckedChange = { on = it; AppSettings.setFullScreen(context, it) })
        }
    }
}

/** The Hub, the dots on icons and Active Frames. Classic turns them on by default; each is a switch of its own. */
@Composable
private fun HubCard(tick: Int) {
    val context = LocalContext.current
    var hub by remember { mutableStateOf(AppSettings.hub(context)) }
    var dots by remember { mutableStateOf(AppSettings.dots(context)) }
    var frames by remember { mutableStateOf(AppSettings.frames(context)) }
    val allowed = remember(tick) { HubStore.allowed(context) }
    val sense = AppSettings.isSense(context) // the Hub and Active Frames are Spirit's
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!sense) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("Hub", style = MaterialTheme.typography.bodyLarge)
                    Text("One list for texts, missed calls, events and notifications. Swipe in from the left edge, or tap the Hub button by the search bar.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = hub, onCheckedChange = { hub = it; AppSettings.setHub(context, it); SettingsTheme.bump() })
            }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("Dots on icons", style = MaterialTheme.typography.bodyLarge)
                    Text("A small count on an app's icon while it has notifications.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = dots, onCheckedChange = { dots = it; AppSettings.setDots(context, it) })
            }
            if (!sense) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("Active Frames", style = MaterialTheme.typography.bodyLarge)
                    Text("Your recent apps as live tiles, on a page to the left of home.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = frames, onCheckedChange = { frames = it; AppSettings.setFrames(context, it) })
            }
            }
            var sounds by remember { mutableStateOf(AppSettings.hubSounds(context)) }
            if (!sense) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("Hub sounds", style = MaterialTheme.typography.bodyLarge)
                    Text("A soft tick when the Hub opens and when you dismiss or clear things. The haptic tick is always on.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = sounds, onCheckedChange = { sounds = it; AppSettings.setHubSounds(context, it) })
            }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("Notification access", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (allowed) "Allowed. Notifications stay in memory on this phone; nothing is saved or sent anywhere."
                        else "Needed to show other apps' notifications in the Hub and on icons. Texts, missed calls and events work without it.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text(if (allowed) "Manage" else "Allow") }
            }
        }
    }
}

/** Weather on the flip clock: off by default; one city, chosen from the offline list. */
@Composable
private fun WeatherCard() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(Weather.enabled(context)) }
    var f by remember { mutableStateOf(Weather.fahrenheit(context)) }
    var city by remember { mutableStateOf(Weather.city(context)?.first) }
    var query by remember { mutableStateOf("") }
    val matches = remember(query) { Gazetteer.search(context, query) }
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("Weather on the flip clock", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Off by default. When on, the clock asks Open-Meteo for the weather in one city (chosen below, or from your approximate location), at most every 30 minutes, and sends nothing else.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = on, onCheckedChange = { on = it; Weather.setEnabled(context, it) })
            }
            if (on) {
                var here by remember { mutableStateOf(Weather.useLocation(context)) }
                val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> Weather.setUseLocation(context, ok); here = ok }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Use my location", style = MaterialTheme.typography.bodyLarge)
                        Text("Optional. Uses your approximate location, never a precise one, to pick the city. Off: choose a city below.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = here, onCheckedChange = { want ->
                        if (!want) { Weather.setUseLocation(context, false); here = false }
                        else if (Weather.locationGranted(context)) { Weather.setUseLocation(context, true); here = true }
                        else ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    })
                }
                Text(if (here) "City: ${Weather.spot(context)?.first ?: "waiting for a location"} (from your location)" else if (city != null) "City: $city" else "Choose a city", style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search for a city") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                matches.forEach { m ->
                    TextButton(onClick = { Weather.setCity(context, m.place.city, m.lat, m.lon); city = m.place.city; query = "" }) { Text(m.place.toString()) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Fahrenheit", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = f, onCheckedChange = { f = it; Weather.setFahrenheit(context, it) })
                }
            }
        }
    }
}

/** Modern is the Material 3 Expressive launcher; Old School is the flat, dark one, in two eras: Spirit (blue, glossy drawn icons) and Sense (graphite and green, flip clock). */
@Composable
private fun StyleCard() {
    val context = LocalContext.current
    var style by remember { mutableStateOf(AppSettings.style(context)) }
    var era by remember { mutableStateOf(AppSettings.era(context)) }
    var menu by remember { mutableStateOf(false) }
    val eras = listOf("spirit" to "Spirit", "sense" to "Sense")
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Style", style = MaterialTheme.typography.titleSmall)
            val options = listOf("modern" to "Modern", "classic" to "Old School")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, (key, label) ->
                    SegmentedButton(
                        selected = style == key,
                        onClick = { style = key; AppSettings.setStyle(context, key); SettingsTheme.bump() },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(label) }
                }
            }
            if (style == "classic") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Era", style = MaterialTheme.typography.bodyLarge)
                    Box {
                        OutlinedButton(onClick = { menu = true }) { Text(eras.first { it.first == era }.second) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            eras.forEach { (key, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    menu = false
                                    if (key != era) { era = key; AppSettings.setEra(context, key); SettingsTheme.bump() }
                                })
                            }
                        }
                    }
                }
                if (era == "sense") {
                    var haptics by remember { mutableStateOf(AppSettings.ringHaptics(context)) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 16.dp)) {
                            Text("Ring haptics", style = MaterialTheme.typography.bodyLarge)
                            Text("A tick as you grab the ring, another as it passes the point where letting go finishes the move, and a thump when an app is dropped on it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = haptics, onCheckedChange = { haptics = it; AppSettings.setRingHaptics(context, it) })
                    }
                }
            }
            Text(
                when {
                    style != "classic" -> "Material 3 Expressive: rounded shapes and colours from your wallpaper."
                    era == "sense" -> "Graphite and silver with one green accent: a flip clock with the weather, glossy icons that stand straight on the wallpaper, an arched carbon dock with Phone, Camera and a silver ring you pull up for your apps, and an app drawer with glossy tabs."
                    else -> "Flat and dark with one blue accent, a steel dial clock, a labelled bar for the dock, and the Hub, notification dots and Active Frames on by default. Each can be switched off below."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AppearanceCard() {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(AppSettings.themeMode(context)) }
    var dynamic by remember { mutableStateOf(AppSettings.dynamicColor(context)) }
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Theme", style = MaterialTheme.typography.titleSmall)
            val options = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, (key, label) ->
                    SegmentedButton(
                        selected = mode == key,
                        onClick = { mode = key; AppSettings.setThemeMode(context, key); SettingsTheme.bump() },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(label) }
                }
            }
            if (!AppSettings.isClassic(context)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("Colours from wallpaper", style = MaterialTheme.typography.bodyLarge)
                    Text("Material You. Off uses the Nano Search violet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = dynamic, onCheckedChange = { dynamic = it; AppSettings.setDynamicColor(context, it); SettingsTheme.bump() })
            }
        }
    }
}

private fun hasPermission(context: Context, p: String?) = p == null || context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

@Composable
internal fun SourceRow(source: AppSettings.Source, tick: Int) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var enabled by remember { mutableStateOf(AppSettings.sourceEnabled(context, source.kind)) }
    val granted = remember(tick, refresh) { hasPermission(context, source.permission) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val permissions = if (source.kind == "photo") arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.ACCESS_MEDIA_LOCATION)
    else arrayOf(source.permission ?: "")
    Card(shape = RoundedCornerShape(20.dp)) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            headlineContent = { Text(source.label) },
            supportingContent = {
                Column {
                    Text(source.detail)
                    if (!granted) Text("Needs permission", color = MaterialTheme.colorScheme.error)
                }
            },
            trailingContent = {
                if (!granted) FilledTonalButton(onClick = { ask.launch(permissions) }) { Text("Allow") }
                else Switch(checked = enabled, onCheckedChange = { enabled = it; AppSettings.setSourceEnabled(context, source.kind, it) })
            },
        )
    }
}

/** Files need "All files access", which Android grants from its own settings page rather than a pop-up. */
@Composable
internal fun FilesRow(tick: Int) {
    val context = LocalContext.current
    val granted = remember(tick) { Environment.isExternalStorageManager() }
    Card(shape = RoundedCornerShape(20.dp)) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            headlineContent = { Text("All files access") },
            supportingContent = { Text(if (granted) "Allowed: file names can be searched." else "Needed to search file names outside photos.") },
            trailingContent = {
                FilledTonalButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
                }) { Text(if (granted) "Manage" else "Allow") }
            },
        )
    }
}

/** What the search has stored on the phone, and a way to throw it away. */
@Composable
private fun DataCard(tick: Int) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val counts = remember(tick, refresh) {
        val index = SearchIndex(context)
        listOf("app" to "apps", "contact" to "contacts", "message" to "messages", "call" to "calls", "event" to "events", "file" to "files", "photo" to "photos", "phototext" to "photos with text", "setting" to "settings")
            .map { (kind, label) -> index.count(kind) to label }.filter { it.first > 0 }
    }
    val size = remember(tick, refresh) {
        val db = context.getDatabasePath("index.db")
        listOf(db, java.io.File(db.path + "-wal")).sumOf { if (it.exists()) it.length() else 0L }
    }
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (counts.isEmpty()) "Nothing indexed yet." else counts.joinToString(" · ") { "${it.first} ${it.second}" }, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Stored only inside this app, about ${android.text.format.Formatter.formatFileSize(context, size)}. It never leaves the phone; the only network use is downloading models, when you ask.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = {
                Thread {
                    val index = SearchIndex(context)
                    AppSettings.SOURCES.forEach { index.replaceKind(it.kind, emptyList()) }
                    index.replaceKind("phototext", emptyList())
                    index.replacePhotos(emptyList()); index.clearOcr()
                    context.getSharedPreferences("nano", Context.MODE_PRIVATE).edit().putBoolean("reindex", true).apply()
                    refresh++
                }.start()
            }) { Text("Clear and rebuild") }
        }
    }
}

@Composable
private fun SetupRow() {
    val context = LocalContext.current
    Card(shape = RoundedCornerShape(24.dp), onClick = { context.startActivity(Intent(context, SetupActivity::class.java)) }) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            headlineContent = { Text("Run setup again") },
            supportingContent = { Text("The short tour: permissions, models and default home.") },
        )
    }
}

@Composable
private fun AdvancedRow() {
    val context = LocalContext.current
    Card(shape = RoundedCornerShape(24.dp), onClick = { context.startActivity(Intent(context, AdvancedActivity::class.java)) }) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            headlineContent = { Text("Advanced") },
            supportingContent = { Text("Processor and memory. Automatic by default.") },
        )
    }
}
