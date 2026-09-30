package ai.nanosearch.launcher

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Which CPU cores and how many threads the models use. The phone is asked what its cores are (their clusters, from sysfs), a sensible
 * choice is made from that, and [CpuTuner] can replace it with a measured one. The user can override both in Settings > Advanced.
 */
object CpuPlan {
    class Plan(val mask: Long, val threads: Int, val label: String, val id: String, val gpu: Boolean = false)

    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences("nano", Context.MODE_PRIVATE)

    fun init(context: Context) { app = context.applicationContext }

    private fun read(cpu: Int, file: String): Long =
        runCatching { File("/sys/devices/system/cpu/cpu$cpu/$file").readText().trim().toLong() }.getOrDefault(0L)

    /** Cores grouped by identical capability, strongest group first. Capacity (when the kernel reports it) outranks clock speed. */
    val clusters: List<List<Int>> by lazy {
        val n = Runtime.getRuntime().availableProcessors()
        val score = (0 until n).associateWith { read(it, "cpu_capacity") * 10_000_000L + read(it, "cpufreq/cpuinfo_max_freq") }
        if (score.values.all { it == 0L }) listOf((0 until n).toList()) // unreadable: one undifferentiated group
        else score.entries.groupBy({ it.value }, { it.key }).toSortedMap(compareByDescending { it }).values.toList()
    }

    val coreCount get() = clusters.sumOf { it.size }
    private val unreadable get() = clusters.size == 1

    private fun plan(id: String, label: String, groups: List<List<Int>>): Plan {
        val cores = groups.flatten()
        return Plan(cores.fold(0L) { m, c -> m or (1L shl c) }, cores.size.coerceIn(1, MAX_THREADS), label, id)
    }

    private val cpuCandidates: List<Plan> by lazy {
        if (unreadable) return@lazy listOf(fallback())
        val out = mutableListOf(plan("top", "${clusters[0].size} fastest cores", listOf(clusters[0])))
        if (clusters.size >= 2 && clusters[0].size + clusters[1].size <= MAX_THREADS)
            out += plan("toptwo", "${clusters[0].size + clusters[1].size} fast and medium cores", clusters.take(2))
        if (clusters.size >= 3) out += plan("nolow", "All but the ${clusters.last().size} slowest cores", clusters.dropLast(1))
        out.distinctBy { it.mask }
    }

    /** The groupings worth trying, most conservative first; the GPU comes last, and only when the user has switched it on and the phone has one. */
    val candidates: List<Plan> get() = if (GpuSupport.usable) cpuCandidates + gpuPlan() else cpuCandidates

    /** The GPU still needs the CPU for the parts it does not run, so it keeps the default core grouping for those. */
    fun gpuPlan(): Plan = default().let { Plan(it.mask, it.threads, "GPU (${GpuSupport.deviceName ?: "Vulkan"}) with ${it.threads} CPU threads", "gpu", gpu = true) }

    private fun fallback(): Plan = if (coreCount == 8) Plan(0xC0L, 2, "2 fastest cores (assumed)", "top") else Plan(0L, 2, "Any 2 cores", "top")

    /** Used before anything is measured: the fastest group if it has at least two cores, otherwise the two fastest groups. */
    fun default(): Plan {
        if (unreadable) return fallback()
        return if (clusters[0].size >= 2) cpuCandidates.first() else cpuCandidates.getOrElse(1) { cpuCandidates.first() }
    }

    /** Stamp of everything that should invalidate a measurement: the chip, its core layout, and the installed build. */
    fun tuneKey(): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
        val updated = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        return "$soc|${clusters.joinToString(",") { it.size.toString() }}|$updated|gpu=${GpuSupport.enabled}"
    }

    fun tuned(): Plan? {
        if (prefs.getString("tuned_key", null) != tuneKey()) return null
        val id = prefs.getString("tuned_id", null) ?: return null
        return candidates.firstOrNull { it.id == id }
    }

    fun manual(): Plan? {
        if (prefs.getString("cpu_mode", "auto") != "manual") return null
        val base = candidates.firstOrNull { it.id == prefs.getString("cpu_manual", "") } ?: return null
        val threads = prefs.getInt("cpu_threads", 0)
        return if (threads in 1 until base.threads) Plan(base.mask, threads, base.label, base.id) else base
    }

    /** What the models use right now: the user's choice, else the measured one, else the default. */
    fun current(): Plan = manual() ?: tuned() ?: default()

    fun saveTuned(plan: Plan, bestMs: Long, defaultMs: Long) {
        prefs.edit().putString("tuned_key", tuneKey()).putString("tuned_id", plan.id).putLong("tuned_ms", bestMs).putLong("tuned_default_ms", defaultMs).apply()
    }

    fun tunedMs() = if (prefs.getString("tuned_key", null) == tuneKey()) prefs.getLong("tuned_ms", 0) to prefs.getLong("tuned_default_ms", 0) else null

    fun setAuto() = prefs.edit().putString("cpu_mode", "auto").remove("cpu_threads").apply()
    fun setManual(id: String, threads: Int) = prefs.edit().putString("cpu_mode", "manual").putString("cpu_manual", id).putInt("cpu_threads", threads).apply()
    val isManual get() = prefs.getString("cpu_mode", "auto") == "manual"

    private const val MAX_THREADS = 6
}
