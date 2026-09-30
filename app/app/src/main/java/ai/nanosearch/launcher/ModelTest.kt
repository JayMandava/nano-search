package ai.nanosearch.launcher

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf

/**
 * "Try this model": runs a model on the phone for a few seconds and reports what it did, so a choice can be made on evidence.
 * Understanding models get 14 labelled requests; answering models get one question.
 */
object ModelTest {
    /** Result line by model id; absent = never run, "Testing…" while running. */
    val results = mutableStateMapOf<String, String>()

    fun canTest(entry: ModelEntry) = entry.slot == Slot.PARSER || entry.slot == Slot.ANSWER

    fun run(context: Context, entry: ModelEntry) {
        if (results[entry.id] == "Testing…") return
        results[entry.id] = "Testing…"
        val app = context.applicationContext
        Services.llm.execute {
            // Give the memory the regular models are holding back first: a 4B model needs room.
            Services.parser.unload(); Services.answerer.unload()
            results[entry.id] = runCatching { if (entry.slot == Slot.PARSER) testParser(app, entry) else testAnswer(entry) }.getOrElse { "Test failed: ${it.message}" }
        }
    }

    private fun testParser(app: Context, entry: ModelEntry): String {
        val rows = app.assets.open("parser_selftest.txt").bufferedReader().readLines().filter { it.isNotBlank() }.map { it.split('|') }
        val parser = QueryParser(app, Services.models, entry)
        var right = 0
        var totalMs = 0L
        var coldMs = 0L
        rows.forEachIndexed { i, (q, action, kind, text) ->
            val r = parser.parse(q)
            if (i == 0) coldMs = r.coldLoadMs else totalMs += r.totalMs
            val p = r.parsed
            if (p != null && p.action == action && p.kind == kind && text in p.text.lowercase()) right++
        }
        parser.unload()
        val avg = totalMs / (rows.size - 1).coerceAtLeast(1)
        return "$right of ${rows.size} right · about ${"%.1f".format(avg / 1000.0)} s a request · loads in ${"%.1f".format(coldMs / 1000.0)} s"
    }

    private fun testAnswer(entry: ModelEntry): String {
        val answerer = Answerer(Services.models, entry)
        val t0 = System.nanoTime()
        var first = 0L
        var pieces = 0
        val text = StringBuilder()
        answerer.answer("What is a nerite snail?", emptyList()) { piece ->
            if (pieces == 0) first = System.nanoTime()
            pieces++; text.append(piece); pieces < 60
        }
        val end = System.nanoTime()
        answerer.unload()
        val firstS = (first - t0) / 1e9
        val rate = if (end > first) pieces / ((end - first) / 1e9) else 0.0
        return "Loaded and first word in ${"%.1f".format(firstS)} s · ${"%.1f".format(rate)} tokens a second\n“${Answerer.clean(text.toString()).take(140)}…”"
    }
}
