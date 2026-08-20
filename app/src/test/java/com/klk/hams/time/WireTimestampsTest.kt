package com.klk.hams.time

import org.junit.Assert.assertEquals
import org.junit.Test

class WireTimestampsTest {

    private val cap = 300L

    @Test
    fun `first press takes real time`() {
        assertEquals(1000L, WireTimestamps.nextSecond(1000L, Long.MIN_VALUE, cap))
    }

    @Test
    fun `a later second is used as-is`() {
        assertEquals(1005L, WireTimestamps.nextSecond(1005L, 1000L, cap))
    }

    @Test
    fun `second press in the same second advances by one`() {
        assertEquals(1001L, WireTimestamps.nextSecond(1000L, 1000L, cap))
    }

    @Test
    fun `a burst inside one second spreads out consecutively`() {
        var last = Long.MIN_VALUE
        val stamps = (1..5).map {
            WireTimestamps.nextSecond(1000L, last, cap).also { s -> last = s }
        }
        // Every press keeps its own slot on the wire - this is the whole point.
        assertEquals(listOf(1000L, 1001L, 1002L, 1003L, 1004L), stamps)
        assertEquals(stamps.size, stamps.toSet().size)
    }

    @Test
    fun `real time catches up once pressing slows`() {
        // Three fast presses push the stamp two seconds ahead...
        var last = WireTimestamps.nextSecond(1000L, Long.MIN_VALUE, cap)
        last = WireTimestamps.nextSecond(1000L, last, cap)
        last = WireTimestamps.nextSecond(1000L, last, cap)
        assertEquals(1002L, last)

        // ...and a press ten seconds later is stamped at its true time, not 1003.
        assertEquals(1010L, WireTimestamps.nextSecond(1010L, last, cap))
    }

    @Test
    fun `drift is capped and true time wins past it`() {
        // lastSecond already sits at the cap ahead of now: advancing would exceed it.
        val now = 1000L
        val last = now + cap
        assertEquals(now, WireTimestamps.nextSecond(now, last, cap))
    }

    @Test
    fun `drift exactly at the cap is still allowed`() {
        val now = 1000L
        val last = now + cap - 1
        assertEquals(now + cap, WireTimestamps.nextSecond(now, last, cap))
    }

    @Test
    fun `a small backwards slip still emits a distinct second`() {
        // NTP nudging the clock back a second must not produce a duplicate.
        assertEquals(2001L, WireTimestamps.nextSecond(1999L, 2000L, cap))
    }

    @Test
    fun `a large backwards jump is treated as a clock correction`() {
        // These handsets arrive with badly wrong clocks and correct themselves on
        // first network contact. Once the jump exceeds the cap the corrected time
        // is the trustworthy one, so it wins over continuing the old sequence.
        assertEquals(1500L, WireTimestamps.nextSecond(1500L, 2000L, cap))
    }

    @Test
    fun `uniqueness re-establishes immediately after a correction`() {
        var last = WireTimestamps.nextSecond(1500L, 2000L, cap)
        assertEquals(1500L, last)
        last = WireTimestamps.nextSecond(1500L, last, cap)
        assertEquals(1501L, last)
    }
}
