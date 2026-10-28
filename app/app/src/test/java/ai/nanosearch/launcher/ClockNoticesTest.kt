package ai.nanosearch.launcher

import org.junit.Assert.*
import org.junit.Test

class ClockNoticesTest {
    @Test fun repeatedTextCreatesANewEventAndOldExpiryCannotDismissIt() {
        val notices = ClockNotices()
        notices.show("Changed Home clock")
        val first = notices.revision
        notices.show("Changed Home clock")
        assertTrue(notices.revision > first)
        notices.expire(first)
        assertEquals("Changed Home clock", notices.text)
        notices.expire(notices.revision)
        assertNull(notices.text)
    }

    @Test fun manualDismissAndReplacementCannotResurrectAnOldMessage() {
        val notices = ClockNotices()
        notices.show("First"); val first = notices.revision
        notices.clear()
        assertNull(notices.text)
        notices.show("Second")
        notices.expire(first)
        assertEquals("Second", notices.text)
    }
}
