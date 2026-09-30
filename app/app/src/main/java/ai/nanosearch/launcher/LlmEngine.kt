package ai.nanosearch.launcher

import java.io.File

/**
 * One loaded model with a cached prompt prefix. All calls are synchronized: run them on a
 * background thread, never the UI thread.
 */
class LlmEngine(
    private val modelFile: File,
    private val prefix: String,
    private val nCtx: Int = 2048,
    private val cacheDir: File? = null,
    /** Which cores and threads to use; null means whatever [CpuPlan.current] says. */
    private val plan: CpuPlan.Plan? = null,
    /** If a GPU plan cannot load, carry on with the CPU instead (what users want); the tuner turns this off so it measures what it asked for. */
    private val cpuFallback: Boolean = true,
) {
    private val cpuNative = NativeLlm()
    private var gpuNative: NativeLlmGpu? = null
    private var native: LlmNative = cpuNative
    private var onGpu = false
    private var handle = 0L

    @get:Synchronized
    val isLoaded get() = handle != 0L

    var loadMs = 0L
        private set

    @Synchronized
    fun load(): Boolean {
        if (handle != 0L) return true
        val t0 = System.nanoTime()
        val p = plan ?: CpuPlan.current()
        var ok = tryLoad(p)
        if (!ok && p.gpu) {
            GpuSupport.block("The GPU could not run this model on this phone, so the CPU is used.")
            if (cpuFallback) ok = tryLoad(CpuPlan.default())
        }
        if (ok) loadMs = (System.nanoTime() - t0) / 1_000_000
        return ok
    }

    private fun tryLoad(p: CpuPlan.Plan): Boolean {
        if (p.gpu && !NativeLlmGpu.present) return false
        val backend: LlmNative = if (p.gpu) (gpuNative ?: NativeLlmGpu().also { gpuNative = it }) else cpuNative
        val attempt = {
            val h = backend.load(modelFile.absolutePath, p.threads, nCtx, p.mask, if (p.gpu) GPU_LAYERS else 0)
            if (h == 0L) false
            else if (backend.setPrefix(h, prefix, cachePath()) < 0) { backend.free(h); false }
            else { handle = h; native = backend; onGpu = p.gpu; true }
        }
        return if (p.gpu) runCatching { GpuSupport.guarded(attempt) }.getOrDefault(false) else attempt()
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
        if (!onGpu || GpuSupport.verified) return native.complete(handle, suffix, grammar, maxTokens, listener)
        // The first generation on the GPU compiles its shaders, the likeliest place for a driver to misbehave, so it runs with the crash mark set.
        return GpuSupport.guarded { native.complete(handle, suffix, grammar, maxTokens, listener) }?.also { GpuSupport.markVerified() }
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
private const val GPU_LAYERS = 99 // "all of them": llama.cpp clamps it to the model's layer count
