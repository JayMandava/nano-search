package ai.nanosearch.launcher.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.PathInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/** One row of a popup menu or sheet: a Material icon, a label, and what happens when it is tapped. */
class MenuItem(val icon: Int, val label: String, val destructive: Boolean = false, val action: () -> Unit)

/** An app offered in the picker sheet. */
class PickApp(val title: String, val key: String, val icon: android.graphics.drawable.Drawable?)

/**
 * The launcher's own menus, drawn the way Android's launchers draw them instead of with system dialogs: a small rounded popup anchored where the
 * press happened (rows as stacked pills, actions in groups) and a bottom sheet for anything longer. Colours come from the Material 3 [Palette].
 * Everything lives in [host], a full-screen layer above the launcher, so it can be dismissed from the activity when Home or Back is pressed.
 */
class Overlays(private val activity: Activity, private val host: FrameLayout, private val pal: Palette) {
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun dpf(v: Int) = v * density
    private val emphasizedDecelerate = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    private val emphasizedAccelerate = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

    private var popupLayer: View? = null
    private var sheetLayer: View? = null
    val isShowing get() = popupLayer != null || sheetLayer != null

    fun dismissAll() { dismissPopup(animate = false); dismissSheet(animate = false) }

    // ---------------------------------------------------------------- popup

