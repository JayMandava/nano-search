@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ai.nanosearch.launcher

import ai.nanosearch.launcher.ui.SettingsSurfaceTheme as NanoTheme
import ai.nanosearch.launcher.ui.os7SettingsEnabled
import ai.nanosearch.launcher.ui.SettingsControlRow
import ai.nanosearch.launcher.ui.nanoColorScheme
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import ai.nanosearch.launcher.ui.SettingsCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ListItemDefaults
import ai.nanosearch.launcher.ui.SettingsTopBar as LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import ai.nanosearch.launcher.ui.SettingsSwitch as Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** Nano Search's own settings: default home, appearance and what the search may read. Models come next. */
class SettingsActivity : SmoothComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Keep one list and one composition alive. Crossfading two LazyColumns that share
            // a LazyListState duplicates layout work and lets both lists drive the same scroll.
            val listState = rememberLazyListState()
            val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
            val version = SettingsTheme.version
            val context = LocalContext.current
            val uiMode = androidx.compose.ui.platform.LocalConfiguration.current.uiMode
            val colors = remember(version, uiMode) { nanoColorScheme(context) }
            NanoTheme(colorScheme = colors) { SettingsScreen(onBack = { finish() }, listState, scroll) }
        }
    }
}

/** Bumped whenever a choice on this screen changes the look of the whole app; the existing screen recomposes in place. */
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
        modifier = if (os7SettingsEnabled()) Modifier else Modifier.nestedScroll(scroll.nestedScrollConnection),
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
            contentPadding = PaddingValues(if (os7SettingsEnabled()) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (os7SettingsEnabled()) 8.dp else 12.dp),
        ) {
            if (BuildConfig.OS7_STANDALONE) {
                item { Header("Search and intelligence") }
                item { ModelsRow() }
                item { AdvancedRow() }
                item { Header("Indexing") }
                item { DataCard(tick) }
                item { Header("Search sources") }
                item { Text("Choose what search can read. Access is optional and stays under your control.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) }
                items(AppSettings.SOURCES, key = { it.kind }) { SourceRow(it, tick) }
                item { FilesRow(tick) }
                item { Header("Launcher") }
                item { DefaultHomeCard(tick) }
                item { Header("Apps") }
                item { HiddenAppsRow(tick) }
                item { Card { Column(Modifier.padding(16.dp)) { IconPackRow() } } }
                item { Header("Notifications") }
                item { LauncherNotificationsCard(tick) }
                item { MutedNotificationAppsRow() }
                item { Header("Display") }
                item { Text("Android Home navigation stays available.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) }
                item { Card(onClick = { context.startActivity(Intent(context, LockClockSettingsActivity::class.java)) }) {
                    ListItem(headlineContent = { Text("Steel Dial wallpaper") }, supportingContent = { Text("Preview the clock, background and position before applying it.") }, colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
                } }
                item { Header("Help") }
                item { SetupRow() }
                item { Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your launcher", style = MaterialTheme.typography.titleMedium)
                    Text("Search apps and the sources you allow. Models add natural-language search, answers, voice and photo understanding. Touch and hold an app for actions; open Notifications from the header.")
                    Text("Hidden apps stay installed. You can show them again in Hidden apps. Choosing a default launcher and allowing notification access are optional.", style = MaterialTheme.typography.bodySmall)
                } } }
            } else {
            item { DefaultHomeCard(tick) }
            item { ModelsRow() }
            item { HiddenAppsRow(tick) }
            item { Header("Appearance") }
            item { AppearanceCard() }
            item { ClockFacesRow() }
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
}

@Composable
private fun ClockFacesRow() {
    val context = LocalContext.current
    Card(shape = RoundedCornerShape(24.dp), onClick = { context.startActivity(Intent(context, ClockFacesActivity::class.java)) }) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            headlineContent = { Text("Clock Faces") },
            supportingContent = { Text("Built-in and imported faces for Home and Lock Clock.") },
        )
    }
}

@Composable
private fun HiddenAppsRow(tick: Int) {
    val context = LocalContext.current
    val count = remember(tick) { AppSettings.hiddenApps(context).size } // re-read when coming back from the screen
    Card(shape = RoundedCornerShape(24.dp), onClick = { context.startActivity(Intent(context, HiddenAppsActivity::class.java)) }) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            headlineContent = { Text("Hidden apps") },
            supportingContent = { Text(if (count == 0) "Keep apps out of the drawer and search. None hidden." else if (count == 1) "1 app hidden from the drawer and search." else "$count apps hidden from the drawer and search.") },
        )
    }
}

