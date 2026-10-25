package ai.nanosearch.launcher.clocks

import ai.nanosearch.launcher.LockClock
import ai.nanosearch.launcher.SpiritWallpapers
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File
import kotlin.math.min

/** The selected built-in face over the existing wallpaper backgrounds, only while visible. */
class ClockWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = ClockEngine()

    private inner class ClockEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val frame = Runnable { drawFrame() }
        private val prefs = getSharedPreferences("nano", MODE_PRIVATE)
        private val motion by lazy { ClockMotionPolicy(this@ClockWallpaperService) { requestFrame() } }
        private val dialBounds = RectF()
        private val power = getSystemService(PowerManager::class.java)
        private var faceRenderer: ClockFaceRenderer? = null
        private var w = 0
        private var h = 0
        private var visible = false
        private var screenActive = true
        private var destroyed = false
        private var receivingTime = false
        private var observingPreferences = false
        private var bg: Bitmap? = null
        private var bgKey = ""
        private val preferenceChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in setOf("lockclock_face", "lockclock_bg", "lockclock_pos", "clocks_reduced_motion")) requestFrame()
        }
        private val clockChanged = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> { screenActive = false; handler.removeCallbacks(frame) }
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> screenActive = true
                }
                faceRenderer?.onTimeChanged()
                requestFrame()
            }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            prefs.registerOnSharedPreferenceChangeListener(preferenceChanged)
            observingPreferences = true
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            w = width; h = height
            bg?.recycle(); bg = null
            requestFrame()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            requestFrame()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible && !destroyed
            handler.removeCallbacks(frame)
            if (this.visible) {
                screenActive = power?.isInteractive != false
                motion.attach()
                motion.refresh()
                if (!receivingTime) {
                    registerReceiver(clockChanged, IntentFilter().apply {
                        addAction(Intent.ACTION_TIME_CHANGED)
                        addAction(Intent.ACTION_TIMEZONE_CHANGED)
                        addAction(Intent.ACTION_DATE_CHANGED)
                        addAction(Intent.ACTION_LOCALE_CHANGED)
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_SCREEN_OFF)
                        addAction(Intent.ACTION_USER_PRESENT)
                    }, Context.RECEIVER_NOT_EXPORTED)
                    receivingTime = true
                }
                faceRenderer?.onTimeChanged()
                requestFrame()
            } else stopObservers()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(frame)
            stopObservers()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            destroyed = true; visible = false
            handler.removeCallbacks(frame)
            stopObservers()
            if (observingPreferences) { prefs.unregisterOnSharedPreferenceChangeListener(preferenceChanged); observingPreferences = false }
            faceRenderer?.release(); faceRenderer = null
            bg?.recycle(); bg = null
            super.onDestroy()
        }

        private fun stopObservers() {
            motion.detach()
            if (receivingTime) { unregisterReceiver(clockChanged); receivingTime = false }
        }

        private fun requestFrame() {
            if (!visible || !screenActive || destroyed) return
            handler.removeCallbacks(frame)
            handler.post(frame)
        }

        private fun drawFrame() {
            handler.removeCallbacks(frame)
            if (!visible || !screenActive || destroyed || w == 0 || h == 0 || !surfaceHolder.surface.isValid) return
            val canvas = try { surfaceHolder.lockCanvas() } catch (_: RuntimeException) { null } ?: return
            val now = System.currentTimeMillis()
            try { render(canvas, now) } finally {
                try { surfaceHolder.unlockCanvasAndPost(canvas) } catch (_: RuntimeException) { /* Surface was withdrawn. */ }
            }
            // Preserve the existing wallpaper's30fps ceiling; reduced motion steps once a second.
            if (visible && screenActive && !destroyed) handler.postDelayed(frame, if (motion.smooth) 33L else motion.frameDelayMillis(now))
        }

        private fun render(c: Canvas, now: Long) {
            val id = prefs.getString("lockclock_bg", "carbon") ?: "carbon"
            val photo = File(filesDir, "lockclock_photo.jpg")
            val stamp = if (id == "photo" && photo.exists()) photo.lastModified() else 0L
            val key = "$id|$w|$h|$stamp"
            if (bg == null || key != bgKey) {
                bg?.recycle()
                bg = when {
                    id == "black" -> null
                    id == "photo" && photo.exists() -> runCatching { coverPhoto(photo, w, h) }.getOrNull()
                    else -> runCatching { SpiritWallpapers.render(if (id == "photo") "carbon" else id, w, h) }.getOrNull()
                }
                bgKey = key
            }
            if (bg != null) c.drawBitmap(bg!!, 0f, 0f, null) else c.drawColor(0xFF000000.toInt())
            if (id == "photo") c.drawColor(0x40000000) // Preserve the existing readability veil.

            val faceId = LockClock.face(this@ClockWallpaperService)
            if (faceRenderer?.id != faceId) {
                faceRenderer?.release()
                faceRenderer = ClockFaces.renderer(this@ClockWallpaperService, faceId)
            }
            val cy = h * when (prefs.getString("lockclock_pos", "middle")) { "top" -> 0.28f; "low" -> 0.72f; else -> 0.5f }
            val side = (min(w, h) * 0.82f).toInt()
            dialBounds.set((w - side) / 2f, cy - side / 2f, (w + side) / 2f, cy + side / 2f)
            // This is the same complete renderer used by the home View, including hand order.
            faceRenderer?.draw(c, dialBounds, now, motion.smooth)
        }

        /** The photo scaled to fill the screen and cropped to its middle, as before. */
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
