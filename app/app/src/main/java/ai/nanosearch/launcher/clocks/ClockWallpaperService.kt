package ai.nanosearch.launcher.clocks

import ai.nanosearch.launcher.ClassicWallpapers
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.text.format.DateFormat
import android.view.SurfaceHolder
import java.util.Calendar
import kotlin.math.min

/**
 * The clocks as a live wallpaper, so they can sit behind the lock screen (and the home screen too). It draws a background (one of the Classic wallpapers or plain black),
 * then the Steel Dial or the flip clock, and only while the screen is on and showing it. What it draws is chosen in the launcher's wallpaper screen.
 */
class ClockWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = ClockEngine()

    private inner class ClockEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val frame = Runnable { drawFrame() }
        private val prefs = getSharedPreferences("nano", MODE_PRIVATE)
        private val dial by lazy { DialArt(this@ClockWallpaperService) }
        private val flip by lazy { FlipFaces(this@ClockWallpaperService) }
        private val dateText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xF2FFFFFF.toInt(); textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setShadowLayer(8f, 0f, 2f, 0x99000000.toInt())
        }
        private var w = 0
        private var h = 0
        private var visible = false
        private var bg: Bitmap? = null
        private var bgKey = ""
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
            val steel = prefs.getString("lockclock_clock", "steel") != "flip"
            try { render(canvas, steel) } finally { runCatching { surfaceHolder.unlockCanvasAndPost(canvas) } }
            // The sweeping seconds hand needs frames; the flip clock only needs the next minute.
            if (visible) handler.postDelayed(frame, if (steel) 33L else 1000L - System.currentTimeMillis() % 1000L + 20L)
        }

        private fun render(c: Canvas, steel: Boolean) {
            val id = prefs.getString("lockclock_bg", "cobalt") ?: "cobalt"
            val key = "$id|$w|$h"
            if (bg == null || key != bgKey) {
                bg = if (id == "black") null else runCatching { ClassicWallpapers.render(id, w, h) }.getOrNull()
                bgKey = key
            }
            if (bg != null) c.drawBitmap(bg!!, 0f, 0f, null) else c.drawColor(0xFF000000.toInt())

            val cy = h * when (prefs.getString("lockclock_pos", "middle")) { "top" -> 0.28f; "low" -> 0.72f; else -> 0.5f }
            val cal = Calendar.getInstance()
            if (steel) {
                val side = (min(w, h) * 0.82f).toInt()
                if (dialFace == null || dialFace!!.width != side) dialFace = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { dial.drawFace(Canvas(it), side) }
                c.save(); c.translate((w - side) / 2f, cy - side / 2f)
                c.drawBitmap(dialFace!!, 0f, 0f, null)
                dial.drawHands(c, side, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000f)
                c.restore()
            } else {
                val gap = w * 0.02f
                val cardH = min(h * 0.2f, (w * 0.86f - gap) / 2f / 1.04f).toInt()
                val cardW = (cardH * 1.04f).toInt()
                val is24 = DateFormat.is24HourFormat(this@ClockWallpaperService)
                val hr = cal.get(Calendar.HOUR_OF_DAY)
                val hourText = if (is24) "%02d".format(hr) else (if (hr % 12 == 0) 12 else hr % 12).toString()
                val left = w / 2f - cardW - gap / 2f
                flip.drawRest(c, left, cy - cardH / 2f, cardW, cardH, Face(hourText, if (is24) null else if (hr < 12) "AM" else "PM"))
                flip.drawRest(c, left + cardW + gap, cy - cardH / 2f, cardW, cardH, Face("%02d".format(cal.get(Calendar.MINUTE))))
                dateText.textSize = w * 0.045f
                c.drawText(DateFormat.format("EEEE, d MMMM", cal).toString(), w / 2f, cy + cardH / 2f + w * 0.09f, dateText)
            }
        }
    }
}
