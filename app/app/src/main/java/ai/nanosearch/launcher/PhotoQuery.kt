package ai.nanosearch.launcher

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Understands photo requests with plain rules, so it is instant and needs no language model:
 * "photos from Goa", "pictures from last September", "images from August 2023", "photos of dogs", "beach photos from Goa last week".
 * It only fires when the request names photos (photo, picture, pic, image, selfie); otherwise it returns null and normal search runs.
 *
 * It separates three things: WHEN ([from] inclusive and [to] exclusive, epoch milliseconds, null = unbounded), WHERE ([place], words after
 * from/in/at/near) and WHAT IS IN THE PICTURE ([content]: words before the noun, "beach photos", or after of/with/showing, "photos of dogs").
 * Words with no connector ("photos goa") land in [content]; the search step decides whether that is really a place.
 */
object PhotoQuery {
    data class Q(val from: Long?, val to: Long?, val place: String?, val content: String?, val screenshots: Boolean = false)

    private val NOUNS = setOf("photo", "photos", "picture", "pictures", "pic", "pics", "image", "images", "selfie", "selfies", "screenshot", "screenshots")
    private val FILLER = setOf(
        "the", "my", "me", "show", "find", "all", "that", "i", "were", "was", "a", "an", "to", "for", "get", "open", "see", "look", "up",
        "search", "any", "some", "there", "have", "do", "did", "and", "with", "of", "from", "in", "at", "on", "near", "during", "taken",
        "last", "this", "past", "is", "are", "you", "can", "please", "take", "give",
    )
    private val CONTENT_CONNECTORS = setOf("of", "with", "showing", "containing", "featuring", "having", "about", "where", "that")
    private val PLACE_CONNECTORS = setOf("from", "in", "at", "near", "around", "during", "taken", "on")
    private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")

    fun parse(query: String, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): Q? {
        val words = query.lowercase(Locale.ENGLISH).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (words.none { it in NOUNS }) return null
        val rest = words.toMutableList()
        val screenshots = words.any { it == "screenshot" || it == "screenshots" }

        fun cal(t: Long = now) = Calendar.getInstance(zone).apply { timeInMillis = t }
        fun startOfDay(c: Calendar) = c.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        fun take(vararg seq: String): Boolean {
            val i = rest.indices.firstOrNull { i -> seq.indices.all { k -> i + k < rest.size && rest[i + k] == seq[k] } } ?: return false
            repeat(seq.size) { rest.removeAt(i) }
            return true
        }

        var from: Long? = null
        var to: Long? = null
        val today = startOfDay(cal()).timeInMillis
        val day = 24L * 3600 * 1000

        when {
            take("today") -> { from = today; to = null }
            take("yesterday") -> { from = today - day; to = today }
            take("this", "week") -> { from = startOfWeek(cal(), zone); to = null }
            take("last", "week") -> { val s = startOfWeek(cal(), zone); from = startOfWeek(cal(s - 1), zone); to = s }
            take("this", "month") -> { from = startOfDay(cal()).apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis; to = null }
            take("last", "month") -> {
                val s = startOfDay(cal()).apply { set(Calendar.DAY_OF_MONTH, 1) }
                to = s.timeInMillis; from = s.apply { add(Calendar.MONTH, -1) }.timeInMillis
            }
            take("this", "year") -> { from = startOfDay(cal()).apply { set(Calendar.DAY_OF_YEAR, 1) }.timeInMillis; to = null }
            take("last", "year") -> {
                val s = startOfDay(cal()).apply { set(Calendar.DAY_OF_YEAR, 1) }
                to = s.timeInMillis; from = s.apply { add(Calendar.YEAR, -1) }.timeInMillis
            }
        }
        if (from == null) {
            // "last 10 days", "past 3 weeks", "last 2 months"
            val m = Regex("\\b(last|past)\\s+(\\d{1,3})\\s+(day|days|week|weeks|month|months)\\b").find(rest.joinToString(" "))
            if (m != null) {
                val n = m.groupValues[2].toInt()
                val c = cal()
                when {
                    m.groupValues[3].startsWith("day") -> c.add(Calendar.DAY_OF_YEAR, -n)
                    m.groupValues[3].startsWith("week") -> c.add(Calendar.WEEK_OF_YEAR, -n)
                    else -> c.add(Calendar.MONTH, -n)
                }
                from = c.timeInMillis
                val joined = rest.joinToString(" ").replace(m.value, " ")
                rest.clear(); rest.addAll(joined.split(' ').filter { it.isNotEmpty() })
            }
        }
        if (from == null) {
            // a month name, optionally with a year ("september", "last september", "august 2023")
            val mi = rest.indexOfFirst { w -> MONTHS.any { it == w || (w.length >= 3 && w == it.take(3)) } }
            if (mi >= 0) {
                val month = MONTHS.indexOfFirst { rest[mi] == it || rest[mi] == it.take(3) }
                rest.removeAt(mi)
                val isLast = take("last")
                val isThis = take("this")
                val yi = rest.indexOfFirst { it.matches(Regex("(19|20)\\d\\d")) }
                val nowCal = cal()
                val curMonth = nowCal.get(Calendar.MONTH)
                val curYear = nowCal.get(Calendar.YEAR)
                // "last September" is the most recent one that has finished (last year's, if it is September now);
                // plain "september" is the current or most recent one; "this september" is this year's.
                val year = when {
                    yi >= 0 -> rest.removeAt(yi).toInt()
                    isLast -> if (month >= curMonth) curYear - 1 else curYear
                    isThis -> curYear
                    else -> if (month > curMonth) curYear - 1 else curYear
                }
                val s = startOfDay(cal()).apply { set(Calendar.YEAR, year); set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, 1) }
                from = s.timeInMillis; to = s.apply { add(Calendar.MONTH, 1) }.timeInMillis
            }
        }
        if (from == null) {
            val yi = rest.indexOfFirst { it.matches(Regex("(19|20)\\d\\d")) }
            if (yi >= 0) {
                val year = rest.removeAt(yi).toInt()
                val s = startOfDay(cal()).apply { set(Calendar.YEAR, year); set(Calendar.DAY_OF_YEAR, 1) }
                from = s.timeInMillis; to = s.apply { add(Calendar.YEAR, 1) }.timeInMillis
            }
        }

        // What is left, once the time words are gone, is split by its connectors into content and place.
        var mode = 0 // 0 before the noun, 1 after it, 2 content (of/with/...), 3 place (from/in/at/...)
        val content = ArrayList<String>()
        val place = ArrayList<String>()
        for (w in rest) {
            when {
                w in NOUNS -> if (mode == 0) mode = 1
                w in CONTENT_CONNECTORS -> mode = 2
                w in PLACE_CONNECTORS -> mode = 3
                w in FILLER -> Unit
                mode == 3 -> place.add(w)
                else -> content.add(w) // before the noun ("beach photos"), after it with no connector, or after of/with
            }
        }
        return Q(from, to, place.joinToString(" ").ifBlank { null }, content.joinToString(" ").ifBlank { null }, screenshots)
    }

    private fun startOfWeek(c: Calendar, zone: TimeZone): Long {
        c.timeZone = zone
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        while (c.get(Calendar.DAY_OF_WEEK) != c.firstDayOfWeek) c.add(Calendar.DAY_OF_YEAR, -1)
        return c.timeInMillis
    }
}
