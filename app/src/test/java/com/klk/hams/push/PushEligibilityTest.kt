package com.klk.hams.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Push eligibility under dictionary v1.2 (2026-04-30).
 *
 * Outbound-approved: 179 (always), 180 (only when workCount > 0), 35 (always).
 * Local-only: 281, 283, 284, 291, 292, 293, plus legacy 279/280 and any unknown
 * code.
 */
class PushEligibilityTest {

    // -- Outbound-approved codes -------------------------------------------------

    @Test fun `plus 179 always queues`() {
        assertTrue(PushEligibility.shouldQueueForPush(179, 1))
        assertTrue(PushEligibility.shouldQueueForPush(179, 100))
    }

    @Test fun `productive minus 180 queues`() {
        assertTrue(PushEligibility.shouldQueueForPush(180, 1))
        assertTrue(PushEligibility.shouldQueueForPush(180, 4))
        assertTrue(PushEligibility.shouldQueueForPush(180, 99))
    }

    @Test fun `self-cancelling minus 180 does not queue`() {
        assertFalse(PushEligibility.shouldQueueForPush(180, 0))
        assertFalse(PushEligibility.shouldQueueForPush(180, -1))
    }

    @Test fun `heartbeat 35 always queues`() {
        assertTrue(PushEligibility.shouldQueueForPush(35, 0))
        assertTrue(PushEligibility.shouldQueueForPush(35, 99))
    }

    // -- Local-only codes (must not queue under v1.2) ---------------------------

    @Test fun `new task 281 never queues`() {
        assertFalse(PushEligibility.shouldQueueForPush(281, 0))
        assertFalse(PushEligibility.shouldQueueForPush(281, 5))
    }

    @Test fun `auto-save codes 283 and 284 do not queue`() {
        assertFalse(PushEligibility.shouldQueueForPush(283, 0))
        assertFalse(PushEligibility.shouldQueueForPush(283, 5))
        assertFalse(PushEligibility.shouldQueueForPush(284, 0))
        assertFalse(PushEligibility.shouldQueueForPush(284, 5))
    }

    @Test fun `battery and GPS edge codes 291 292 293 do not queue`() {
        assertFalse(PushEligibility.shouldQueueForPush(291, 0))
        assertFalse(PushEligibility.shouldQueueForPush(291, 5))
        assertFalse(PushEligibility.shouldQueueForPush(292, 0))
        assertFalse(PushEligibility.shouldQueueForPush(292, 5))
        assertFalse(PushEligibility.shouldQueueForPush(293, 0))
        assertFalse(PushEligibility.shouldQueueForPush(293, 5))
    }

    @Test fun `legacy dev codes 279 and 280 do not queue under v1_2`() {
        assertFalse(PushEligibility.shouldQueueForPush(279, 1))
        assertFalse(PushEligibility.shouldQueueForPush(279, 100))
        assertFalse(PushEligibility.shouldQueueForPush(280, 0))
        assertFalse(PushEligibility.shouldQueueForPush(280, 5))
    }

    @Test fun `unknown event codes do not queue`() {
        assertFalse(PushEligibility.shouldQueueForPush(0, 0))
        assertFalse(PushEligibility.shouldQueueForPush(178, 1))
        assertFalse(PushEligibility.shouldQueueForPush(181, 1))
        assertFalse(PushEligibility.shouldQueueForPush(282, 1))
        assertFalse(PushEligibility.shouldQueueForPush(999, 0))
        assertFalse(PushEligibility.shouldQueueForPush(-1, 0))
    }

    // -- pushedFlagOnInsert -----------------------------------------------------

    @Test fun `pushedFlagOnInsert returns 0 only for outbound-approved codes`() {
        assertEquals(0, PushEligibility.pushedFlagOnInsert(179, 1))
        assertEquals(0, PushEligibility.pushedFlagOnInsert(180, 5))
        assertEquals(0, PushEligibility.pushedFlagOnInsert(35, 0))
    }

    @Test fun `pushedFlagOnInsert returns 1 for self-cancelling minus`() {
        assertEquals(1, PushEligibility.pushedFlagOnInsert(180, 0))
        assertEquals(1, PushEligibility.pushedFlagOnInsert(180, -1))
    }

    @Test fun `pushedFlagOnInsert returns 1 for every local-only code`() {
        for (code in listOf(281, 283, 284, 291, 292, 293, 279, 280)) {
            assertEquals(
                "code $code must insert as local-only (pushed=1)",
                1,
                PushEligibility.pushedFlagOnInsert(code, 5)
            )
        }
    }

    @Test fun `pushedFlagOnInsert returns 1 for unknown codes`() {
        assertEquals(1, PushEligibility.pushedFlagOnInsert(0, 0))
        assertEquals(1, PushEligibility.pushedFlagOnInsert(999, 5))
        assertEquals(1, PushEligibility.pushedFlagOnInsert(-1, 1))
    }
}
