package ai.nanosearch.launcher

import ai.nanosearch.launcher.ui.MenuItem
import ai.nanosearch.launcher.ui.Palette
import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * The Hub: one list for texts, missed calls, upcoming events and the notifications of every other app, with filters, replying in place for
 * apps that allow it, and dismissing. It slides in over the home screen from the left and is closed by tapping the dimmed strip, the close
 * button or Back.
 */
class HubScreen(
    private val activity: Activity, private val host: FrameLayout, private val pal: Palette, private val openAccess: () -> Unit,
    /** Told when the Hub appears and goes, so the system bars can match its colours. */
    private val onShown: (Boolean) -> Unit = {},
    /** Opens a popup menu next to a row (the launcher's own menu, drawn above the Hub). */
    private val popup: (View, List<List<MenuItem>>) -> Unit = { _, _ -> },
) {
    private val standalone = BuildConfig.OS7_STANDALONE
    private val ink get() = if (standalone) 0xFF17232D.toInt() else pal.onSurface
    private val secondaryInk get() = if (standalone) 0xFF50606C.toInt() else pal.onVariant
    private val accent get() = if (standalone) 0xFF006BA8.toInt() else pal.primary
    private val onAccent get() = if (standalone) Color.WHITE else pal.onPrimary
    private val accentSurface get() = if (standalone) 0xFFDCEEF9.toInt() else pal.primaryContainer
    private val onAccentSurface get() = if (standalone) 0xFF143B56.toInt() else pal.onPrimaryContainer
    private val neutralSurface get() = if (standalone) 0xFFE7ECEF.toInt() else pal.container
    private val separator get() = if (standalone) 0xFFC8D0D6.toInt() else pal.outline
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val pm = activity.packageManager
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var layer: FrameLayout? = null
    private var panel: LinearLayout? = null
    private var listView: ListView? = null
    private var banner: TextView? = null
    private var title: TextView? = null
    private var countBadge: TextView? = null
    private var clearButton: TextView? = null
    private val chips = LinkedHashMap<String, TextView>()
    private var appChip: TextView? = null
    private var appFilter: String? = null
    private var filter = "all"
    private var items: List<HubData.Item> = emptyList()
    private var replying: String? = null
    private val drafts = HashMap<String, String>()
    private val icons = LruCache<String, android.graphics.drawable.Drawable>(64)
    private val changed: () -> Unit = { reload() }

    val isShowing get() = layer != null

    private fun round(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(pal.radius(radius)).toFloat() }

    /** Spirit gives the Hub glass: a glossy header bar, glass filter pills and card rows. */
    private val os7 = standalone || AppSettings.os7Mode(activity)
    private val spirit = !os7
    private fun glass(radiusDp: Float, look: ai.nanosearch.launcher.ui.SpiritGlass.Look = ai.nanosearch.launcher.ui.SpiritGlass.Look.DARK): android.graphics.drawable.Drawable =
        ai.nanosearch.launcher.ui.SpiritGlass(activity, radiusDp, look, accent)

    /** Builds the Hub offscreen. [peeking] true leaves it at the edge for the caller to drag in. */
    private fun build() {
        if (layer != null) return
        // the size the bars have when shown, even in full screen, so the header stays clear of the status bar when it slides in over the Hub
        val bars = host.rootWindowInsets?.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val top = bars?.top ?: dp(24); val bottom = bars?.bottom ?: dp(24)
        val screenW = activity.resources.displayMetrics.widthPixels
        val width = if (standalone) screenW else (screenW * 0.9f).toInt()

        title = NText(activity).apply { text = if(os7) "Notifications" else "Hub"; textSize = if (standalone) 20f else if (spirit) 24f else 26f; setTextColor(if (standalone) Color.WHITE else ink); isAccessibilityHeading = true; if (spirit) setShadowLayer(3f, 0f, 2f, 0xCC000000.toInt()); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        clearButton = NText(activity).apply {
            text = "Clear all"; contentDescription = "Clear dismissible notifications"; textSize = 14f; setTextColor(if (standalone) Color.WHITE else accent); gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { clearAll() }
        }
        val close = NText(activity).apply { text = "✕"; contentDescription = "Close notifications"; textSize = 18f; setTextColor(if (standalone) Color.WHITE else secondaryInk); gravity = Gravity.CENTER; setOnClickListener { dismiss() } }
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            if (standalone) {
                setPadding(dp(12), 0, dp(4), 0)
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF4A5864.toInt(), 0xFF17212B.toInt()))
            }
            if (spirit) setPadding(dp(18), 0, dp(10), 0) // just the title and Clear all, no close button (Back or a tap beside the panel closes it)
            addView(title, LinearLayout.LayoutParams(-2, -2))
            // the unread count is a small pill beside the title, not part of it
            countBadge = NText(activity).apply {
                textSize = 12f; setTextColor(onAccent); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; minWidth = dp(20); setPadding(dp(6), 0, dp(6), 0)
                background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(10).toFloat() }; visibility = View.GONE
            }
            addView(countBadge, LinearLayout.LayoutParams(-2, dp(20)).apply { marginStart = dp(10) })
            addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(clearButton, LinearLayout.LayoutParams(-2, dp(if (standalone) 48 else 44)))
            if (!spirit) addView(close, LinearLayout.LayoutParams(dp(if (standalone) 48 else 44), dp(if (standalone) 48 else 44)))
        }

        val chipRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val filters = if (standalone) listOf("all" to "All", "pinned" to "Pinned") else listOf("all" to "All", "priority" to "Priority", "message" to "Messages", "call" to "Calls", "event" to "Calendar", "app" to "Apps")
        for ((key, label) in filters) {
            val chip = NText(activity).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener { filter = key; replying = null; styleChips(); refreshList() }
            }
            chips[key] = chip
            chipRow.addView(chip, LinearLayout.LayoutParams(-2, dp(if (standalone) 48 else 34)).apply { marginEnd = dp(8) })
        }
        appChip = NText(activity).apply {
            textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0); visibility = View.GONE
            setOnClickListener { appFilter = null; styleChips(); refreshList() }
        }
        chipRow.addView(appChip, 0, LinearLayout.LayoutParams(-2, dp(if (standalone) 48 else 34)).apply { marginEnd = dp(8) })
        styleChips()
        val chipScroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) }

        banner = NText(activity).apply {
            text = if (standalone) "Allow notification access  ›" else "See notifications from your other apps  ›"; textSize = 13f; setTextColor(onAccentSurface); gravity = Gravity.CENTER_VERTICAL
            if (spirit) { background = glass(9f, ai.nanosearch.launcher.ui.SpiritGlass.Look.ACCENT); setTextColor(0xFFFFFFFF.toInt()) } else background = round(accentSurface, 19)
            setPadding(dp(14), 0, dp(14), 0); setOnClickListener { openAccess() }
        }

        listView = ListView(activity).apply {
            adapter = rows
            if (spirit) { divider = android.graphics.drawable.ColorDrawable(0); dividerHeight = dp(8); clipToPadding = false; setPadding(dp(10), dp(6), dp(10), bottom + dp(12)) }
            else { divider = android.graphics.drawable.ColorDrawable(separator); dividerHeight = dp(1); clipToPadding = false; setPadding(0, 0, 0, bottom + dp(12)) }
            emptyView = null
        }

        val p = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            if (spirit) { // the panel's far side has minor curves, and its edge catches the light
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF23262A.toInt(), 0xFF111214.toInt())).apply {
                    val rad = dp(14).toFloat(); cornerRadii = floatArrayOf(0f, 0f, rad, rad, rad, rad, 0f, 0f); setStroke(dp(1), 0x33FFFFFF)
                }
                clipToOutline = true
            } else if (standalone) {
                background = android.graphics.drawable.LayerDrawable(arrayOf(
                    android.graphics.drawable.ColorDrawable(0xFFF6F8F9.toInt()),
                    android.graphics.drawable.ColorDrawable(0xFF17212B.toInt()),
                )).apply { setLayerHeight(1, top + dp(48)); setLayerGravity(1, Gravity.TOP) }
            } else setBackgroundColor(pal.panel or 0xFF000000.toInt())
            isClickable = true
            elevation = dp(16).toFloat()
            if (standalone || spirit) setPadding(0, top, 0, 0) else setPadding(dp(16), top + dp(8), dp(8), 0)
            addView(header, LinearLayout.LayoutParams(-1, if (standalone || spirit) dp(48) else -2))
            if (spirit) { // a chrome rule under the title: a bright line over a dark one, fading out at both ends
                addView(View(activity).apply {
                    background = android.graphics.drawable.GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x00FFFFFF, 0x88FFFFFF.toInt(), 0x88FFFFFF.toInt(), 0x00FFFFFF))
                }, LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(10); marginEnd = dp(10) })
                addView(View(activity).apply { setBackgroundColor(0x99000000.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(10); marginEnd = dp(10) })
            }
            addView(chipScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (spirit) 12 else 6); bottomMargin = dp(10); if (spirit) marginStart = dp(10) })
            addView(banner, LinearLayout.LayoutParams(-1, dp(if (standalone) 48 else 38)).apply { bottomMargin = dp(8); marginEnd = dp(if (spirit) 10 else 8); if (spirit) marginStart = dp(10) })
            addView(listView, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val scrim = View(activity).apply { setBackgroundColor(pal.scrim); alpha = 0f; setOnClickListener { dismiss() } }
        val l = FrameLayout(activity).apply {
            addView(scrim, FrameLayout.LayoutParams(-1, -1))
            addView(p, FrameLayout.LayoutParams(width, -1, Gravity.START))
        }
        host.addView(l, FrameLayout.LayoutParams(-1, -1))
        layer = l; panel = p
        p.translationX = -width.toFloat()
        HubStore.listen(changed)
        onShown(true)
        reload()
    }

    fun show() {
        if (layer != null) return
        build()
        val p = panel ?: return
        p.animate().translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator(1.6f)).start()
        layer?.getChildAt(0)?.animate()?.alpha(1f)?.setDuration(260)?.start()
        HubFeedback.play(activity, p, HubFeedback.Cue.OPEN)
    }

    // ---- peek: the Hub follows a finger dragged in from the left edge, and is kept or let go on release

    val isPeeking get() = peeking
    private var peeking = false

    fun beginPeek() { if (layer != null) return; build(); peeking = true; panel?.let { HubFeedback.play(activity, it, HubFeedback.Cue.ARRIVE) } }

    /** [fraction] 0 = out of sight, 1 = fully in. */
    fun peek(fraction: Float) {
        val p = panel ?: return
        val f = fraction.coerceIn(0f, 1f)
        p.translationX = -p.width * (1f - f)
        layer?.getChildAt(0)?.alpha = f
    }

    /** Lets the Hub settle: all the way in, or back out of sight. */
    fun endPeek(open: Boolean) {
        peeking = false
        val p = panel ?: return
        if (open) {
            p.animate().translationX(0f).setDuration(180).setInterpolator(DecelerateInterpolator(1.6f)).start()
            layer?.getChildAt(0)?.animate()?.alpha(1f)?.setDuration(180)?.start()
            HubFeedback.play(activity, p, HubFeedback.Cue.OPEN)
        } else dismiss()
    }

    fun dismiss(animate: Boolean = true) {
        val l = layer ?: return
        val p = panel
        layer = null; panel = null; listView = null
        onShown(false)
        HubStore.unlisten(changed)
        hideKeyboard(l)
        if (!animate || p == null) { host.removeView(l); return }
        p.animate().translationX(-p.width.toFloat()).setDuration(200).withEndAction { host.removeView(l) }.start()
        (l.getChildAt(0)).animate().alpha(0f).setDuration(200).start()
    }

    /** Back closes the reply box first, then the Hub. */
    fun back() { if (replying != null) { replying = null; refreshList() } else dismiss() }

    private fun hideKeyboard(v: View) { (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0) }

    private fun styleChips() {
        appChip?.let { c ->
            val pkg = appFilter
            c.visibility = if (pkg != null) View.VISIBLE else View.GONE
            if (pkg != null) { c.text = "${appName(pkg)}  ✕"; c.setTextColor(if (spirit) 0xFFFFFFFF.toInt() else onAccentSurface); c.background = if (spirit) glass(9f, ai.nanosearch.launcher.ui.SpiritGlass.Look.ACCENT) else round(accentSurface, 17) }
        }
        for ((key, chip) in chips) {
            val on = key == filter
            chip.setTextColor(if (spirit && on) 0xFFFFFFFF.toInt() else if (on) onAccent else secondaryInk)
            chip.background = if (spirit) glass(9f, if (on) ai.nanosearch.launcher.ui.SpiritGlass.Look.ACCENT else ai.nanosearch.launcher.ui.SpiritGlass.Look.DARK) else round(if (on) accent else neutralSurface, 17)
        }
    }

    private fun reload() {
        val l = layer ?: return
        banner?.visibility = if (HubStore.allowed(activity)) View.GONE else View.VISIBLE
        io.execute {
            val data = runCatching { if (standalone) StandaloneHubData.load(activity) else HubData.load(activity) }.getOrDefault(emptyList())
            ui.post { if (layer === l) { items = data; refreshList() } }
        }
    }

    private fun appName(pkg: String) = runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrDefault(pkg)

    private fun shown(): List<HubData.Item> {
        var list = items
        appFilter?.let { pkg -> list = list.filter { it.pkg == pkg } }
        return when (filter) {
            "all" -> list
            "pinned" -> list.filter { it.id in HubStore.pinned(activity) }
            "priority" -> list.filter { HubData.isPriority(activity, it) }
            else -> list.filter { it.kind == filter }
        }
    }

    private fun refreshList() {
        val unread = items.count { it.unread && (standalone || it.kind == "message" || it.kind == "call") }
        title?.text = if(os7) "Notifications" else "Hub"
        countBadge?.let { it.text = if (unread > 99) "99+" else unread.toString(); it.visibility = if (unread > 0) View.VISIBLE else View.GONE }
        clearButton?.visibility = if (if (standalone) items.any { it.note?.clearable == true } else items.isNotEmpty()) View.VISIBLE else View.GONE
        rows.notifyDataSetChanged()
    }

    // ---------------------------------------------------------------- rows

    private val rows = object : BaseAdapter() {
        override fun getCount() = shown().size.coerceAtLeast(1)
        override fun getItem(position: Int): Any = shown().getOrNull(position) ?: ""
        override fun getItemId(position: Int) = position.toLong()
        override fun isEnabled(position: Int) = shown().isNotEmpty()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (shown().isEmpty()) 1 else 0

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val data = shown()
            if (data.isEmpty()) return ((convertView as? TextView) ?: NText(activity)).apply {
                text = if (standalone && !HubStore.allowed(activity)) "No notification access yet\nUse Allow notification access above" else if (standalone && filter == "pinned") "No pinned notifications\nTouch and hold a notification to pin it" else "All caught up"; textSize = 16f; setTextColor(secondaryInk); gravity = Gravity.CENTER
                layoutParams = AbsListViewParams(-1, dp(220))
                if (spirit) { // an envelope gem above the words
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; setPadding(0, dp(48), 0, 0)
                    setCompoundDrawables(null, AppIcons.kindGem(activity, "email").constantState!!.newDrawable().mutate().apply { setBounds(0, 0, dp(72), dp(72)); alpha = 170 }, null, null)
                    compoundDrawablePadding = dp(10)
                }
            }
            val item = data[position]
            val row = (convertView as? LinearLayout) ?: buildRow()
            bind(row, item)
            return row
        }
    }

    @Suppress("FunctionName")
    private fun AbsListViewParams(w: Int, h: Int) = android.widget.AbsListView.LayoutParams(w, h)

    private class Parts(val icon: ImageView, val title: TextView, val time: TextView, val body: TextView, val actions: LinearLayout, val reply: TextView, val dismiss: TextView, val replyBox: LinearLayout, val replyField: EditText, val send: TextView)

    private fun buildRow(): LinearLayout {
        val icon = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val title = NText(activity).apply { textSize = 16f; setTextColor(ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val time = NText(activity).apply { textSize = 12f; setTextColor(secondaryInk); setPadding(dp(8), 0, dp(8), 0) }
        val body = NText(activity).apply { textSize = 14f; setTextColor(secondaryInk); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val reply = NText(activity).apply { text = "Reply"; textSize = 13f; setTextColor(accent); setPadding(0, dp(8), dp(20), dp(6)) }
        val dismiss = NText(activity).apply { text = "Dismiss"; textSize = 13f; setTextColor(secondaryInk); setPadding(0, dp(8), dp(20), dp(6)) }
        if (spirit) for (b in listOf(reply, dismiss)) { b.setPadding(dp(14), dp(6), dp(14), dp(6)); b.background = glass(9f) } // small glass buttons
        val actions = LinearLayout(activity).apply {
            if (spirit) { setPadding(0, dp(8), 0, dp(2)); addView(reply, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }); addView(dismiss) }
            else { addView(reply); addView(dismiss) }
        }
        val field = NEdit(activity).apply {
            hint = "Reply"; textSize = 15f; setTextColor(ink); setHintTextColor(secondaryInk); maxLines = 4
            background = round(neutralSurface, 10).also { it.setStroke(dp(1), separator) }
            setPadding(dp(10), dp(6), dp(10), dp(6)); imeOptions = EditorInfo.IME_ACTION_SEND
        }
        val send = NText(activity).apply { text = "Send"; textSize = 14f; setTextColor(onAccent); gravity = Gravity.CENTER; background = round(accent, 10); setPadding(dp(14), 0, dp(14), 0) }
        val replyBox = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            addView(field, LinearLayout.LayoutParams(0, -2, 1f)); addView(send, LinearLayout.LayoutParams(-2, dp(40)).apply { marginStart = dp(8); marginEnd = dp(8) })
        }
        val head = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; addView(title, LinearLayout.LayoutParams(0, -2, 1f)); addView(time) }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(head); addView(body); addView(actions); addView(replyBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        return LinearLayout(activity).apply {
            gravity = Gravity.TOP
            if (spirit || standalone) setPadding(dp(12), dp(12), dp(12), dp(10)) else setPadding(0, dp(12), 0, dp(10))
            addView(icon, LinearLayout.LayoutParams(dp(if (spirit) 46 else 42), dp(if (spirit) 46 else 42)).apply { marginEnd = dp(12); topMargin = dp(2) })
            addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            tag = Parts(icon, title, time, body, actions, reply, dismiss, replyBox, field, send)
        }
    }

    private fun bind(row: LinearLayout, item: HubData.Item) {
        val p = row.tag as Parts
        if (spirit) { // a glass card; an unread one has a blue edge down its left side
            val card = glass(11f, ai.nanosearch.launcher.ui.SpiritGlass.Look.CARD)
            row.background = if (item.unread) android.graphics.drawable.LayerDrawable(arrayOf(card, android.graphics.drawable.ColorDrawable(accent))).apply { setLayerWidth(1, dp(3)); setLayerGravity(1, Gravity.START); setLayerInset(1, 0, dp(9), 0, dp(9)) } else card
        }
        if (standalone) {
            row.background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(0xFFC9E6F8.toInt()))
                addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.ColorDrawable(0xFFC9E6F8.toInt()))
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.WHITE))
            }
            row.isFocusable = true
            p.reply.minHeight = dp(48); p.dismiss.minHeight = dp(48); p.send.minHeight = dp(48)
        }
        p.title.text = item.title.ifBlank { if (standalone) item.pkg?.let(::appName) ?: "Notification" else "(no title)" }
        p.title.typeface = Typeface.create("sans-serif", if (item.unread) Typeface.BOLD else Typeface.NORMAL)
        p.time.text = (if (item.id in HubStore.pinned(activity)) "★ " else "") + whenText(item)
        p.body.text = if (standalone) listOfNotNull(item.pkg?.let(::appName), item.body.takeIf { it.isNotBlank() }).joinToString(" · ") else item.body
        p.body.maxLines = if (standalone) 3 else 2
        p.body.visibility = if (p.body.text.isBlank()) View.GONE else View.VISIBLE
        bindIcon(p.icon, item)
        val note = item.note
        p.reply.visibility = if (note?.reply != null && replying != item.id) View.VISIBLE else View.GONE
        p.dismiss.visibility = if (note?.clearable == true && replying != item.id) View.VISIBLE else View.GONE
        p.actions.visibility = if (p.reply.visibility == View.VISIBLE || p.dismiss.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        p.reply.setOnClickListener { replying = item.id; rows.notifyDataSetChanged() }
        p.dismiss.setOnClickListener { note?.let { HubStore.dismiss(it.key) } }
        val replyingHere = replying == item.id && note?.reply != null
        p.replyBox.visibility = if (replyingHere) View.VISIBLE else View.GONE
        p.replyField.removeTextChangedListener(p.replyField.tag as? android.text.TextWatcher)
        p.replyField.setText(drafts[item.id].orEmpty())
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) { drafts[item.id] = s?.toString().orEmpty() }
        }
        p.replyField.addTextChangedListener(watcher); p.replyField.tag = watcher
        p.send.setOnClickListener { sendReply(item, p.replyField.text.toString()) }
        p.replyField.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendReply(item, p.replyField.text.toString()); true } else false }
        if (replyingHere) p.replyField.post { p.replyField.requestFocus(); (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(p.replyField, 0) }
        row.translationX = 0f; row.alpha = 1f
        row.setOnLongClickListener { v -> showItemMenu(v, item); true }
        row.setOnClickListener { open(item) }
        row.setOnTouchListener(SwipeToDismiss(item))
    }

    private fun bindIcon(view: ImageView, item: HubData.Item) {
        view.clearColorFilter(); view.background = null; view.setPadding(0, 0, 0, 0)
        val pkg = item.pkg
        if (spirit) { // every source is a glossy gem
            view.setImageDrawable(when {
                item.kind == "event" -> AppIcons.kindGem(activity, "calendar", Calendar.getInstance().get(Calendar.DAY_OF_MONTH))
                item.kind == "call" -> AppIcons.kindGem(activity, "phone")
                pkg != null -> pm.getLaunchIntentForPackage(pkg)?.component?.let { runCatching { AppIcons.get(activity, it) }.getOrNull() } ?: icons.get("g|$pkg") ?: runCatching { AppIcons.gemOf(activity, pm.getApplicationIcon(pkg)) }.getOrNull()?.also { icons.put("g|$pkg", it) } ?: AppIcons.kindGem(activity, "message")
                else -> AppIcons.kindGem(activity, "message")
            })
            return
        }
        val drawable = pkg?.let { k -> icons.get(k) ?: runCatching { pm.getApplicationIcon(k) }.getOrNull()?.also { icons.put(k, it) } }
        if (drawable != null && item.kind != "event" && item.kind != "call") { view.setImageDrawable(drawable); return }
        view.setImageResource(when (item.kind) { "call" -> R.drawable.ic_kind_call; "event" -> R.drawable.ic_kind_event; else -> R.drawable.ic_kind_message })
        view.setColorFilter(onAccentSurface)
        view.setPadding(dp(10), dp(10), dp(10), dp(10))
        view.background = round(accentSurface, 12)
    }

    private fun whenText(item: HubData.Item): String {
        val now = System.currentTimeMillis()
        val t = item.whenMs
        val cal = Calendar.getInstance().apply { timeInMillis = t }
        val clock = DateFormat.getTimeFormat(activity).format(cal.time)
        if (item.kind == "event" && t > now) {
            val days = ((t - now) / 86_400_000L).toInt()
            return if (days == 0 && Calendar.getInstance().get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)) clock else DateFormat.format("EEE", cal).toString() + " " + clock
        }
        val mins = (now - t) / 60_000L
        return when {
            mins < 1 -> "now"
            mins < 60 -> "${mins}m"
            mins < 24 * 60 -> "${mins / 60}h"
            else -> DateFormat.format("d MMM", cal).toString()
        }
    }

    private fun showItemMenu(anchor: View, item: HubData.Item) {
        val note = item.note
        val pinned = item.id in HubStore.pinned(activity)
        val hour = 3_600_000L
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis - System.currentTimeMillis()
        val groups = ArrayList<List<MenuItem>>()
        groups.add(listOf(
            MenuItem(R.drawable.ic_star, if (pinned) "Unpin" else "Pin to top") { HubStore.togglePin(activity, item.id); reload() },
            MenuItem(R.drawable.ic_menu_clock, "Snooze for an hour") { snooze(item, hour) },
            MenuItem(R.drawable.ic_menu_clock, "Snooze until tomorrow") { snooze(item, tomorrow) },
        ))
        item.pkg?.let { pkg ->
            groups.add(listOf(
                MenuItem(R.drawable.ic_menu_apps, "Only ${appName(pkg)}") { appFilter = pkg; filter = "all"; styleChips(); refreshList() },
                MenuItem(R.drawable.ic_menu_remove, if (standalone) "Hide ${appName(pkg)} from Notifications" else "Hide ${appName(pkg)} from the Hub") { HubStore.toggleMuted(activity, pkg); reload() },
            ))
        }
        popup(anchor, groups)
    }

    private fun snooze(item: HubData.Item, ms: Long) {
        val note = item.note
        if (note != null) HubStore.snoozeNotification(note.key, ms) else HubStore.snoozeItem(activity, item.id, System.currentTimeMillis() + ms)
        HubFeedback.play(activity, layer, HubFeedback.Cue.DISMISS)
        items = items.filter { it.id != item.id }
        refreshList()
    }

    /** Takes one item off the list: a notification is cleared from the shade, anything else is just remembered as dismissed. */
    private fun dismissItem(item: HubData.Item) {
        val note = item.note
        if (note != null) HubStore.dismiss(note.key) else HubStore.markDismissed(activity, listOf(item.id))
        HubFeedback.play(activity, layer, HubFeedback.Cue.DISMISS)
        items = items.filter { it.id != item.id }
        refreshList()
    }

    private fun clearAll() {
        HubFeedback.play(activity, layer, HubFeedback.Cue.CLEAR)
        if (standalone) {
            val removable = items.filter { it.note?.clearable == true }
            removable.forEach { it.note?.let { note -> HubStore.dismiss(note.key) } }
            val ids = removable.map { it.id }.toSet()
            items = items.filterNot { it.id in ids }
        } else {
            HubStore.dismissAll()
            HubStore.markDismissed(activity, items.filter { it.note == null }.map { it.id })
            items = emptyList()
        }
        replying = null
        refreshList()
    }

    /** Drag a row sideways to dismiss it: it follows the finger and fades, then goes if it was dragged far enough or flicked. */
    private inner class SwipeToDismiss(private val item: HubData.Item) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(activity).scaledTouchSlop
        private var downX = 0f; private var downY = 0f
        private var swiping = false
        private var tracker: VelocityTracker? = null

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            if (standalone && item.note?.clearable != true) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; swiping = false; tracker?.recycle(); tracker = VelocityTracker.obtain().also { it.addMovement(e) } }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(e)
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!swiping && Math.abs(dx) > slop * 2 && Math.abs(dx) > Math.abs(dy) * 1.6f) { swiping = true; v.parent?.requestDisallowInterceptTouchEvent(true); v.isPressed = false }
                    if (swiping) { v.translationX = dx; v.alpha = (1f - Math.abs(dx) / v.width).coerceIn(0.1f, 1f); return true }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val was = swiping
                    swiping = false
                    tracker?.computeCurrentVelocity(1000)
                    val vx = tracker?.xVelocity ?: 0f
                    tracker?.recycle(); tracker = null
                    if (was) {
                        val dx = v.translationX
                        val away = e.actionMasked == MotionEvent.ACTION_UP && (Math.abs(dx) > v.width / 3f || Math.abs(vx) > 1400f && dx * vx > 0)
                        if (away) {
                            val to = if (dx >= 0) v.width.toFloat() else -v.width.toFloat()
                            v.animate().translationX(to).alpha(0f).setDuration(160).withEndAction { dismissItem(item) }.start()
                        } else v.animate().translationX(0f).alpha(1f).setDuration(160).start()
                        return true
                    }
                }
            }
            return false
        }
    }

    /**
     * Options for sending another app's PendingIntent from here. Since Android 14 the system, by default, counts an app's PendingIntent as the app that
     * made it, which is in the background, and blocks the activity it starts (nothing happens, and no error comes back). This screen is in front and the
     * user just tapped, so it says so: the intent may start with this launcher's right to open things.
     */
    private fun sendOptions(): Bundle = android.app.ActivityOptions.makeBasic().apply {
        if (android.os.Build.VERSION.SDK_INT >= 34) setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
    }.toBundle()

    private fun launchApp(pkg: String) = pm.getLaunchIntentForPackage(pkg)?.let { activity.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } ?: throw IllegalStateException("no launcher activity for $pkg")

    /** A tap on an item opens it: a notification opens exactly what the app meant (the conversation, the mail), and if that cannot be sent the app itself opens. */
    private fun open(item: HubData.Item) {
        val note = item.note
        val ok = runCatching {
            when {
                note?.contentIntent != null -> {
                    val sent = runCatching { note.contentIntent.send(activity, 0, null, null, null, null, sendOptions()) }.isSuccess
                    if (!sent) launchApp(note.pkg) // the app's own intent was cancelled or refused: still take the user to the app
                    if (note.clearable) HubStore.dismiss(note.key)
                }
                item.open != null -> activity.startActivity(item.open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                item.pkg != null -> launchApp(item.pkg)
            }
        }.isSuccess
        if (ok) dismiss() else Toast.makeText(activity, "Couldn't open that", Toast.LENGTH_SHORT).show()
    }

    private fun sendReply(item: HubData.Item, text: String) {
        val action = item.note?.reply ?: return
        if (text.isBlank()) return
        val inputs = action.remoteInputs ?: return
        val free = inputs.firstOrNull { it.allowFreeFormInput } ?: return
        val result = Intent()
        RemoteInput.addResultsToIntent(inputs, result, Bundle().apply { putCharSequence(free.resultKey, text) })
        val sent = runCatching { action.actionIntent.send(activity, 0, result, null, null, null, sendOptions()) }.isSuccess
        Toast.makeText(activity, if (sent) "Sent" else "Couldn't send that", Toast.LENGTH_SHORT).show()
        if (sent) { drafts.remove(item.id); replying = null; hideKeyboard(layer ?: return); refreshList() }
    }
}
