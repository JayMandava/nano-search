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
class FlipClockView(context: Context, private val panel: Boolean = false) : View(context) {
    private val art = FlipFaces(context)
    private val density = resources.displayMetrics.density

    private var hour = Face("--")
    private var minute = Face("--")
    private var oldHour = hour
    private var oldMinute = minute
    private var progress = 1f
    private var flipper: ValueAnimator? = null
    private var dateText = ""
    private var weather: ai.nanosearch.launcher.Weather.Now? = null
    private var weatherText = ""
    private var cityName = ""
    private var spin = 0f
    private var spinner: ValueAnimator? = null
    private val weatherArt = WeatherArt()

    // [panel] (the Sense home): the weather sits in a dark glass strip under the cards, with its picture breaking out over their lower edge
    private val panelOn get() = panel && weather != null && weatherText.isNotEmpty()
    private val panelRect = RectF()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val panelFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelGloss = ai.nanosearch.launcher.ui.SenseGloss(context, 6)

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

    // ---- weather (optional; see Weather)

    private fun loadWeather() {
        weather = ai.nanosearch.launcher.Weather.cached(context)
        weatherText = weather?.let { ai.nanosearch.launcher.Weather.format(context, it) } ?: ""
        cityName = ai.nanosearch.launcher.Weather.spot(context)?.first ?: ""
        computeGeometry()
        ai.nanosearch.launcher.Weather.refresh(context) { post { loadWeather(); invalidate() } }
        if (weather != null && isShown && spinner == null) {
            spinner = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 6000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                addUpdateListener { spin = it.animatedValue as Float; invalidate() }
                start()
            }
        } else if (weather == null) stopSpinner()
    }

    private fun stopSpinner() { spinner?.cancel(); spinner = null }

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
        loadWeather()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        flipper?.cancel()
        stopSpinner()
        if (receiving) { runCatching { context.unregisterReceiver(clockChanged) }; receiving = false }
        super.onDetachedFromWindow()
    }

    /** Hidden (the drawer is over it, another app is in front): stop ticking. Back in view: catch up without a flip. */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && isAttachedToWindow) { advance(animate = false); scheduleTick(); loadWeather() } else { removeCallbacks(tick); stopSpinner() }
    }

    // ---- layout and drawing

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { computeGeometry(); advance(animate = false) }

    private fun computeGeometry() {
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        val dateH = if (panelOn) 62f * density else 22f * density
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
        panelRect.set(leftX, topY + cardH + 8f * density, leftX + 2 * cardW + gap, topY + cardH + 8f * density + 54f * density)
    }

    /** The glass strip: city, conditions and date on the left, the temperature and today's high and low on the right. */
    private fun drawPanel(canvas: Canvas, w: ai.nanosearch.launcher.Weather.Now) {
        val r = 6f * density
        // a soft shadow under the strip (a hardware canvas has no shape shadows, so a few faint offset layers stand in)
        panelFill.style = Paint.Style.FILL; panelFill.shader = null
        for (i in 1..5) {
            panelFill.color = 0x14000000
            canvas.drawRoundRect(panelRect.left - i * density * 0.6f, panelRect.top + i * density * 1.1f, panelRect.right + i * density * 0.6f, panelRect.bottom + i * density * 1.6f, r + i * density, r + i * density, panelFill)
        }
        panelGloss.setBounds(panelRect.left.toInt(), panelRect.top.toInt(), panelRect.right.toInt(), panelRect.bottom.toInt())
        panelGloss.draw(canvas)
        val left = panelRect.left + 12f * density
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textPaint.color = 0xFFC9CED3.toInt(); textPaint.textSize = 12f * density
        canvas.drawText(DateFormat.format("d MMM", Calendar.getInstance()).toString(), left, panelRect.top + 16f * density, textPaint)
        textPaint.color = 0xFFFFFFFF.toInt(); textPaint.textSize = 16f * density; textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        canvas.drawText(cityName.ifEmpty { "Here" }, left, panelRect.top + 33f * density, textPaint)
        textPaint.color = 0xFFC9CED3.toInt(); textPaint.textSize = 13f * density; textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        canvas.drawText(ai.nanosearch.launcher.Weather.label(w), left, panelRect.top + 49f * density, textPaint)
        val right = panelRect.right - 12f * density
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = 0xFFFFFFFF.toInt(); textPaint.textSize = 34f * density; textPaint.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        canvas.drawText(weatherText, right, panelRect.top + 34f * density, textPaint)
        ai.nanosearch.launcher.Weather.highLow(context, w)?.let { (hi, lo) ->
            textPaint.color = 0xFFC9CED3.toInt(); textPaint.textSize = 12f * density; textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            canvas.drawText("H $hi   L $lo", right, panelRect.top + 49f * density, textPaint)
        }
        // the picture of the weather stands over the edge of the cards, as on the original widget
        weatherArt.draw(canvas, w.sky, w.day, width / 2f + 6f * density, cardsArea.bottom - 4f * density, 74f * density, spin)
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
        val w = weather
        if (panelOn) { drawPanel(canvas, w!!) }
        else if (w != null && weatherText.isNotEmpty()) {
            // date, then a small drawn sky and the temperature, centred as one line
            val dateW = datePaint.measureText(dateText)
            val icon = 20f * density
            val tempW = datePaint.measureText(weatherText)
            val gapW = 10f * density
            val total = dateW + gapW + icon + 4f * density + tempW
            val x0 = (width - total) / 2f
            datePaint.textAlign = Paint.Align.LEFT
            canvas.drawText(dateText, x0, dateBaseline, datePaint)
            weatherArt.draw(canvas, w.sky, w.day, x0 + dateW + gapW + icon / 2f, dateBaseline - 5f * density, icon, spin)
            canvas.drawText(weatherText, x0 + dateW + gapW + icon + 4f * density, dateBaseline, datePaint)
            datePaint.textAlign = Paint.Align.CENTER
        } else canvas.drawText(dateText, width / 2f, dateBaseline, datePaint)
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
