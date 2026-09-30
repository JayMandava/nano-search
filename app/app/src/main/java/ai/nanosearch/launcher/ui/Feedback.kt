package ai.nanosearch.launcher.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View

/** Icons grow a little under the finger, as they do in Android's own launchers. Taps and long-presses still work (the listener never consumes the touch). */
@SuppressLint("ClickableViewAccessibility")
fun View.pressScale(scale: Float = 1.1f) {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
        }
        false
    }
}