    fun dismissPopup(animate: Boolean = true) {
        val layer = popupLayer ?: return
        popupLayer = null
        val card = (layer as FrameLayout).getChildAt(1)
        if (!animate) { host.removeView(layer); return }
        // Launcher3's close: the menu shrinks to half size over 233 ms while it fades out late (83 ms, after 150 ms).
        card.animate().scaleX(0.5f).scaleY(0.5f).setDuration(233).setInterpolator(emphasizedAccelerate).start()
        for (i in 1 until layer.childCount) {
            android.animation.ObjectAnimator.ofFloat(layer.getChildAt(i), View.ALPHA, 1f, 0f).apply {
                startDelay = 150; duration = 83
                if (i == 1) addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { host.removeView(layer) } })
            }.start()
        }
        layer.getChildAt(0).animate().alpha(0f).setDuration(200).start()
    }

    /** Popup above or below a view (an icon), whichever has room. */
    fun popupFor(anchor: View, groups: List<List<MenuItem>>) {
        val at = IntArray(2)
        anchor.getLocationOnScreen(at)
        popup(at[0] + anchor.width / 2f, at[1].toFloat(), at[1] + anchor.height.toFloat(), groups, arrow = true)
    }

    /** Popup at a point (where a long-press happened). [top] and [bottom] are the edges it must not cover; for a bare point they are the same. */
    fun popup(x: Float, top: Float, bottom: Float = top, groups: List<List<MenuItem>>, arrow: Boolean = false) {
        dismissAll()
        val layer = FrameLayout(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) { dismissPopup(); true } else true }
        }
        // a faint scrim that eases in, so the menu reads as the top layer
        val scrim = View(activity).apply { setBackgroundColor(0x22000000); alpha = 0f }
        layer.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        scrim.animate().alpha(1f).setDuration(200).start()

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dpf(6)
            clipChildren = false
        }
        val rows = ArrayList<View>()
        groups.forEachIndexed { gi, group ->
            group.forEachIndexed { i, item ->
                val row = menuRow(item, cornerRadii(i, group.size, 24, 4))
                rows += row
                card.addView(row, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = if (i == 0 && gi > 0) dp(6) else if (i > 0) dp(2) else 0 })
            }
        }
        card.measure(View.MeasureSpec.makeMeasureSpec(dp(280), View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        val w = max(card.measuredWidth, dp(216))
        val h = card.measuredHeight
        val screenW = host.resources.displayMetrics.widthPixels
        val screenH = host.resources.displayMetrics.heightPixels
        val margin = dp(12)
        val left = (x - w / 2f).coerceIn(margin.toFloat(), (screenW - w - margin).toFloat().coerceAtLeast(margin.toFloat()))
        val gap = dp(8)
        val below = bottom + gap + h + margin <= screenH - dp(24)
        val topPx = if (below) bottom + gap else (top - gap - h).coerceAtLeast(margin.toFloat())
        layer.addView(card, FrameLayout.LayoutParams(w, h).apply { leftMargin = left.toInt(); this.topMargin = topPx.toInt() })
        val pointer = if (arrow) TriangleView(activity, pal.card, pointingDown = !below).also {
            val aw = dp(12); val ah = dp(10)
            val ax = (x - aw / 2f).coerceIn(left + dp(16), left + w - dp(16) - aw)
            val ay = if (below) topPx - ah + 1 else topPx + h - 1
            layer.addView(it, FrameLayout.LayoutParams(aw, ah).apply { leftMargin = ax.toInt(); this.topMargin = ay.toInt() })
        } else null
        host.addView(layer, FrameLayout.LayoutParams(-1, -1))
        popupLayer = layer

        // Launcher3's open: grow from half size to 102% over 200 ms, then settle back to 100% over 200 ms; everything fades in within 83 ms.
        card.pivotX = (x - left).coerceIn(0f, w.toFloat())
        card.pivotY = if (below) 0f else h.toFloat()
        card.scaleX = 0.5f; card.scaleY = 0.5f; card.alpha = 0f
        card.animate().scaleX(1.02f).scaleY(1.02f).setDuration(200).setInterpolator(emphasizedDecelerate).withEndAction {
            card.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(PathInterpolator(0.3f, 0f, 0.33f, 1f)).start()
        }.start()
        android.animation.ObjectAnimator.ofFloat(card, View.ALPHA, 0f, 1f).setDuration(83).start() // a separate animator: a view's animate() shares one duration
        pointer?.apply { alpha = 0f; pivotX = width / 2f; animate().alpha(1f).setStartDelay(60).setDuration(83).start() }
    }

    private fun cornerRadii(i: Int, n: Int, outer: Int, inner: Int): FloatArray {
        if (n == 1) return FloatArray(8) { dpf(100) } // a lone row is a full pill
        val top = if (i == 0) dpf(outer) else dpf(inner)
        val bottom = if (i == n - 1) dpf(outer) else dpf(inner)
        return floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
    }

    private fun menuRow(item: MenuItem, radii: FloatArray): View {
        val ink = if (item.destructive) pal.error else pal.onSurface
        val bg = GradientDrawable().apply { setColor(pal.card); cornerRadii = radii }
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadii = radii }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(20), 0)
            background = RippleDrawable(ColorStateList.valueOf(pal.outline), bg, mask)
            addView(ImageView(activity).apply { setImageResource(item.icon); setColorFilter(ink) }, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(TextView(activity).apply {
                text = item.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ink)
                maxLines = 1
                setPadding(dp(18), 0, 0, 0)
            }, LinearLayout.LayoutParams(-2, -2))
            setOnClickListener { dismissPopup(); item.action() }
        }
    }

    // ---------------------------------------------------------------- bottom sheet

    fun dismissSheet(animate: Boolean = true) {
        val layer = sheetLayer ?: return
        sheetLayer = null
        hideKeyboard(layer)
        if (!animate) { host.removeView(layer); return }
        val sheet = (layer as FrameLayout).getChildAt(1)
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(200).setInterpolator(emphasizedAccelerate).withEndAction { host.removeView(layer) }.start()
        layer.getChildAt(0).animate().alpha(0f).setDuration(200).start()
    }

    private fun hideKeyboard(v: View) {
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun bottomInset(): Int = host.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())?.bottom ?: 0

    /** A rounded-top sheet sliding up from the bottom with a drag handle and a title; [content] fills the rest. Swipe down, tap outside or Back closes it. */
    fun sheet(title: String?, content: View, maxHeightFraction: Float = 0.7f) {
        dismissAll()
        val layer = FrameLayout(activity).apply { isClickable = true }
        val scrim = View(activity).apply { setBackgroundColor(pal.scrim); alpha = 0f; setOnClickListener { dismissSheet() } }
        layer.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        scrim.animate().alpha(1f).setDuration(250).start()

        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(pal.card); cornerRadii = floatArrayOf(dpf(28), dpf(28), dpf(28), dpf(28), 0f, 0f, 0f, 0f) }
            elevation = dpf(16)
            setPadding(0, 0, 0, bottomInset())
            isClickable = true
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(12), dp(24), if (title != null) dp(8) else dp(4))
            addView(View(activity).apply { background = GradientDrawable().apply { setColor(pal.onVariant); alpha = 110; cornerRadius = dpf(2) } }, LinearLayout.LayoutParams(dp(32), dp(4)))
            if (title != null) addView(TextView(activity).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(pal.onSurface)
                setPadding(0, dp(16), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
        }
        // dragging the header down closes the sheet
        var startY = 0f
        header.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> startY = e.rawY
                MotionEvent.ACTION_MOVE -> sheet.translationY = max(0f, e.rawY - startY)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    if (sheet.translationY > sheet.height * 0.3f) dismissSheet() else sheet.animate().translationY(0f).setDuration(180).start()
            }
            true
        }
        sheet.addView(header, LinearLayout.LayoutParams(-1, -2))
        val maxH = (host.resources.displayMetrics.heightPixels * maxHeightFraction).toInt()
        sheet.addView(MaxHeightFrame(activity, maxH).apply { addView(content, FrameLayout.LayoutParams(-1, -2)) }, LinearLayout.LayoutParams(-1, -2))
        layer.addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        host.addView(layer, FrameLayout.LayoutParams(-1, -1))
        sheetLayer = layer
        sheet.translationY = host.resources.displayMetrics.heightPixels.toFloat()
        sheet.animate().translationY(0f).setDuration(320).setInterpolator(emphasizedDecelerate).start()
    }

    fun listSheet(title: String?, items: List<MenuItem>) {
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(12)) }
        items.forEachIndexed { i, item ->
            list.addView(menuRow(item, cornerRadii(i, items.size, 24, 4)).also { r -> r.setOnClickListener { dismissSheet(); item.action() } },
                LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = if (i > 0) dp(2) else 0 })
        }
        sheet(title, list)
    }

    /** A sheet listing arbitrary rows from [adapter]; used for the widget picker. */
    fun adapterSheet(title: String, adapter: BaseAdapter, onPick: (Int) -> Unit) {
        val list = ListView(activity).apply {
            this.adapter = adapter
            divider = null
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setOnItemClickListener { _, _, p, _ -> dismissSheet(); onPick(p) }
            setPadding(0, 0, 0, dp(12)); clipToPadding = false
        }
        sheet(title, list, 0.75f)
    }

    /** A searchable list of apps with their icons. */
    fun appPicker(title: String, apps: List<PickApp>, onPick: (PickApp) -> Unit) {
        var shown = apps
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val a = shown[position]
                return (convertView as? LinearLayout) ?: LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8))
                    addView(ImageView(activity), LinearLayout.LayoutParams(dp(40), dp(40)))
                    addView(TextView(activity).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(pal.onSurface); setPadding(dp(16), 0, 0, 0); maxLines = 1 }, LinearLayout.LayoutParams(-2, -2))
                }.also { }.apply {
                    (getChildAt(0) as ImageView).setImageDrawable(a.icon)
                    (getChildAt(1) as TextView).text = a.title
                }
            }
        }
        val search = EditText(activity).apply {
            hint = "Search apps"
            setHintTextColor(pal.onVariant); setTextColor(pal.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setSingleLine()
            background = GradientDrawable().apply { setColor(pal.container); cornerRadius = dpf(28) }
            setPadding(dp(20), 0, dp(20), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val q = s.toString().trim().lowercase()
                    shown = if (q.isEmpty()) apps else apps.filter { it.title.lowercase().contains(q) }
                    adapter.notifyDataSetChanged()
                }
            })
        }
        val list = ListView(activity).apply {
            this.adapter = adapter
            divider = null
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setOnItemClickListener { _, _, p, _ -> dismissSheet(); onPick(shown[p]) }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
            addView(search, LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(dp(20), 0, dp(20), dp(8)) })
            addView(list, LinearLayout.LayoutParams(-1, dp(420)))
        }
        sheet(title, content, 0.85f)
    }

    /** The small arrow that ties a popup to the icon it came from. */
    private class TriangleView(context: Context, private val color: Int, private val pointingDown: Boolean) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = this@TriangleView.color }
        private val path = android.graphics.Path()
        override fun onDraw(canvas: android.graphics.Canvas) {
            path.reset()
            val w = width.toFloat(); val h = height.toFloat()
            if (pointingDown) { path.moveTo(0f, 0f); path.lineTo(w, 0f); path.lineTo(w / 2, h) } else { path.moveTo(0f, h); path.lineTo(w, h); path.lineTo(w / 2, 0f) }
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    /** Wraps content that can be taller than the screen allows: it gets at most [maxHeight] pixels. */
    private class MaxHeightFrame(context: Context, private val maxHeight: Int) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(min(maxHeight, View.MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: maxHeight), View.MeasureSpec.AT_MOST))
        }
    }
}
