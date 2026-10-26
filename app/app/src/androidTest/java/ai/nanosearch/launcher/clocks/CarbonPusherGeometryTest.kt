package ai.nanosearch.launcher.clocks

import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.min
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarbonPusherGeometryTest {
    private val controls = listOf(CarbonPusherAction.START_PAUSE, CarbonPusherAction.SPLIT_RELEASE, CarbonPusherAction.RESET, CarbonPusherAction.LUME)
    @Test fun controlsFitAndRemainDisjointWith48DpTargetsAtNormalHomeSizes() {
        for (density in listOf(1f, 2f, 3f)) for ((widthDp, heightDp) in listOf(360f to 360f, 500f to 300f, 300f to 500f, 240f to 240f, 120f to 120f, 90f to 300f, 300f to 56f)) {
            val width = widthDp * density; val height = heightDp * density
            val layout = CarbonPusherGeometry.layout(width, height, density)
            val rects = controls.map { it to layout.boundsFor(it) }
            for ((action, bounds) in rects) {
                assertTrue("$action must have a positive target at ${widthDp}x${heightDp}dp", bounds.width() > 0 && bounds.height() > 0)
                assertTrue("$action must stay inside the host", bounds.left >= 0 && bounds.top >= 0 && bounds.right <= width && bounds.bottom <= height)
                assertEquals(action, layout.hitTest(bounds.centerX(), bounds.centerY()))
                if (min(widthDp, heightDp) >= 300) {
                    assertTrue("$action width must remain48dp at normal home sizes", bounds.width() >= 48 * density - .5f)
                    assertTrue("$action height must remain48dp at normal home sizes", bounds.height() >= 48 * density - .5f)
                }
            }
            for (a in rects.indices) for (b in a + 1 until rects.size) assertFalse("Distinct controls must never share a touch point", RectF.intersects(rects[a].second, rects[b].second))
        }
    }
    @Test fun onlyTheActualDialOpensClockAndThereIsExactlyOneLeftPusher() {
        for (density in listOf(1f, 2f, 3f)) {
            val side = 360 * density
            val layout = CarbonPusherGeometry.layout(side, side, density)
            assertEquals(CarbonPusherAction.OPEN_CLOCK, layout.hitTest(side / 2, side / 2))
            assertEquals(CarbonPusherAction.LUME, layout.hitTest(side * .95f, side * .5f))
            assertNull(layout.hitTest(0f, 0f)); assertNull(layout.hitTest(side, side)); assertNull(layout.hitTest(-1f, -1f))
            assertNull("The upper left case has no second button", layout.hitTest(side * .085f, side * .339f))
        }
    }
}
