package ai.nanosearch.launcher

import ai.nanosearch.launcher.clocks.FlipClockView
import android.app.Activity
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.hypot
import kotlin.math.min

/**
 * The Sense lock screen: the flip clock above a silver ring with four app shortcuts beside it. Pull the ring up to unlock, or drag a shortcut into the ring
 * to unlock and open that app. It shows over the lock screen; a PIN or pattern, if the phone has one, is still asked for before anything opens.
 */
class SenseLockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val root = FrameLayout(this)
        root.addView(FlipClockView(this), FrameLayout.LayoutParams(-1, (resources.displayMetrics.density * 190).toInt()).apply { topMargin = (resources.displayMetrics.density * 110).toInt() })
        root.addView(RingView(this, shortcuts(this)) { target -> unlock(target) }, ViewGroup.LayoutParams(-1, -1))
        setContentView(root)
        window.statusBarColor = 0
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun unlock(target: ComponentName?) {
        SenseTones.play(this, SenseTones.Tone.UNLOCK)
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        fun open() {
            if (target != null) runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            finish()
        }
        if (!km.isKeyguardLocked) return open()
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = open()
            override fun onDismissError() = open()
            override fun onDismissCancelled() = Unit
        })
    }

    companion object {
        /** The four shortcuts: the user's own choice when set, otherwise the phone, messages, camera and browser apps found on the phone. */
        fun shortcuts(context: Context): List<ComponentName> {
            val saved = context.getSharedPreferences("nano", MODE_PRIVATE).getString("sense_lock_apps", null)?.split('|')?.mapNotNull { ComponentName.unflattenFromString(it) }.orEmpty()
            if (saved.size == 4) return saved
            val pm = context.packageManager
            val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
                .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) to it.loadLabel(pm).toString() }
            return listOf("phone", "message", "camera", "browser").mapNotNull { kind -> apps.firstOrNull { AppIcons.kindOf(it.first.packageName, it.second) == kind }?.first }
        }
    }
}

/** The ring and the shortcut icons. Dragging is all one touch: whatever is grabbed follows the finger and lets go where the finger does. */
private class RingView(context: Context, private val apps: List<ComponentName>, private val onUnlock: (ComponentName?) -> Unit) : View(context) {
    private val d = resources.displayMetrics.density
    private val icons: List<Drawable> = apps.map { AppIcons.get(context, it) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var grabbed = -2 // -2 nothing, -1 the ring, 0.. a shortcut
    private var fx = 0f
    private var fy = 0f
    private val ringR get() = 40 * d
    private val iconR get() = 28 * d
    private val cx get() = width / 2f
    private val homeY get() = height - 130 * d

    private fun slot(i: Int): Pair<Float, Float> {
        val inner = ringR + iconR + 12 * d
        val outer = inner + 2 * iconR + 8 * d
        val dx = listOf(-outer, -inner, inner, outer)[i.coerceIn(0, 3)]
        return cx + dx to homeY
    }

    override fun onDraw(c: Canvas) {
        val ringX = if (grabbed == -1) fx else cx
        val ringY = if (grabbed == -1) fy else homeY
        val pull = ((homeY - ringY) / (130 * d)).coerceIn(0f, 1f)
        // a soft tray under the controls
        paint.shader = LinearGradient(0f, homeY - 120 * d, 0f, height.toFloat(), 0x00000000, 0xB0000000.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, homeY - 120 * d, width.toFloat(), height.toFloat(), paint); paint.shader = null
        icons.forEachIndexed { i, icon ->
            val (sx, sy) = slot(i)
            val x = if (grabbed == i) fx else sx
            val y = if (grabbed == i) fy else sy
            val s = iconR * (if (grabbed == i) 1.25f else 1f)
            icon.setBounds((x - s).toInt(), (y - s).toInt(), (x + s).toInt(), (y + s).toInt())
            icon.alpha = if (grabbed == -1) (255 * (1f - pull * 0.7f)).toInt() else 255
            icon.draw(c)
        }
        // the ring: a glow, a translucent glass disc and a silver rim
        val near = grabbed >= 0 && hypot(fx - cx, fy - homeY) < ringR * 1.6f
        val r = ringR * (if (near) 1.12f else 1f)
        paint.shader = RadialGradient(ringX, ringY, r * 2.1f, if (near) 0x88B7F25A.toInt() else 0x558FD0FF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(ringX, ringY, r * 2.1f, paint)
        paint.shader = RadialGradient(ringX, ringY - r * 0.3f, r, 0x40FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(ringX, ringY, r, paint)
        paint.shader = LinearGradient(0f, ringY - r, 0f, ringY + r, 0xFFF4F7FA.toInt(), 0xFF6F7880.toInt(), Shader.TileMode.CLAMP)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 6 * d
        c.drawCircle(ringX, ringY, r - 3 * d, paint)
        paint.shader = null; paint.style = Paint.Style.FILL
        paint.color = 0xCCFFFFFF.toInt() // a chevron: pull up
        val a = 10 * d
        val path = android.graphics.Path().apply { moveTo(ringX - a, ringY + a * 0.4f); lineTo(ringX, ringY - a * 0.5f); lineTo(ringX + a, ringY + a * 0.4f) }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3.5f * d; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        c.drawPath(path, paint); paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fx = e.x; fy = e.y
                grabbed = when {
                    hypot(e.x - cx, e.y - homeY) < ringR * 1.3f -> -1
                    else -> apps.indices.firstOrNull { val (sx, sy) = slot(it); hypot(e.x - sx, e.y - sy) < iconR * 1.5f } ?: -2
                }
                if (grabbed != -2) performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                invalidate(); return grabbed != -2
            }
            MotionEvent.ACTION_MOVE -> { fx = e.x; fy = e.y; invalidate() }
            MotionEvent.ACTION_UP -> {
                val g = grabbed
                grabbed = -2
                if (g == -1 && homeY - e.y > 100 * d) { performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM); onUnlock(null) }
                else if (g >= 0 && hypot(e.x - cx, e.y - homeY) < ringR * 1.6f) { performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM); onUnlock(apps[g]) }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { grabbed = -2; invalidate() }
        }
        return true
    }
}