@Composable
private fun ModelsRow() {
    val context = LocalContext.current
    Card(shape = RoundedCornerShape(24.dp), onClick = { context.startActivity(Intent(context, ModelsActivity::class.java)) }) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            headlineContent = { Text(if (BuildConfig.OS7_STANDALONE) "On-device models" else "Models") },
            supportingContent = { Text("Choose, download or import the models that understand, answer, listen and see.") },
        )
    }
}

@Composable
internal fun Header(text: String) {
    val classic = os7SettingsEnabled()
    Text(text, style = MaterialTheme.typography.titleMedium,
        color = if (classic) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
        modifier = (if (classic) Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 10.dp, vertical = 6.dp)
            else Modifier.padding(start = 4.dp, top = 8.dp)).semantics { heading() })
}

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
            Text(if (BuildConfig.OS7_STANDALONE) { if (isDefault) "This is your default launcher" else "Default launcher" } else if (isDefault) "Nano Search is your home app" else "Make Nano Search your home app", style = MaterialTheme.typography.titleLarge)
            Text(
                if (isDefault) "The home button and swipe-up gesture open this launcher."
                else if (os7SettingsEnabled()) "Pick it as the default so the home button opens your classic home screen."
                else "Pick it as the default so the home button opens your search-first home screen.",
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
                Text(if (os7SettingsEnabled()) "Also hide the navigation bar on Home. Swipe an edge to reveal Android controls."
                    else "Hide the status bar and the navigation bar. Swipe from the top for the notification shade, or from the bottom for the navigation bar; they hide again by themselves.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = on, onCheckedChange = { on = it; AppSettings.setFullScreen(context, it) })
        }
    }
}

