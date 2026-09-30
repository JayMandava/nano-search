@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ai.nanosearch.launcher

import ai.nanosearch.launcher.ui.NanoTheme
import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

/** First run: what this is, what it may read, which models to fetch, and making it the home app. Four short steps, all skippable. */
class SetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Services.init(this)
        getSharedPreferences("nano", MODE_PRIVATE).edit().putBoolean("setupShown", true).apply() // leaving with Back must not bring it up again
        setContent { NanoTheme { SetupScreen(onDone = { finishSetup(this); finish() }) } }
    }

    companion object {
        fun needed(context: Context): Boolean {
            val prefs = context.getSharedPreferences("nano", Context.MODE_PRIVATE)
            if (prefs.getBoolean("setupDone", false) || prefs.getBoolean("setupShown", false)) return false
            // A phone that already has its models (an earlier version) does not need the tour.
            if (ModelStore.installed(ModelStore.selected(Slot.PARSER))) { prefs.edit().putBoolean("setupDone", true).apply(); return false }
            return true
        }

        fun finishSetup(context: Context) = context.getSharedPreferences("nano", Context.MODE_PRIVATE).edit().putBoolean("setupDone", true).apply()
    }
}

/** The models a first run offers: the recommended one for each job that needs a download to work at all. */
private val STARTER_IDS = listOf("parser-qwen35-2b", "answer-gemma4-e2b", "voice-small", "photo-mobileclip-s0", "ocr-ppocrv4")

@Composable
private fun SetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.systemBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Step ${step + 1} of 4", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                when (step) {
                    0 -> Welcome()
                    1 -> Access(tick)
                    2 -> Starter()
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { Text("Make it your home screen", style = MaterialTheme.typography.headlineMedium) }
                        item { DefaultHomeCard(tick) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) TextButton(onClick = { step-- }) { Text("Back") } else TextButton(onClick = onDone) { Text("Skip setup") }
                Button(onClick = { if (step < 3) step++ else onDone() }) { Text(if (step < 3) "Next" else "Done") }
            }
        }
    }
}

@Composable
private fun Welcome() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Search your whole phone. Privately.", style = MaterialTheme.typography.headlineLarge)
        Text("Apps, contacts, messages, calendar, files and photos, in one search bar. Ask it questions, speak to it, find pictures by what is in them.", style = MaterialTheme.typography.bodyLarge)
        Text("Everything is understood by small models that run on this phone. Your data is never sent anywhere. The only time Nano Search goes online is to download a model, and only when you tell it to.", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Access(tick: Int) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("What may search read?", style = MaterialTheme.typography.headlineMedium) }
        item { Text("Allow only what you want. You can change this any time in Settings.", style = MaterialTheme.typography.bodyMedium) }
        items(AppSettings.SOURCES, key = { it.kind }) { SourceRow(it, tick) }
        item { FilesRow(tick) }
    }
}

@Composable
private fun Starter() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val wifi = context.getSharedPreferences("nano", Context.MODE_PRIVATE).getBoolean("wifiOnly", true)
    val totalRam = remember { ActivityManager.MemoryInfo().also { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }.totalMem }
    LaunchedEffect(Unit) { while (true) { ModelStore.poll(); version++; delay(900) } }
    val starters = ModelCatalog.all.filter { it.id in STARTER_IDS }
    val missing = starters.filter { !ModelStore.installed(it) && !ModelStore.active(it) }
    val current = version
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Download the models", style = MaterialTheme.typography.headlineMedium) }
        item {
            Text(
                "These are the recommended ones, about ${android.text.format.Formatter.formatFileSize(context, starters.sumOf { it.bytes })} in all. Best on Wi-Fi. You can skip this and pick models later in Settings.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Button(enabled = missing.isNotEmpty(), onClick = { missing.forEach { ModelStore.download(it, wifi) }; version++ }) {
                Text(if (missing.isEmpty()) "All downloading or installed" else "Download all")
            }
        }
        items(starters, key = { it.id }) { ModelCard(it, ModelStore.selected(it.slot).id == it.id, current, totalRam, wifi) { version++ } }
    }
}
