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
 *
 * It also keeps the open chat: a follow-up continues from the model's own memory of the exchange, so only the
 * new words are processed, and the conversation is re-read from its text if that memory was dropped. It lives in
 * memory only and lasts until [endConversation].
 */
class Answerer(private val files: ModelFiles, private val only: ModelEntry? = null) {
    private var engine: LlmEngine? = null
    private var engineFile: File? = null
    private val system = "You are a helpful assistant built into the user's phone. Answer the question directly and accurately " +
        "in plain text, in a few sentences and under 100 words unless more is clearly needed. Do not use markdown. " +
        "Sometimes results from searching the phone are provided: use them only if they help answer the question, " +
        "and otherwise ignore them and answer from your own knowledge. Never mention that you searched or could not find apps or contacts. " +
        "The conversation may continue: treat a short follow-up as being about what was just discussed."

    /** One exchange: what was sent as the user's words (search context included) and what the model wrote back. */
    private class Turn(val userText: String, val answer: String)

    private val lock = Any()
    private val turns = ArrayList<Turn>()
    private var kvLive = false // the engine's context holds exactly the conversation in [turns]
    private var epoch = 0

    val available get() = files.answer.exists()

    fun endConversation() = synchronized(lock) { turns.clear(); kvLive = false; epoch++ }

    /** [followUp] continues the conversation; otherwise the question starts a new one. */
    fun answer(question: String, results: List<Item>, followUp: Boolean = false, onToken: (String) -> Boolean): String? {
        val model = only ?: files.answerModel
        val file = ModelStore.file(model.parts.first())
        if (engineFile != file) { engine?.unload(); engine = null; synchronized(lock) { kvLive = false } }
        val e = engine ?: LlmEngine(file, model.format.prefix(system), nCtx = N_CTX, cacheDir = files.cacheDir).also { engine = it; engineFile = file }
        if (!e.load()) return null
        val fmt = model.format
        val skip = model.skipThinking
        val context = if (results.isEmpty()) "" else
            "Results from searching the phone (use only if relevant):\n" +
                results.take(8).joinToString("\n") { "- ${it.kind}: ${it.title}" + if (it.sub.isNotEmpty()) " (${it.sub.take(80)})" else "" } + "\n\n"
        val userText = "${context}Question: ${question.trim()}"

        val history: List<Turn>
        val continuing: Boolean
        val epoch0: Int
        synchronized(lock) {
            if (!followUp || turns.isEmpty()) { turns.clear(); kvLive = false }
            history = turns.toList()
            continuing = kvLive
            epoch0 = epoch
        }

        var streamed = false
        var cancelled = false
        val listener = object : NativeLlm.TokenListener {
            override fun onToken(piece: String): Boolean {
                streamed = true
                val keep = onToken(piece)
                if (!keep) cancelled = true
                return keep
            }
        }

        var out: String? = null
        if (history.isNotEmpty() && continuing) {
            // The model still holds the conversation: send only the new turn. Null means it would not fit.
            out = e.chat(fmt.turn(userText, skip), MAX_TOKENS, reset = false, listener = listener)
        }
        if (out == null && !streamed && !cancelled) {
            // Start from the cached prefix and re-read as much of the conversation as fits.
            var h = history
            val budget = (e.contextSize - MAX_TOKENS - 200) * CHARS_PER_TOKEN
            while (h.isNotEmpty() && h.sumOf { it.userText.length + it.answer.length + 64 } + userText.length > budget) h = h.drop(1)
            out = e.chat(replay(fmt, skip, h, userText), MAX_TOKENS, reset = true, listener = listener)
            if (out == null && !streamed && !cancelled && h.isNotEmpty()) out = e.chat(replay(fmt, skip, emptyList(), userText), MAX_TOKENS, reset = true, listener = listener)
        }

        synchronized(lock) {
            if (epoch != epoch0) return out // the conversation was ended while this answer was being written
            if (out != null && out.isNotEmpty()) {
                // A stopped answer counts too: the user saw it, so the chat carries on from it.
                turns.add(Turn(userText, out))
                while (turns.size > MAX_TURNS) turns.removeAt(0)
                kvLive = true // a stopped answer is in the context exactly as shown, so the next turn can carry straight on
            } else {
                kvLive = false // the context now ends in a half-written answer
            }
        }
        return out
    }

    private fun replay(fmt: ChatFormat, skip: Boolean, history: List<Turn>, userText: String): String {
        if (history.isEmpty()) return userText + fmt.suffix(skip)
        val sb = StringBuilder()
        history.forEachIndexed { i, t ->
            sb.append(if (i == 0) t.userText + fmt.suffix(skip) else fmt.turn(t.userText, skip)).append(t.answer)
        }
        return sb.append(fmt.turn(userText, skip)).toString()
    }

    val isLoaded get() = engine?.isLoaded == true
    fun stats() = engine?.stats() ?: ""
    fun unload() { engine?.unload(); synchronized(lock) { kvLive = false } } // the words are kept: the next follow-up re-reads them

    companion object {
        private const val N_CTX = 2048
        private const val MAX_TOKENS = 256
        private const val MAX_TURNS = 6
        private const val CHARS_PER_TOKEN = 3 // deliberately low: an estimate that is too big only drops an old turn sooner
        /** Models sometimes emit markdown even when told not to; strip the common marks for display. */
        fun clean(text: String): String = text
            .replace(Regex("\\*\\*|__|`{1,3}"), "")
            .replace(Regex("(?m)^\\s*#{1,6}\\s*"), "")
            .replace(Regex("(?m)^\\s*[*-]\\s+"), "• ")
            .trim()
    }
}
