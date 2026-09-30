package ai.nanosearch.launcher

import ai.nanosearch.launcher.ui.MenuItem
import ai.nanosearch.launcher.ui.Overlays
import ai.nanosearch.launcher.ui.Palette
import ai.nanosearch.launcher.ui.pressScale
import android.app.Activity
import android.appwidget.AppWidgetHost
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * One home screen page: a scrolling column holding this page's widgets and, below them, a grid of app shortcuts. The first page also carries the clock,
 * which [MainActivity] slots in at the top of [root]. Long-pressing empty space (or a shortcut) opens the launcher's popup menu.
 */
class HomePage(
    private val activity: Activity,
    val id: String,
    widgetHost: AppWidgetHost,
    private val overlays: Overlays,
    private val pal: Palette,
    /** Long-press on empty space: the screen position, for anchoring the menu. */
    private val onEmptyLongPress: (x: Float, y: Float) -> Unit,
    private val launch: (ComponentName, View) -> Unit,
    private val appInfo: (ComponentName) -> Unit,
) {
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val prefs = activity.getSharedPreferences("nano", Context.MODE_PRIVATE)
    private val key = "shortcuts_$id"

    val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val widgetBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
    private val grid = GridLayout(activity).apply { columnCount = COLUMNS; setPadding(dp(8), dp(4), dp(8), dp(8)) }
    val widgets = HomeWidgets(activity, widgetHost, widgetBox, id, overlays, pal)

    /** Scrolls when its contents outgrow the page; otherwise it lets touches through, so the page swipes and the drawer pulls as usual. */
    val scroll = object : ScrollView(activity) {
        private fun idle() = !canScrollVertically(1) && !canScrollVertically(-1)
        override fun onInterceptTouchEvent(e: MotionEvent) = if (idle()) false else super.onInterceptTouchEvent(e)
        override fun onTouchEvent(e: MotionEvent) = if (idle()) false else super.onTouchEvent(e)
    }.apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true }

    private var lastX = 0f
    private var lastY = 0f

    init {
        // The column fills the page, so long-pressing anywhere that is not a widget or an icon lands on it.
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(widgetBox, LinearLayout.LayoutParams(-1, -2))
            addView(grid, LinearLayout.LayoutParams(-1, -2))
            addView(View(activity), LinearLayout.LayoutParams(-1, 0, 1f))
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) { lastX = e.rawX; lastY = e.rawY }; false }
            setOnLongClickListener { onEmptyLongPress(lastX, lastY); true }
        }
        scroll.addView(content, android.widget.FrameLayout.LayoutParams(-1, -1))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    fun restore() { widgets.restore(); rebuildGrid() }

    val shortcuts: List<String> get() = prefs.getString(key, "")!!.split(';').filter { it.isNotBlank() }
    val itemCount get() = widgets.count + shortcuts.size

    fun addShortcut(component: String) {
        if (component in shortcuts) return
        prefs.edit().putString(key, (shortcuts + component).joinToString(";")).apply()
        rebuildGrid()
    }

    private fun removeShortcut(component: String) {
        prefs.edit().putString(key, (shortcuts - component).joinToString(";")).apply()
        rebuildGrid()
    }

    /** Deletes the page's contents for good. */
    fun clear() { widgets.removeAll(); prefs.edit().remove(key).apply() }

    private fun rebuildGrid() {
        grid.removeAllViews()
        val pm = activity.packageManager
        val cellW = (activity.resources.displayMetrics.widthPixels - dp(16)) / COLUMNS
        for (c in shortcuts) {
            val cn = ComponentName.unflattenFromString(c) ?: continue
            val icon = runCatching { pm.getActivityIcon(cn) }.getOrNull() ?: continue // uninstalled since: skipped (and not shown)
            val label = runCatching { pm.getActivityInfo(cn, 0).loadLabel(pm).toString() }.getOrDefault("")
            val cell = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(2), dp(10), dp(2), dp(10))
                addView(ImageView(activity).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(56), dp(56)))
                addView(TextView(activity).apply {
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(Color.WHITE)
                    setShadowLayer(6f, 0f, 1f, 0x99000000.toInt())
                    maxLines = 1
                    gravity = Gravity.CENTER
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                pressScale()
                setOnClickListener { launch(cn, it) }
                setOnLongClickListener {
                    overlays.popupFor(it, listOf(listOf(
                        MenuItem(R.drawable.ic_menu_info, "App info") { appInfo(cn) },
                        MenuItem(R.drawable.ic_menu_remove, "Remove") { removeShortcut(c) },
                    )))
                    true
                }
            }
            grid.addView(cell, GridLayout.LayoutParams().apply { width = cellW; height = GridLayout.LayoutParams.WRAP_CONTENT })
        }
    }

    private companion object { const val COLUMNS = 4 }
}
