package ai.nanosearch.launcher.clocks

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.provider.AlarmClock
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.min

/**
 * The flip clock, drawn by the launcher itself at the screen's refresh rate. It flips when the minute (or hour) changes, only ticks while it is on screen,
 * and has two touch targets: the cards open the Clock app, the date line opens the Calendar.
 */
class FlipClockView(context: Context) : View(context) {
    private val art = FlipFaces(context)
    private val density = resources.displayMetrics.density

    private var hour = Face("--")
    private var minute = Face("--")
    private var oldHour = hour
    private var oldMinute = minute
    private var progress = 1f
    private var flipper: ValueAnimator? = null
    private var dateText = ""

    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setShadowLayer(4f * density, 0f, 1f * density, 0x99000000.toInt())
    }

    // geometry, set when the size is known
    private var cardW = 0
    private var cardH = 0
    private var leftX = 0f
    private var topY = 0f
    private var gap = 0f
    private var dateBaseline = 0f
    private val cardsArea = RectF()
    private val dateArea = RectF()

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    // ---- time

    private fun readTime(): Pair<Face, Face> {
        val cal = Calendar.getInstance()
        val is24 = DateFormat.is24HourFormat(context)
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val hourText = if (is24) "%02d".format(h) else (if (h % 12 == 0) 12 else h % 12).toString()
        val ampm = if (is24) null else if (h < 12) "AM" else "PM"
        dateText = DateFormat.format("EEEE, d MMMM", cal).toString()
        return Face(hourText, ampm) to Face("%02d".format(cal.get(Calendar.MINUTE)))
    }

    /** Shows the current time; flips to it if asked and the view is showing, otherwise just snaps. */
    private fun advance(animate: Boolean) {
        val (h, m) = readTime()
        if (h == hour && m == minute) { invalidate(); return }
        flipper?.let { it.removeAllListeners(); it.cancel() }
        if (animate && isShown && cardW > 0 && hour.text != "--") {
            oldHour = hour; oldMinute = minute
            hour = h; minute = m
            progress = 0f
            flipper = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = FLIP_MS
                interpolator = LinearInterpolator()
                addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { progress = 1f; invalidate() } })
                start()
            }
        } else {
            hour = h; minute = m; oldHour = h; oldMinute = m; progress = 1f
            invalidate()
        }
    }

    private val tick = object : Runnable {
        override fun run() { advance(animate = true); scheduleTick() }
    }

    private fun scheduleTick() {
        removeCallbacks(tick)
        postDelayed(tick, 60_000L - System.currentTimeMillis() % 60_000L + 25L)
    }

    private val clockChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { advance(animate = false); scheduleTick() }
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
        advance(animate = false)
        scheduleTick()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        flipper?.cancel()
        if (receiving) { runCatching { context.unregisterReceiver(clockChanged) }; receiving = false }
        super.onDetachedFromWindow()
    }

    /** Hidden (the drawer is over it, another app is in front): stop ticking. Back in view: catch up without a flip. */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && isAttachedToWindow) { advance(animate = false); scheduleTick() } else removeCallbacks(tick)
    }

    // ---- layout and drawing

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val dateH = 22f * density
        val pad = 6f * density
        gap = 5f * density
        val availH = h - dateH - pad
        val availW = (w - gap) / 2f
        cardH = min(availH, availW / 1.04f).toInt().coerceAtLeast(40)
        cardW = (cardH * 1.04f).toInt()
        leftX = (w - (2 * cardW + gap)) / 2f
        topY = pad / 2f
        datePaint.textSize = 14f * density
        dateBaseline = topY + cardH + 18f * density
        cardsArea.set(leftX, topY, leftX + 2 * cardW + gap, topY + cardH)
        dateArea.set(0f, topY + cardH, w.toFloat(), h.toFloat())
        advance(animate = false)
    }

    private var pressedRegion = 0

    override fun onDraw(canvas: Canvas) {
        if (cardW <= 0) return
        val alpha = if (pressedRegion == 1) 200 else 255
        val hourChanging = progress < 1f && oldHour != hour
        val minuteChanging = progress < 1f && oldMinute != minute
        if (hourChanging) art.drawFlip(canvas, leftX, topY, cardW, cardH, oldHour, hour, progress) else art.drawRest(canvas, leftX, topY, cardW, cardH, hour, alpha)
        val mx = leftX + cardW + gap
        if (minuteChanging) art.drawFlip(canvas, mx, topY, cardW, cardH, oldMinute, minute, progress) else art.drawRest(canvas, mx, topY, cardW, cardH, minute, alpha)
        datePaint.alpha = if (pressedRegion == 2) 150 else 242
        canvas.drawText(dateText, width / 2f, dateBaseline, datePaint)
    }

    // ---- touch: cards -> Clock, date -> Calendar

    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private fun regionAt(x: Float, y: Float): Int {
        val pad = 8f * density
        return when {
            RectF(cardsArea).apply { inset(-pad, -pad) }.contains(x, y) && y < cardsArea.bottom + pad / 2 -> 1
            dateArea.contains(x, y) -> 2
            else -> 0
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; pressedRegion = regionAt(e.x, e.y); invalidate(); return pressedRegion != 0 }
            MotionEvent.ACTION_MOVE -> if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) { pressedRegion = 0; invalidate() }
            MotionEvent.ACTION_UP -> {
                val region = pressedRegion
                pressedRegion = 0; invalidate()
                if (region != 0 && region == regionAt(e.x, e.y) && abs(e.x - downX) <= slop && abs(e.y - downY) <= slop) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                    if (region == 1) openClock(e) else openCalendar(e)
                }
            }
            MotionEvent.ACTION_CANCEL -> { pressedRegion = 0; invalidate() }
        }
        return true
    }

    private fun launch(intent: Intent, e: MotionEvent) {
        val options = ActivityOptions.makeScaleUpAnimation(this, e.x.toInt(), e.y.toInt(), 1, 1).toBundle()
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options) }
    }

    /**
     * The Clock app's own screen. Asking it to "show alarms" needs an alarm permission this launcher does not hold, so find whichever app handles that request
     * and open its launcher activity instead.
     */
    private fun openClock(e: MotionEvent) {
        val pm = context.packageManager
        val handler = pm.resolveActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS), 0)?.activityInfo?.packageName
        val open = handler?.let { pm.getLaunchIntentForPackage(it) } ?: Intent(AlarmClock.ACTION_SHOW_ALARMS)
        launch(open, e)
    }

    private fun openCalendar(e: MotionEvent) {
        val main = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
        val intent = if (main.resolveActivity(context.packageManager) != null) main
        else Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/${System.currentTimeMillis()}"))
        launch(intent, e)
    }

    private companion object { const val FLIP_MS = 560L }
}
