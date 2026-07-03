package com.klk.hams.ui.count

import com.klk.hams.data.location.GpsLockState
import org.junit.Assert.assertEquals
import org.junit.Test

class GpsLockHysteresisTest {
    private fun next(current: GpsLockState, ageMs: Long): GpsLockState =
        CountViewModel.nextGpsLockState(current, ageMs, staleAfterMs = 8_000, relockBelowMs = 3_000)

    @Test fun lockedStaysLockedAtBoundaryLow() {
        assertEquals(GpsLockState.Locked, next(GpsLockState.Locked, 7_999))
    }
    @Test fun lockedStaysLockedExactlyAtThreshold() {
        assertEquals(GpsLockState.Locked, next(GpsLockState.Locked, 8_000))
    }
    @Test fun lockedGoesStaleAbove8s() {
        assertEquals(GpsLockState.Stale, next(GpsLockState.Locked, 8_001))
    }
    @Test fun staleStaysStaleAt3sBoundary() {
        assertEquals(GpsLockState.Stale, next(GpsLockState.Stale, 3_000))
    }
    @Test fun staleStaysStaleAt4s() {
        assertEquals(GpsLockState.Stale, next(GpsLockState.Stale, 4_000))
    }
    @Test fun staleRelocksBelow3s() {
        assertEquals(GpsLockState.Locked, next(GpsLockState.Stale, 2_999))
    }
    @Test fun acquiringRelocksOnFreshFix() {
        assertEquals(GpsLockState.Locked, next(GpsLockState.Acquiring, 1_000))
    }
    @Test fun acquiringStaysOnOldFix() {
        assertEquals(GpsLockState.Stale, next(GpsLockState.Acquiring, 5_000))
    }
    @Test fun noPermissionRecoversWhenFresh() {
        assertEquals(GpsLockState.Locked, next(GpsLockState.NoPermission, 1_000))
    }
    @Test fun locationDisabledRecoversWhenFresh() {
        assertEquals(GpsLockState.Locked, next(GpsLockState.LocationDisabled, 1_500))
    }
    @Test fun hysteresisBandNoFlipUpward() {
        // Starting from Stale, age in band (between relock and stale-after) should stay Stale.
        assertEquals(GpsLockState.Stale, next(GpsLockState.Stale, 5_000))
    }
    @Test fun hysteresisBandNoFlipDownward() {
        // Starting from Locked, age in band should stay Locked.
        assertEquals(GpsLockState.Locked, next(GpsLockState.Locked, 5_000))
    }
}
