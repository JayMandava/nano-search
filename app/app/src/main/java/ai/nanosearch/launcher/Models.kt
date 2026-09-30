package ai.nanosearch.launcher

import android.content.Context
import org.json.JSONObject
import java.io.File

data class Parsed(val action: String, val kind: String, val text: String)

class ParseResult(val parsed: Parsed?, val totalMs: Long, val coldLoadMs: Long, val stats: String)

/** Model files live in the app's private storage (<files>/models); which one each job uses is the user's choice in Settings. */
class ModelFiles(context: Context) {
    val dir = File(context.filesDir, "models").apply { mkdirs() }
    val cacheDir = File(context.cacheDir, "prefix")
    // The user's current choice for each job; see ModelStore.
    val parserModel get() = ModelStore.selected(Slot.PARSER)
    val answerModel get() = ModelStore.selected(Slot.ANSWER)
    val parser get() = ModelStore.file(parserModel.parts.first())
    val answer get() = ModelStore.file(answerModel.parts.first())
    val whisper get() = ModelStore.file(ModelStore.selected(Slot.VOICE).parts.first()) // speech-to-text model for voice search
}

/** Turns "call mom" style requests into {action, kind, text} with a small grammar-constrained model. */
class QueryParser(context: Context, private val files: ModelFiles, private val only: ModelEntry? = null) {
    private val grammar = context.assets.open("parser.gbnf").bufferedReader().use { it.readText() }
    private val system = context.assets.open("parser_system.txt").bufferedReader().use { it.readText().trim() }
    private var engine: LlmEngine? = null
    private var engineFile: File? = null

    val available get() = files.parser.exists()
    val isLoaded get() = engine?.isLoaded == true

    fun parse(query: String): ParseResult {
        val t0 = System.nanoTime()
        val model = only ?: files.parserModel
        val file = ModelStore.file(model.parts.first())
        if (engineFile != file) { engine?.unload(); engine = null }
        val e = engine ?: LlmEngine(file, model.format.prefix(system), cacheDir = files.cacheDir).also { engine = it; engineFile = file }
        val wasLoaded = e.isLoaded
        if (!e.load()) return ParseResult(null, 0, 0, "load failed")
        val out = e.complete(query.trim() + model.format.suffix(model.skipThinking), grammar, 48)
        val parsed = out?.let { runCatching { toParsed(it) }.getOrNull() }
        return ParseResult(parsed, (System.nanoTime() - t0) / 1_000_000, if (wasLoaded) 0 else e.loadMs, e.stats())
    }

    private fun toParsed(json: String): Parsed {
        val o = JSONObject(json)
        return Parsed(o.getString("action"), o.getString("kind"), o.getString("text").trim())
    }

    fun unload() { engine?.unload() }
}

/**
 * Streams an answer from a larger model. It is a general assistant first: phone search results are passed as
 * optional context, used only when they help answer the question.
 */
class Answerer(private val files: ModelFiles, private val only: ModelEntry? = null) {
    private var engine: LlmEngine? = null
    private var engineFile: File? = null
    private val system = "You are a helpful assistant built into the user's phone. Answer the question directly and accurately " +
        "in plain text, in a few sentences and under 100 words unless more is clearly needed. Do not use markdown. " +
        "Sometimes results from searching the phone are provided: use them only if they help answer the question, " +
        "and otherwise ignore them and answer from your own knowledge. Never mention that you searched or could not find apps or contacts."

    val available get() = files.answer.exists()

    fun answer(question: String, results: List<Item>, onToken: (String) -> Boolean): String? {
        val model = only ?: files.answerModel
        val file = ModelStore.file(model.parts.first())
        if (engineFile != file) { engine?.unload(); engine = null }
        val e = engine ?: LlmEngine(file, model.format.prefix(system), nCtx = 1024, cacheDir = files.cacheDir).also { engine = it; engineFile = file }
        if (!e.load()) return null
        val context = if (results.isEmpty()) "" else
            "Results from searching the phone (use only if relevant):\n" +
                results.take(8).joinToString("\n") { "- ${it.kind}: ${it.title}" + if (it.sub.isNotEmpty()) " (${it.sub.take(80)})" else "" } + "\n\n"
        val listener = object : NativeLlm.TokenListener {
            override fun onToken(piece: String) = onToken(piece)
        }
        return e.complete("${context}Question: ${question.trim()}" + model.format.suffix(model.skipThinking), null, 256, listener)
    }

    val isLoaded get() = engine?.isLoaded == true
    fun stats() = engine?.stats() ?: ""
    fun unload() { engine?.unload() }

    companion object {
        /** Models sometimes emit markdown even when told not to; strip the common marks for display. */
        fun clean(text: String): String = text
            .replace(Regex("\\*\\*|__|`{1,3}"), "")
            .replace(Regex("(?m)^\\s*#{1,6}\\s*"), "")
            .replace(Regex("(?m)^\\s*[*-]\\s+"), "• ")
            .trim()
    }
}
