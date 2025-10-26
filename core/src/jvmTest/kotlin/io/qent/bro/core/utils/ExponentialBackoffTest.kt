package io.qent.bro.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExponentialBackoffTest {
    @Test
    fun computes_delays_and_caps_at_max() {
        val backoff = ExponentialBackoff(initialDelayMs = 100, maxDelayMs = 1000, factor = 2.0)
        assertEquals(100, backoff.delayForAttempt(0)) // non-positive -> initial
        assertEquals(100, backoff.delayForAttempt(1))
        assertEquals(200, backoff.delayForAttempt(2))
        assertEquals(400, backoff.delayForAttempt(3))
        assertEquals(800, backoff.delayForAttempt(4))
        assertEquals(1000, backoff.delayForAttempt(5)) // capped
        assertEquals(1000, backoff.delayForAttempt(10)) // stays capped
        assertTrue(backoff.delayForAttempt(-1) == 100L)
    }
}
