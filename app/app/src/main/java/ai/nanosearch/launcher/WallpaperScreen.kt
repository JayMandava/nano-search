package ai.nanosearch.launcher

import ai.nanosearch.launcher.ui.Palette
import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The wallpaper screen: what the home and lock screens show right now, the ways to change them, and a crop step
 * for photos (pan and zoom inside a frame the shape of the screen, pick home / lock / both). A home wallpaper can be
 * wider than the screen so it slides as the home pages are swiped; see [WallpaperScreen.homeWidthFactor].
 */
class WallpaperScreen(
    private val activity: Activity,
    private val host: FrameLayout,
    private val pal: Palette,
    private val pageCount: () -> Int,
    private val pickPhoto: () -> Unit,
    private val openSystemApp: () -> Unit,
    private val openLive: () -> Unit,
) {
    private enum class Target { HOME, LOCK, BOTH }

    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val screenW = activity.resources.displayMetrics.widthPixels
    private val screenH = activity.resources.displayMetrics.heightPixels
    private val wm = WallpaperManager.getInstance(activity)
    private val io = Executors.newSingleThreadExecutor()

    private var layer: FrameLayout? = null
    private var content: FrameLayout? = null
    private var cropping = false
    /** Where the photo being picked is meant to go, when the user started from a thumbnail or the lock switch; null means the crop step asks. */
    private var pendingTarget: Target? = null

    val isShowing get() = layer != null

    // ---------------------------------------------------------------- showing and leaving

    fun show() {
        ensureLayer()
        showOverview()
    }

    /** The photo came back from the picker: crop it. */
    fun startCrop(uri: Uri) {
        ensureLayer()
        io.execute {
            val bmp = runCatching { decode(uri) }.onFailure { Log.w(TAG, "cannot read that photo: $it") }.getOrNull()
            activity.runOnUiThread {
                if (bmp == null) Toast.makeText(activity, "Couldn't open that photo", Toast.LENGTH_SHORT).show() else showCrop(bmp)
            }
        }
    }

    /** Back: from the crop to the overview, from the overview out. */
    fun back() { if (cropping) showOverview() else dismiss() }

    fun dismiss() {
        layer?.let { host.removeView(it) }
        layer = null; content = null; cropping = false
    }

    private fun ensureLayer() {
        if (layer != null) return
        val l = FrameLayout(activity).apply {
            setBackgroundColor(pal.panel or 0xFF000000.toInt()) // solid: nothing of the home screen shows through
            isClickable = true
        }
        val c = FrameLayout(activity)
        l.addView(c, FrameLayout.LayoutParams(-1, -1))
        host.addView(l, FrameLayout.LayoutParams(-1, -1))
        layer = l; content = c
    }

    private fun insets(): Pair<Int, Int> {
        val i = host.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars())
        return (i?.top ?: dp(24)) to (i?.bottom ?: dp(24))
    }

    // ---------------------------------------------------------------- overview

    private fun showOverview() {
        cropping = false
        val (top, bottom) = insets()
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), top + dp(8), dp(20), bottom + dp(16))
        }
        col.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label("Wallpaper", 24f, pal.onSurface, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("✕", 18f, pal.onVariant).apply { gravity = Gravity.CENTER; setOnClickListener { dismiss() } }, LinearLayout.LayoutParams(dp(44), dp(44)))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val colW = (screenW - dp(40) - dp(12)) / 2
        val thumbH = min(colW * screenH / screenW, (screenH * 0.40f).toInt())
        val homeImg = thumbView(); val lockImg = thumbView()
        val homeCaption = label("Home screen", 14f, pal.onVariant)
        val lockCaption = label("Lock screen", 14f, pal.onVariant)
        homeImg.setOnClickListener { pendingTarget = Target.HOME; pickPhoto() }
        lockImg.setOnClickListener { pendingTarget = Target.LOCK; pickPhoto() }
        col.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(thumbColumn(homeImg, homeCaption, colW, thumbH))
            addView(thumbColumn(lockImg, lockCaption, colW, thumbH), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // The lock screen follows the home wallpaper unless it has one of its own. Turning this off gives it its own copy (tap the lock preview to choose another photo); turning it on drops that copy.
        var programmatic = false
        val sameSwitch = Switch(activity).apply {
            text = "Same wallpaper on the lock screen"
            setTextColor(pal.onSurface)
            textSize = 15f
            isChecked = true
            isEnabled = false // until the current wallpapers have been read
            setOnCheckedChangeListener { v, on ->
                if (programmatic) return@setOnCheckedChangeListener
                if (!on) {
                    io.execute {
                        val copied = runCatching { copyHomeToLock() }.onFailure { Log.w(TAG, "cannot copy the home wallpaper: $it") }.getOrDefault(false)
                        activity.runOnUiThread {
                            if (layer == null) return@runOnUiThread
                            if (copied) showOverview()
                            else {
                                // a live wallpaper has no image to copy: the switch stays on and the lock screen needs a photo
                                programmatic = true; (v as Switch).isChecked = true; programmatic = false
                                Toast.makeText(activity, "Choose a photo for the lock screen", Toast.LENGTH_SHORT).show()
                                pendingTarget = Target.LOCK
                                pickPhoto()
                            }
                        }
                    }
                } else {
                    io.execute { runCatching { wm.clear(WallpaperManager.FLAG_LOCK) } }
                    activity.runOnUiThread { showOverview() }
                }
            }
        }
        col.addView(sameSwitch, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16); marginStart = dp(4); marginEnd = dp(4) })
        loadThumbs(homeImg, lockImg) { lockOwn ->
            programmatic = true; sameSwitch.isChecked = !lockOwn; programmatic = false
            sameSwitch.isEnabled = true
        }

        col.addView(row(R.drawable.ic_menu_wallpaper, "Choose a photo") { pendingTarget = null; pickPhoto() })
        col.addView(row(R.drawable.ic_menu_wallpaper, "Wallpaper & style") { openSystemApp() })
        col.addView(row(R.drawable.ic_menu_apps, "Live wallpapers") { openLive() })
        col.addView(row(R.drawable.ic_menu_home, "Use default wallpaper") {
            io.execute { runCatching { wm.clear(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) } }
            activity.runOnUiThread { Toast.makeText(activity, "Default wallpaper restored", Toast.LENGTH_SHORT).show(); showOverview() }
        })
        swap(ScrollView(activity).apply { isFillViewport = true; addView(col) })
    }

    private fun thumbView() = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = round(pal.container, 20)
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    private fun thumbColumn(img: ImageView, caption: TextView, w: Int, h: Int) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(img, LinearLayout.LayoutParams(w, h))
        addView(caption.apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(8), 0, 0) }, LinearLayout.LayoutParams(w, -2))
    }

    /** Reads the current wallpapers off the main thread; [done] learns whether the lock screen has a wallpaper of its own. A lock screen without one shows the home wallpaper. */
    private fun loadThumbs(home: ImageView, lock: ImageView, w: Int = 0, h: Int = 0, done: (lockOwn: Boolean) -> Unit) {
        val tw = home.layoutParams?.width ?: w
        val th = home.layoutParams?.height ?: h
        io.execute {
            val homeBmp = if (wm.wallpaperInfo == null) thumb(WallpaperManager.FLAG_SYSTEM, tw, th) else null
            val lockBmp = thumb(WallpaperManager.FLAG_LOCK, tw, th)
            activity.runOnUiThread {
                if (layer == null) return@runOnUiThread
                home.setImageBitmap(homeBmp)
                lock.setImageBitmap(lockBmp ?: homeBmp)
                done(lockBmp != null)
            }
        }
    }

    private fun thumb(which: Int, w: Int, h: Int): Bitmap? = runCatching {
        wm.getWallpaperFile(which)?.use { pfd ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, bounds)
            if (bounds.outWidth <= 0) return@use null
            var sample = 1
            while (bounds.outHeight / (sample * 2) >= h && bounds.outWidth / (sample * 2) >= w) sample *= 2
            BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.onFailure { Log.w(TAG, "cannot read wallpaper $which: $it") }.getOrNull()

    // ---------------------------------------------------------------- crop

    /** How many screens wide the home wallpaper is when it scrolls: a little more for each extra page, never more than 1.75. */
    private fun homeWidthFactor() = min(1f + 0.25f * (pageCount() - 1), 1.75f)

    private fun showCrop(bmp: Bitmap) {
        cropping = true
        val (top, bottom) = insets()
        var target = pendingTarget ?: Target.BOTH
        pendingTarget = null
        var scroll = pageCount() > 1
        val screenRatio = screenW.toFloat() / screenH

        val crop = CropView(activity, bmp, reserveTop = top + dp(72), reserveBottom = bottom + dp(210))
        val chips = ArrayList<Pair<Target, TextView>>()
        val scrollSwitch = Switch(activity).apply {
            text = "Slide with home pages"
            setTextColor(pal.onSurface)
            textSize = 15f
            isChecked = scroll
        }
        val hint = label("", 12f, pal.onVariant)

        fun refresh() {
            val wide = scroll && target != Target.LOCK && pageCount() > 1
            crop.ratio = if (wide) screenRatio * homeWidthFactor() else screenRatio
            crop.lockWindowRatio = if (wide && target == Target.BOTH) screenRatio else null
            scrollSwitch.visibility = if (pageCount() > 1 && target != Target.LOCK) View.VISIBLE else View.GONE
            hint.text = when {
                crop.lockWindowRatio != null -> "The dashed outline is what the lock screen shows."
                wide -> "Wider than the screen: the wallpaper slides as you swipe between home pages."
                else -> "Pinch to zoom, drag to move."
            }
            chips.forEach { (t, v) ->
                v.background = round(if (t == target) pal.primaryContainer else pal.container, 20)
                v.setTextColor(if (t == target) pal.onPrimaryContainer else pal.onSurface)
            }
        }
        scrollSwitch.setOnCheckedChangeListener { _, on -> scroll = on; refresh() }

        val chipRow = LinearLayout(activity).apply {
            for ((t, name) in listOf(Target.HOME to "Home", Target.LOCK to "Lock", Target.BOTH to "Both")) {
                val v = label(name, 15f, pal.onSurface).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(10), 0, dp(10))
                    setOnClickListener { target = t; refresh() }
                }
                chips += t to v
                addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (t != Target.HOME) marginStart = dp(8) })
            }
        }
        val cancel = label("Cancel", 16f, pal.onSurface).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setOnClickListener { showOverview() }
        }
        val set = label("Set wallpaper", 16f, pal.onPrimary, bold = true).apply {
            gravity = Gravity.CENTER
            background = round(pal.primary, 24)
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setOnClickListener {
                isEnabled = false
                text = "Setting…"
                val region = crop.regionInBitmap()
                val ratio = crop.ratio
                val t = target
                val wide = scroll && t != Target.LOCK && pageCount() > 1
                io.execute {
                    val msg = runCatching { writeWallpaper(bmp, region, ratio, t, wide); "Wallpaper updated" }
                        .getOrElse { Log.w(TAG, "wallpaper failed: $it"); "Couldn't set that photo as the wallpaper" }
                    activity.runOnUiThread { Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show(); if (layer != null) showOverview() }
                }
            }
        }
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = round(pal.card, 28)
            setPadding(dp(20), dp(16), dp(20), bottom + dp(12))
            addView(chipRow)
            addView(scrollSwitch, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(LinearLayout(activity).apply {
                gravity = Gravity.END
                addView(cancel)
                addView(set)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        val title = label("Crop wallpaper", 20f, pal.onSurface, bold = true).apply { setPadding(dp(20), top + dp(16), dp(20), 0) }
        refresh()
        swap(FrameLayout(activity).apply {
            addView(crop, FrameLayout.LayoutParams(-1, -1))
            addView(title, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        })
    }

    /** Cuts the chosen region out of the photo and hands the result to the system, home and lock each getting their own shape. */
    private fun writeWallpaper(src: Bitmap, region: RectF, frameRatio: Float, target: Target, wide: Boolean) {
        val homeW = if (wide) (screenH * frameRatio).roundToInt() else screenW
        fun cut(r: RectF, outW: Int, outH: Int): Bitmap {
            val x = r.left.roundToInt().coerceIn(0, src.width - 1)
            val y = r.top.roundToInt().coerceIn(0, src.height - 1)
            val w = r.width().roundToInt().coerceIn(1, src.width - x)
            val h = r.height().roundToInt().coerceIn(1, src.height - y)
            return Bitmap.createScaledBitmap(Bitmap.createBitmap(src, x, y, w, h), outW, outH, true)
        }
        // the part of the frame the lock screen shows when the frame is wider than the screen
        val lockRegion = RectF(region).apply {
            val w = height() * screenW / screenH
            val cx = centerX()
            left = cx - w / 2; right = cx + w / 2
        }
        when (target) {
            Target.HOME -> { wm.suggestDesiredDimensions(homeW, screenH); wm.setBitmap(cut(region, homeW, screenH), null, true, WallpaperManager.FLAG_SYSTEM) }
            Target.LOCK -> wm.setBitmap(cut(region, screenW, screenH), null, true, WallpaperManager.FLAG_LOCK)
            Target.BOTH ->
                if (!wide) { wm.suggestDesiredDimensions(screenW, screenH); wm.setBitmap(cut(region, screenW, screenH), null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) }
                else {
                    wm.suggestDesiredDimensions(homeW, screenH)
                    wm.setBitmap(cut(region, homeW, screenH), null, true, WallpaperManager.FLAG_SYSTEM)
                    wm.setBitmap(cut(lockRegion, screenW, screenH), null, true, WallpaperManager.FLAG_LOCK)
                }
        }
    }

    /** Makes the lock screen independent by giving it a copy of what the home wallpaper shows (its middle, in the screen's shape). False if home has no image, as with a live wallpaper. */
    private fun copyHomeToLock(): Boolean {
        val pfd = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM) ?: return false
        val bmp = pfd.use { f ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFileDescriptor(f.fileDescriptor, null, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2
            BitmapFactory.decodeFileDescriptor(f.fileDescriptor, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false
        val ratio = screenW.toFloat() / screenH
        val w = min(bmp.width, (bmp.height * ratio).roundToInt())
        val h = min(bmp.height, (bmp.width / ratio).roundToInt())
        val cut = Bitmap.createBitmap(bmp, (bmp.width - w) / 2, (bmp.height - h) / 2, w, h)
        wm.setBitmap(Bitmap.createScaledBitmap(cut, screenW, screenH, true), null, true, WallpaperManager.FLAG_LOCK)
        return true
    }

    /** Decodes the photo at a size that is plenty for the screen, upright according to its EXIF orientation. */
    private fun decode(uri: Uri): Bitmap {
        val resolver = activity.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2
        val raw = resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }!!
        val orientation = runCatching { resolver.openInputStream(uri)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) { ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f }
        if (degrees == 0f) return raw
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees) }, true)
    }

    // ---------------------------------------------------------------- small helpers

    private fun swap(v: View) {
        content?.removeAllViews()
        content?.addView(v, FrameLayout.LayoutParams(-1, -1))
    }

    private fun label(text: String, sp: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun round(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }

    private fun row(icon: Int, text: String, onClick: () -> Unit) = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = round(pal.container, 24)
        setPadding(dp(18), dp(14), dp(18), dp(14))
        addView(ImageView(activity).apply { setImageResource(icon); setColorFilter(pal.onSurface) }, LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(label(text, 16f, pal.onSurface).apply { setPadding(dp(16), 0, 0, 0) })
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }

    private companion object { const val TAG = "nanosearch" }
}

/** The photo under a frame: drag to move, pinch to zoom, and the photo always covers the frame. */
private class CropView(context: Context, private val bmp: Bitmap, private val reserveTop: Int, private val reserveBottom: Int) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val m = Matrix()
    private val frame = RectF()
    private var placed = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeWidth = 2 * density }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeWidth = 2 * density
        pathEffect = DashPathEffect(floatArrayOf(10 * density, 8 * density), 0f)
    }

    /** Width over height of the frame. */
    var ratio = 0.45f
        set(v) { field = v; if (width > 0) { layoutFrame(); fit(); invalidate() } }
    /** If set, a dashed window of this shape is drawn in the middle of the frame. */
    var lockWindowRatio: Float? = null
        set(v) { field = v; invalidate() }

    private var lastX = 0f
    private var lastY = 0f
    private var lastCount = 0
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val s = currentScale()
            val ns = (s * d.scaleFactor).coerceIn(minScale(), minScale() * 6f)
            m.postScale(ns / s, ns / s, d.focusX, d.focusY)
            clamp(); invalidate()
            return true
        }
    })

    private fun currentScale(): Float { val v = FloatArray(9); m.getValues(v); return v[Matrix.MSCALE_X] }
    private fun minScale() = max(frame.width() / bmp.width, frame.height() / bmp.height)

    private fun layoutFrame() {
        val margin = 20 * density
        val availW = width - 2 * margin
        val availH = height - reserveTop - reserveBottom - 8 * density
        var w = availW
        var h = w / ratio
        if (h > availH) { h = availH; w = h * ratio }
        val cx = width / 2f
        val cy = reserveTop + (height - reserveTop - reserveBottom) / 2f
        frame.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    /** Keeps the photo where it is but big enough to cover the frame; the first time, centred. */
    private fun fit() {
        val min = minScale()
        if (!placed) {
            m.setScale(min, min)
            m.postTranslate(frame.centerX() - bmp.width * min / 2, frame.centerY() - bmp.height * min / 2)
            placed = true
        } else if (currentScale() < min) {
            m.postScale(min / currentScale(), min / currentScale(), frame.centerX(), frame.centerY())
        }
        clamp()
    }

    private fun clamp() {
        val r = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        m.mapRect(r)
        var dx = 0f
        var dy = 0f
        if (r.left > frame.left) dx = frame.left - r.left else if (r.right < frame.right) dx = frame.right - r.right
        if (r.top > frame.top) dy = frame.top - r.top else if (r.bottom < frame.bottom) dy = frame.bottom - r.bottom
        m.postTranslate(dx, dy)
    }

    /** The part of the photo inside the frame, in the photo's own pixels. */
    fun regionInBitmap(): RectF {
        val inv = Matrix()
        m.invert(inv)
        return RectF(frame).also { inv.mapRect(it) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { layoutFrame(); fit() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress && e.pointerCount == 1) {
            if (lastCount == 1 && e.actionMasked == MotionEvent.ACTION_MOVE) {
                m.postTranslate(e.x - lastX, e.y - lastY)
                clamp(); invalidate()
            }
            lastX = e.x; lastY = e.y
        }
        lastCount = if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) 0 else e.pointerCount
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    override fun onDraw(c: Canvas) {
        c.drawBitmap(bmp, m, paint)
        c.save()
        c.clipOutRect(frame)
        c.drawColor(0xB3000000.toInt())
        c.restore()
        c.drawRoundRect(frame, 6 * density, 6 * density, border)
        lockWindowRatio?.let { r ->
            val w = frame.height() * r
            val l = frame.centerX() - w / 2
            c.drawRect(l, frame.top, l + w, frame.bottom, dashed)
        }
    }
}
