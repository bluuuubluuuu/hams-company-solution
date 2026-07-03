package com.klk.hams.diagnostics

import com.klk.hams.AppConfig
import com.klk.hams.data.location.GpsLockEvaluator
import com.klk.hams.data.location.GpsLockState

/**
 * Emits GPS_LOST / GPS_RECOVERY on Locked<->Stale transitions, but only when the
 * new state is **sustained** for [dwellMs]. Under intermittent signal the raw lock
 * state flaps every few seconds; without a dwell that would emit (and push to
 * Wialon) a lost/recovery pair per flap. Requiring the state to hold collapses
 * brief flaps into nothing and reports only genuine, sustained losses/recoveries.
 *
 * First sample seeds state silently (no emission).
 */
class GpsLockTransition(
    private val staleAfterMs: Long = AppConfig.GPS_LOCK_STALE_AFTER_MS,
    private val relockBelowMs: Long = AppConfig.GPS_LOCK_RELOCK_BELOW_MS,
    private val dwellMs: Long = AppConfig.GPS_EVENT_DWELL_MS,
) {
    private var raw: GpsLockState? = null          // raw lock state (seeded first sample)
    private var reported: GpsLockState? = null     // last state an event was emitted for
    private var candidate: GpsLockState? = null    // state currently accumulating dwell
    private var candidateSinceMs = 0L

    fun onSnapshotAge(ageMs: Long, nowMs: Long): DiagnosticType? {
        val prevRaw = raw
        val newRaw = if (prevRaw == null) {
            if (ageMs > staleAfterMs) GpsLockState.Stale else GpsLockState.Locked
        } else {
            GpsLockEvaluator.next(prevRaw, ageMs, staleAfterMs, relockBelowMs)
        }
        raw = newRaw

        if (reported == null) {                    // seed silently on the first sample
            reported = newRaw
            candidate = newRaw
            candidateSinceMs = nowMs
            return null
        }
        if (newRaw != candidate) {                 // state changed -> restart the dwell timer
            candidate = newRaw
            candidateSinceMs = nowMs
            return null
        }
        if (newRaw == reported) return null         // no net change vs what we last reported
        if (nowMs - candidateSinceMs < dwellMs) return null  // not held long enough yet

        val prev = reported
        reported = newRaw
        return when {
            prev == GpsLockState.Locked && newRaw == GpsLockState.Stale -> DiagnosticType.GPS_LOST
            prev == GpsLockState.Stale && newRaw == GpsLockState.Locked -> DiagnosticType.GPS_RECOVERY
            else -> null
        }
    }
}
