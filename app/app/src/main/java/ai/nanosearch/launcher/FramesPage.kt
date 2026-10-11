package ai.nanosearch.launcher

import ai.nanosearch.launcher.ui.Palette
import android.app.Activity
import android.content.ComponentName
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Active Frames: the apps you opened last, as tiles that carry what the app is saying right now (its newest notification) or when you last opened it.
 * It is the page to the left of the home pages. Tap a tile to open the app, press and hold for its menu.
 */
class FramesPage(
    private val activity: Activity,
    private val pal: Palette,
    private val launch: (ComponentName, View) -> Unit,
    private val menu: (ComponentName, View) -> Unit,
) {
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val pm = activity.packageManager

    private val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(24)) }
    val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false; addView(column) }
    val root: View = scroll

    private class Frame(val cn: ComponentName, val label: String, val note: HubStore.Note?, val openedAt: Long)

    private fun round(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(pal.radius(radius)).toFloat() }

    fun refresh() {
        column.removeAllViews()
        val frames = collect()
        column.addView(NText(activity).apply { text = "Active Frames"; textSize = 13f; setTextColor(pal.onVariant); setPadding(dp(4), dp(8), 0, dp(10)); letterSpacing = 0.06f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        if (frames.isEmpty()) {
            column.addView(NText(activity).apply { text = "Apps you open show up here, with what they are saying right now."; textSize = 15f; setTextColor(pal.onVariant); setPadding(dp(4), dp(8), dp(4), 0) })
            return
        }
        for (i in frames.indices step 2) {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in 0..1) {
                val f = frames.getOrNull(i + j)
                row.addView(if (f != null) tile(f) else View(activity), LinearLayout.LayoutParams(0, dp(136), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            column.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /** Apps with something to say first (newest notification first), then the rest by when they were last opened. */
    private fun collect(): List<Frame> {
        val out = ArrayList<Frame>()
        val seen = HashSet<String>()
        val recents = AppUsage.recents(activity)
        val openedAt = recents.associate { it.first.substringBefore('/') to it.second }
        fun add(cn: ComponentName?, note: HubStore.Note?) {
            if (cn == null || cn.packageName == activity.packageName || !seen.add(cn.packageName)) return
            val label = runCatching { pm.getActivityInfo(cn, 0).loadLabel(pm).toString() }.getOrNull() ?: return
            out.add(Frame(cn, label, note, openedAt[cn.packageName] ?: 0L))
        }
        if (AppSettings.dots(activity) || AppSettings.hub(activity)) {
            for (n in HubStore.snapshot()) add(pm.getLaunchIntentForPackage(n.pkg)?.component, n)
        }
        for ((comp, _) in recents) add(ComponentName.unflattenFromString(comp), HubStore.snapshot().firstOrNull { it.pkg == comp.substringBefore('/') })
        return out.take(8)
    }

    private fun tile(f: Frame): View {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = round(pal.card, 12).also { if (pal.classic) it.setStroke(dp(1), pal.outline) }
            setPadding(dp(12), dp(10), dp(12), dp(8))
            setOnClickListener { launch(f.cn, it) }
            setOnLongClickListener { menu(f.cn, it); true }
        }
        val count = HubStore.countFor(f.cn.packageName)
        val head = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(activity).apply { setImageDrawable(AppIcons.get(activity, f.cn)) }, LinearLayout.LayoutParams(dp(34), dp(34)))
            addView(NText(activity).apply { text = f.label; textSize = 14f; setTextColor(pal.onSurface); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            if (count > 0 && AppSettings.dots(activity)) addView(NText(activity).apply {
                text = if (count > 9) "9+" else count.toString(); textSize = 10f; setTextColor(pal.onPrimary); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; minWidth = dp(18); setPadding(dp(4), 0, dp(4), 0)
                background = GradientDrawable().apply { setColor(pal.primary); cornerRadius = dp(9).toFloat() }
            }, LinearLayout.LayoutParams(-2, dp(18)))
        }
        box.addView(head, LinearLayout.LayoutParams(-1, -2))
        val line = f.note?.let { n -> listOf(n.title, n.body).filter { it.isNotBlank() }.joinToString("\n") } ?: ""
        box.addView(NText(activity).apply {
            text = if (line.isNotBlank()) line else "Nothing new"
            textSize = 13f; setTextColor(if (line.isNotBlank()) pal.onSurface else pal.onVariant); maxLines = 3; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        val whenText = (f.note?.whenMs ?: f.openedAt).takeIf { it > 0 }?.let {
            if (System.currentTimeMillis() - it < 60_000L) "just now" else DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
        }
        box.addView(NText(activity).apply { text = if (f.note != null) whenText.orEmpty() else if (whenText != null) "Opened $whenText" else ""; textSize = 11f; setTextColor(pal.onVariant) }, LinearLayout.LayoutParams(-1, -2))
        return box
    }
}
