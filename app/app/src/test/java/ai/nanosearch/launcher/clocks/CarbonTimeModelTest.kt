package ai.nanosearch.launcher.clocks

import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class CarbonTimeModelTest {
    private fun at(value: String) = Instant.parse(value).toEpochMilli()
    @Test fun fractionalSecondsCarryThroughAllThreeHands() {
        val angles = CarbonTimeModel.angles(at("2026-10-25T03:30:30.500Z"), TimeZone.getTimeZone("UTC"))
        assertEquals(105.25417f, angles.hour, .001f)
        assertEquals(183.05f, angles.minute, .001f)
        assertEquals(183f, angles.second, .001f)
    }
    @Test fun midnightNoonAndNegativeEpochStayWithinDialRange() {
        for (value in listOf("2026-10-25T00:00:00Z", "2026-10-25T12:00:00Z")) {
            val angles = CarbonTimeModel.angles(at(value), TimeZone.getTimeZone("UTC"))
            assertEquals(0f, angles.hour, .001f); assertEquals(0f, angles.minute, .001f); assertEquals(0f, angles.second, .001f)
        }
        val negative = CarbonTimeModel.angles(-500, TimeZone.getTimeZone("UTC"))
        assertEquals(357f, negative.second, .001f)
        assertEquals(359.95f, negative.minute, .001f)
    }
    @Test fun timezonesAndDaylightSavingUseTheActualInstant() {
        val epoch = at("2026-11-01T06:30:00Z")
        assertEquals(45f, CarbonTimeModel.angles(epoch, TimeZone.getTimeZone("America/New_York")).hour, .001f)
        assertEquals(0f, CarbonTimeModel.angles(epoch, TimeZone.getTimeZone("Asia/Kolkata")).hour, .001f)
    }
    @Test fun reducedMotionQuantizesWallTimeWithoutChangingTheDateBoundary() {
        assertEquals(1999L, ClockFaces.renderEpochMillis(1999L, true))
        assertEquals(1000L, ClockFaces.renderEpochMillis(1999L, false))
        assertEquals(-1000L, ClockFaces.renderEpochMillis(-1L, false))
    }
}
