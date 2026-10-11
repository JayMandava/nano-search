package ai.nanosearch.launcher

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
) {
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
    private var clearButton: TextView? = null
    private val chips = LinkedHashMap<String, TextView>()
    private var filter = "all"
    private var items: List<HubData.Item> = emptyList()
    private var replying: String? = null
    private val drafts = HashMap<String, String>()
    private val icons = LruCache<String, android.graphics.drawable.Drawable>(64)
    private val changed: () -> Unit = { reload() }

    val isShowing get() = layer != null

    private fun round(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(pal.radius(radius)).toFloat() }

    fun show() {
        if (layer != null) return
        val bars = host.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars())
        val top = bars?.top ?: dp(24); val bottom = bars?.bottom ?: dp(24)
        val screenW = activity.resources.displayMetrics.widthPixels
        val width = (screenW * 0.9f).toInt()

        title = TextView(activity).apply { text = "Hub"; textSize = 26f; setTextColor(pal.onSurface); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        clearButton = TextView(activity).apply {
            text = "Clear"; textSize = 14f; setTextColor(pal.primary); gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { HubStore.dismissAll() }
        }
        val close = TextView(activity).apply { text = "✕"; textSize = 18f; setTextColor(pal.onVariant); gravity = Gravity.CENTER; setOnClickListener { dismiss() } }
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            addView(clearButton, LinearLayout.LayoutParams(-2, dp(44)))
            addView(close, LinearLayout.LayoutParams(dp(44), dp(44)))
        }

        val chipRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        for ((key, label) in listOf("all" to "All", "message" to "Messages", "call" to "Calls", "event" to "Calendar", "app" to "Apps")) {
            val chip = TextView(activity).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener { filter = key; replying = null; styleChips(); refreshList() }
            }
            chips[key] = chip
            chipRow.addView(chip, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(8) })
        }
        styleChips()
        val chipScroll = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) }

        banner = TextView(activity).apply {
            text = "See notifications from your other apps  ›"; textSize = 13f; setTextColor(pal.onPrimaryContainer); gravity = Gravity.CENTER_VERTICAL
            background = round(pal.primaryContainer, 19); setPadding(dp(14), 0, dp(14), 0); setOnClickListener { openAccess() }
        }

        listView = ListView(activity).apply {
            adapter = rows
            divider = android.graphics.drawable.ColorDrawable(pal.outline); dividerHeight = dp(1)
            clipToPadding = false; setPadding(0, 0, 0, bottom + dp(12))
            emptyView = null
        }

        val p = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.panel or 0xFF000000.toInt())
            isClickable = true
            elevation = dp(16).toFloat()
            setPadding(dp(16), top + dp(8), dp(8), 0)
            addView(header, LinearLayout.LayoutParams(-1, -2))
            addView(chipScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(10) })
            addView(banner, LinearLayout.LayoutParams(-1, dp(38)).apply { bottomMargin = dp(8); marginEnd = dp(8) })
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
        p.animate().translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator(1.6f)).start()
        scrim.animate().alpha(1f).setDuration(260).start()
        HubStore.listen(changed)
        onShown(true)
        reload()
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
        for ((key, chip) in chips) {
            val on = key == filter
            chip.setTextColor(if (on) pal.onPrimary else pal.onVariant)
            chip.background = round(if (on) pal.primary else pal.container, 17)
        }
    }

    private fun reload() {
        val l = layer ?: return
        banner?.visibility = if (HubStore.allowed(activity)) View.GONE else View.VISIBLE
        io.execute {
            val data = runCatching { HubData.load(activity) }.getOrDefault(emptyList())
            ui.post { if (layer === l) { items = data; refreshList() } }
        }
    }

    private fun shown() = if (filter == "all") items else items.filter { it.kind == filter || (filter == "message" && it.kind == "email") }

    private fun refreshList() {
        val unread = items.count { it.unread && (it.kind == "message" || it.kind == "call") }
        title?.text = if (unread > 0) "Hub  ·  $unread" else "Hub"
        clearButton?.visibility = if (items.any { it.note?.clearable == true }) View.VISIBLE else View.GONE
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
            if (data.isEmpty()) return convertView ?: TextView(activity).apply {
                text = "All caught up"; textSize = 16f; setTextColor(pal.onVariant); gravity = Gravity.CENTER
                layoutParams = AbsListViewParams(-1, dp(220))
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
        val title = TextView(activity).apply { textSize = 16f; setTextColor(pal.onSurface); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val time = TextView(activity).apply { textSize = 12f; setTextColor(pal.onVariant); setPadding(dp(8), 0, dp(8), 0) }
        val body = TextView(activity).apply { textSize = 14f; setTextColor(pal.onVariant); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val reply = TextView(activity).apply { text = "Reply"; textSize = 13f; setTextColor(pal.primary); setPadding(0, dp(8), dp(20), dp(6)) }
        val dismiss = TextView(activity).apply { text = "Dismiss"; textSize = 13f; setTextColor(pal.onVariant); setPadding(0, dp(8), dp(20), dp(6)) }
        val actions = LinearLayout(activity).apply { addView(reply); addView(dismiss) }
        val field = EditText(activity).apply {
            hint = "Reply"; textSize = 15f; setTextColor(pal.onSurface); setHintTextColor(pal.onVariant); maxLines = 4
            background = round(pal.container, 10).also { it.setStroke(dp(1), pal.outline) }
            setPadding(dp(10), dp(6), dp(10), dp(6)); imeOptions = EditorInfo.IME_ACTION_SEND
        }
        val send = TextView(activity).apply { text = "Send"; textSize = 14f; setTextColor(pal.onPrimary); gravity = Gravity.CENTER; background = round(pal.primary, 10); setPadding(dp(14), 0, dp(14), 0) }
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
            setPadding(0, dp(12), 0, dp(10))
            addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12); topMargin = dp(2) })
            addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            tag = Parts(icon, title, time, body, actions, reply, dismiss, replyBox, field, send)
        }
    }

    private fun bind(row: LinearLayout, item: HubData.Item) {
        val p = row.tag as Parts
        p.title.text = item.title.ifBlank { "(no title)" }
        p.title.typeface = Typeface.create("sans-serif", if (item.unread) Typeface.BOLD else Typeface.NORMAL)
        p.time.text = whenText(item)
        p.body.text = item.body
        p.body.visibility = if (item.body.isBlank()) View.GONE else View.VISIBLE
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
        row.setOnClickListener { open(item) }
    }

    private fun bindIcon(view: ImageView, item: HubData.Item) {
        view.clearColorFilter(); view.background = null; view.setPadding(0, 0, 0, 0)
        val pkg = item.pkg
        val drawable = pkg?.let { k -> icons.get(k) ?: runCatching { pm.getApplicationIcon(k) }.getOrNull()?.also { icons.put(k, it) } }
        if (drawable != null && item.kind != "event" && item.kind != "call") { view.setImageDrawable(drawable); return }
        view.setImageResource(when (item.kind) { "call" -> R.drawable.ic_kind_call; "event" -> R.drawable.ic_kind_event; else -> R.drawable.ic_kind_message })
        view.setColorFilter(pal.onPrimaryContainer)
        view.setPadding(dp(10), dp(10), dp(10), dp(10))
        view.background = round(pal.primaryContainer, 12)
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

    private fun open(item: HubData.Item) {
        val note = item.note
        val ok = runCatching {
            when {
                note?.contentIntent != null -> { note.contentIntent.send(); if (note.clearable) HubStore.dismiss(note.key) }
                item.open != null -> activity.startActivity(item.open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                item.pkg != null -> pm.getLaunchIntentForPackage(item.pkg)?.let { activity.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
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
        val sent = runCatching { action.actionIntent.send(activity, 0, result) }.isSuccess
        Toast.makeText(activity, if (sent) "Sent" else "Couldn't send that", Toast.LENGTH_SHORT).show()
        if (sent) { drafts.remove(item.id); replying = null; hideKeyboard(layer ?: return); refreshList() }
    }
}
