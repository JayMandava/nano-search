package ai.nanosearch.launcher.clocks

import org.junit.Assert.*
import org.junit.Test

class CarbonLumeStateTest {
    @Test fun crownHasAHoldThenFadeAndExpiresAfterThreeSeconds() {
        val idle = CarbonLumeState()
        assertFalse(idle.active(1000)); assertEquals(0f, idle.alpha(1000, false), 0f)
        val lit = idle.trigger(1000)
        assertTrue(lit.active(1000)); assertEquals(1f, lit.alpha(2199, false), 0f)
        assertTrue(lit.alpha(2700, false) in 0.001f..0.999f)
        assertTrue(lit.active(3999)); assertFalse(lit.active(4000))
        assertEquals(0f, lit.alpha(4000, false), 0f)
    }
    @Test fun repeatedCrownTapRestartsTheWindowAndClearCancelsIt() {
        val first = CarbonLumeState().trigger(1000)
        val second = first.trigger(3000)
        assertEquals(1f, second.alpha(3000, false), 0f)
        assertTrue(second.active(5999)); assertFalse(second.active(6000))
        assertFalse(second.clear().active(3000)); assertEquals(0f, second.clear().alpha(3000, false), 0f)
    }
    @Test fun reducedMotionHoldsAStaticGlowThenTurnsOffWithoutFading() {
        val state = CarbonLumeState().trigger(1000)
        assertEquals(1f, state.alpha(1000, true), 0f)
        assertEquals(1f, state.alpha(2700, true), 0f)
        assertEquals(1f, state.alpha(3999, true), 0f)
        assertEquals(0f, state.alpha(4000, true), 0f)
    }
}