/** The Hub, the dots on icons and Active Frames. They are on by default; each is a switch of its own. */
@Composable
private fun HubCard(tick: Int) {
    val context = LocalContext.current
    var hub by remember { mutableStateOf(AppSettings.hub(context)) }
    var dots by remember { mutableStateOf(AppSettings.dots(context)) }
    var frames by remember { mutableStateOf(AppSettings.frames(context)) }
    val allowed = remember(tick) { HubStore.allowed(context) }
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("Hub", style = MaterialTheme.typography.bodyLarge)
                    Text(if (os7SettingsEnabled()) "One list for texts, missed calls, events and notifications. Open Notifications from the Home header or Home Menu."
                        else "One list for texts, missed calls, events and notifications. Swipe in from the left edge, or tap the Hub button by the search bar.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = hub, onCheckedChange = { hub = it; AppSettings.setHub(context, it); SettingsTheme.bump() })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("Dots on icons", style = MaterialTheme.typography.bodyLarge)
                    Text("A small count on an app's icon while it has notifications.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = dots, onCheckedChange = { dots = it; AppSettings.setDots(context, it) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("Active Frames", style = MaterialTheme.typography.bodyLarge)
                    Text(if (os7SettingsEnabled()) "Your recent apps as live tiles. Open Home Menu, then Nano workspace & widgets, to reach Frames and your original pages."
                        else "Your recent apps as live tiles, on a page to the left of home.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = frames, onCheckedChange = { frames = it; AppSettings.setFrames(context, it) })
            }
            var sounds by remember { mutableStateOf(AppSettings.hubSounds(context)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("Hub sounds", style = MaterialTheme.typography.bodyLarge)
                    Text("A soft tick when the Hub opens and when you dismiss or clear things. The haptic tick is always on.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = sounds, onCheckedChange = { sounds = it; AppSettings.setHubSounds(context, it) })
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

@Composable
private fun AppearanceCard() {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(AppSettings.themeMode(context)) }
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            var classic by remember { mutableStateOf(AppSettings.os7Mode(context)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("OS7 interface", style = MaterialTheme.typography.titleSmall)
                    Text("Classic launcher and Options. Turn off to return to Spirit. Your settings and data stay intact.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = classic, onCheckedChange = { classic = it; AppSettings.setOs7Mode(context, it); SettingsTheme.bump() })
            }
            Text(if (os7SettingsEnabled()) "Spirit color mode" else "Theme", style = MaterialTheme.typography.titleSmall)
            if (os7SettingsEnabled()) Text("Applies when OS7 interface is turned off. OS7 keeps its classic dark Home and pale Options screens.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            IconPackRow()
        }
    }
}

/** Which installed icon pack dresses the app icons: none (this launcher's own), or any pack found on the phone. */
@Composable
private fun IconPackRow() {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(AppSettings.iconPack(context)) }
    var open by remember { mutableStateOf(false) }
    val packs by produceState<List<IconPacks.Pack>?>(null, open) {
        value = if (open) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            IconPacks.installed(context)
        } else null
    }
    val name by produceState(if (chosen.isEmpty()) "Built in" else "Loading…", chosen) {
        value = if (chosen.isEmpty()) "Built in" else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { context.packageManager.let { it.getApplicationLabel(it.getApplicationInfo(chosen, 0)).toString() } }.getOrDefault("Not installed")
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Icon pack", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(name, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyLarge)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        Text(if (BuildConfig.OS7_STANDALONE) "Use an installed icon pack. Apps without matching artwork keep their built-in icons." else "Dress app icons with an installed icon pack. Apps the pack does not cover keep Nano Search's own icons.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        title = { Text("Icon pack") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                val options = listOf("" to "Built in") + packs.orEmpty().map { it.pkg to it.label }
                options.forEach { (pkg, label) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = chosen == pkg, role = Role.RadioButton,
                                onClick = { chosen = pkg; AppSettings.setIconPack(context, pkg); SettingsTheme.bump(); open = false }),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = chosen == pkg, onClick = null)
                        Text(label, Modifier.weight(1f).padding(vertical = 12.dp))
                    }
                }
                if (packs.isNullOrEmpty()) Text(if (packs == null) "Loading icon packs…" else "No icon packs are installed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
    )
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
        SettingsControlRow(
            title = source.label,
            modifier = Modifier.padding(16.dp),
            description = {
                Text(source.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!granted) Text("Needs permission", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            },
            control = {
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
        SettingsControlRow(
            title = "All files access",
            modifier = Modifier.padding(16.dp),
            description = { Text(if (granted) "Allowed: file names can be searched." else "Needed to search file names outside photos.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            control = {
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
    // counting what is stored opens the search database: done off the main thread, so the card appears at once and scrolling to it never stalls
    val stored by produceState<Pair<List<Pair<Int, String>>, Long>?>(null, tick, refresh) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val index = SearchIndex(context)
            val counts = listOf("app" to "apps", "contact" to "contacts", "message" to "messages", "call" to "calls", "event" to "events", "file" to "files", "photo" to "photos", "phototext" to "photos with text", "setting" to "settings")
                .map { (kind, label) -> index.count(kind) to label }.filter { it.first > 0 }
            val db = context.getDatabasePath("index.db")
            counts to listOf(db, java.io.File(db.path + "-wal")).sumOf { if (it.exists()) it.length() else 0L }
        }
    }
    val counts = stored?.first.orEmpty()
    val size = stored?.second ?: 0L
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (BuildConfig.OS7_STANDALONE) Text("Search index", style = MaterialTheme.typography.titleMedium)
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

/** Notification controls are independent of search sources and model downloads. */
@Composable
internal fun LauncherNotificationsCard(tick: Int, setup: Boolean = false) {
    val context = LocalContext.current
    val allowed = remember(tick) { HubStore.allowed(context) }
    var badges by remember { mutableStateOf(AppSettings.dots(context)) }
    var sounds by remember { mutableStateOf(AppSettings.hubSounds(context)) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Notification access", style = MaterialTheme.typography.titleMedium)
            Text(if (allowed) "Allowed. Notifications are shown only on this phone." else "Optional. Allow access to see notifications here and badges on app icons. You can continue without it.", style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text(if (allowed) "Manage access" else "Choose access") }
            if (!setup) {
                SettingsControlRow("App badges") {
                    Switch(checked = badges, onCheckedChange = { badges = it; AppSettings.setDots(context, it) })
                }
                SettingsControlRow("Notification hub sounds") {
                    Switch(checked = sounds, onCheckedChange = { sounds = it; AppSettings.setHubSounds(context, it) })
                }
            }
        }
    }
}

@Composable
private fun MutedNotificationAppsRow() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(HubStore.mutedApps(context)) }
    Card(onClick = { muted = HubStore.mutedApps(context); open = true }) {
        ListItem(headlineContent = { Text("Hidden notification apps") }, supportingContent = { Text(if (muted.isEmpty()) "None hidden" else "${muted.size} apps hidden from Notifications") }, colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Hidden notification apps") },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            if (muted.isEmpty()) Text("No apps are hidden. Touch and hold a notification to hide its app here.")
            muted.sorted().forEach { pkg ->
                val label = remember(pkg) { runCatching { context.packageManager.getApplicationInfo(pkg, 0).loadLabel(context.packageManager).toString() }.getOrDefault(pkg) }
                TextButton(onClick = { HubStore.toggleMuted(context, pkg); muted = HubStore.mutedApps(context) }) { Text("Show $label") }
            }
        } }, confirmButton = { TextButton(onClick = { open = false }) { Text("Done") } })
}
