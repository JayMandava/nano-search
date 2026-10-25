package ai.nanosearch.launcher.clocks

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockMotionPolicyTest {
    @Test fun everyVisibilityAndAccessibilityRestrictionStopsSmoothMotion() {
        for (attached in listOf(false, true)) for (visible in listOf(false, true))
            for (reduced in listOf(false, true)) for (animators in listOf(false, true)) for (powerSave in listOf(false, true)) {
                assertEquals("attached=$attached visible=$visible reduced=$reduced animators=$animators powerSave=$powerSave",
                    attached && visible && !reduced && animators && !powerSave,
                    ClockMotionPolicy.shouldAnimate(attached, visible, reduced, animators, powerSave))
            }
    }

    @Test fun stationaryClocksUseOneSecondBoundariesInsteadOfFrameCadence() {
        for (now in listOf(0L, 1L, 999L, 1000L, 1234L)) {
            assertEquals(16L, ClockMotionPolicy.frameDelayMillis(true, now))
            assertEquals(1000L - now % 1000L, ClockMotionPolicy.frameDelayMillis(false, now))
        }
    }
}
