package ai.nanosearch.launcher

import org.junit.Assert.*
import org.junit.Test

class StandaloneHubDataTest {
    private fun note(key: String, pkg: String = "app.$key", time: Long = 1L, kind: String = "message") =
        HubStore.Note(key, pkg, "Title $key", "Body $key", time, kind, null, null, true)

    @Test fun includesOnlySuppliedLiveNotificationsAndKeepsPayloads() {
        val message = note("message")
        val event = note("event", kind = "event")
        val actual = StandaloneHubData.select(listOf(message, event), emptySet(), emptySet(), emptySet())
        assertEquals(listOf("n:message", "n:event"), actual.map { it.id })
        assertTrue(actual.all { it.kind == "app" && it.open == null })
        assertSame(message, actual.first().note)
        assertEquals("Title message", actual.first().title)
        assertEquals("Body message", actual.first().body)
    }

    @Test fun pinnedNotificationsComeFirstThenNewestFirst() {
        val actual = StandaloneHubData.select(listOf(note("old", time = 1), note("new", time = 4), note("pin", time = 2)), emptySet(), setOf("n:pin"), emptySet())
        assertEquals(listOf("n:pin", "n:new", "n:old"), actual.map { it.id })
    }

    @Test fun mutedAppsAndSnoozedNotificationIdsStayHiddenWithoutChangingInput() {
        val notes = listOf(note("a"), note("b"), note("c"))
        val actual = StandaloneHubData.select(notes, setOf("app.a"), setOf("n:a", "n:b"), setOf("n:b"))
        assertEquals(listOf("n:c"), actual.map { it.id })
        assertEquals(3, notes.size)
    }

    @Test fun emptySnapshotDoesNotInventSmsCallsOrCalendarItems() {
        assertTrue(StandaloneHubData.select(emptyList(), emptySet(), setOf("s:1", "c:2", "e:3"), emptySet()).isEmpty())
    }
}
