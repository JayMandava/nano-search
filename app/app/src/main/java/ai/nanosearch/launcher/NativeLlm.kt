package ai.nanosearch.launcher

/** What the engine needs from the native layer. There are two implementations of it: the CPU library and the optional GPU one. */
interface LlmNative {
    /** [gpuLayers] is how many layers go to the GPU (0 = CPU only). Returns 0 if the model could not be loaded. */
    fun load(path: String, threads: Int, nCtx: Int, cpuMask: Long, gpuLayers: Int): Long
    /** Returns the prefix token count, or -1 on failure. A non-null [cachePath] is read if present and written if not. */
    fun setPrefix(handle: Long, text: String, cachePath: String?): Int
    fun complete(handle: Long, suffix: String, grammar: String?, maxTokens: Int, listener: NativeLlm.TokenListener?): String?
    /** One conversation turn. With [reset] the context is rewound to the cached prefix; without it the text continues what is already there. Null if it does not fit. */
    fun chat(handle: Long, suffix: String, maxTokens: Int, listener: NativeLlm.TokenListener?, reset: Boolean): String?
    /** Tokens currently in the context. */
    fun kvUsed(handle: Long): Int
    fun stats(handle: Long): String
    fun free(handle: Long)
}

/** JNI bindings for nanollm.cpp, CPU build. Not thread-safe; [LlmEngine] serializes access. */
class NativeLlm : LlmNative {
    interface TokenListener {
        /** Return false to stop generation. */
        fun onToken(piece: String): Boolean
    }

    external override fun load(path: String, threads: Int, nCtx: Int, cpuMask: Long, gpuLayers: Int): Long
    external override fun setPrefix(handle: Long, text: String, cachePath: String?): Int
    external override fun complete(handle: Long, suffix: String, grammar: String?, maxTokens: Int, listener: TokenListener?): String?
    external override fun chat(handle: Long, suffix: String, maxTokens: Int, listener: TokenListener?, reset: Boolean): String?
    external override fun kvUsed(handle: Long): Int
    external override fun stats(handle: Long): String
    external override fun free(handle: Long)

    companion object {
        init {
            System.loadLibrary("nanollm")
        }
    }
}

/**
 * The same bindings on the GPU build of the library (Vulkan). Loading the library does not touch the GPU driver; the first call does.
 * It is only ever used when the user has turned the GPU on in Settings > Advanced.
 */
class NativeLlmGpu : LlmNative {
    external override fun load(path: String, threads: Int, nCtx: Int, cpuMask: Long, gpuLayers: Int): Long
    external override fun setPrefix(handle: Long, text: String, cachePath: String?): Int
    external override fun complete(handle: Long, suffix: String, grammar: String?, maxTokens: Int, listener: NativeLlm.TokenListener?): String?
    external override fun chat(handle: Long, suffix: String, maxTokens: Int, listener: NativeLlm.TokenListener?, reset: Boolean): String?
    external override fun kvUsed(handle: Long): Int
    external override fun stats(handle: Long): String
    external override fun free(handle: Long)

    /** One line per GPU the backend can see, "name | free/total bytes"; empty if none. */
    external fun gpuInfo(): String

    companion object {
        /** False if the library is missing from this build. */
        val present: Boolean by lazy { runCatching { System.loadLibrary("nanollm_gpu") }.isSuccess }
    }
}
