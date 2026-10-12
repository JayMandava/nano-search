package ai.nanosearch.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The ring itself: a thick brushed-silver bezel with nothing inside it. [glow] lights it green; [hot] is the stronger glow when an app is held over it. */
open class RingArt(context: Context) : View(context) {
    protected val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var glow = 0f
        set(v) { field = v; invalidate() }
    var hot = false
        set(v) { field = v; invalidate() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) / 2f - 4 * d
        val band = 15 * d
        // shadow under the bezel
        paint.shader = RadialGradient(cx, cy + 7 * d, r * 1.22f, 0x88000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy + 7 * d, r * 1.22f, paint)
        val lit = if (hot) 1f else glow
        if (lit > 0f) {
            val gr = width / 2f // the glow must fade out inside the view's own bounds, or it shows as a square
            val peak = ((if (hot) 0xCC else 0x88) * lit).toInt().coerceIn(0, 255) shl 24 or 0xB7F25A
            paint.shader = RadialGradient(cx, cy, gr, intArrayOf(0x00B7F25A, 0x00B7F25A, peak, 0x00B7F25A), floatArrayOf(0f, (r - band) / gr, (r - band * 0.2f) / gr, 1f), Shader.TileMode.CLAMP) // a halo round the bezel; the middle stays clear
            c.drawCircle(cx, cy, gr, paint)
        }
        // the clear middle: only a soft dark edge just inside the bezel gives it thickness
        paint.shader = RadialGradient(cx, cy, r - band, intArrayOf(0x00000000, 0x00000000, 0x66000000), floatArrayOf(0f, 0.78f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r - band, paint)
        // the brushed bezel, light and dark all the way round
        val metal = intArrayOf(0xFFFFFFFF.toInt(), 0xFF8B9299.toInt(), 0xFFEFF2F4.toInt(), 0xFF4D535A.toInt(), 0xFFF3F5F7.toInt(), 0xFF747B83.toInt(), 0xFFFFFFFF.toInt())
        paint.shader = android.graphics.SweepGradient(cx, cy, metal, floatArrayOf(0f, 0.17f, 0.33f, 0.52f, 0.7f, 0.86f, 1f))
        paint.style = Paint.Style.STROKE; paint.strokeWidth = band
        c.drawCircle(cx, cy, r - band / 2f, paint)
        // a turned groove down the middle of the bezel, and bright and dark hairlines either side
        paint.shader = null
        paint.strokeWidth = 1.1f * d; paint.color = 0x66000000
        c.drawCircle(cx, cy, r - band / 2f, paint)
        paint.strokeWidth = 1.4f * d; paint.color = 0xE6FFFFFF.toInt()
        c.drawCircle(cx, cy, r - 0.7f * d, paint)
        paint.color = 0xBB000000.toInt()
        c.drawCircle(cx, cy, r - band, paint)
        paint.style = Paint.Style.FILL
    }
}

/**
 * The dock's ring. Grab it and pull up: it follows your finger as far as the top of the screen, and the app drawer grows round it. A tap does nothing.
 * [listener] is told where the ring is, so the screen can follow it; it decides, on release, whether the drawer opens.
 */
class SenseRing(context: Context, private val rest: Float, private val listener: Listener) : RingArt(context) {
    interface Listener {
        fun onStart(ring: SenseRing, cx: Float, cy: Float)
        fun onPull(pull: Float, cx: Float, cy: Float)
        /** Returns true if the drawer opened (the ring then settles back quietly). */
        fun onRelease(pull: Float, velocityY: Float): Boolean
    }

    private var downY = 0f
    private var pull = 0f
    private var tracker: android.view.VelocityTracker? = null
    private var startX = 0f
    private var startY = 0f

    init { translationY = rest }

    fun screenCentre(): Pair<Float, Float> { val at = IntArray(2); getLocationOnScreen(at); return at[0] + width / 2f to at[1] + height / 2f }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downY = e.rawY; pull = 0f
                tracker?.recycle(); tracker = android.view.VelocityTracker.obtain().also { it.addMovement(e) }
                parent?.requestDisallowInterceptTouchEvent(true)
                animate().cancel()
                val (cx, cy) = screenCentre(); startX = cx; startY = cy
                glow = 1f
                listener.onStart(this, cx, cy)
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                pull = (downY - e.rawY).coerceAtLeast(0f)
                listener.onPull(pull, startX, startY - pull)
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                tracker?.addMovement(e); tracker?.computeCurrentVelocity(1000)
                val vy = tracker?.yVelocity ?: 0f
                tracker?.recycle(); tracker = null
                glow = 0f
                val opened = listener.onRelease(if (e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) 0f else pull, vy)
                if (!opened) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        return true
    }
}

