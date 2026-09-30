@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ai.nanosearch.launcher

import ai.nanosearch.launcher.ui.NanoTheme
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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

/**
 * Settings > Advanced. By default everything here is automatic and this screen only reports what was chosen. It exists for people who want
 * to override the processor or memory behaviour, with a one-tap return to Auto.
 */
class AdvancedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Services.init(this)
        setContent { NanoTheme { AdvancedScreen(onBack = { finish() }) } }
    }
}

/** A changed processor or memory choice takes effect the next time a model loads, so drop the loaded ones now. */
private fun reloadModels() = Services.llm.execute { Services.parser.unload(); Services.answerer.unload() }

@Composable
private fun AdvancedScreen(onBack: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Advanced") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        val v = version
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    "Nano Search picks these itself and re-checks when the phone or the app changes. Change them only if you know why. A wrong choice can make it slower or use more battery.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item { ProcessorCard(v) { version++ } }
            item { GpuCard(v) { version++ } }
            item { MemoryCard(v) { version++ } }
            item {
                TextButton(onClick = { CpuPlan.setAuto(); MemoryPolicy.mode = "auto"; reloadModels(); version++ }) { Text("Reset everything to Auto") }
            }
        }
    }
}

@Composable
private fun ProcessorCard(version: Int, changed: () -> Unit) {
    val context = LocalContext.current
    val manual = CpuPlan.isManual
    val current = CpuPlan.current()
    val measuring = CpuTuner.status.value
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Processor", style = MaterialTheme.typography.titleMedium)
            Text(
                "This phone has ${CpuPlan.coreCount} cores in ${CpuPlan.clusters.size} " + if (CpuPlan.clusters.size == 1) "group." else "groups (${CpuPlan.clusters.joinToString(", ") { it.size.toString() }} cores, fastest first).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Auto", "Manual").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = (i == 1) == manual,
                        onClick = { if (i == 0) CpuPlan.setAuto() else CpuPlan.setManual(current.id, 0); reloadModels(); changed() },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(label) }
                }
            }
            if (!manual) {
                val measured = CpuPlan.tunedMs()
                Text(
                    "Using: ${current.label}, ${current.threads} threads." + when {
                        measured != null && measured.first > 0 -> " Measured: about ${"%.1f".format(measured.first / 1000.0)} s a request."
                        CpuPlan.candidates.size < 2 -> " Nothing else to choose between on this phone."
                        else -> " Not measured yet; that happens by itself while charging."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (measuring.isNotEmpty()) Text(measuring, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                if (CpuPlan.candidates.size > 1) FilledTonalButton(enabled = measuring != "Measuring…", onClick = { CpuTuner.run(context, force = true); changed() }) { Text("Measure now") }
            } else {
                CpuPlan.candidates.forEach { plan ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = plan.id == current.id, onClick = { CpuPlan.setManual(plan.id, 0); reloadModels(); changed() })
                        Text(plan.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                val base = CpuPlan.candidates.firstOrNull { it.id == current.id } ?: current
                if (base.threads > 1) {
                    var threads by remember(base.id, version) { mutableStateOf(current.threads.toFloat()) }
                    Text("Threads: ${threads.toInt()} of ${base.threads}", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = threads, onValueChange = { threads = it },
                        onValueChangeFinished = { CpuPlan.setManual(base.id, threads.toInt()); reloadModels(); changed() },
                        valueRange = 1f..base.threads.toFloat(), steps = (base.threads - 2).coerceAtLeast(0),
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryCard(version: Int, changed: () -> Unit) {
    val context = LocalContext.current
    val mode = MemoryPolicy.mode
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Memory", style = MaterialTheme.typography.titleMedium)
            Text(
                "This phone has ${Formatter.formatFileSize(context, MemoryPolicy.totalBytes)} of RAM. Models stay loaded after use so the next request is instant, then are released.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val options = listOf("auto" to "Auto", "keep" to "Keep loaded", "quick" to "Free quickly")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, (key, label) ->
                    SegmentedButton(selected = mode == key, onClick = { MemoryPolicy.mode = key; changed() }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label) }
                }
            }
            fun mins(ms: Long) = if (ms >= 60_000) "${ms / 60_000} min" else "${ms / 1000} s"
            Text(
                "Understanding model released after ${mins(MemoryPolicy.parserIdleMs)} idle, answering model after ${mins(MemoryPolicy.answerIdleMs)}. " +
                    if (MemoryPolicy.keepBoth) "Both can be loaded at once." else "Only one is loaded at a time.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun GpuCard(version: Int, changed: () -> Unit) {
    val context = LocalContext.current
    val measuring = CpuTuner.status.value // read here so the card refreshes when a background test finishes
    val on = GpuSupport.enabled
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("GPU (experimental)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Runs the language models on the graphics chip (Vulkan). It helps on some phones and slows down others, so it is only used if a test on this phone shows it is faster. " +
                    "If it fails or crashes, it switches itself off.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Try the GPU", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = on, onCheckedChange = {
                    GpuSupport.enabled = it
                    reloadModels()
                    if (it) CpuTuner.run(context, force = true)
                    changed()
                })
            }
            val blocked = GpuSupport.blocked
            val name = if (on) GpuSupport.deviceName else null
            when {
                blocked != null -> Text(blocked, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                on && name != null -> Text("GPU found: $name. $measuring", style = MaterialTheme.typography.bodyMedium)
                on -> Text("This phone reports no usable GPU, so the CPU is used.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
