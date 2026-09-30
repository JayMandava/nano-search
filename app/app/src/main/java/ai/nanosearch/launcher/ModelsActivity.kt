@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ai.nanosearch.launcher

import ai.nanosearch.launcher.ui.NanoTheme
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Pick, download, import and remove the models behind each job. */
class ModelsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Services.init(this)
        setContent { NanoTheme { ModelsScreen(onBack = { finish() }) } }
    }
}

private fun wifiOnly(c: Context) = c.getSharedPreferences("nano", Context.MODE_PRIVATE).getBoolean("wifiOnly", true)

@Composable
private fun ModelsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) } // bumped after a choice or delete so every card re-reads the store
    var wifi by remember { mutableStateOf(wifiOnly(context)) }
    val totalRam = remember { ActivityManager.MemoryInfo().also { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }.totalMem }
    LaunchedEffect(Unit) { while (true) { ModelStore.poll(); version++; delay(900) } }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Models") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Everything runs on this phone", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Downloading a model is the only time Nano Search uses the internet, and only when you tap Download. Every file is checked against its checksum before it is used.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Download on Wi-Fi only", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Switch(checked = wifi, onCheckedChange = { wifi = it; context.getSharedPreferences("nano", Context.MODE_PRIVATE).edit().putBoolean("wifiOnly", it).apply() })
                        }
                    }
                }
            }
            val current = version // the list below is rebuilt whenever a choice, download or delete changes the store
            for (slot in Slot.entries) {
                item(key = "h-${slot.name}") {
                    Column(Modifier.padding(start = 4.dp, top = 12.dp)) {
                        Text(slot.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(slot.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val entries = ModelStore.entries(slot)
                val chosen = ModelStore.selected(slot)
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
    val failure = entry.parts.firstNotNullOfOrNull { ModelStore.status[it.file] as? Transfer.Failed }
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = if (selected && installed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp, 12.dp, 16.dp, 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected && installed, enabled = installed, onClick = { ModelStore.select(entry); changed() })
                Column(Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium)
                    Text(entry.note, style = MaterialTheme.typography.bodyMedium)
                    val size = if (entry.custom) "Your file" else Formatter.formatFileSize(context, entry.bytes)
                    val memory = if (entry.custom) "" else " · needs about ${Formatter.formatFileSize(context, entry.ramBytes)} of memory"
                    Text("$size$memory · ${entry.license}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    active -> TextButton(onClick = { ModelStore.cancel(entry); changed() }) { Text("Cancel") }
                    installed -> TextButton(onClick = {
                        ModelStore.delete(entry)?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                        changed()
                    }) { Text("Delete") }
                    tooBig -> Text("Too big", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    else -> FilledTonalButton(onClick = { ModelStore.download(entry, wifi); changed() }) { Text("Download") }
                }
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
            if (tested != null) Text(tested, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp, top = 6.dp))
            if (installed && ModelTest.canTest(entry) && tested != "Testing…") TextButton(onClick = { ModelTest.run(context, entry) }) { Text(if (tested == null) "Try it" else "Try again") }
            if (downloading.any { it.stalled }) {
                Text("Not connecting. Check Wi-Fi, and that Nano Search is allowed to use the network.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                TextButton(onClick = {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")))
                }) { Text("Open app settings") }
            }
            if (failure != null) Text(failure.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
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