/** The Sense dock: a dark glossy bar whose top edge rises in an arch to the middle, with a bright rim along the arch. */
class SenseArch(context: Context, private val topInset: Float = 0f) : android.graphics.drawable.Drawable() {
    private val d = context.resources.displayMetrics.density
    private val rise = 24 * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * d }
    private val body = android.graphics.Path()
    private val edge = android.graphics.Path()

    override fun onBoundsChange(b: android.graphics.Rect) {
        val apex = b.top + topInset          // the arch's crown; the space above it belongs to the ring
        val side = apex + rise
        edge.reset(); edge.moveTo(b.left.toFloat(), side); edge.quadTo(b.exactCenterX(), apex - rise, b.right.toFloat(), side)
        body.reset(); body.moveTo(b.left.toFloat(), b.bottom.toFloat()); body.lineTo(b.left.toFloat(), side)
        body.quadTo(b.exactCenterX(), apex - rise, b.right.toFloat(), side); body.lineTo(b.right.toFloat(), b.bottom.toFloat()); body.close()
        fill.shader = LinearGradient(0f, apex, 0f, b.bottom.toFloat(), intArrayOf(0xF2454A4F.toInt(), 0xF2191B1E.toInt(), 0xFF08090A.toInt()), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        rim.shader = LinearGradient(b.left.toFloat(), 0f, b.right.toFloat(), 0f, intArrayOf(0x66FFFFFF, 0xEEFFFFFF.toInt(), 0x66FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }

    private val dots = Paint().apply { shader = SenseMaterial.dots(context) }
    private val brushed = Paint().apply { shader = SenseMaterial.brushed(context); alpha = 60 }
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(c: Canvas) {
        c.drawPath(body, fill)
        c.save(); c.clipPath(body)
        c.drawRect(bounds, dots)       // perforated carbon over the glossy dark
        c.restore()
        // a soft light rolling off the crown of the arch
        gloss.shader = LinearGradient(0f, bounds.top + topInset, 0f, bounds.top + topInset + 32 * d, 0x30FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.save(); c.clipPath(body); c.drawRect(bounds.left.toFloat(), bounds.top + topInset, bounds.right.toFloat(), bounds.top + topInset + 34 * d, gloss); c.restore()
        c.drawPath(edge, rim)
    }
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(cf: android.graphics.ColorFilter?) = Unit
    @Deprecated("Drawable API") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * The tab bar of the Sense app drawer: three glossy tabs, the chosen one raised, each with a small drawn icon (the four squares, the star, the download arrow).
 */
class SenseTabBar(context: Context, private val tabs: List<Triple<String, String, Int>>, private val accent: Int, private val onSelect: (String) -> Unit) : View(context) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var selected = tabs.first().first
    private val brush = Paint().apply { shader = SenseMaterial.brushed(context) }

    fun select(key: String) { selected = key; invalidate() }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_UP) {
            val i = (e.x / (width / tabs.size.toFloat())).toInt().coerceIn(0, tabs.size - 1)
            onSelect(tabs[i].first)
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.shader = LinearGradient(0f, 0f, 0f, h, 0xFF2A2C2F.toInt(), 0xFF0C0D0E.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint); paint.shader = null
        brush.alpha = 55; c.drawRect(0f, 0f, w, h, brush)
        paint.color = 0x77FFFFFF; c.drawRect(0f, 0f, w, 1f * d, paint)
        val cell = w / tabs.size
        tabs.forEachIndexed { i, (key, label, icon) ->
            val x0 = i * cell
            val on = key == selected
            if (on) {
                // the chosen tab is a raised glass key, lit and bordered all the way round
                // the outer tabs stand in from the screen's edge and its rounded corners, so their borders are never cut off
                val r = android.graphics.RectF(x0 + (if (i == 0) 14 * d else 4 * d), 6 * d, x0 + cell - (if (i == tabs.size - 1) 14 * d else 4 * d), h - 12 * d)
                paint.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(0xFF74777C.toInt(), 0xFF4A4D52.toInt(), 0xFF2C2E31.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                c.drawRoundRect(r, 12 * d, 12 * d, paint); paint.shader = null
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.4f * d
                paint.shader = LinearGradient(0f, r.top, 0f, r.bottom, 0xFFFFFFFF.toInt(), 0x99FFFFFF.toInt(), Shader.TileMode.CLAMP)
                c.drawRoundRect(r, 12 * d, 12 * d, paint); paint.shader = null; paint.style = Paint.Style.FILL
            } else if (i > 0) { paint.color = 0x33FFFFFF; c.drawRect(x0, 10 * d, x0 + 1f * d, h - 10 * d, paint) }
            drawIcon(c, icon, x0 + cell / 2f, h * 0.36f, 11 * d, on)
            paint.color = if (on) 0xFFFFFFFF.toInt() else 0xFFC4C8CC.toInt(); paint.textSize = 11.5f * d; paint.textAlign = Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.create("sans-serif", if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            c.drawText(label, x0 + cell / 2f, h - 19 * d, paint)
        }
    }

    private fun drawIcon(c: Canvas, kind: Int, cx: Float, cy: Float, r: Float, on: Boolean) {
        paint.style = Paint.Style.FILL
        when (kind) {
            0 -> { // four squares, one of them green when this tab is chosen
                val s = r * 0.85f; val g = r * 0.12f
                for (row in 0..1) for (col in 0..1) {
                    paint.color = if (row == 0 && col == 1 && on) accent else 0xFFB9BEC3.toInt()
                    val l = cx + (col - 1) * (s + g) + (if (col == 1) g else 0f) - (if (col == 0) 0f else 0f)
                    val t = cy + (row - 1) * (s + g) + (if (row == 1) g else 0f)
                    c.drawRoundRect(l, t, l + s, t + s, 2.5f * d, 2.5f * d, paint)
                }
            }
            1 -> { // star
                paint.color = 0xFFE4E7EA.toInt()
                val path = android.graphics.Path()
                for (i in 0 until 10) {
                    val a = Math.PI / 5 * i - Math.PI / 2
                    val rr = if (i % 2 == 0) r * 1.1f else r * 0.48f
                    val x = cx + (rr * kotlin.math.cos(a)).toFloat(); val y = cy + (rr * kotlin.math.sin(a)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close(); c.drawPath(path, paint)
            }
            else -> { // a circle with a down arrow
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * d; paint.color = 0xFFD5D9DD.toInt()
                c.drawCircle(cx, cy, r * 1.05f, paint)
                paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND; paint.strokeWidth = 2.4f * d
                c.drawLine(cx, cy - r * 0.5f, cx, cy + r * 0.4f, paint)
                val p = android.graphics.Path().apply { moveTo(cx - r * 0.45f, cy + r * 0.0f); lineTo(cx, cy + r * 0.5f); lineTo(cx + r * 0.45f, cy + r * 0.0f) }
                c.drawPath(p, paint); paint.style = Paint.Style.FILL
            }
        }
    }
}

/**
 * Leap view: every home page at once as a small picture, so one tap goes to any of them. Pinch in on the home screen, tap the arc of dots,
 * or choose it from the home menu. [thumbs] are drawn by the caller; the page you are on gets a green rim.
 */
class LeapView(
    context: Context, private val pal: Palette, titles: List<String>, thumbs: List<Bitmap?>, current: Int,
    private val onPick: (Int) -> Unit, private val onClose: () -> Unit,
) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    init {
        setBackgroundColor(0xF4050607.toInt())
        isClickable = true
        setOnClickListener { onClose() }
        val columns = if (thumbs.size <= 4) 2 else 3
        val gridWidth = resources.displayMetrics.widthPixels - dp(40)
        val cell = gridWidth / columns
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(20), dp(96), dp(20), dp(40)) }
        col.addView(TextView(context).apply { text = "Leap"; textSize = 20f; setTextColor(0xFFF1F3F4.toInt()); gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(18)) })
        var row: LinearLayout? = null
        thumbs.forEachIndexed { i, bmp ->
            if (i % columns == 0) { row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL }; col.addView(row, LinearLayout.LayoutParams(-1, -2)) }
            val w = cell - dp(16)
            val h = if (bmp != null) (w * bmp.height.toFloat() / bmp.width).toInt() else (w * 1.9f).toInt()
            val frame = FrameLayout(context).apply {
                background = GradientDrawable().apply { setColor(0xFF15181B.toInt()); cornerRadius = dp(10).toFloat(); setStroke(if (i == current) dp(3) else dp(1), if (i == current) pal.primary else 0x66C9D0D6) }
                clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(bmp); setPadding(dp(3), dp(3), dp(3), dp(3)) }, LayoutParams(-1, -1))
            }
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                addView(frame, LinearLayout.LayoutParams(w, h))
                addView(TextView(context).apply { text = titles[i]; textSize = 12f; setTextColor(if (i == current) pal.primary else 0xFFC9D0D6.toInt()); gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(14)) })
                setOnClickListener { onPick(i) }
            }
            row!!.addView(item, LinearLayout.LayoutParams(cell, -2))
        }
        addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(col, LayoutParams(-1, -2)) }, LayoutParams(-1, -1))
        alpha = 0f; scaleX = 1.08f; scaleY = 1.08f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start()
    }

    fun dismiss(done: () -> Unit) { animate().alpha(0f).scaleX(1.08f).scaleY(1.08f).setDuration(150).withEndAction(done).start() }
}
