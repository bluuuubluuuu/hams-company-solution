package com.klk.hams.time

/**
 * Keeps event times distinct at the resolution the wire actually has.
 *
 * Wialon IPS carries whole seconds (`IPSFrameBuilder` formats `HHmmss`), and
 * Wialon stores one message per unit per timestamp. Two presses inside the same
 * second therefore produced two frames Wialon could not both keep, and a report
 * counting messages under-reported the day's work — 3471 against 5691 on
 * 18 Aug 2026, a 39% shortfall that grew with how fast the worker pressed.
 *
 * Rather than refuse fast presses, later ones are stored one second apart so
 * every press gets its own slot. Nothing is lost; a burst of ten taps in three
 * seconds becomes ten messages spread over ten seconds, and real time catches up
 * the moment pressing slows.
 *
 * Only the event's wire `timestamp` is adjusted. `created_at` keeps true clock
 * time, so retention sweeps and audit ordering are unaffected.
 *
 * Pure arithmetic, no Android dependency — JVM testable.
 */
object WireTimestamps {

    /**
     * The second to stamp an event with, given the true time [nowSecond] and the
     * [lastSecond] already handed out (`Long.MIN_VALUE` when none has been).
     *
     * Returns [nowSecond] when it is genuinely later. Otherwise advances one
     * second past [lastSecond] — unless doing so would push the stamp more than
     * [maxDriftSec] beyond real time, in which case true time wins and the
     * collision is accepted. A wrong time is worse than a lost duplicate.
     */
    fun nextSecond(
        nowSecond: Long,
        lastSecond: Long,
        maxDriftSec: Long,
    ): Long {
        if (nowSecond > lastSecond) return nowSecond
        val advanced = lastSecond + 1
        return if (advanced - nowSecond > maxDriftSec) nowSecond else advanced
    }
}
