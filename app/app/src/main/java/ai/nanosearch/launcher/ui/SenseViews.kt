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

/**
 * The ring: a thick brushed-silver ring with a smoked-glass middle, sitting in the middle of the Sense dock. Tap it, or pull it up, to open the app drawer.
 * [onOpen] gets the ring's centre on the screen, so the drawer can open outwards from it.
 */
class SenseRing(context: Context, private val onOpen: (Float, Float) -> Unit) : View(context) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var downX = 0f
    private var downY = 0f
    private var pull = 0f
    private var glow = 0f
    private val restY = -20 * d // the ring floats above the two apps

    init { translationY = restY }
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    private fun fire() {
        val at = IntArray(2); getLocationOnScreen(at)
        onOpen(at[0] + width / 2f, at[1] + height / 2f)
    }

    private fun release(open: Boolean) {
        if (open) { performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM); fire() }
        animate().translationY(restY).setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
        val back = android.animation.ValueAnimator.ofFloat(glow, 0f).apply { duration = 260; addUpdateListener { glow = it.animatedValue as Float; invalidate() } }
        back.start()
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; pull = 0f; glow = 1f; parent?.requestDisallowInterceptTouchEvent(true); animate().cancel(); invalidate(); return true }
            android.view.MotionEvent.ACTION_MOVE -> {
                pull = (downY - e.rawY).coerceIn(0f, 52f * d)
                translationY = restY - pull
            }
            android.view.MotionEvent.ACTION_UP -> {
                val moved = kotlin.math.hypot(e.rawX - downX, e.rawY - downY)
                release(open = pull > 28 * d || moved < slop)
            }
            android.view.MotionEvent.ACTION_CANCEL -> release(open = false)
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) / 2f - 3 * d
        val band = 11 * d
        // shadow
        paint.shader = RadialGradient(cx, cy + 4 * d, r * 1.3f, 0x77000000, 0x00000000, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy + 4 * d, r * 1.3f, paint)
        // soft glow while held
        if (glow > 0f) {
            paint.shader = RadialGradient(cx, cy, r * 1.5f, ((0x66 * glow).toInt() shl 24) or 0xB7F25A, 0x00B7F25A, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r * 1.5f, paint)
        }
        // the smoked glass in the middle
        paint.shader = RadialGradient(cx, cy - r * 0.3f, r, 0xCC30363B.toInt(), 0xEE06080A.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r - band * 0.5f, paint)
        // the brushed ring: a sweep of light and dark, so it catches the light all the way round
        val metal = intArrayOf(0xFFF7F9FA.toInt(), 0xFF8B9299.toInt(), 0xFFE6EAEC.toInt(), 0xFF565C63.toInt(), 0xFFEEF1F3.toInt(), 0xFF7B828A.toInt(), 0xFFF7F9FA.toInt())
        paint.shader = android.graphics.SweepGradient(cx, cy, metal, floatArrayOf(0f, 0.18f, 0.34f, 0.52f, 0.7f, 0.86f, 1f))
        paint.style = Paint.Style.STROKE; paint.strokeWidth = band
        c.drawCircle(cx, cy, r - band / 2f, paint)
        // a bright hairline on the outer edge and a dark one on the inner edge
        paint.shader = null
        paint.strokeWidth = 1.2f * d; paint.color = 0xCCFFFFFF.toInt()
        c.drawCircle(cx, cy, r - 0.6f * d, paint)
        paint.color = 0xAA000000.toInt()
        c.drawCircle(cx, cy, r - band, paint)
        paint.style = Paint.Style.FILL
        // the curved highlight across the top of the glass
        paint.shader = LinearGradient(0f, cy - r, 0f, cy, 0x44FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawArc(cx - r + band, cy - r + band, cx + r - band, cy + r - band, 180f, 180f, true, paint)
        paint.shader = null
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
