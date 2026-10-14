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

    /** Spirit: each frame is a glass card with a header bar, a shadow and a bevelled edge. */
    private val spirit = pal.classic && !pal.sense

    private val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(24)); clipChildren = false; clipToPadding = false }
    val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(column) }
    val root: View = scroll

    private class Frame(val cn: ComponentName, val label: String, val note: HubStore.Note?, val openedAt: Long)

    private fun round(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(pal.radius(radius)).toFloat() }

    fun refresh() {
        if (spirit && !loading) {
            loading = true
            Thread {
                val m = runCatching { LiveFrames.media(activity) }.getOrDefault(emptyList())
                val e = runCatching { HubData.load(activity) }.getOrDefault(emptyList())
                val map = HashMap<String, LiveFrames.Live>()
                for (f in runCatching { collect(m) }.getOrDefault(emptyList())) runCatching { LiveFrames.live(activity, f.cn.packageName, f.label, e) }.getOrNull()?.let { map[f.cn.packageName] = it }
                val sig = map.entries.sortedBy { it.key }.joinToString(";") { it.key + it.value.signature }
                activity.runOnUiThread {
                    loading = false
                    if (m.map { it.title + it.playing } != media.map { it.title + it.playing } || sig != liveSig) { media = m; extras = e; liveByPkg = map; liveSig = sig; draw() }
                }
            }.start()
        }
        draw()
    }

    private fun draw() {
        column.removeAllViews()
        val frames = collect()
        column.addView(NText(activity).apply { text = "Active Frames"; textSize = 13f; setTextColor(if (spirit) 0xFFFFFFFF.toInt() else pal.onVariant); setPadding(dp(4), dp(8), 0, dp(10)); letterSpacing = 0.06f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); if (spirit) setShadowLayer(6f, 0f, 1f, 0xCC000000.toInt()) })
        if (frames.isEmpty()) {
            column.addView(NText(activity).apply { text = "Apps you open show up here, with what they are saying right now."; textSize = 15f; setTextColor(pal.onVariant); setPadding(dp(4), dp(8), dp(4), 0) })
            return
        }
        if (spirit) { // a frame playing music takes a full row; the rest sit two to a row
            val queue = ArrayList(frames)
            while (queue.isNotEmpty()) {
                val first = queue.removeAt(0)
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
                if (mediaFor(first) != null) row.addView(spiritTile(first, wide = true), LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(8)) })
                else {
                    row.addView(spiritTile(first), LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(8)) })
                    val nextIdx = queue.indexOfFirst { mediaFor(it) == null }
                    if (nextIdx >= 0) row.addView(spiritTile(queue.removeAt(nextIdx)), LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(8)) })
                    else row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
                }
                column.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
            return
        }
        for (i in frames.indices step 2) {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
            for (j in 0..1) {
                val f = frames.getOrNull(i + j)
                row.addView(if (f != null) tile(f) else View(activity), LinearLayout.LayoutParams(0, dp(136), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            column.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /** Apps with something to say first (newest notification first), then the rest by when they were last opened. */
    private fun collect(playing: List<LiveFrames.Media> = media): List<Frame> {
        val out = ArrayList<Frame>()
        val seen = HashSet<String>()
        val recents = AppUsage.recents(activity)
        val openedAt = recents.associate { it.first.substringBefore('/') to it.second }
        fun add(cn: ComponentName?, note: HubStore.Note?) {
            if (cn == null || cn.packageName == activity.packageName || !seen.add(cn.packageName)) return
            val label = runCatching { pm.getActivityInfo(cn, 0).loadLabel(pm).toString() }.getOrNull() ?: return
            out.add(Frame(cn, label, note, openedAt[cn.packageName] ?: 0L))
        }
        if (spirit) for (m in playing) add(pm.getLaunchIntentForPackage(m.pkg)?.component, null) // whatever is playing leads
        if (AppSettings.dots(activity) || AppSettings.hub(activity)) {
            for (n in HubStore.snapshot()) add(pm.getLaunchIntentForPackage(n.pkg)?.component, n)
        }
        for ((comp, _) in recents) add(ComponentName.unflattenFromString(comp), HubStore.snapshot().firstOrNull { it.pkg == comp.substringBefore('/') })
        return out.take(8)
    }

    private fun whenOf(f: Frame): String {
        val whenText = (f.note?.whenMs ?: f.openedAt).takeIf { it > 0 }?.let {
            if (System.currentTimeMillis() - it < 60_000L) "just now" else DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
        }
        return if (f.note != null) whenText.orEmpty() else if (whenText != null) "Opened $whenText" else ""
    }

    /** The live things worth showing for an app with no notification: its next events, its missed calls, or its latest texts. Loaded off the main thread. */
    private var extras: List<HubData.Item> = emptyList()
    private var media: List<LiveFrames.Media> = emptyList()
    private var loading = false
    private var liveSig = ""

    private fun mediaFor(f: Frame) = media.firstOrNull { it.pkg == f.cn.packageName }

    private var liveByPkg: Map<String, LiveFrames.Live> = emptyMap()

    private fun glassButton(label: String, onClick: () -> Unit) = NText(activity).apply {
        text = label; textSize = 13f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
        background = ai.nanosearch.launcher.ui.SpiritGlass(activity, 9f, ai.nanosearch.launcher.ui.SpiritGlass.Look.DARK, pal.primary)
        setOnClickListener { onClick(); postDelayed({ refresh() }, 350) }
    }

    /** A glass card: a glossy header with the app's gem, its name and unread count, then what the app is doing, and when. */
    private fun spiritTile(f: Frame, wide: Boolean = false): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ai.nanosearch.launcher.ui.SpiritGlass(activity, 11f, ai.nanosearch.launcher.ui.SpiritGlass.Look.CARD, pal.primary)
            clipToOutline = true
            elevation = dp(5).toFloat()
            minimumHeight = dp(150)
            setOnClickListener { launch(f.cn, it) }
            setOnLongClickListener { menu(f.cn, it); true }
        }
        val count = HubStore.countFor(f.cn.packageName)
        val head = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ai.nanosearch.launcher.ui.SpiritGlass(activity, 11f, ai.nanosearch.launcher.ui.SpiritGlass.Look.DOCK, pal.primary, topOnly = true)
            setPadding(dp(8), dp(6), dp(10), dp(6))
            addView(ImageView(activity).apply { setImageDrawable(AppIcons.get(activity, f.cn)) }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(NText(activity).apply { text = f.label; textSize = 14f; setTextColor(pal.onSurface); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            if (count > 0 && AppSettings.dots(activity)) addView(NText(activity).apply {
                text = if (count > 9) "9+" else count.toString(); textSize = 10f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; minWidth = dp(20); setPadding(dp(5), 0, dp(5), 0)
                background = ai.nanosearch.launcher.ui.SpiritGlass(activity, 10f, ai.nanosearch.launcher.ui.SpiritGlass.Look.ACCENT, pal.primary)
            }, LinearLayout.LayoutParams(-2, dp(20)))
        }
        card.addView(head, LinearLayout.LayoutParams(-1, dp(52)))
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(9), dp(12), 0) }
        fun text(t: String, size: Float, color: Int, lines: Int, bold: Boolean = false) = NText(activity).apply {
            this.text = t; textSize = size; setTextColor(color); maxLines = lines; ellipsize = TextUtils.TruncateAt.END; if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        fun picture(b: android.graphics.Bitmap, h: Int) = ImageView(activity).apply {
            setImageBitmap(b); scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(7).toFloat()) }
        }.let { img -> LinearLayout(activity).apply { addView(img, LinearLayout.LayoutParams(-1, dp(h))) } }

        val m = mediaFor(f)
        val note = f.note
        val live = liveByPkg[f.cn.packageName]
        when {
            m != null -> { // now playing: the cover, the track and the transport buttons
                val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
                if (m.art != null) row.addView(ImageView(activity).apply {
                    setImageBitmap(m.art); scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat()) }
                }, LinearLayout.LayoutParams(dp(84), dp(84)).apply { marginEnd = dp(12) })
                val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                col.addView(text(m.title, 15f, pal.onSurface, 2, bold = true))
                if (m.artist.isNotBlank()) col.addView(text(m.artist, 13f, pal.onVariant, 1), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
                val controls = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
                controls.addView(glassButton("◀◀") { m.previous() }, LinearLayout.LayoutParams(0, dp(36), 1f))
                controls.addView(glassButton(if (m.playing) "❚❚" else "▶") { m.toggle() }, LinearLayout.LayoutParams(0, dp(36), 1.3f).apply { marginStart = dp(6); marginEnd = dp(6) })
                controls.addView(glassButton("▶▶") { m.next() }, LinearLayout.LayoutParams(0, dp(36), 1f))
                col.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                body.addView(row)
            }
            note != null && (note.title.isNotBlank() || note.body.isNotBlank()) -> {
                if (note.picture != null) body.addView(picture(note.picture, if (wide) 110 else 70), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                if (note.title.isNotBlank()) body.addView(text(note.title, 13.5f, pal.onSurface, 1, bold = true))
                val shown = if (note.lines.size > 1) note.lines.takeLast(3) else listOf(note.body).filter { it.isNotBlank() }
                for (l in shown) body.addView(text(l, 12.5f, pal.onVariant, if (shown.size == 1 && note.picture == null) 3 else 1), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            }
            live != null -> {
                live.big?.let { body.addView(text(it, 30f, pal.onSurface, 1, bold = false).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }) }
                live.bigSub?.let { body.addView(text(it, 12.5f, pal.onVariant, 1), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }) }
                if (live.photos.isNotEmpty()) {
                    val strip = LinearLayout(activity)
                    for ((i, bmp) in live.photos.withIndex()) strip.addView(ImageView(activity).apply {
                        setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                        outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(6).toFloat()) }
                    }, LinearLayout.LayoutParams(0, dp(if (wide) 84 else 56), 1f).apply { if (i > 0) marginStart = dp(5) })
                    body.addView(strip, LinearLayout.LayoutParams(-1, -2))
                }
                for (l in live.lines) body.addView(text(l, 13f, pal.onSurface, 1), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(3) })
                for (sc in live.shortcuts) body.addView(LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = ai.nanosearch.launcher.ui.SpiritGlass(activity, 8f, ai.nanosearch.launcher.ui.SpiritGlass.Look.DARK, pal.primary)
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    sc.icon?.let { addView(ImageView(activity).apply { setImageDrawable(it) }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(8) }) }
                    addView(text(sc.label, 12.5f, pal.onSurface, 1), LinearLayout.LayoutParams(0, -2, 1f))
                    setOnClickListener { sc.open() }
                }, LinearLayout.LayoutParams(-1, dp(32)).apply { bottomMargin = dp(4) })
            }
            else -> { // nothing the app lets us read: its gem, standing in for the frame's picture
                body.gravity = Gravity.CENTER
                body.addView(ImageView(activity).apply { setImageDrawable(AppIcons.get(activity, f.cn)); alpha = 0.85f }, LinearLayout.LayoutParams(dp(60), dp(60)))
            }
        }
        card.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        card.addView(NText(activity).apply { text = if (m != null) (if (m.playing) "Playing" else "Paused") else whenOf(f); textSize = 11f; setTextColor(pal.onVariant); setPadding(dp(12), dp(4), dp(12), dp(9)); gravity = Gravity.END }, LinearLayout.LayoutParams(-1, -2))
        return card
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
