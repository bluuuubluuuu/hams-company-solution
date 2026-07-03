package com.klk.hams.diagnostics

import android.content.Context

/**
 * Guarantees exactly one shutdown marker (event_code 40) per off-cycle.
 *
 * `ACTION_SHUTDOWN` is best-effort on Android — battery death, force power-off,
 * and fast power-off skip it or kill the process before the DB write flushes, so
 * a real power-off can leave no `40` row (device-verified). This tracker infers
 * the missed shutdown on the next boot:
 *
 *  1. The service marks the session `running` when it starts, and periodically
 *     stamps `last_seen` (a liveness wall-clock, the backfill date).
 *  2. On a clean `ACTION_SHUTDOWN` the receiver records the real `40` and clears
 *     `running` — nothing to backfill.
 *  3. On boot, if `running` is still set, the previous session died without a
 *     clean shutdown, so a backfilled `40` is emitted dated at `last_seen`.
 *
 * Net: one `40` per off-cycle — the real one when the OS cooperates, or a
 * backfilled one on the next boot when it doesn't — always paired with the `29`.
 */
object ShutdownTracker {
    const val PREFS = "hams_shutdown"
    const val KEY_SESSION_RUNNING = "session_running"
    const val KEY_LAST_SEEN = "last_seen_iso"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Service is up — mark the session live (not yet cleanly shut down). */
    fun markSessionStarted(context: Context) {
        prefs(context).edit().putBoolean(KEY_SESSION_RUNNING, true).commit()
    }

    /** Periodic liveness stamp — the date a backfilled `40` is attributed to. */
    fun updateLastSeen(context: Context, nowIso: String) {
        prefs(context).edit().putString(KEY_LAST_SEEN, nowIso).apply()
    }

    /**
     * Clean shutdown observed (`ACTION_SHUTDOWN`): the real `40` was recorded, so
     * consume the running flag — the next boot must NOT backfill.
     */
    fun markCleanShutdown(context: Context) {
        prefs(context).edit().putBoolean(KEY_SESSION_RUNNING, false).commit()
    }

    /**
     * Called once per genuine boot (from [BootReceiver.recordBootIfNew], inside
     * its phantom-boot guard so it runs exactly once per boot session). Returns
     * the timestamp to backfill a shutdown at, or null if the previous session
     * shut down cleanly. Consumes the running flag either way, making it
     * idempotent against duplicate boot broadcasts.
     */
    fun consumeBackfillOnBoot(context: Context): String? {
        val p = prefs(context)
        val running = p.getBoolean(KEY_SESSION_RUNNING, false)
        val lastSeen = p.getString(KEY_LAST_SEEN, null)
        p.edit().putBoolean(KEY_SESSION_RUNNING, false).commit()
        return backfillTimestamp(running, lastSeen)
    }

    /**
     * Pure decision (unit-tested): backfill only when the previous session was
     * still marked running AND we have a last-seen date to attribute it to.
     */
    internal fun backfillTimestamp(sessionRunning: Boolean, lastSeenIso: String?): String? =
        if (sessionRunning && lastSeenIso != null) lastSeenIso else null
}
