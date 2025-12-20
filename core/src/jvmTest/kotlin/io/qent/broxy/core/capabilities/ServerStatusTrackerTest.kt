package io.qent.broxy.core.capabilities

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerStatusTrackerTest {
    @Test
    fun set_records_connecting_timestamps_and_clears_on_status_change() {
        var nowMillis = 100L
        val tracker = ServerStatusTracker { nowMillis }

        tracker.set("s1", ServerConnectionStatus.Connecting)
        assertEquals(100L, tracker.connectingSince("s1"))

        nowMillis = 200L
        tracker.set("s1", ServerConnectionStatus.Available)
        assertNull(tracker.connectingSince("s1"))
        assertEquals(ServerConnectionStatus.Available, tracker.statusFor("s1"))
    }

    @Test
    fun setAll_uses_single_timestamp_and_retain_prunes_maps() {
        val tracker = ServerStatusTracker { 500L }
        tracker.setAll(listOf("a", "b"), ServerConnectionStatus.Connecting)

        assertEquals(500L, tracker.connectingSince("a"))
        assertEquals(500L, tracker.connectingSince("b"))

        tracker.retain(setOf("b"))
        assertNull(tracker.statusFor("a"))
        assertNull(tracker.connectingSince("a"))
        assertEquals(ServerConnectionStatus.Connecting, tracker.statusFor("b"))
    }
}
