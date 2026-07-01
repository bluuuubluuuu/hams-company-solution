package com.klk.hams.push

import com.klk.hams.AppConfig

/**
 * Stateful detector for GPS-degraded edge events (dictionary v1.2).
 *
 * "Degraded" means the latest fix has an HDOP strictly above
 * [AppConfig.GPS_HDOP_DEGRADED_THRESHOLD]. The detector emits the local-only
 * code [AppConfig.EVENT_CODE_GPS_DEGRADED] (293) exactly once on entering the
 * degraded state and stays silent until the fix recovers (HDOP at-or-below
 * threshold) — at which point a subsequent re-degrade will fire again.
 *
 * Null HDOP ("no fix data" / not yet known) is treated as not-degraded so we
 * don't flood the SQLite events table while location is still warming up; the
 * separate GPS gate at app launch is the user-facing guarantee that counting
 * cannot proceed without a valid fix.
 *
 * Output is local-only — 293 must NOT be queued for outbound push under v1.2;
 * [PushEligibility] insert-marks it `pushed = 1`.
 */
class GpsDegradedDetector(
    private val threshold: Double = AppConfig.GPS_HDOP_DEGRADED_THRESHOLD
) {

    private var degraded: Boolean = false

    /**
     * Feeds a new HDOP reading and returns 293 on the leading edge of the
     * degraded state, or `null` otherwise (recovery, continued degraded,
     * unchanged good fix, or unknown fix).
     */
    fun observe(hdop: Double?): Int? {
        val nowDegraded = hdop != null && hdop > threshold
        return when {
            nowDegraded && !degraded -> {
                degraded = true
                AppConfig.EVENT_CODE_GPS_DEGRADED
            }
            !nowDegraded -> {
                degraded = false
                null
            }
            else -> null
        }
    }
}
