package ai.nanosearch.launcher.clocks

import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.AlarmClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.min

/**
 * The analogue clock. The dial is a still picture; the hands are vector shapes redrawn on every frame, so the seconds hand sweeps smoothly,
 * but only while the clock is on screen. Tapping it opens the Clock app.
 */
class SteelDialView(context: Context) : View(context) {
    private val art = DialArt(context)
    private var face: Bitmap? = null
    private var side = 0
    private var hour = 0; private var minute = 0; private var second = 0

    private fun readTime() {
        val cal = Calendar.getInstance()
        hour = cal.get(Calendar.HOUR_OF_DAY); minute = cal.get(Calendar.MINUTE); second = cal.get(Calendar.SECOND)
    }

    private val clockChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { readTime(); invalidate() }
    }
    private var receiving = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!receiving) {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            }
            context.registerReceiver(clockChanged, f, Context.RECEIVER_NOT_EXPORTED)
            receiving = true
        }
        readTime(); invalidate()
    }

    override fun onDetachedFromWindow() {
        if (receiving) { runCatching { context.unregisterReceiver(clockChanged) }; receiving = false }
        super.onDetachedFromWindow()
    }

    /** Drawing continues frame by frame only while the clock is visible: hidden (the drawer is over it, another app in front), nothing is drawn. */
    private var visible = false
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visible = isVisible
        if (isVisible && isAttachedToWindow) { readTime(); invalidate() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { side = min(w, h); face = null }

    override fun onDraw(canvas: Canvas) {
        if (side <= 0) return
        val now = System.currentTimeMillis()
        readTime()
        if (face == null) {
            face = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { art.drawFace(Canvas(it), side) }
        }
        // whole pixels, so the dial is never resampled and stays sharp
        val dx = ((width - side) / 2).toFloat(); val dy = ((height - side) / 2).toFloat()
        canvas.save(); canvas.translate(dx, dy)
        canvas.drawBitmap(face!!, 0f, 0f, null)
        art.drawHands(canvas, side, hour, minute, second + (now % 1000) / 1000f)
        canvas.restore()
        if (visible) postInvalidateOnAnimation() // the next frame, so the seconds hand sweeps
    }

    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop


    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; return true }
            MotionEvent.ACTION_UP -> if (abs(e.x - downX) <= slop && abs(e.y - downY) <= slop) {
                performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                openClock(e)
            }
        }
        return true
    }

    /** The Clock app's own screen (asking it to "show alarms" needs a permission the launcher does not hold, so open whichever app handles that request). */
    private fun openClock(e: MotionEvent) {
        val pm = context.packageManager
        val handler = pm.resolveActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS), 0)?.activityInfo?.packageName
        val open = handler?.let { pm.getLaunchIntentForPackage(it) } ?: Intent(AlarmClock.ACTION_SHOW_ALARMS)
        val options = ActivityOptions.makeScaleUpAnimation(this, e.x.toInt(), e.y.toInt(), 1, 1).toBundle()
        runCatching { context.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options) }
    }
}
