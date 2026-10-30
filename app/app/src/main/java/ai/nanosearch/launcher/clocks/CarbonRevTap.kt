package ai.nanosearch.launcher.clocks

import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.hypot

/** Wallpaper only: a one-finger tap that starts and ends on the rev button. */
internal class CarbonRevTap(private val slop:Float) {
    private var armed=false
    private var x=0f
    private var y=0f
    private var downTime=0L
    private var pointer=-1
    fun cancel() {armed=false}
    fun touch(event:MotionEvent,layout:CarbonPusherLayout,offsetX:Float,offsetY:Float,enabled:Boolean):Boolean {
        val px=event.x-offsetX;val py=event.y-offsetY
        if(!enabled || event.pointerCount!=1) {cancel();return false}
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {x=px;y=py;downTime=event.eventTime;pointer=event.getPointerId(0);armed=layout.hitTest(px,py)==CarbonPusherAction.REV}
            MotionEvent.ACTION_MOVE -> if(event.getPointerId(0)!=pointer || hypot(px-x,py-y)>slop || layout.hitTest(px,py)!=CarbonPusherAction.REV)cancel()
            MotionEvent.ACTION_UP -> {
                val fire=armed && event.getPointerId(0)==pointer && event.eventTime>=downTime &&
                    event.eventTime-downTime<ViewConfiguration.getLongPressTimeout() && hypot(px-x,py-y)<=slop && layout.hitTest(px,py)==CarbonPusherAction.REV
                cancel();return fire
            }
            MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_POINTER_UP -> cancel()
        }
        return false
    }
}
