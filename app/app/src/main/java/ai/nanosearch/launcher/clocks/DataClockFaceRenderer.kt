package ai.nanosearch.launcher.clocks

import ai.nanosearch.launcher.clocks.packs.*
import android.content.Context
import android.graphics.*
import android.os.Looper
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.*

/** Fixed native analog-v1 vocabulary. No pack-defined code, fonts, paths, timing or networking. */
class DataClockFaceRenderer private constructor(
    private val context: Context?, private val pack: InstalledFacePack,
    private val validationImages: Map<String, Bitmap>?,
) : ClockFaceRenderer {
    constructor(context: Context, pack: InstalledFacePack) : this(context, pack, null)
    override val id = pack.id
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var assets: PackAssetPool.Handle? = null
    private var face: Bitmap? = null
    private var faceSide = 0
    private var zone = TimeZone.getDefault()
    private var calendar = Calendar.getInstance(zone)
    private var dateSecond = Long.MIN_VALUE
    private var dateText = ""
    private val path = Path()
    private val spec get() = pack.spec

    private fun handle(): PackAssetPool.Handle? {
        assets?.let { current -> if (PackAssetPool.shouldRetry(current)) { current.close(); assets = null } }
        return assets ?: PackAssetPool.acquire(requireNotNull(context), pack)?.also { assets = it }
    }

    /** Detail/grid callers run previews on their worker; ordinary View draw never waits. */
    fun awaitAssets() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val current = handle() ?: throw FacePackResourceException("Face is no longer available")
            if (!current.ready.await(15, TimeUnit.SECONDS) || current.error != null) throw FacePackResourceException("Face preview is unavailable")
        }
    }

    override fun draw(canvas: Canvas, bounds: RectF, epochMillis: Long, motion: Boolean) {
        val side = min(bounds.width(), bounds.height())
        if (side <= 0) return
        val images = validationImages ?: run {
            val loaded = handle()
            if (loaded == null || loaded.ready.count != 0L || loaded.error != null) {
                paint.color = 0xff202329.toInt(); paint.style = Paint.Style.FILL
                canvas.drawCircle(bounds.centerX(), bounds.centerY(), side * .47f, paint)
                paint.color = Color.WHITE; paint.typeface = Typeface.DEFAULT; paint.textAlign = Paint.Align.CENTER
                paint.textSize = side * .055f
                canvas.drawText(if (loaded?.error != null) "Face unavailable" else "Loading face…", bounds.centerX(), bounds.centerY(), paint)
                return
            }
            loaded.images
        }
        val raster = side.toInt().coerceIn(32, 1024)
        if (face == null || faceSide != raster) {
            face?.recycle()
            faceSide = raster
            face = Bitmap.createBitmap(raster, raster, Bitmap.Config.ARGB_8888).also { drawStatic(Canvas(it), raster.toFloat(), images) }
        }
        val save = canvas.save()
        canvas.translate(bounds.centerX() - side / 2f, bounds.centerY() - side / 2f)
        canvas.scale(side / raster, side / raster)
        val s = raster.toFloat()
        canvas.drawBitmap(requireNotNull(face), 0f, 0f, null)
        path.reset(); path.addCircle(s / 2f, s / 2f, .48f * s, Path.Direction.CW); canvas.clipPath(path)
        val now = ClockFaces.renderEpochMillis(epochMillis, motion)
        drawDate(canvas, s, now)
        val angles = CarbonTimeModel.angles(now, zone)
        spec.hands.second?.let { drawHand(canvas, s, it, angles.second) }
        drawHand(canvas, s, spec.hands.hour, angles.hour)
        drawHand(canvas, s, spec.hands.minute, angles.minute)
        paint.style = Paint.Style.FILL; paint.color = spec.hands.minute.color
        canvas.drawCircle(s / 2f, s / 2f, .014f * s, paint)
        canvas.restoreToCount(save)
    }

    private fun drawStatic(c: Canvas, s: Float, images: Map<String, Bitmap>) {
        paint.style = Paint.Style.FILL; paint.color = spec.dial.backgroundColor
        c.drawCircle(s / 2, s / 2, .48f * s, paint)
        val save = c.save()
        path.reset(); path.addCircle(s / 2, s / 2, .48f * s, Path.Direction.CW); c.clipPath(path)
        spec.dial.textureAsset?.let { key -> images[key]?.let { image ->
            paint.color = Color.WHITE; paint.isFilterBitmap = true
            c.drawBitmap(image, null, RectF(.02f * s, .02f * s, .98f * s, .98f * s), paint)
        } }
        val ticks = when (spec.dial.ticks) { FacePackTicks.NONE -> 0; FacePackTicks.HOUR -> 12; FacePackTicks.MINUTE -> 60 }
        paint.color = spec.dial.tickColor; paint.strokeCap = Paint.Cap.BUTT
        repeat(ticks) { i ->
            val hour = ticks == 12 || i % 5 == 0
            val theta = 2 * Math.PI * i / ticks - Math.PI / 2
            paint.strokeWidth = (if (hour) .006f else .0025f) * s
            val r0 = (if (hour) .406f else .433f) * s; val r1 = .455f * s
            c.drawLine(s / 2 + cos(theta).toFloat() * r0, s / 2 + sin(theta).toFloat() * r0,
                s / 2 + cos(theta).toFloat() * r1, s / 2 + sin(theta).toFloat() * r1, paint)
        }
        paint.color = spec.dial.numeralColor; paint.textSize = .07f * s; paint.textAlign = Paint.Align.CENTER
        paint.typeface = when (spec.dial.font) {
            FacePackFont.SYSTEM_SANS -> Typeface.create("sans-serif", Typeface.NORMAL)
            FacePackFont.SYSTEM_CONDENSED -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            FacePackFont.SYSTEM_MONO -> Typeface.MONOSPACE
        }
        for (hour in 1..12) {
            if (spec.dial.numerals == FacePackNumerals.NONE || (spec.dial.numerals == FacePackNumerals.QUARTERS && hour % 3 != 0)) continue
            val theta = Math.PI * hour / 6 - Math.PI / 2
            val x = s / 2 + cos(theta).toFloat() * .355f * s
            val y = s / 2 + sin(theta).toFloat() * .355f * s - (paint.ascent() + paint.descent()) / 2
            c.drawText(hour.toString(), x, y, paint)
        }
        c.restoreToCount(save)
    }

    private fun drawHand(c: Canvas, s: Float, hand: FacePackHand, angle: Float) {
        val saved = c.save(); c.rotate(angle, s / 2, s / 2)
        val x = s / 2; val y = s / 2; val w = hand.width * s / 2
        paint.style = Paint.Style.FILL; paint.color = hand.color
        if (hand.shape == FacePackHandShape.BAR) {
            c.drawRect(x - w, y - hand.length * s, x + w, y + hand.tail * s, paint)
        } else {
            path.reset(); path.moveTo(x, y - hand.length * s)
            path.lineTo(x + w, y); path.lineTo(x + w * .5f, y + hand.tail * s)
            path.lineTo(x - w * .5f, y + hand.tail * s); path.lineTo(x - w, y); path.close()
            c.drawPath(path, paint)
        }
        c.restoreToCount(saved)
    }

    private fun drawDate(c: Canvas, s: Float, now: Long) {
        val date = spec.dateWindow ?: return
        if (dateSecond != Math.floorDiv(now, 1000L)) {
            calendar.timeInMillis = now; dateText = calendar.get(Calendar.DAY_OF_MONTH).toString()
            dateSecond = Math.floorDiv(now, 1000L)
        }
        val box = if (date.position == FacePackDatePosition.RIGHT) RectF(.64f * s, .458f * s, .80f * s, .542f * s)
            else RectF(.42f * s, .66f * s, .58f * s, .744f * s)
        paint.color = date.backgroundColor; paint.style = Paint.Style.FILL; c.drawRect(box, paint)
        paint.color = date.textColor; paint.textSize = .065f * s; paint.typeface = Typeface.DEFAULT; paint.textAlign = Paint.Align.CENTER
        c.drawText(dateText, box.centerX(), box.centerY() - (paint.ascent() + paint.descent()) / 2f, paint)
    }

    override fun onTimeChanged() { zone = TimeZone.getDefault(); calendar = Calendar.getInstance(zone); dateSecond = Long.MIN_VALUE }
    override fun release() { face?.recycle(); face = null; faceSide = 0; assets?.close(); assets = null }

    companion object {
        /** Exercise the actual native template before the durable registry can expose a revision. */
        fun validate(pack: ValidatedFacePack, cancelled: () -> Boolean = { false }) {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Pack validation must run off the main thread" }
            val images = linkedMapOf<String, Bitmap>()
            var renderer: DataClockFaceRenderer? = null
            var target: Bitmap? = null
            try {
                var decoded = 0L
                for (asset in pack.spec.assets) {
                    checkCancelled(cancelled)
                    val bytes = File(pack.directory, asset.path).inputStream().use { it.readNBytes(FacePackLimits.MAX_PNG_BYTES + 1) }
                    checkPack(bytes.size <= FacePackLimits.MAX_PNG_BYTES, "Artwork exceeds its limit")
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    val needed = options.outWidth.toLong() * options.outHeight * 4
                    checkPack(options.outWidth in 1..1024 && options.outHeight in 1..1024 && needed > 0 && decoded + needed <= 8L * 1024 * 1024, "Artwork exceeds its allocation limit")
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                        ?: throw FacePackException("Artwork cannot be decoded")
                    if (bitmap.width != options.outWidth || bitmap.height != options.outHeight || bitmap.allocationByteCount > needed) {
                        bitmap.recycle(); throw FacePackException("Artwork allocation changed")
                    }
                    decoded += bitmap.allocationByteCount
                    images[asset.key] = bitmap
                }
                checkCancelled(cancelled)
                val candidate = InstalledFacePack("validation", FacePackSource.IMPORTED, pack.spec, pack.digest, pack.directory, pack.payloadBytes, pack.licenseText)
                renderer = DataClockFaceRenderer(null, candidate, images)
                target = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(target)
                renderer.draw(canvas, RectF(0f, 0f, 256f, 256f), 1_791_999_030_123L, true)
                renderer.draw(canvas, RectF(0f, 0f, 256f, 256f), 1_791_999_060_000L, false)
                checkCancelled(cancelled)
            } catch (failure: OutOfMemoryError) {
                throw FacePackResourceException("Not enough memory to validate artwork", failure)
            } finally {
                renderer?.release(); target?.recycle(); images.values.forEach { it.recycle() }
            }
        }
    }
}

