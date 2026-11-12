@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ai.nanosearch.launcher

import ai.nanosearch.launcher.ui.SettingsSurfaceTheme as NanoTheme
import ai.nanosearch.launcher.ui.os7SettingsEnabled
import ai.nanosearch.launcher.ui.SettingsControlRow
import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import ai.nanosearch.launcher.ui.SettingsCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import ai.nanosearch.launcher.ui.SettingsTopBar as LargeTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import ai.nanosearch.launcher.ui.SettingsSwitch as Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Pick, download, import and remove the models behind each job. */
class ModelsActivity : SmoothComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Services.init(this)
        setContent { NanoTheme { ModelsScreen(onBack = { finish() }) } }
    }
}

private fun wifiOnly(c: Context) = c.getSharedPreferences("nano", Context.MODE_PRIVATE).getBoolean("wifiOnly", true)

@Composable
internal fun ModelsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) } // bumped after a choice or delete so every card re-reads the store
    var wifi by remember { mutableStateOf(wifiOnly(context)) }
    val totalRam = remember { ActivityManager.MemoryInfo().also { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }.totalMem }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Transfer state is observable. Idle polling must not invalidate the entire list.
            while (true) { ModelStore.poll(); delay(900) }
        }
    }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = if (os7SettingsEnabled()) Modifier else Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Models") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(if (os7SettingsEnabled()) 12.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(if (os7SettingsEnabled()) 8.dp else 12.dp)) {
            item {
                Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Everything runs on this phone", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Downloading a model is the only time Nano Search uses the internet, and only when you tap Download. Every file is checked against its checksum before it is used.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        SettingsControlRow("Download on Wi-Fi only") {
                            Switch(checked = wifi, onCheckedChange = { wifi = it; context.getSharedPreferences("nano", Context.MODE_PRIVATE).edit().putBoolean("wifiOnly", it).apply() })
                        }
                    }
                }
            }
            val current = version // the list below is rebuilt whenever a choice, download or delete changes the store
            for (slot in Slot.entries) {
                item(key = "h-${slot.name}") {
                    Column(Modifier.padding(start = 4.dp, top = 16.dp, end = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(slot.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(slot.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val entries = ModelStore.entries(slot)
                val chosen = ModelStore.displayChoice(slot)
                for (e in entries) item(key = e.id) { ModelCard(e, e.id == chosen.id, current, totalRam, wifi) { version++ } }
                if (slot != Slot.PHOTO && slot != Slot.OCR) item(key = "i-${slot.name}") { ImportButton(slot) { version++ } }
            }
        }
    }
}

@Composable
internal fun ModelCard(entry: ModelEntry, selected: Boolean, version: Int, totalRam: Long, wifi: Boolean, changed: () -> Unit) {
    val context = LocalContext.current
    val installed = ModelStore.installed(entry)
    val active = ModelStore.active(entry)
    val tooBig = entry.ramBytes > totalRam * 0.75
    val failure = entry.parts.firstNotNullOfOrNull { part ->
        if (ModelStore.partInstalled(entry, part)) null else ModelStore.status[part.file] as? Transfer.Failed
    }
    val chosen = selected && (installed || active)
    Card(modifier = Modifier.fillMaxWidth().testTag("model-${entry.id}")
        .selectable(selected = chosen, enabled = installed, role = Role.RadioButton,
            onClick = { ModelStore.select(entry); changed() }),
        shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = chosen, enabled = installed, onClick = null,
                    modifier = Modifier.size(48.dp).testTag("model-selector-${entry.id}"))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium)
                    Text(when {
                        chosen && active && !installed -> "Selected when ready"
                        chosen -> "Selected"
                        installed -> "Downloaded · tap to select"
                        active -> "Downloading"
                        else -> "Not downloaded"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(entry.note, Modifier.fillMaxWidth().testTag("model-description-${entry.id}"), style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (entry.custom) "Your file" else "Download: ${Formatter.formatFileSize(context, entry.bytes)} · Memory: about ${Formatter.formatFileSize(context, entry.ramBytes)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Licence: ${entry.license}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (chosen && active && !installed) {
                val fallback = ModelStore.selected(entry.slot)
                Text(if (ModelStore.installed(fallback)) "Selected when ready. Using ${fallback.name} until the download is verified."
                    else "Selected when ready. The download must finish and pass verification first.",
                    style = MaterialTheme.typography.bodySmall)
            }
            val downloading = entry.parts.mapNotNull { ModelStore.status[it.file] as? Transfer.Downloading }
            val verifying = entry.parts.any { ModelStore.status[it.file] == Transfer.Verifying }
            if (verifying) {
                LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                Text("Checking the file…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            } else if (downloading.isNotEmpty() || active) {
                val done = downloading.sumOf { it.done } + entry.parts.filter { ModelStore.file(it).length() == it.bytes }.sumOf { it.bytes }
                val fraction = (done.toFloat() / entry.bytes).coerceIn(0f, 1f)
                LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Text("${Formatter.formatFileSize(context, done)} of ${Formatter.formatFileSize(context, entry.bytes)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            val tested = ModelTest.results[entry.id]
            if (tested != null) Text(tested, style = MaterialTheme.typography.bodySmall)
            if (downloading.any { it.stalled }) {
                Text("Not connecting. Check Wi-Fi, and that Nano Search is allowed to use the network.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                TextButton(onClick = {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")))
                }) { Text("Open app settings") }
            }
            if (failure != null) Text(failure.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // Both the button and its label use the available width. Shrink-wrapped text can
            // round below fractional glyph widths. Height remains content-driven at larger fonts.
            Column(Modifier.fillMaxWidth().testTag("model-actions-${entry.id}"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val actionModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                if (installed && ModelTest.canTest(entry) && tested != "Testing…") {
                    TextButton(onClick = { ModelTest.run(context, entry) }, modifier = actionModifier) {
                        Text(if (tested == null) "Try it" else "Try again", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                }
                when {
                    active -> TextButton(onClick = { ModelStore.cancel(entry); changed() }, modifier = actionModifier) {
                        Text("Cancel", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                    installed -> TextButton(onClick = {
                        ModelStore.delete(entry)?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                        changed()
                    }, modifier = actionModifier) { Text("Delete", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                    tooBig -> Text("Too big for this phone", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    else -> FilledTonalButton(onClick = { ModelStore.download(entry, wifi); changed() }, modifier = actionModifier) {
                        Text("Download", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportButton(slot: Slot, changed: () -> Unit) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "model"
        busy = true
        Thread {
            val ok = runCatching { ModelStore.import(slot, uri, name) }.isSuccess
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                busy = false; changed()
                if (!ok) Toast.makeText(context, "Could not import that file.", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }
    TextButton(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) {
        Text(if (busy) "Copying…" else if (slot == Slot.VOICE) "Import a Whisper .bin file" else "Import a .gguf file")
    }
}
