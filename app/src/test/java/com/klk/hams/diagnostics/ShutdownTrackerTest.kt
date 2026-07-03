package com.klk.hams.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShutdownTrackerTest {

    @Test fun uncleanShutdown_withLastSeen_backfillsAtLastSeen() {
        // Previous session still marked running (no ACTION_SHUTDOWN) -> backfill.
        assertEquals(
            "2026-07-02T10:15:00Z",
            ShutdownTracker.backfillTimestamp(sessionRunning = true, lastSeenIso = "2026-07-02T10:15:00Z")
        )
    }

    @Test fun cleanShutdown_flagCleared_noBackfill() {
        // ACTION_SHUTDOWN cleared the flag -> the real 40 was recorded, nothing to infer.
        assertNull(ShutdownTracker.backfillTimestamp(sessionRunning = false, lastSeenIso = "2026-07-02T10:15:00Z"))
    }

    @Test fun runningButNoLastSeen_noBackfill() {
        // Service died before ever stamping last_seen -> no date to attribute a 40 to.
        assertNull(ShutdownTracker.backfillTimestamp(sessionRunning = true, lastSeenIso = null))
    }

    @Test fun neverRanAndNoLastSeen_noBackfill() {
        assertNull(ShutdownTracker.backfillTimestamp(sessionRunning = false, lastSeenIso = null))
    }
}
