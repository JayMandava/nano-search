package ai.nanosearch.launcher.clocks

import ai.nanosearch.launcher.SpiritWallpapers
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import java.io.File
import android.view.SurfaceHolder
import java.util.Calendar
import kotlin.math.min

/**
 * The clock as a live wallpaper, so it can sit behind the lock screen (and the home screen too). It draws a background (one of the drawn wallpapers, a photo you chose, or plain black),
 * then the Steel Dial, and only while the screen is on and showing it. What it draws is chosen in the launcher's wallpaper screen.
 */
class ClockWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = ClockEngine()

    private inner class ClockEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val frame = Runnable { drawFrame() }
        private val prefs = getSharedPreferences("nano", MODE_PRIVATE)
        private val dial by lazy { DialArt(this@ClockWallpaperService) }
        private var w = 0
        private var h = 0
        private var visible = false
        private var bg: Bitmap? = null
        private var bgKey = ""
        private var photoStamp = 0L
        private var dialFace: Bitmap? = null

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            w = width; h = height; bg = null; dialFace = null
            drawFrame()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            handler.removeCallbacks(frame)
            if (visible) drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(frame)
            super.onSurfaceDestroyed(holder)
        }

        private fun drawFrame() {
            handler.removeCallbacks(frame)
            if (w == 0 || h == 0) return
            val canvas = try { surfaceHolder.lockCanvas() } catch (e: Exception) { null } ?: return
            try { render(canvas) } finally { runCatching { surfaceHolder.unlockCanvasAndPost(canvas) } }
            // The sweeping seconds hand needs frames.
            if (visible) handler.postDelayed(frame, 33L)
        }

        private fun render(c: Canvas) {
            val id = prefs.getString("lockclock_bg", "carbon") ?: "carbon"
            val photo = File(filesDir, "lockclock_photo.jpg")
            val stamp = if (id == "photo" && photo.exists()) photo.lastModified() else 0L
            val key = "$id|$w|$h|$stamp"
            if (bg == null || key != bgKey) {
                bg = when {
                    id == "black" -> null
                    id == "photo" && photo.exists() -> runCatching { coverPhoto(photo, w, h) }.getOrNull()
                    else -> runCatching { SpiritWallpapers.render(if (id == "photo") "carbon" else id, w, h) }.getOrNull()
                }
                bgKey = key
            }
            if (bg != null) c.drawBitmap(bg!!, 0f, 0f, null) else c.drawColor(0xFF000000.toInt())
            if (id == "photo") c.drawColor(0x40000000) // a light veil, so the dial stays readable on any picture

            val cy = h * when (prefs.getString("lockclock_pos", "middle")) { "top" -> 0.28f; "low" -> 0.72f; else -> 0.5f }
            val cal = Calendar.getInstance()
            val side = (min(w, h) * 0.82f).toInt()
            if (dialFace == null || dialFace!!.width != side) dialFace = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { dial.drawFace(Canvas(it), side) }
            c.save(); c.translate((w - side) / 2f, cy - side / 2f)
            c.drawBitmap(dialFace!!, 0f, 0f, null)
            dial.drawHands(c, side, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000f)
            c.restore()
        }

        /** The photo scaled to fill the screen and cropped to its middle, as a wallpaper is. */
        private fun coverPhoto(file: File, w: Int, h: Int): Bitmap? {
            val src = BitmapFactory.decodeFile(file.path) ?: return null
            val scale = maxOf(w / src.width.toFloat(), h / src.height.toFloat())
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val m = Matrix().apply { postScale(scale, scale); postTranslate((w - src.width * scale) / 2f, (h - src.height * scale) / 2f) }
            c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
            src.recycle()
            return out
        }
    }
}
