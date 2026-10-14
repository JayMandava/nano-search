package ai.nanosearch.launcher

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.media.ExifInterface
import java.io.File

/**
 * The clock live wallpaper is optional: the launcher only offers it when the build includes it (it lives in the `clocks` package,
 * next to the clock it draws). These are the choices it reads from the launcher's preferences, a way to bring in a photo of your own as its background,
 * and the way to open Android's own live wallpaper preview for it.
 */
object LockClock {
    private const val SERVICE = "ai.nanosearch.launcher.clocks.ClockWallpaperService"

    private val PRESETS = SpiritWallpapers.ALL.map { it.id to it.name }
    val POSITIONS = listOf("top" to "Top", "middle" to "Middle", "low" to "Low")

    private fun prefs(c: Context) = c.getSharedPreferences("nano", Context.MODE_PRIVATE)
    fun component(c: Context) = ComponentName(c.packageName, SERVICE)
    fun available(c: Context) = runCatching { c.packageManager.getServiceInfo(component(c), 0) }.isSuccess

    /** Where the photo you chose is kept (inside the app; nothing is shared). */
    fun photoFile(c: Context) = File(c.filesDir, "lockclock_photo.jpg")
    fun hasPhoto(c: Context) = photoFile(c).exists()

    /** The wallpapers it can draw behind the clock: the drawn ones, your own photo once you have chosen one, and plain black. */
    fun backgrounds(c: Context): List<Pair<String, String>> = PRESETS + (if (hasPhoto(c)) listOf("photo" to "Your photo") else emptyList()) + ("black" to "Black")

    fun background(c: Context) = prefs(c).getString("lockclock_bg", "carbon")?.takeIf { k -> backgrounds(c).any { it.first == k } } ?: "carbon"
    fun position(c: Context) = prefs(c).getString("lockclock_pos", "middle") ?: "middle"

    private fun next(options: List<Pair<String, String>>, current: String) = options[(options.indexOfFirst { it.first == current } + 1) % options.size].first
    fun cycleBackground(c: Context) = prefs(c).edit().putString("lockclock_bg", next(backgrounds(c), background(c))).apply()
    fun cyclePosition(c: Context) = prefs(c).edit().putString("lockclock_pos", next(POSITIONS, position(c))).apply()

    fun nameOf(options: List<Pair<String, String>>, key: String) = options.firstOrNull { it.first == key }?.second ?: key

    /**
     * Keeps the picked picture as the clock's background: turned upright by its orientation, shrunk to at most 2400 px on the long side
     * and saved as a JPEG in the app's own storage. Run off the main thread. Returns false if the picture could not be read.
     */
    fun setPhoto(c: Context, uri: Uri): Boolean = runCatching {
        val resolver = c.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2400) sample *= 2
        var bmp = resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return false
        val turn = runCatching {
            resolver.openInputStream(uri)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (turn) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        }
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val tmp = File(c.filesDir, "lockclock_photo.tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        tmp.renameTo(photoFile(c))
        prefs(c).edit().putString("lockclock_bg", "photo").apply()
        true
    }.getOrDefault(false)

    /** Android's preview for the wallpaper, with its own choice of home screen, lock screen or both. */
    fun openPicker(context: Context) {
        context.startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component(context)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
