package com.klk.hams

object AppConfig {
    const val IPS_HOST: String = BuildConfig.IPS_HOST
    const val IPS_PORT: Int = BuildConfig.IPS_PORT
    const val DEVICE_UNIQUE_ID: String = BuildConfig.DEVICE_UNIQUE_ID
    // Shared secret (x-hams-key) guarding the n8n manual-claim / release webhooks.
    const val HAMS_CLAIM_SECRET: String = BuildConfig.HAMS_CLAIM_SECRET
    // Office manual-pairing endpoints (n8n): admin keys in the unit id + supervisor OTP.
    const val MANUAL_CLAIM_URL: String = BuildConfig.MANUAL_CLAIM_URL
    const val RELEASE_URL: String = BuildConfig.RELEASE_URL

    const val MAX_COUNT_PER_TASK: Int = 9999

    const val BATCH_SIZE: Int = 10
    const val BATCH_DELAY_MS: Long = 75
    const val MAX_RETRY_ATTEMPTS: Int = 5
    // Bumped 7 → 30 on 2026-05-15 to align with FR-06 ("local storage can hold
    // minimum 30 days of data"). Only affects terminal-state tasks via
    // TaskRepository.purgeStaleTerminalTasks; pending and active tasks are
    // never purged regardless of age.
    const val SQLITE_RETENTION_DAYS: Int = 30

    // Phase 3.3: cap on distinct task ids drained per PushWorker run. Long
    // backlogs split across multiple worker runs so a Wi-Fi flap interrupts at
    // most this many tasks. Cross-run user-facing denominator is preserved by
    // [com.klk.hams.push.PushCampaign].
    const val PUSH_TASK_BATCH_LIMIT: Int = 10

    // Outbound-approved Wialon event_code values for the existing task-event path:
    //   179 = FFB plus / cut
    //   180 = FFB productive minus / correction (push only when work_count > 0)
    //    35 = periodic beacon ("heartbeat" in HAMS code/UI; P99L "Track By Time Interval")
    // These remain the only values EventDao/PushEligibility queue from the events
    // table. Diagnostics telemetry uses its own diagnostics table + TelemetryCode
    // path, where Option B adds per-action outbound codes.
    const val EVENT_CODE_PLUS: Int = 179
    const val EVENT_CODE_MINUS: Int = 180
    const val EVENT_CODE_HEARTBEAT: Int = 35

    // Local/custom HAMS event codes — NOT outbound-approved under v1.2.
    // They may appear in the SQLite events table for audit/UI/state purposes
    // (PushEligibility will mark them pushed=1 at insert time so the push engine
    // never sees them), but they must never reach IPSFrameBuilder. Do not promote
    // any of these to outbound without a matching Wialon report/sensor/notification
    // contract and a coordinated update across AppConfig, IPSFrameBuilder,
    // PushEligibility, EventDao, the dictionary, and the unit tests.
    const val EVENT_CODE_NEW_TASK: Int = 281            // local-only task boundary marker
    const val EVENT_CODE_AUTO_SAVE_KILL: Int = 283      // local-only audit row for auto-save on kill
    const val EVENT_CODE_AUTO_SAVE_WIFI: Int = 284      // local-only audit row for pre-push auto-save
    const val EVENT_CODE_BATTERY_WARN: Int = 291        // local-only edge marker
    const val EVENT_CODE_BATTERY_CRITICAL: Int = 292    // local-only edge marker
    const val EVENT_CODE_GPS_DEGRADED: Int = 293        // local-only diagnostic marker

    // Legacy dev counting codes from the pre-v1.2 design. Retained here only so
    // any historical reference in code/tests still compiles; they are NOT
    // outbound-approved and are NOT used by recordPlus/recordMinus anymore.
    @Deprecated(
        "v1.2 outbound policy uses 179/180 directly. 279/280 are not outbound-approved.",
        ReplaceWith("EVENT_CODE_PLUS")
    )
    const val EVENT_CODE_PLUS_DEV: Int = 279
    @Deprecated(
        "v1.2 outbound policy uses 179/180 directly. 279/280 are not outbound-approved.",
        ReplaceWith("EVENT_CODE_MINUS")
    )
    const val EVENT_CODE_MINUS_DEV: Int = 280

    const val HEARTBEAT_INTERVAL_MINUTES: Int = 1
    const val BATTERY_WARN_THRESHOLD_PCT: Int = 20
    const val BATTERY_CRITICAL_THRESHOLD_PCT: Int = 10
    const val GPS_HDOP_DEGRADED_THRESHOLD: Double = 5.0

    const val PUSH_NEW_TASK_TO_WIALON: Boolean = false
    const val PUSH_MINUS_ONLY_IF_PRODUCTIVE: Boolean = true

    // Continuous GPS stream (Task 2.7.5, revised after field validation 2026-05-06).
    // While a task is active the stream uses PRIORITY_HIGH_ACCURACY at 1 s interval —
    // BALANCED was observed to coalesce updates to >10 s gaps, causing visible flap.
    // While only the foreground reason is held, the stream falls back to BALANCED.
    // Press path reads StateFlow snapshot synchronously; STALENESS_MS gates the +/- buttons.
    const val LOCATION_STREAM_INTERVAL_MS: Long = 1_000
    const val LOCATION_STREAM_FASTEST_MS: Long = 500
    const val LOCATION_STREAM_STALENESS_MS: Long = 10_000

    // Watchdog: if no fix has arrived within WATCHDOG_MS, fire a one-off
    // getCurrentLocation(HIGH_ACCURACY) to wake up a stalled stream.
    const val LOCATION_STREAM_WATCHDOG_CHECK_MS: Long = 3_000
    const val LOCATION_STREAM_WATCHDOG_MS: Long = 6_000

    // GPS lock-state hysteresis (Task 20). Going Stale needs a real gap; returning
    // to Locked is quick. Eliminates pill-color flicker at the staleness boundary.
    const val GPS_LOCK_STALE_AFTER_MS: Long = 8_000
    const val GPS_LOCK_RELOCK_BELOW_MS: Long = 3_000

    // Behaviour telemetry - GPS lost/recovery must be SUSTAINED this long before a
    // diagnostic fires, so intermittent-signal flapping doesn't spam Wialon with
    // lost/recovery pairs. Only genuine, held losses/recoveries are reported.
    const val GPS_EVENT_DWELL_MS: Long = 20_000

    // Behaviour telemetry: motion state detector (diagnostics telemetry push, 2026-07-02).
    const val MOTION_START_SPEED_KMH: Double = 1.5
    const val MOTION_STOP_SPEED_KMH: Double = 0.5
    const val MOTION_DEBOUNCE_MS: Long = 15_000

    // Manual push session config (Task 2.8 spec).
    const val PUSH_MANUAL_TIMEOUT_MS: Long = 30L * 60_000L

    /**
     * Backoff schedule between retry attempts during a single push session.
     * Applied while inside the manual-mode 30-minute budget; auto-mode uses
     * WorkManager's own backoff (handled by Result.retry()).
     */
    val PUSH_RETRY_BACKOFF_MS: List<Long> = listOf(10_000L, 30_000L, 60_000L, 120_000L)
}
