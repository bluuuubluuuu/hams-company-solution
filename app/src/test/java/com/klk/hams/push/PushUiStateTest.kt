package com.klk.hams.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PushUiStateTest {

    @Test fun idleEqualsItself() {
        assertEquals(PushUiState.Idle, PushUiState.Idle)
    }

    @Test fun pendingWifiHoldsTaskCount() {
        val s = PushUiState.PendingWifi(pendingTasks = 3)
        assertEquals(3, s.pendingTasks)
    }

    @Test fun pushingEqualsByTotalAndDone() {
        assertEquals(PushUiState.Pushing(10, 4), PushUiState.Pushing(10, 4))
        assertNotEquals(PushUiState.Pushing(10, 4), PushUiState.Pushing(10, 5))
    }

    @Test fun isLockableHelperFlagsPushingAndPendingWifi() {
        assertTrue(PushUiState.PendingWifi(1).isLockable)
        assertTrue(PushUiState.Pushing(1, 0).isLockable)
        assertFalse(PushUiState.Idle.isLockable)
        assertFalse(PushUiState.Completed(1, Instant.EPOCH).isLockable)
        assertFalse(PushUiState.Failed("x", 1).isLockable)
    }
}
