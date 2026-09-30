package ai.nanosearch.launcher

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import java.io.File

/**
 * Measures, once, which core grouping runs the understanding model fastest on this phone. Every candidate is timed on the same requests;
 * a grouping replaces the default only if it is clearly faster (by [MARGIN]), so a noisy run cannot make things worse. If anything fails
 * or the phone is hot, the current choice stays. Runs in the background, only while charging, and again only when the chip or app build changes.
 */
object CpuTuner {
    /** Shown in Settings > Advanced: "", "Measuring…" or a one-line result. */
    val status = mutableStateOf("")
    private const val MARGIN = 0.92
    private const val REQUESTS = 7 // the first is a warm-up and is not counted

    fun maybeRun(context: Context) {
        if (CpuPlan.candidates.size < 2 || CpuPlan.tuned() != null || CpuPlan.isManual) return
        val battery = context.getSystemService(BatteryManager::class.java)
        if (!battery.isCharging) return
        run(context)
    }

    /** [force] is the "Measure now" button: it skips the charging rule and the already-measured check. */
    fun run(context: Context, force: Boolean = false, dryRunWith: List<CpuPlan.Plan>? = null) {
        if (status.value == "Measuring…") return
        val app = context.applicationContext
        if (!force && Build.VERSION.SDK_INT >= 29 && app.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) return
        val model = ModelStore.selected(Slot.PARSER)
        val file = ModelStore.file(model.parts.first())
        if (!file.exists()) { status.value = "Download an understanding model first."; return }
        status.value = "Measuring…"
        Services.llm.execute {
            Services.parser.unload(); Services.answerer.unload()
            val result = runCatching { measure(app, file, model, dryRunWith) }.getOrElse { "Measuring failed; keeping the current choice." }
            status.value = (GpuSupport.blocked?.let { "$it " } ?: "") + result
        }
    }

    /** [dryRun] measures these candidates instead of the phone's own and saves nothing; it exists to test this code on phones with only one sensible grouping. */
    private fun measure(app: Context, file: File, model: ModelEntry, dryRun: List<CpuPlan.Plan>?): String {
        val system = app.assets.open("parser_system.txt").bufferedReader().use { it.readText().trim() }
        val grammar = app.assets.open("parser.gbnf").bufferedReader().use { it.readText() }
        val queries = app.assets.open("parser_selftest.txt").bufferedReader().readLines().filter { it.isNotBlank() }.map { it.substringBefore('|') }.take(REQUESTS)
        val results = LinkedHashMap<CpuPlan.Plan, Long>()
        for (plan in dryRun ?: CpuPlan.candidates) {
            val engine = LlmEngine(file, model.format.prefix(system), cacheDir = Services.models.cacheDir, plan = plan, cpuFallback = false)
            if (!engine.load()) continue
            val times = queries.mapIndexed { i, q ->
                val t0 = System.nanoTime()
                engine.complete(q + model.format.suffix(model.skipThinking), grammar, 48)
                if (i == 0) null else (System.nanoTime() - t0) / 1_000_000
            }.filterNotNull().sorted()
            engine.unload()
            if (times.isNotEmpty()) results[plan] = times[times.size / 2]
        }
        results.forEach { (plan, ms) -> Log.i("nanosearch", "tuner: ${plan.label} (${plan.threads} threads) median $ms ms") }
        val baseline = dryRun?.first() ?: CpuPlan.default()
        val baseMs = results.entries.firstOrNull { it.key.id == baseline.id }?.value ?: return "Measuring failed; keeping the current choice."
        val best = results.entries.minByOrNull { it.value }!!
        val pick = if (best.key.id != baseline.id && best.value < baseMs * MARGIN) best.key else baseline
        if (dryRun == null) CpuPlan.saveTuned(pick, results[pick] ?: baseMs, baseMs)
        return "Chose ${pick.label}: about ${"%.1f".format((results[pick] ?: baseMs) / 1000.0)} s a request" +
            if (pick.id != baseline.id) " (default ${"%.1f".format(baseMs / 1000.0)} s)." else "."
    }
}