/** Shared, bounded decode pool. Retained revision leases prevent deletion while an active renderer reads it. */
private object PackAssetPool {
    private const val MAX_SHARED_BYTES = 32L * 1024 * 1024
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "clock-pack-artwork").apply { isDaemon = true } }
    private val entries = mutableMapOf<String, Handle>()
    private var reserved = 0L

    class Handle(val key: String, val lease: FacePackLease) : AutoCloseable {
        val ready = CountDownLatch(1)
        @Volatile var images: Map<String, Bitmap> = emptyMap()
        @Volatile var error: String? = null
        var refs = 1
        var bytes = 0L
        var finished = false
        var retryable = false
        var retryAt = 0L
        override fun close() = release(this)
    }

    @Synchronized fun acquire(context: Context, pack: InstalledFacePack): Handle? {
        val key = "${pack.id}:${pack.digest}"
        entries[key]?.let { it.refs++; return it }
        val store = FacePackStore.get(context.applicationContext)
        val lease = store.acquire(pack.id) ?: return null
        if (lease.pack.digest != pack.digest) { lease.close(); return null }
        val item = Handle(key, lease); entries[key] = item
        worker.execute { load(store, item) }
        return item
    }

    private fun load(store: FacePackStore, h: Handle) {
        val images = linkedMapOf<String, Bitmap>()
        var corruption = false
        try {
            val pack = h.lease.pack
            for (asset in pack.spec.assets) {
                val file = File(pack.directory, asset.path)
                check(file.length() in 1..FacePackLimits.MAX_PNG_BYTES.toLong()) { "Artwork size changed" }
                val bytes = file.inputStream().use { it.readNBytes(FacePackLimits.MAX_PNG_BYTES + 1) }
                check(bytes.size <= FacePackLimits.MAX_PNG_BYTES) { "Artwork size changed" }
                check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == asset.sha256) { "Artwork checksum changed" }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val needed = bounds.outWidth.toLong() * bounds.outHeight * 4
                check(bounds.outWidth in 1..1024 && bounds.outHeight in 1..1024 && needed <= 4L * 1024 * 1024) { "Artwork bounds changed" }
                synchronized(this) {
                    if (reserved + needed > MAX_SHARED_BYTES) throw ArtworkMemoryLimit()
                    check(h.bytes + needed <= 8L * 1024 * 1024) { "Artwork memory limit" }
                    reserved += needed; h.bytes += needed
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                    ?: error("Artwork decode failed")
                if (bitmap.width != bounds.outWidth || bitmap.height != bounds.outHeight || bitmap.allocationByteCount > needed) {
                    bitmap.recycle(); error("Artwork allocation changed")
                }
                images[asset.key] = bitmap
            }
            h.images = images.toMap()
        } catch (_: ArtworkMemoryLimit) { h.error = "Artwork cache is full"; h.retryable = true }
        catch (_: OutOfMemoryError) { h.error = "Not enough memory for artwork"; h.retryable = true }
        catch (_: IOException) { h.error = "Artwork is temporarily unavailable"; h.retryable = true }
        catch (e: Exception) { h.error = "Artwork is unavailable"; corruption = true }
        finally {
            if (h.error != null) {
                images.values.forEach { it.recycle() }; h.images = emptyMap()
                synchronized(this) { reserved -= h.bytes; h.bytes = 0 }
            }
            synchronized(this) {
                h.retryAt = android.os.SystemClock.uptimeMillis() + 5_000L
                h.finished = true; if (h.refs == 0) dispose(h)
            }
            h.ready.countDown(); ClockFaceRegistry.changed()
        }
        if (corruption) store.quarantine(h.lease.pack.id, h.lease.pack.digest)
    }

    /** Only a visible renderer requests a bounded retry; valid data is never quarantined for pressure. */
    @Synchronized fun shouldRetry(h: Handle): Boolean {
        if (!h.finished || !h.retryable) return false
        if (entries[h.key] !== h) return true
        if (android.os.SystemClock.uptimeMillis() < h.retryAt) return false
        entries.remove(h.key, h)
        return true
    }

    @Synchronized private fun release(h: Handle) {
        if (h.refs <= 0) return
        h.refs--
        if (h.refs == 0 && h.finished) dispose(h)
    }

    private fun dispose(h: Handle) {
        entries.remove(h.key, h); h.images.values.forEach { it.recycle() }; h.images = emptyMap()
        reserved -= h.bytes; h.bytes = 0; h.lease.close()
    }
    private class ArtworkMemoryLimit : Exception()
}
