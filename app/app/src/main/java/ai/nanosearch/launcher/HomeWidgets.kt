package ai.nanosearch.launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.graphics.Color
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
import android.widget.TextView
import android.widget.Toast
import ai.nanosearch.launcher.ui.MenuItem
import ai.nanosearch.launcher.ui.Overlays
import ai.nanosearch.launcher.ui.Palette

/**
 * Home-screen widgets through Android's standard widget host. Widgets are picked from every widget the phone
 * offers (with previews), bound with the user's approval, configured if they need it, stacked in [box], and
 * remembered by id along with their size. Long-press a widget to resize or remove it.
 * The activity forwards start/stop and activity results.
 */
class HomeWidgets(
    private val activity: Activity,
    private val host: AppWidgetHost,
    private val box: LinearLayout,
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

    private val maxWidthPx get() = activity.resources.displayMetrics.widthPixels - dp(32)

    /** Removes every widget on this page for good (the page is being deleted). */
    fun removeAll() {
        items.forEach { token -> token.toIntOrNull()?.let { host.deleteAppWidgetId(it) }; prefs.edit().remove("wsize_$token").apply() }
        items = emptyList()
        box.removeAllViews()
    }

    /** Rebuilds the views for every saved widget; drops ones whose provider has since been uninstalled. */
    fun restore() {
        box.removeAllViews()
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
                    background = GradientDrawable().apply { setColor(pal.container); cornerRadius = dp(16).toFloat() }
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                }, LinearLayout.LayoutParams(dp(88), dp(64)))
                row.addView(TextView(activity).apply {
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
        val widthPx = saved?.first?.takeIf { it > 0 }?.let { dp(it) } ?: ViewGroup.LayoutParams.MATCH_PARENT
        // Widgets handle their own touches, so the long-press has to be caught by a wrapper around them.
        val frame = LongPressFrame(activity).apply {
            addView(view, FrameLayout.LayoutParams(-1, -1))
        }
        frame.onLongPress = {
            overlays.popupFor(frame, listOf(listOf(
                MenuItem(R.drawable.ic_menu_resize, "Resize") { startResize(id, minWidthPx(info), minHeightPx(info), frame) { w, h -> sizeChanged(view, w, h) } },
                MenuItem(R.drawable.ic_menu_remove, "Remove widget", destructive = true) { remove(id.toString(), frame) },
            )))
        }
        box.addView(frame, LinearLayout.LayoutParams(widthPx, heightPx).apply { bottomMargin = dp(12); gravity = Gravity.CENTER_HORIZONTAL })
        sizeChanged(view, widthPx, heightPx)
    }

    /** Tells the widget how big it now is, so it can pick a layout (clocks, calendars and so on have several). */
    private fun sizeChanged(view: AppWidgetHostView, widthPx: Int, heightPx: Int) {
        val w = toDp(if (widthPx > 0) widthPx else maxWidthPx)
        val h = toDp(heightPx)
        view.updateAppWidgetSize(null, w, h, w, h)
    }

    /** Shows a frame with a drag handle in the bottom-right corner and a Done button; releasing saves the size. */
    private fun startResize(key: Any, minW: Int, minH: Int, frame: LongPressFrame, onSize: (Int, Int) -> Unit) {
        frame.longPressEnabled = false
        val border = View(activity).apply {
            background = GradientDrawable().apply { setStroke(dp(2), pal.primary); cornerRadius = dp(12).toFloat() }
        }
        val handle = TextView(activity).apply {
            text = "⤡"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(pal.primary); shape = GradientDrawable.OVAL }
        }
        val done = TextView(activity).apply {
            text = "Done"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply { setColor(pal.primary); cornerRadius = dp(16).toFloat() }
        }
        fun finish() {
            frame.removeView(border); frame.removeView(handle); frame.removeView(done)
            frame.longPressEnabled = true
        }
        done.setOnClickListener { finish() }

        var startW = 0; var startH = 0; var downX = 0f; var downY = 0f
        handle.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    startW = frame.width; startH = frame.height; downX = e.rawX; downY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val w = (startW + (e.rawX - downX)).toInt().coerceIn(minW, maxWidthPx)
                    val h = (startH + (e.rawY - downY)).toInt().coerceIn(minH, dp(640))
                    frame.layoutParams = (frame.layoutParams as LinearLayout.LayoutParams).apply { width = w; height = h }
                    onSize(w, h)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                    val full = frame.width >= maxWidthPx - dp(8)
                    saveSize(key, if (full) 0 else toDp(frame.width), toDp(frame.height))
                }
            }
            true
        }
        frame.addView(border, FrameLayout.LayoutParams(-1, -1))
        frame.addView(handle, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.END or Gravity.BOTTOM))
        frame.addView(done, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply { setMargins(0, dp(6), dp(6), 0) })
    }

    private fun remove(token: String, view: View) {
        box.removeView(view)
        token.toIntOrNull()?.let { host.deleteAppWidgetId(it) }
        items = items - token
        prefs.edit().remove("wsize_$token").apply()
    }

    /** A widget the launcher draws itself: no provider, no binding, no host view; it is an ordinary View in the page. */
    private fun showBuiltin(token: String) {
        val b = BuiltinWidgets.find(token.split(':').getOrNull(1) ?: return) ?: return
        val view = b.create(activity)
        val saved = savedSize(token)
        val heightPx = saved?.second?.takeIf { it > 0 }?.let { dp(it) } ?: dp(b.defaultHeightDp)
        val widthPx = saved?.first?.takeIf { it > 0 }?.let { dp(it) } ?: ViewGroup.LayoutParams.MATCH_PARENT
        val frame = LongPressFrame(activity).apply { addView(view, FrameLayout.LayoutParams(-1, -1)) }
        frame.onLongPress = {
            overlays.popupFor(frame, listOf(listOf(
                MenuItem(R.drawable.ic_menu_resize, "Resize") { startResize(token, dp(180), dp(90), frame) { _, _ -> } },
                MenuItem(R.drawable.ic_menu_remove, "Remove widget", destructive = true) { remove(token, frame) },
            )))
        }
        box.addView(frame, LinearLayout.LayoutParams(widthPx, heightPx).apply { bottomMargin = dp(12); gravity = Gravity.CENTER_HORIZONTAL })
    }

    companion object {
        const val HOST_ID = 1024
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
