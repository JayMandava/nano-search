package ai.nanosearch.launcher.clocks

import android.graphics.Canvas
import android.graphics.RectF

/** Historical adapter; the dial is an exact identifier-only 11166bf fixture. */
internal class Baseline084CarbonClockFaceRenderer {
    private val art=Baseline084CarbonDialArt()
    private var battery: Baseline084BatteryBand?=null
    fun onBatteryChanged(percent:Int?) { battery=PhoneBattery.band(percent)?.let {Baseline084BatteryBand.valueOf(it.name)} }
    fun draw(c:Canvas,bounds:RectF,epoch:Long,motion:Boolean) = art.draw(c,bounds,ClockFaces.renderEpochMillis(epoch,motion),motion=motion,batteryBand=battery)
    fun release()=art.release()
}
