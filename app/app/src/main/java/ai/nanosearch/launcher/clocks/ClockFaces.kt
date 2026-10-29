package ai.nanosearch.launcher.clocks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import kotlin.math.min

/** One rendering contract is used by both home widgets and live wallpaper engines. */
interface ClockFaceRenderer {
    val id: String
    fun draw(canvas: Canvas, bounds: RectF, epochMillis: Long, motion: Boolean)
    fun onBatteryChanged(percent: Int?) = Unit
    fun onTimeChanged()
    fun release()
}

data class ClockFaceDefinition(
    val id: String,
    val label: String,
    val defaultHeightDp: Int,
    val credit: String? = null,
)

object ClockFaces {
    const val STEEL_ID = "steeldial"
    const val CARBON_ID = "carbon01"
    private val builtIns: List<ClockFaceDefinition> = listOf(
        ClockFaceDefinition(STEEL_ID, "Steel Dial", 300),
        ClockFaceDefinition(CARBON_ID, "Carbon 01", 300, "Inspired by RM 65-01 McLaren W1"),
    )
    val all: List<ClockFaceDefinition> get() = builtIns + ClockFaceRegistry.definitions()
    private val packId = Regex("pack-(import|curated)-[a-f0-9]{32}")
    fun isPackId(id: String?): Boolean = id != null && packId.matches(id)
    fun normalizeSelection(id: String?): String = id?.takeIf { it == STEEL_ID || it == CARBON_ID || isPackId(it) } ?: STEEL_ID
    fun labelFor(id: String?): String = all.firstOrNull { it.id == id }?.label
        ?: if (isPackId(id)) "Unavailable face — Steel Dial" else builtIns.first().label

    fun find(id: String?): ClockFaceDefinition = all.firstOrNull { it.id == id } ?: builtIns.first()
    fun renderer(context: Context, id: String?): ClockFaceRenderer {
        ClockFaceRegistry.ensure(context)
        if (isPackId(id)) ClockFaceRegistry.installed(context, requireNotNull(id))?.let { return DataClockFaceRenderer(context.applicationContext, it) }
        return when (id) {
            CARBON_ID -> CarbonClockFaceRenderer().apply { onBatteryChanged(PhoneBattery.read(context)) }
            else -> SteelClockFaceRenderer(context)
        }
    }

    fun preview(context: Context, id: String?, size: Int = 360, epochMillis: Long = System.currentTimeMillis()): Bitmap {
        val side = size.coerceIn(32, 1536)
        val renderer = renderer(context, id)
        return try {
            if (renderer is DataClockFaceRenderer) renderer.awaitAssets()
            Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also {
                renderer.draw(Canvas(it), RectF(0f, 0f, side.toFloat(), side.toFloat()), epochMillis, false)
            }
        } finally { renderer.release() }
    }

    fun renderEpochMillis(epochMillis: Long, motion: Boolean): Long =
        if (motion) epochMillis else Math.floorDiv(epochMillis, 1000L) * 1000L
}

class CarbonClockFaceRenderer : ClockFaceRenderer {
    override val id = ClockFaces.CARBON_ID
    private val art = CarbonDialArt()
    private var battery: BatteryBand? = null
    override fun onBatteryChanged(percent: Int?) { battery = PhoneBattery.band(percent) }
    override fun draw(canvas: Canvas, bounds: RectF, epochMillis: Long, motion: Boolean) {
        art.draw(canvas, bounds, ClockFaces.renderEpochMillis(epochMillis, motion), motion = motion, batteryBand = battery)
    }
    fun drawChronograph(canvas: Canvas, bounds: RectF, epochMillis: Long, motion: Boolean, elapsedMillis: Long, running: Boolean, lumeAlpha: Float = 0f) {
        art.draw(canvas, bounds, ClockFaces.renderEpochMillis(epochMillis, motion), elapsedMillis, running, motion, lumeAlpha = lumeAlpha, batteryBand = battery)
    }
    override fun onTimeChanged() = art.onTimeChanged()
    override fun release() = art.release()
}

/** Cache the static face and whole-second time values; paint hands directly for exact compositing. */
class SteelClockFaceRenderer(context: Context) : ClockFaceRenderer {
    override val id = ClockFaces.STEEL_ID
    private val art = DialArt(context)
    private var zone = java.util.TimeZone.getDefault()
    private var face: Bitmap? = null
    private var cachedHour = 0
    private var cachedMinute = 0
    private var cachedSecond = 0f
    private var stillAt = Long.MIN_VALUE
    private var cachedSide = 0

    override fun draw(canvas: Canvas, bounds: RectF, epochMillis: Long, motion: Boolean) {
        val side = min(bounds.width(), bounds.height())
        if (side <= 0) return
        val rasterSide = side.toInt().coerceIn(32, 1536)
        if (cachedSide != rasterSide || face == null) {
            release()
            cachedSide = rasterSide
            face = Bitmap.createBitmap(rasterSide, rasterSide, Bitmap.Config.ARGB_8888).also { art.drawFace(Canvas(it), rasterSide) }
        }
        val now = ClockFaces.renderEpochMillis(epochMillis, motion)
        val angles = CarbonTimeModel.angles(now, zone)
        val second = angles.second / 6f
        val wholeSecond = Math.floorDiv(now, 1000L)
        if (stillAt != wholeSecond) {
            val cachedAngles = CarbonTimeModel.angles(wholeSecond * 1000L, zone)
            cachedHour = (cachedAngles.hour / 30f).toInt()
            cachedMinute = (cachedAngles.minute / 6f).toInt()
            cachedSecond = cachedAngles.second / 6f
            stillAt = wholeSecond
        }
        val save = canvas.save()
        canvas.translate(bounds.centerX() - side / 2f, bounds.centerY() - side / 2f)
        canvas.scale(side / rasterSide, side / rasterSide)
        canvas.drawBitmap(requireNotNull(face), 0f, 0f, null)
        art.drawSeconds(canvas, rasterSide, second)
        // An intermediate transparent bitmap changes antialiased edge compositing by a pixel.
        // Direct vectors preserve the original DialArt.picture output and avoid a second raster.
        art.drawHourMinute(canvas, rasterSide, cachedHour, cachedMinute, cachedSecond)
        art.drawPivot(canvas, rasterSide)
        canvas.restoreToCount(save)
    }

    override fun onTimeChanged() { zone = java.util.TimeZone.getDefault(); stillAt = Long.MIN_VALUE }
    override fun release() {
        face?.recycle(); face = null
        cachedSide = 0; stillAt = Long.MIN_VALUE
    }
}
