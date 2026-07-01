package com.klk.hams.push

import com.klk.hams.AppConfig

/**
 * Pure bucket logic for battery threshold edge detection (dictionary v1.2).
 *
 * Maps a battery percent into one of three ordered buckets and decides whether
 * a downward bucket transition should emit a local-only event_code:
 *
 *   - `BUCKET_NORMAL`   ( pct >= [AppConfig.BATTERY_WARN_THRESHOLD_PCT] )
 *   - `BUCKET_WARN`     ( [AppConfig.BATTERY_CRITICAL_THRESHOLD_PCT] <= pct
 *                          < [AppConfig.BATTERY_WARN_THRESHOLD_PCT] )
 *   - `BUCKET_CRITICAL` ( pct < [AppConfig.BATTERY_CRITICAL_THRESHOLD_PCT] )
 *
 * Edge rules (CLAUDE.md "Battery Edge Detection Logic"):
 *   - Same bucket          -> no fire
 *   - Upward transition    -> no fire (charging recovery is silent)
 *   - normal -> warn       -> 291
 *   - normal -> critical   -> 292 (steep drop skips 291, fires the worst code)
 *   - warn   -> critical   -> 292
 *
 * Persistence of the last bucket value is the caller's responsibility (in
 * production: SharedPreferences; in tests: an in-memory variable). This object
 * is stateless so it can be unit-tested with no Android dependencies.
 *
 * Output codes are HAMS-local 291/292 — they must NOT be queued for outbound
 * push. The accompanying [PushEligibility] policy already insert-marks them
 * `pushed = 1`; battery itself rides every pushed 179/180/35 message via the
 * `battery` param.
 */
object BatteryEdgeDetector {

    const val BUCKET_NORMAL: Int = 100
    const val BUCKET_WARN: Int = 20
    const val BUCKET_CRITICAL: Int = 10

    /** Maps a battery percent to its bucket label. */
    fun bucketOf(pct: Int): Int = when {
        pct < AppConfig.BATTERY_CRITICAL_THRESHOLD_PCT -> BUCKET_CRITICAL
        pct < AppConfig.BATTERY_WARN_THRESHOLD_PCT     -> BUCKET_WARN
        else                                           -> BUCKET_NORMAL
    }

    /**
     * Returns the local-only event_code to emit for a downward bucket
     * transition, or `null` if no event should fire.
     *
     * `prevBucket == null` represents a first-ever observation (no persisted
     * bucket state yet) and is intentionally silent: the caller should record
     * `newBucket` as the baseline without emitting. This prevents fresh
     * installs / cleared prefs from spuriously firing 291 or 292 the first
     * time the receiver wakes up at an already-low battery level.
     */
    fun edgeEvent(prevBucket: Int?, newBucket: Int): Int? {
        if (prevBucket == null) return null
        if (newBucket >= prevBucket) return null
        return if (newBucket == BUCKET_CRITICAL) AppConfig.EVENT_CODE_BATTERY_CRITICAL
        else AppConfig.EVENT_CODE_BATTERY_WARN
    }
}
