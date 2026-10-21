package ai.nanosearch.launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import ai.nanosearch.launcher.ui.MenuItem
import ai.nanosearch.launcher.ui.Overlays
import ai.nanosearch.launcher.ui.Palette

/**
 * Home-screen widgets through Android's standard widget host. Widgets are picked from every widget the phone
 * offers (with previews), bound with the user's approval, configured if they need it, placed in [box], and
 * remembered by id along with their size and position. Long-press a widget to move, resize or remove it.
 *
 * [box] is a free-placement area: every widget sits at its own saved position, so widgets can be dragged anywhere on the page, side by side
 * or apart. A page nobody has moved a widget on looks as it always did (a centred stack, in the order they were added); the first move
 * freezes that layout in place. The area is as tall as its lowest widget, and the page's shortcuts sit below it, so a widget never covers an icon.
 * The activity forwards start/stop and activity results.
 */
class HomeWidgets(
    private val activity: Activity,
    private val host: AppWidgetHost,
    private val box: WidgetCanvas,
    pageKey: String,
    private val overlays: Overlays,
    private val pal: Palette,
) {
    private val manager = AppWidgetManager.getInstance(activity)
    private val prefs = activity.getSharedPreferences("nano", Activity.MODE_PRIVATE)
    private val density = activity.resources.displayMetrics.density

    private fun dp(v: Int) = (v * density).toInt()
    private fun toDp(px: Int) = (px / density).toInt()

    /** The first page keeps the original key, so widgets placed before there were pages stay where they were. */
    private val idsKey = if (pageKey == "0") "widgets" else "widgets_$pageKey"

    /** What is on this page, in order: an Android widget id ("42"), or a built-in widget ("b:<id>:<n>"). */
    private var items: List<String>
        get() = prefs.getString(idsKey, "")!!.split(',').filter { it.isNotBlank() }
        set(v) = prefs.edit().putString(idsKey, v.joinToString(",")).apply()

    private val ids: List<Int> get() = items.mapNotNull { it.toIntOrNull() }

    val count get() = items.size

    /** Saved size in dp as (width, height); width 0 means full width. */
    private fun savedSize(id: Any): Pair<Int, Int>? =
        prefs.getString("wsize_$id", null)?.split(',')?.let { p -> if (p.size == 2) (p[0].toIntOrNull() ?: 0) to (p[1].toIntOrNull() ?: 0) else null }

    private fun saveSize(id: Any, wDp: Int, hDp: Int) = prefs.edit().putString("wsize_$id", "$wDp,$hDp").apply()

    /** Saved position in dp as (left, top) inside the area; none means the widget has never been moved and takes the next place in the stack. */
    private fun savedPos(token: String): Pair<Int, Int>? =
        prefs.getString("wpos_$token", null)?.split(',')?.let { p -> if (p.size == 2) (p[0].toIntOrNull() ?: 0) to (p[1].toIntOrNull() ?: 0) else null }

    private fun savePos(token: String, xDp: Int, yDp: Int) = prefs.edit().putString("wpos_$token", "$xDp,$yDp").apply()

    private val screenW get() = activity.resources.displayMetrics.widthPixels

    /** Each widget's frame with the token it stands for, in the order they were put on the page. */
    private val frames = LinkedHashMap<View, String>()

    private fun lp(v: View) = v.layoutParams as FrameLayout.LayoutParams

    /** The lowest edge of anything on the page so far, or -1 when it is empty. */
    private fun lowestEdge(): Int = frames.keys.maxOfOrNull { lp(it).topMargin + lp(it).height } ?: -1

    /** More space under the last widget than the top has above the first, so the shortcuts below do not crowd it; nothing extra on an empty page. */
    private fun fitPadding() = box.setPadding(0, dp(8), 0, if (frames.isEmpty()) dp(8) else dp(20))

    /**
     * Puts a widget's frame on the page: where the user left it, or (never moved) centred below everything already there, which is the
     * stack the page has always been. Sizes are always whole pixels, so a widget can be moved without changing how wide it is.
     */
    private fun place(frame: LongPressFrame, token: String, widthPx: Int, heightPx: Int) {
        val params = FrameLayout.LayoutParams(widthPx, heightPx, Gravity.TOP or Gravity.START)
        val pos = savedPos(token)
        if (pos != null) {
            params.leftMargin = dp(pos.first).coerceIn(0, (screenW - widthPx).coerceAtLeast(0)); params.topMargin = dp(pos.second)
        } else {
            params.leftMargin = ((screenW - widthPx) / 2).coerceAtLeast(0)
            val edge = lowestEdge()
            params.topMargin = if (edge < 0) 0 else edge + dp(12)
        }
        box.addView(frame, params)
        frames[frame] = token
        fitPadding()
    }

    /** The first move on a page: every widget keeps exactly where it is now, so nothing jumps when one of them is dragged away. */
    private fun freezeLayout() {
        for ((frame, token) in frames) {
            if (savedPos(token) == null) savePos(token, toDp(lp(frame).leftMargin), toDp(lp(frame).topMargin))
        }
    }

    private fun scrollParent(): ScrollView? = generateSequence(box.parent as? View) { it.parent as? View }.filterIsInstance<ScrollView>().firstOrNull()

    private val maxWidthPx get() = activity.resources.displayMetrics.widthPixels - dp(32)

    /** Removes every widget on this page for good (the page is being deleted). */
    fun removeAll() {
        items.forEach { token -> token.toIntOrNull()?.let { host.deleteAppWidgetId(it) }; prefs.edit().remove("wsize_$token").remove("wpos_$token").apply() }
        items = emptyList()
        box.removeAllViews(); frames.clear(); fitPadding()
    }

    /** Rebuilds the views for every saved widget; drops ones whose provider has since been uninstalled. */
    fun restore() {
        box.removeAllViews(); frames.clear()
        val kept = items.filter { token ->
            val id = token.toIntOrNull()
            when {
                id != null -> manager.getAppWidgetInfo(id) != null.also { }
                else -> token.startsWith("b:") && BuiltinWidgets.find(token.split(':').getOrNull(1) ?: "") != null
            }
        }
        items.filter { it !in kept }.forEach { token -> token.toIntOrNull()?.let { host.deleteAppWidgetId(it) } }
        items = kept
        kept.forEach { token -> token.toIntOrNull()?.let { show(it) } ?: showBuiltin(token) }
    }

    /**
     * Puts a style's default clock at the top of this page the first time it is asked for under [flag]. It is an ordinary widget: long-press it to resize
     * or remove it, and once removed it stays removed for that style. A clock the user added themselves is never touched.
     */
    fun seedDefault(flag: String, builtinId: String, heightDp: Int) {
        if (prefs.getBoolean(flag, false)) return
        prefs.edit().putBoolean(flag, true).apply()
        if (BuiltinWidgets.find(builtinId) == null) return
        val token = "b:$builtinId:${System.currentTimeMillis()}"
        items = listOf(token) + items
        saveSize(token, 0, heightDp)
        prefs.edit().putString("${flag}_token", token).apply()
    }

    /** Takes a style's default clock off this page when another style is chosen (if it is still the one that was seeded), so it comes back fresh when that style does. */
    fun dropDefault(flag: String) {
        val token = prefs.getString("${flag}_token", null)
        prefs.edit().remove(flag).remove("${flag}_token").apply()
        if (token != null && token in items) {
            items = items - token
            prefs.edit().remove("wsize_$token").remove("wpos_$token").apply()
        }
    }

    fun pick() {
        val providers = manager.installedProviders.sortedBy { it.loadLabel(activity.packageManager).toString().lowercase() }
        val builtins = BuiltinWidgets.all
        val entries: List<Any> = builtins + providers
        if (entries.isEmpty()) {
            Toast.makeText(activity, "This phone has no widgets installed", Toast.LENGTH_SHORT).show()
            return
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = entries.size
            override fun getItem(position: Int) = entries[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val entry = entries[position]
                val info = entry as? AppWidgetProviderInfo
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(24), dp(10), dp(24), dp(10))
                }
                val preview = if (info == null) runCatching { (entry as BuiltinWidget).preview(activity) }.getOrNull()
                else runCatching { info.loadPreviewImage(activity, 0) }.getOrNull() ?: runCatching { info.loadIcon(activity, 0) }.getOrNull()
                row.addView(ImageView(activity).apply {
                    setImageDrawable(preview)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = GradientDrawable().apply { setColor(pal.container); cornerRadius = dp(pal.radius(16)).toFloat() }
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                }, LinearLayout.LayoutParams(dp(88), dp(64)))
                row.addView(NText(activity).apply {
                    text = info?.loadLabel(activity.packageManager) ?: (entry as BuiltinWidget).label
                    textSize = 16f
                    setTextColor(pal.onSurface)
                    setPadding(dp(16), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                return row
            }
        }
        overlays.adapterSheet("Add widget", adapter) { i -> (entries[i] as? BuiltinWidget)?.let { addBuiltin(it) } ?: add(entries[i] as AppWidgetProviderInfo) }
    }

    private fun add(info: AppWidgetProviderInfo) {
        pending = this
        val id = host.allocateAppWidgetId()
        if (manager.bindAppWidgetIdIfAllowed(id, info.provider)) {
            configureOrShow(id)
        } else {
            // First use of this widget: Android asks the user to allow it.
            activity.startActivityForResult(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider),
                REQ_BIND
            )
        }
    }

    private fun configureOrShow(id: Int) {
        val info = manager.getAppWidgetInfo(id)
        if (info?.configure != null) {
            host.startAppWidgetConfigureActivityForResult(activity, id, 0, REQ_CONFIGURE, null)
        } else {
            save(id)
        }
    }

    private fun save(id: Int) {
        items = items + id.toString()
        show(id)
    }

    private fun addBuiltin(b: BuiltinWidget) {
        val token = "b:${b.id}:${System.currentTimeMillis()}"
        items = items + token
        showBuiltin(token)
    }

    private fun handleResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQ_BIND && requestCode != REQ_CONFIGURE) return false
        val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        if (id == -1) return true
        when {
            resultCode != Activity.RESULT_OK -> host.deleteAppWidgetId(id)
            requestCode == REQ_BIND -> configureOrShow(id)
            else -> save(id)
        }
        return true
    }

    private fun minWidthPx(info: AppWidgetProviderInfo) = (info.minResizeWidth.takeIf { it > 0 } ?: info.minWidth).coerceAtLeast(dp(56))
    private fun minHeightPx(info: AppWidgetProviderInfo) = (info.minResizeHeight.takeIf { it > 0 } ?: info.minHeight).coerceAtLeast(dp(48))

    private fun show(id: Int) {
        val info = manager.getAppWidgetInfo(id) ?: return
        val view: AppWidgetHostView = host.createView(activity, id, info)
        view.setAppWidget(id, info)
        val saved = savedSize(id)
        val heightPx = saved?.second?.takeIf { it > 0 }?.let { dp(it) } ?: info.minHeight.coerceAtLeast(dp(96))
        val widthPx = saved?.first?.takeIf { it > 0 }?.let { dp(it) } ?: maxWidthPx
        // Widgets handle their own touches, so the long-press has to be caught by a wrapper around them.
        val frame = LongPressFrame(activity).apply {
            addView(view, FrameLayout.LayoutParams(-1, -1))
        }
        frame.onLongPress = {
            overlays.popupFor(frame, listOf(listOf(
                MenuItem(R.drawable.ic_menu_resize, "Move or resize") { startEdit(id.toString(), id, minWidthPx(info), minHeightPx(info), frame) { w, h -> sizeChanged(view, w, h) } },
                MenuItem(R.drawable.ic_menu_remove, "Remove widget", destructive = true) { remove(id.toString(), frame) },
            )))
        }
        place(frame, id.toString(), widthPx, heightPx)
        sizeChanged(view, widthPx, heightPx)
    }

    /** Tells the widget how big it now is, so it can pick a layout (clocks, calendars and so on have several). */
    private fun sizeChanged(view: AppWidgetHostView, widthPx: Int, heightPx: Int) {
        val w = toDp(if (widthPx > 0) widthPx else maxWidthPx)
        val h = toDp(heightPx)
        view.updateAppWidgetSize(null, w, h, w, h)
    }

    // ---- the grid and the alignment help while a widget is moved or resized

    /** The faint grid of dots and the guide lines drawn under the widgets while one is being moved or resized; the page's [WidgetCanvas] calls it to draw. */
    private inner class GridHint {
        val guideX = ArrayList<Int>()
        val guideY = ArrayList<Int>()
        private var dots = FloatArray(0)
        private var dotsFor = 0 to 0
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (pal.onVariant and 0x00FFFFFF) or 0x66000000; strokeWidth = dp(2).toFloat(); strokeCap = Paint.Cap.ROUND }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.primary; strokeWidth = dp(1).toFloat() }

        fun draw(c: Canvas, width: Int, height: Int) {
            val step = dp(GRID_DP)
            if (dotsFor != width to height) { // the dots are worked out once for this size, not on every frame
                val list = ArrayList<Float>()
                var y = 0; while (y <= height) { var x = 0; while (x <= width) { list.add(x.toFloat()); list.add(y.toFloat()); x += step }; y += step }
                dots = list.toFloatArray(); dotsFor = width to height
            }
            c.drawPoints(dots, dotPaint)
            guideX.forEach { c.drawLine(it.toFloat(), 0f, it.toFloat(), height.toFloat(), linePaint) }
            guideY.forEach { c.drawLine(0f, it.toFloat(), width.toFloat(), it.toFloat(), linePaint) }
        }
    }

    /** The places an edge or the middle of a widget likes to line up with: the page's margins and centre, and the edges and middles of the other widgets. */
    private fun alignLines(self: View, horizontal: Boolean): List<Int> {
        val out = ArrayList<Int>()
        if (horizontal) out += listOf(dp(16), screenW / 2, screenW - dp(16)) else out += 0
        for (f in frames.keys) {
            if (f === self) continue
            val p = lp(f)
            if (horizontal) out += listOf(p.leftMargin, p.leftMargin + p.width / 2, p.leftMargin + p.width)
            else out += listOf(p.topMargin, p.topMargin + p.height / 2, p.topMargin + p.height)
        }
        return out
    }

    /** Where a widget starting at [start] with length [size] should sit: lined up with something close by (that line is also returned, to be drawn), otherwise on the grid. */
    private fun alignStart(start: Int, size: Int, lines: List<Int>): Pair<Int, Int?> {
        val near = dp(8)
        var shift = 0; var best = near + 1; var guide: Int? = null
        for (mine in intArrayOf(start, start + size / 2, start + size)) for (l in lines) {
            val d = abs(l - mine)
            if (d < best) { best = d; shift = l - mine; guide = l }
        }
        if (guide != null) return (start + shift) to guide
        val g = dp(GRID_DP)
        return (((start + g / 2) / g) * g) to null
    }

    /** The same for one edge that is being pulled (the right or bottom of a widget being resized). */
    private fun alignEdge(edge: Int, lines: List<Int>): Pair<Int, Int?> {
        val near = dp(8)
        val hit = lines.minByOrNull { abs(it - edge) }
        if (hit != null && abs(hit - edge) <= near) return hit to hit
        val g = dp(GRID_DP)
        return (((edge + g / 2) / g) * g) to null
    }

    /**
     * Move and resize mode: a frame round the widget. Drag anywhere on it to move it (the page scrolls along when it is taken near the top or bottom edge),
     * pull the round handle in the corner to resize it, and press Done to finish. The position and size are saved when the finger lifts.
     */
    private fun startEdit(token: String, key: Any, minW: Int, minH: Int, frame: LongPressFrame, onSize: (Int, Int) -> Unit) {
        freezeLayout()
        frame.longPressEnabled = false
        val border = View(activity).apply {
            background = GradientDrawable().apply { setStroke(dp(2), pal.primary); cornerRadius = dp(pal.radius(12)).toFloat() }
        }
        val handle = NText(activity).apply {
            text = "⤡"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(pal.primary); shape = GradientDrawable.OVAL }
        }
        val done = NText(activity).apply {
            text = "Done"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply { setColor(pal.primary); cornerRadius = dp(pal.radius(16)).toFloat() }
        }
        val scroller = scrollParent()
        val hint = GridHint()
        box.overlay = { c, w, h -> hint.draw(c, w, h) }                    // drawn by the area itself, under every widget
        box.minimumHeight = (scroller?.height ?: 0)                        // the page is a whole sheet to place things on while editing; the shortcuts wait below it
        var lastX = 0f; var lastY = 0f
        var startLeft = 0; var startTop = 0; var downX = 0f; var downY = 0f; var scroll0 = 0
        var startW = 0; var startH = 0

        /** Puts the widget where the finger has taken it, allowing for how far the page has scrolled since the touch began. */
        fun moveTo(rawX: Float, rawY: Float) {
            val p = lp(frame)
            val maxX = (screenW - p.width).coerceAtLeast(0)
            val rawLeft = (startLeft + (rawX - downX)).toInt().coerceIn(0, maxX)
            val rawTop = (startTop + (rawY - downY) + ((scroller?.scrollY ?: 0) - scroll0)).toInt().coerceAtLeast(0)
            val (ax, gx) = alignStart(rawLeft, p.width, alignLines(frame, true))
            val (ay, gy) = alignStart(rawTop, p.height, alignLines(frame, false))
            val x = ax.coerceIn(0, maxX); val y = ay.coerceAtLeast(0)
            hint.guideX.clear(); gx?.let { hint.guideX.add(it) }
            hint.guideY.clear(); gy?.let { hint.guideY.add(it) }
            if (x != p.leftMargin || y != p.topMargin) { p.leftMargin = x; p.topMargin = y; frame.layoutParams = p }
            box.invalidate()
        }

        // A finger held near the top or bottom of the page keeps scrolling it, so a widget can be taken a long way down or back up in one drag.
        val autoScroll = object : Runnable {
            override fun run() {
                val sv = scroller ?: return
                val at = IntArray(2); sv.getLocationOnScreen(at)
                val edge = dp(90)
                val step = when { lastY > at[1] + sv.height - edge -> dp(10); lastY < at[1] + edge -> -dp(10); else -> 0 }
                if (step != 0) { sv.scrollBy(0, step); moveTo(lastX, lastY) }
                frame.postDelayed(this, 16)
            }
        }

        fun finish() {
            frame.removeCallbacks(autoScroll)
            frame.removeView(border); frame.removeView(handle); frame.removeView(done)
            box.overlay = null; box.minimumHeight = 0; box.invalidate()
            frame.longPressEnabled = true
        }
        done.setOnClickListener { finish() }

        border.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    val p = lp(frame)
                    startLeft = p.leftMargin; startTop = p.topMargin; downX = e.rawX; downY = e.rawY; lastX = downX; lastY = downY
                    scroll0 = scroller?.scrollY ?: 0
                    frame.postDelayed(autoScroll, 16)
                }
                MotionEvent.ACTION_MOVE -> { lastX = e.rawX; lastY = e.rawY; moveTo(lastX, lastY) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    frame.removeCallbacks(autoScroll)
                    v.parent.requestDisallowInterceptTouchEvent(false)
                    hint.guideX.clear(); hint.guideY.clear(); box.invalidate()
                    val p = lp(frame)
                    savePos(token, toDp(p.leftMargin), toDp(p.topMargin))
                }
            }
            true
        }

        handle.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    startW = lp(frame).width; startH = lp(frame).height; downX = e.rawX; downY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val p = lp(frame)
                    // the top-left corner stays exactly where it is: the widget only grows or shrinks, and stops at the screen's edge
                    val maxW = minOf(maxWidthPx, screenW - p.leftMargin).coerceAtLeast(minW)
                    val (right, gr) = alignEdge(p.leftMargin + (startW + (e.rawX - downX)).toInt(), alignLines(frame, true))
                    val (bottom, gb) = alignEdge(p.topMargin + (startH + (e.rawY - downY)).toInt(), alignLines(frame, false))
                    val w = (right - p.leftMargin).coerceIn(minW, maxW)
                    val h = (bottom - p.topMargin).coerceIn(minH, dp(640))
                    hint.guideX.clear(); if (gr != null && right - p.leftMargin == w) hint.guideX.add(gr)
                    hint.guideY.clear(); if (gb != null && bottom - p.topMargin == h) hint.guideY.add(gb)
                    p.width = w; p.height = h
                    frame.layoutParams = p
                    onSize(w, h)
                    box.invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                    hint.guideX.clear(); hint.guideY.clear(); box.invalidate()
                    val p = lp(frame)
                    val full = p.width >= maxWidthPx - dp(8)
                    saveSize(key, if (full) 0 else toDp(p.width), toDp(p.height))
                    savePos(token, toDp(p.leftMargin), toDp(p.topMargin))
                }
            }
            true
        }
        frame.addView(border, FrameLayout.LayoutParams(-1, -1))
        frame.addView(handle, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.END or Gravity.BOTTOM))
        frame.addView(done, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply { setMargins(0, dp(6), dp(6), 0) })
        Toast.makeText(activity, "Drag to move, pull the corner to resize. Edges snap to the grid and to each other.", Toast.LENGTH_SHORT).show()
    }

    /** After one widget leaves, those that have never been moved close up the gap again, as the stack always did; moved ones stay exactly where they were put. */
    private fun restack() {
        var edge = -1
        for ((f, t) in frames) {
            val p = lp(f)
            if (savedPos(t) == null) { p.topMargin = if (edge < 0) 0 else edge + dp(12); f.layoutParams = p }
            edge = maxOf(edge, p.topMargin + p.height)
        }
    }

    private fun remove(token: String, view: View) {
        box.removeView(view); frames.remove(view); restack(); fitPadding()
        token.toIntOrNull()?.let { host.deleteAppWidgetId(it) }
        items = items - token
        prefs.edit().remove("wsize_$token").remove("wpos_$token").apply()
    }

    /** A widget the launcher draws itself: no provider, no binding, no host view; it is an ordinary View in the page. */
    private fun showBuiltin(token: String) {
        val b = BuiltinWidgets.find(token.split(':').getOrNull(1) ?: return) ?: return
        val view = b.create(activity)
        val saved = savedSize(token)
        val heightPx = saved?.second?.takeIf { it > 0 }?.let { dp(it) } ?: dp(b.defaultHeightDp)
        val widthPx = saved?.first?.takeIf { it > 0 }?.let { dp(it) } ?: maxWidthPx
        val frame = LongPressFrame(activity).apply { addView(view, FrameLayout.LayoutParams(-1, -1)) }
        frame.onLongPress = {
            overlays.popupFor(frame, listOf(listOf(
                MenuItem(R.drawable.ic_menu_resize, "Move or resize") { startEdit(token, token, dp(180), dp(90), frame) { _, _ -> } },
                MenuItem(R.drawable.ic_menu_remove, "Remove widget", destructive = true) { remove(token, frame) },
            )))
        }
        place(frame, token, widthPx, heightPx)
    }

    companion object {
        const val HOST_ID = 1024
        /** The spacing of the grid shown while a widget is moved or resized, in dp. */
        private const val GRID_DP = 16
        private const val REQ_BIND = 41
        private const val REQ_CONFIGURE = 42

        /** The page whose "add widget" is waiting for Android's bind or configure screen to come back. */
        private var pending: HomeWidgets? = null

        /** Returns true if the result belonged to a widget request. */
        fun dispatch(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
            if (requestCode != REQ_BIND && requestCode != REQ_CONFIGURE) return false
            val owner = pending
            if (owner != null) owner.handleResult(requestCode, resultCode, data)
            if (requestCode == REQ_CONFIGURE || resultCode != Activity.RESULT_OK) pending = null
            return true
        }
    }
}

/**
 * The page's widget area: a free-placement layout (every child sits at its own margins) that can draw something under its children, which is how the grid
 * and the guide lines show while a widget is moved. Its origin is the inside of its padding, the same as the widgets' positions.
 */
class WidgetCanvas(context: Context) : FrameLayout(context) {
    var overlay: ((Canvas, Int, Int) -> Unit)? = null

    init { setWillNotDraw(false) }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val draw = overlay ?: return
        c.save()
        c.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        draw(c, width - paddingLeft - paddingRight, height - paddingTop - paddingBottom)
        c.restore()
    }
}

/** Fires [onLongPress] after the system long-press timeout and then takes the gesture away from the widget inside. */
class LongPressFrame(context: Context) : FrameLayout(context) {
    var onLongPress: () -> Unit = {}
    var longPressEnabled = true
    private var fired = false
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val check = Runnable {
        fired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress()
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (!longPressEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fired = false
                downX = e.x
                downY = e.y
                postDelayed(check, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > slop) removeCallbacks(check)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(check)
        }
        return fired
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) fired = false
        return true
    }
}
