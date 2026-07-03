package com.klk.hams.data.location

object GpsLockEvaluator {
    fun next(
        prev: GpsLockState,
        ageMs: Long,
        staleAfterMs: Long,
        relockBelowMs: Long,
    ): GpsLockState = when (prev) {
        GpsLockState.Locked ->
            if (ageMs > staleAfterMs) GpsLockState.Stale else GpsLockState.Locked
        GpsLockState.Stale,
        GpsLockState.Acquiring,
        GpsLockState.NoPermission,
        GpsLockState.LocationDisabled ->
            if (ageMs < relockBelowMs) GpsLockState.Locked else GpsLockState.Stale
    }
}
