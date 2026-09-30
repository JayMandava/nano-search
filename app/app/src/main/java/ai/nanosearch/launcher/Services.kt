package ai.nanosearch.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Process-wide model state. The launcher's activity is destroyed and recreated whenever the user leaves
 * and comes back (its task resets), but a loaded model is over a gigabyte: it must belong to the process,
 * not to any one activity, or every recreation would load another copy.
 */
object Services {
    private const val PARSER_IDLE_MS = 5 * 60_000L
    private const val ANSWER_IDLE_MS = 30_000L

    private lateinit var appContext: Context
    private val main = Handler(Looper.getMainLooper())

    /** Models are slow and memory-hungry: one thread, never queued behind index work. */
    val llm = Executors.newSingleThreadExecutor()

    val models by lazy { ModelFiles(appContext) }
    val parser by lazy { QueryParser(appContext, models) }
    val answerer by lazy { Answerer(models) }
    val clip by lazy { ClipEngine(appContext) }
    val ocr by lazy { OcrEngine(appContext) }

    private val unloadParser = Runnable { llm.execute { parser.unload() } }
    private val unloadAnswerer = Runnable { llm.execute { answerer.unload() } }
    private val unloadClip = Runnable { clip.unloadText(); clip.unloadImage(); ocr.unload() }

    fun init(context: Context) {
        appContext = context.applicationContext
        ModelStore.init(appContext)
    }

    fun parserUsed() {
        main.removeCallbacks(unloadParser)
        main.postDelayed(unloadParser, PARSER_IDLE_MS)
    }

    fun answererUsed() {
        main.removeCallbacks(unloadAnswerer)
        main.postDelayed(unloadAnswerer, ANSWER_IDLE_MS)
    }

    fun answererBusy() = main.removeCallbacks(unloadAnswerer)

    /** The image models are 45 MB and 170 MB in memory; let go of them two minutes after the last use. */
    fun clipUsed() {
        main.removeCallbacks(unloadClip)
        main.postDelayed(unloadClip, 2 * 60_000L)
    }
}
