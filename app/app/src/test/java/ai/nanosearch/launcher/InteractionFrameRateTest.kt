package ai.nanosearch.launcher

import android.app.Activity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class InteractionFrameRateTest {
    @Test fun requestsSupportedNinetyOrOneTwentyRatherThanHardCodingOneTwenty() {
        assertEquals(90f, interactionRefreshRate(listOf(60f, 90f)), 0f)
        assertEquals(120f, interactionRefreshRate(listOf(60f, 90f, 120f, 144f)), 0f)
        assertEquals(119.88f, interactionRefreshRate(listOf(59.94f, 119.88f)), 0f)
    }

    @Test fun toleratesSixtyOnlyMissingAndInvalidModes() {
        assertEquals(60f, interactionRefreshRate(listOf(60f)), 0f)
        assertEquals(0f, interactionRefreshRate(emptyList()), 0f)
        assertEquals(90f, interactionRefreshRate(listOf(Float.NaN, -1f, Float.POSITIVE_INFINITY, 90f)), 0f)
        assertEquals(144f, interactionRefreshRate(listOf(60f, 144f)), 0f)
    }

    @Test fun leavingTheActivityRestoresItsOriginalWindowPreference() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.window.attributes = activity.window.attributes.apply { preferredRefreshRate = 50f }
        val frames = InteractionFrameRate(activity)
        frames.resume()
        frames.pause()
        assertEquals(50f, activity.window.attributes.preferredRefreshRate, 0f)
        // A delayed animation callback after pause must not restart the hint.
        frames.boost()
        assertEquals(50f, activity.window.attributes.preferredRefreshRate, 0f)
        controller.pause().stop().destroy()
    }
}
