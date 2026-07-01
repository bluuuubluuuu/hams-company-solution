package com.klk.hams.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [PushCampaign] using an in-memory [PushCampaign.CampaignStore]
 * fake. No Android, no SharedPreferences. Covers the Task 3.3b state machine:
 *
 *   - Idle → Active via [PushCampaign.start]
 *   - Cumulative [PushCampaign.recordCompletion] increments capped at total
 *   - [PushCampaign.clear] returns to Idle
 *   - [PushCampaign.resetIfStale] catches force-stop / new-tasks drift
 */
class PushCampaignTest {

    private class FakeStore : PushCampaign.CampaignStore {
        var state: Pair<Int, Int>? = null
        override fun load(): Pair<Int, Int>? = state
        override fun save(done: Int, total: Int) { state = done to total }
        override fun clear() { state = null }
    }

    @Test fun snapshot_returnsNull_whenIdle() {
        val campaign = PushCampaign(FakeStore())
        assertNull(campaign.snapshot())
    }

    @Test fun start_locksInTotal_doneStartsAtZero() {
        val campaign = PushCampaign(FakeStore())

        campaign.start(43)

        assertEquals(0 to 43, campaign.snapshot())
    }

    @Test fun start_isNoOp_whenAlreadyActive() {
        val campaign = PushCampaign(FakeStore())
        campaign.start(43)
        campaign.recordCompletion()

        // Caller tries to start again with a different total; original wins.
        campaign.start(99)

        assertEquals(1 to 43, campaign.snapshot())
    }

    @Test fun recordCompletion_incrementsDone() {
        val campaign = PushCampaign(FakeStore())
        campaign.start(3)

        campaign.recordCompletion()
        campaign.recordCompletion()

        assertEquals(2 to 3, campaign.snapshot())
    }

    @Test fun recordCompletion_isCappedAtTotal() {
        val campaign = PushCampaign(FakeStore())
        campaign.start(2)
        repeat(5) { campaign.recordCompletion() }

        assertEquals(2 to 2, campaign.snapshot())
    }

    @Test fun recordCompletion_isNoOp_whenIdle() {
        val campaign = PushCampaign(FakeStore())
        campaign.recordCompletion()
        assertNull(campaign.snapshot())
    }

    @Test fun clear_resetsToIdle() {
        val campaign = PushCampaign(FakeStore())
        campaign.start(5)
        campaign.recordCompletion()

        campaign.clear()

        assertNull(campaign.snapshot())
    }

    @Test fun clear_allowsFreshStartWithNewTotal() {
        val campaign = PushCampaign(FakeStore())
        campaign.start(5)
        campaign.clear()

        campaign.start(10)

        assertEquals(0 to 10, campaign.snapshot())
    }

    @Test fun resetIfStale_returnsFalse_whenIdle() {
        val campaign = PushCampaign(FakeStore())
        assertFalse(campaign.resetIfStale(currentPending = 5))
        assertNull(campaign.snapshot())
    }

    @Test fun resetIfStale_returnsFalse_whenPendingMatchesExpected() {
        val campaign = PushCampaign(FakeStore())
        campaign.start(10)
        campaign.recordCompletion()
        campaign.recordCompletion()
        // Expected remaining = 8. Observed pending = 8 → consistent.

        assertFalse(campaign.resetIfStale(currentPending = 8))
        assertEquals(2 to 10, campaign.snapshot())
    }

    @Test fun resetIfStale_returnsFalse_whenPendingLowerThanExpected() {
        // Lower-than-expected pending is fine — events drained, possibly via
        // another path. Campaign keeps counting.
        val campaign = PushCampaign(FakeStore())
        campaign.start(10)
        campaign.recordCompletion()

        assertFalse(campaign.resetIfStale(currentPending = 3))
        assertEquals(1 to 10, campaign.snapshot())
    }

    @Test fun resetIfStale_resnaps_whenPendingExceedsExpectedRemaining() {
        // Force-stop scenario: campaign had 10 total, 2 done (expected 8 left).
        // After force-stop + new task arrivals, pending = 15. Re-snap to 15.
        val campaign = PushCampaign(FakeStore())
        campaign.start(10)
        campaign.recordCompletion()
        campaign.recordCompletion()

        val reset = campaign.resetIfStale(currentPending = 15)

        assertTrue(reset)
        assertEquals(0 to 15, campaign.snapshot())
    }

    @Test fun start_rejectsZeroOrNegative() {
        val campaign = PushCampaign(FakeStore())
        try {
            campaign.start(0); assertTrue("expected IAE", false)
        } catch (_: IllegalArgumentException) { /* ok */ }
        try {
            campaign.start(-1); assertTrue("expected IAE", false)
        } catch (_: IllegalArgumentException) { /* ok */ }
    }
}
