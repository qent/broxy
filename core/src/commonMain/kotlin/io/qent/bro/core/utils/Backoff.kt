package io.qent.bro.core.utils

import kotlin.math.min

class ExponentialBackoff(
    private val initialDelayMs: Long = 500,
    private val maxDelayMs: Long = 8000,
    private val factor: Double = 2.0
) {
    fun delayForAttempt(attempt: Int): Long {
        if (attempt <= 0) return initialDelayMs
        val delay = (initialDelayMs * factor.pow(attempt - 1)).toLong()
        return min(delay, maxDelayMs)
    }

    private fun Double.pow(exp: Int): Double {
        var r = 1.0
        repeat(exp) { r *= this }
        return r
    }
}

