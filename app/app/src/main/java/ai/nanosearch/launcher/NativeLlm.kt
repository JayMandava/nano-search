package ai.nanosearch.launcher

/** JNI bindings for nanollm.cpp. Not thread-safe; [LlmEngine] serializes access. */
class NativeLlm {
    interface TokenListener {
        /** Return false to stop generation. */
        fun onToken(piece: String): Boolean
    }

    external fun load(path: String, threads: Int, nCtx: Int, cpuMask: Long): Long
    /** Returns the prefix token count, or -1 on failure. A non-null [cachePath] is read if present and written if not. */
    external fun setPrefix(handle: Long, text: String, cachePath: String?): Int
    external fun complete(handle: Long, suffix: String, grammar: String?, maxTokens: Int, listener: TokenListener?): String?
    external fun stats(handle: Long): String
    external fun free(handle: Long)

    companion object {
        init {
            System.loadLibrary("nanollm")
        }
    }
}
