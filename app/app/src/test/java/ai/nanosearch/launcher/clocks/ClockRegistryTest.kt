package ai.nanosearch.launcher.clocks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockRegistryTest {
    @Test fun stableIdentifiersPreserveExistingSteelSelections() {
        assertEquals("steeldial", ClockFaces.STEEL_ID)
        assertEquals("carbon01", ClockFaces.CARBON_ID)
        assertEquals(listOf("steeldial", "carbon01"), ClockFaces.all.map { it.id })
        assertEquals(ClockFaces.all.size, ClockFaces.all.map { it.id }.toSet().size)
        assertEquals("Built-in picker must expose the same stable face ids", ClockFaces.all.map { it.id }, ClockWidgets.all.map { it.id })
        assertTrue(ClockFaces.all.all { it.label.isNotBlank() && it.defaultHeightDp > 0 })
    }
    @Test fun oldOrUnknownPreferencesAlwaysFallBackToSteel() {
        for (id in listOf(null, "", "unknown-future-face", "STEELDIAL")) assertEquals(ClockFaces.STEEL_ID, ClockFaces.find(id).id)
        assertEquals(ClockFaces.CARBON_ID, ClockFaces.find("carbon01").id)
    }
}
