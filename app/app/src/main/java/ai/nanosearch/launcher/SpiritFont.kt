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
object SpiritFont {
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
    private var busy = false

    init { swap(typeface, 0) }

    private fun swap(tf: Typeface?, style: Int) {
        if (busy) return
        busy = true
        super.setTypeface(SpiritFont.get(context, SpiritFont.weightOf(tf, style)))
        busy = false
    }

    override fun setTypeface(tf: Typeface?, style: Int) { swap(tf, style) }
    override fun setTypeface(tf: Typeface?) { swap(tf, 0) }
}

class NEdit @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : EditText(context, attrs) {
    private var busy = false

    init { swap(typeface, 0) }

    private fun swap(tf: Typeface?, style: Int) {
        if (busy) return
        busy = true
        super.setTypeface(SpiritFont.get(context, SpiritFont.weightOf(tf, style)))
        busy = false
    }

    override fun setTypeface(tf: Typeface?, style: Int) { swap(tf, style) }
    override fun setTypeface(tf: Typeface?) { swap(tf, 0) }
}
