package ai.nanosearch.launcher

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.widget.EditText
import android.widget.TextView

/**
 * The Classic style's typeface: Source Sans 3, a humanist sans in the spirit of the one that style is modelled on. It is a variable font, so one file serves every weight.
 * [NText] and [NEdit] are TextView and EditText that use it when Classic is on, mapping whatever weight the code asked for onto the font's own weight axis.
 */
object ClassicFont {
    private val cache = HashMap<Int, Typeface>()

    fun get(context: Context, weight: Int): Typeface = synchronized(cache) {
        val w = weight.coerceIn(200, 900)
        cache.getOrPut(w) {
            runCatching { Typeface.Builder(context.assets, "SourceSans3.ttf").setFontVariationSettings("'wght' $w").build() }.getOrNull() ?: Typeface.DEFAULT
        }
    }

    fun weightOf(tf: Typeface?, style: Int): Int {
        val base = tf?.weight ?: 400
        return if (style and Typeface.BOLD != 0) maxOf(base, 700) else base
    }
}

class NText @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : TextView(context, attrs) {
    private val classic = AppSettings.isClassic(context)
    private var busy = false

    init { if (classic) swap(typeface, 0) }

    private fun swap(tf: Typeface?, style: Int) {
        if (!classic || busy) return
        busy = true
        super.setTypeface(ClassicFont.get(context, ClassicFont.weightOf(tf, style)))
        busy = false
    }

    override fun setTypeface(tf: Typeface?, style: Int) { if (classic) swap(tf, style) else super.setTypeface(tf, style) }
    override fun setTypeface(tf: Typeface?) { if (classic) swap(tf, 0) else super.setTypeface(tf) }
}

class NEdit @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : EditText(context, attrs) {
    private val classic = AppSettings.isClassic(context)
    private var busy = false

    init { if (classic) swap(typeface, 0) }

    private fun swap(tf: Typeface?, style: Int) {
        if (!classic || busy) return
        busy = true
        super.setTypeface(ClassicFont.get(context, ClassicFont.weightOf(tf, style)))
        busy = false
    }

    override fun setTypeface(tf: Typeface?, style: Int) { if (classic) swap(tf, style) else super.setTypeface(tf, style) }
    override fun setTypeface(tf: Typeface?) { if (classic) swap(tf, 0) else super.setTypeface(tf) }
}
