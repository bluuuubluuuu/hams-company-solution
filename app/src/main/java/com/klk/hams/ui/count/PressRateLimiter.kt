package com.klk.hams.ui.count

/**
 * Holds recorded presses of one button at least [com.klk.hams.AppConfig.PRESS_MIN_INTERVAL_MS]
 * apart.
 *
 * Why a rate limit rather than a debounce: Wialon stores every message the
 * handset sends, same-second messages included (verified against raw unit
 * messages, 2026-08-19). What cannot keep up is the notification layer feeding
 * the count report — it fires at most once per second, so several presses inside
 * one second were counted as one. Reports read ~61% of the presses actually
 * recorded. Spacing presses out gives each one its own second, so each triggers.
 *
 * This deliberately refuses genuine presses when someone taps faster than the
 * limit. That cost is accepted, but it must never be silent: the caller announces
 * a rejection with [PressFeedback.Refused] so the worker hears that the press did
 * not count and slows down. An unannounced rejection would recreate exactly the
 * invisible loss this exists to remove.
 *
 * Tracked per button, so a `-` correction immediately after a `+` is not blocked.
 *
 * Pure arithmetic, no Android dependency — JVM testable.
 */
object PressRateLimiter {

    /**
     * True when a press at [nowMs] is far enough after [lastAcceptedMs] to record.
     * Pass `Long.MIN_VALUE` as [lastAcceptedMs] when nothing has been accepted yet.
     *
     * [nowMs] must come from a monotonic source (`SystemClock.elapsedRealtime`),
     * not wall time — a clock correction must never swallow a press, and these
     * handsets do correct their clocks by months on first network contact.
     */
    fun accepts(nowMs: Long, lastAcceptedMs: Long, minIntervalMs: Long): Boolean {
        if (lastAcceptedMs == Long.MIN_VALUE) return true
        return nowMs - lastAcceptedMs >= minIntervalMs
    }
}
