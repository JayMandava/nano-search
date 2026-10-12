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

/** The round silver button in the middle of the Sense launcher bar: a glass disc with a rim and a grid of nine dots, for "all apps". */
class SenseAppsButton(context: Context, private val accent: Int) : View(context) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pressedNow = false

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) / 2f - 2 * d
        // soft shadow under the button
        paint.shader = RadialGradient(cx, cy + 3 * d, r * 1.25f, 0x66000000, 0x00000000, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy + 3 * d, r * 1.25f, paint)
        // the silver rim, then the dark glass inside it
        paint.shader = LinearGradient(0f, cy - r, 0f, cy + r, 0xFFF3F6F8.toInt(), 0xFF5F676E.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, paint)
        paint.shader = LinearGradient(0f, cy - r, 0f, cy + r, if (pressedNow) 0xFF3E454B.toInt() else 0xFF2A2F34.toInt(), 0xFF0E1012.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r - 3.5f * d, paint)
        // a curved highlight over the top half
        paint.shader = LinearGradient(0f, cy - r, 0f, cy, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawArc(cx - r + 4 * d, cy - r + 4 * d, cx + r - 4 * d, cy + r - 4 * d, 180f, 180f, true, paint)
        paint.shader = null
        // nine dots
        val gap = 9 * d
        for (row in -1..1) for (col in -1..1) {
            paint.color = if (row == 0 && col == 0) accent else 0xFFF1F3F4.toInt()
            c.drawCircle(cx + col * gap, cy + row * gap, 2.6f * d, paint)
        }
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { pressedNow = true; invalidate() }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { pressedNow = false; invalidate() }
        }
        return super.onTouchEvent(e)
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
