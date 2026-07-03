package com.klk.hams.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpsLockTransitionTest {
    // dwell = 20s: a lost/recovery must be sustained that long before it emits.
    private fun detector() =
        GpsLockTransition(staleAfterMs = 8_000, relockBelowMs = 3_000, dwellMs = 20_000)

    @Test
    fun sustainedLoss_emitsGpsLost_afterDwell() {
        val d = detector()
        assertNull(d.onSnapshotAge(1_000, 0))            // seed Locked
        assertNull(d.onSnapshotAge(9_000, 1_000))        // raw Stale — dwell starts
        assertNull(d.onSnapshotAge(9_000, 15_000))       // 14s held, < 20s
        assertEquals(DiagnosticType.GPS_LOST, d.onSnapshotAge(9_000, 21_000)) // 20s held
    }

    @Test
    fun briefFlap_isSuppressed() {
        val d = detector()
        assertNull(d.onSnapshotAge(1_000, 0))            // Locked
        assertNull(d.onSnapshotAge(9_000, 1_000))        // stale begins
        assertNull(d.onSnapshotAge(1_000, 5_000))        // back to locked before dwell -> flap
        assertNull(d.onSnapshotAge(1_000, 40_000))       // stays locked -> nothing ever emitted
    }

    @Test
    fun sustainedRecovery_emitsAfterDwell() {
        val d = detector()
        d.onSnapshotAge(1_000, 0)                          // Locked
        d.onSnapshotAge(9_000, 1_000)                      // stale begins
        assertEquals(DiagnosticType.GPS_LOST, d.onSnapshotAge(9_000, 21_000)) // lost
        assertNull(d.onSnapshotAge(1_000, 22_000))        // relock begins
        assertNull(d.onSnapshotAge(1_000, 30_000))        // 8s held, < 20s
        assertEquals(DiagnosticType.GPS_RECOVERY, d.onSnapshotAge(1_000, 42_000)) // 20s held
    }

    @Test
    fun firstSample_stale_initialisesSilently() {
        assertNull(detector().onSnapshotAge(9_000, 0))
    }
}
