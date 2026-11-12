package ai.nanosearch.launcher

import android.app.Activity
import android.os.Build
import android.os.PowerManager
import android.view.Display
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity

/** A short interaction hint, never a promise about the panel's rate or rendered frame time. */
internal class InteractionFrameRate(private val activity: Activity) {
    private val decor get() = activity.window.decorView
    private var active = false
    private var previousRate = 0f
    private var preferredRate = 0f
    private var hinted = false
    private val release = Runnable { reset() }

    fun resume() {
        active = true
        previousRate = activity.window.attributes.preferredRefreshRate
        // The attached window is authoritative (including secondary displays). A newly
        // created/headless activity may not have a display-associated Context yet.
        @Suppress("DEPRECATION")
        val display = resolveInteractionDisplay(decor.display) { activity.windowManager.defaultDisplay }
        val current = display?.mode
        preferredRate = if (current == null) 0f else interactionRefreshRate(
            display?.supportedModes.orEmpty().filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                .map { it.refreshRate },
        )
        boost()
    }

    fun pause() {
        active = false
        decor.removeCallbacks(release)
        reset()
    }

    fun touch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_SCROLL -> boost()
        }
    }

    fun boost(holdMs: Long = 1_200L) {
        if (!active) return
        decor.removeCallbacks(release)
        if (!hinted) {
            if (activity.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true) return
            if (Build.VERSION.SDK_INT >= 35) {
                decor.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH)
            } else if (preferredRate > 0f && preferredRate > previousRate) {
                activity.window.attributes = activity.window.attributes.apply { preferredRefreshRate = preferredRate }
            }
            hinted = true
        }
        decor.postDelayed(release, holdMs)
    }

    private fun reset() {
        if (!hinted) return
        if (Build.VERSION.SDK_INT >= 35) {
            decor.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE)
        } else {
            activity.window.attributes = activity.window.attributes.apply { preferredRefreshRate = previousRate }
        }
        hinted = false
    }
}

/** Only missing display-context support is optional; unrelated platform failures still surface. */
internal fun resolveInteractionDisplay(attached: Display?, windowDisplay: () -> Display?): Display? =
    attached ?: try { windowDisplay() } catch (_: UnsupportedOperationException) { null }

/** Prefer a supported 90/120 Hz mode without changing resolution or inventing an unsupported rate. */
internal fun interactionRefreshRate(rates: List<Float>): Float {
    val valid = rates.filter { it.isFinite() && it > 0f }
    return valid.filter { it <= 120.5f }.maxOrNull()?.takeIf { it > 60.5f }
        ?: valid.maxOrNull() ?: 0f
}

/** Shared by View and Compose screens, so navigation and flings receive the same bounded hint. */
open class SmoothActivity : Activity() {
    private val interactionFrames by lazy { InteractionFrameRate(this) }
    override fun onResume() { super.onResume(); interactionFrames.resume() }
    override fun onPause() { interactionFrames.pause(); super.onPause() }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        interactionFrames.touch(event)
        return super.dispatchTouchEvent(event)
    }
    protected fun requestInteractionFrames(holdMs: Long = 1_200L) = interactionFrames.boost(holdMs)
}

open class SmoothComponentActivity : ComponentActivity() {
    private val interactionFrames by lazy { InteractionFrameRate(this) }
    override fun onResume() { super.onResume(); interactionFrames.resume() }
    override fun onPause() { interactionFrames.pause(); super.onPause() }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        interactionFrames.touch(event)
        return super.dispatchTouchEvent(event)
    }
}
