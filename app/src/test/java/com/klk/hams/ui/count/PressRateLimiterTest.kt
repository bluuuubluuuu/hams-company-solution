package com.klk.hams.ui.count

import com.klk.hams.AppConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PressRateLimiterTest {

    private val min = AppConfig.PRESS_MIN_INTERVAL_MS

    @Test
    fun `the first press is always accepted`() {
        assertTrue(PressRateLimiter.accepts(0L, Long.MIN_VALUE, min))
    }

    @Test
    fun `a press inside the interval is refused`() {
        assertFalse(PressRateLimiter.accepts(1_000L + min - 1, 1_000L, min))
    }

    @Test
    fun `exactly at the interval is accepted`() {
        assertTrue(PressRateLimiter.accepts(1_000L + min, 1_000L, min))
    }

    @Test
    fun `steady pressing at the limit is never refused`() {
        var last = 0L
        for (i in 1..10) {
            val now = i * min
            assertTrue("press $i at ${now}ms was refused", PressRateLimiter.accepts(now, last, min))
            last = now
        }
    }

    @Test
    fun `a burst records only the presses the notification layer can carry`() {
        // Ten taps 200 ms apart: without the limit these would share seconds and
        // be reported as one. Only presses at least the interval apart record.
        var last = Long.MIN_VALUE
        var recorded = 0
        for (i in 0 until 10) {
            val now = i * 200L
            if (PressRateLimiter.accepts(now, last, min)) {
                recorded++
                last = now
            }
        }
        assertTrue("expected far fewer than 10 recorded, got $recorded", recorded < 10)
        assertTrue("at least the first press must record", recorded >= 1)
    }

    @Test
    fun `hold-to-repeat never outruns the limit`() {
        // Auto-repeat must not generate presses the limiter would only reject.
        assertTrue(AppConfig.PRESS_REPEAT_INTERVAL_MS >= min)
    }
}
