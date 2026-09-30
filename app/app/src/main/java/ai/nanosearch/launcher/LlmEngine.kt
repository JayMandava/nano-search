package ai.nanosearch.launcher

import java.io.File

/**
 * One loaded model with a cached prompt prefix. All calls are synchronized: run them on a
 * background thread, never the UI thread.
 */
class LlmEngine(
    private val modelFile: File,
    private val prefix: String,
    private val threads: Int = 2,
    private val nCtx: Int = 2048,
    private val cacheDir: File? = null,
) {
    private val native = NativeLlm()
    private var handle = 0L

    @get:Synchronized
    val isLoaded get() = handle != 0L

    var loadMs = 0L
        private set

    @Synchronized
    fun load(): Boolean {
        if (handle != 0L) return true
        val t0 = System.nanoTime()
        val h = native.load(modelFile.absolutePath, threads, nCtx, CpuInfo.bigCoreMask())
        if (h == 0L) return false
        if (native.setPrefix(h, prefix, cachePath()) < 0) {
            native.free(h)
            return false
        }
        handle = h
        loadMs = (System.nanoTime() - t0) / 1_000_000
        return true
    }

    /**
     * The processed prefix is identical on every load, so it is cached on disk. The key covers everything
     * that changes it: the model file, the prefix text, and [CACHE_VERSION] (bump when the native layer changes).
     */
    private fun cachePath(): String? {
        val dir = cacheDir ?: return null
        dir.mkdirs()
        val key = "$CACHE_VERSION|${modelFile.length()}|${modelFile.lastModified()}|$prefix".hashCode().toUInt().toString(16)
        return File(dir, "prefix-${modelFile.nameWithoutExtension}-$key.bin").absolutePath
    }

    @Synchronized
    fun complete(suffix: String, grammar: String?, maxTokens: Int, listener: NativeLlm.TokenListener? = null): String? {
        if (handle == 0L) return null
        return native.complete(handle, suffix, grammar, maxTokens, listener)
    }

    @Synchronized
    fun stats(): String = if (handle == 0L) "unloaded" else native.stats(handle)

    @Synchronized
    fun unload() {
        if (handle != 0L) native.free(handle)
        handle = 0L
    }
}

private const val CACHE_VERSION = 1

object CpuInfo {
    /**
     * Bitmask of the fastest cores. Inference is fastest and steadiest pinned to them: on
     * big.LITTLE phones, adding the little cores made generation slower and noisier.
     */
    fun bigCoreMask(): Long {
        val freqs = (0 until Runtime.getRuntime().availableProcessors()).map { cpu ->
            runCatching { File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrDefault(0L)
        }
        val max = freqs.maxOrNull() ?: 0L
        if (max == 0L) return if (freqs.size == 8) 0xC0L else 0L // sysfs unreadable: assume 6+2 layout
        var mask = 0L
        freqs.forEachIndexed { i, f -> if (f == max) mask = mask or (1L shl i) }
        return mask
    }
}
