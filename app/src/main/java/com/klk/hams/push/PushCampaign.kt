package com.klk.hams.push

import android.content.Context
import android.content.SharedPreferences

/**
 * Cross-run progress tracker for the Phase 3.3 task-cap design.
 *
 * Background — the Phase 2.8 push worker drains all pending events in one
 * `PushWorker` run. Task 3.3a added a per-run cap on the number of distinct
 * task ids drained ([PushEngine.taskBatchLimit]), so a long backlog now
 * splits across multiple `PushWorker` runs. Without cross-run memory, the
 * notification denominator would reset each run ("1 of 33" instead of
 * "11 of 43") and the user would lose the sense of total progress.
 *
 * `PushCampaign` keeps two ints — `done` and `total` — persisted across
 * worker runs. The state machine is:
 *
 *   - **Idle**     ([snapshot] returns null) — no campaign in progress.
 *   - **Active**   ([snapshot] returns (done, total)) — a backlog is being
 *                  drained. `total` was locked in at first observation and
 *                  does NOT grow when new tasks finalize mid-campaign; the
 *                  user-visible promise stays honest ("you're X of 43 done").
 *
 * Lifecycle:
 *
 *   - `start(total)` transitions Idle → Active with `done = 0`. Calling
 *     `start` while Active is a no-op (the original total wins).
 *   - `recordCompletion()` increments `done`. Capped at `total` so the
 *     notification never shows `44 / 43`.
 *   - `clear()` transitions back to Idle. Called when pending reaches 0.
 *   - `resetIfStale(currentPending)` is a sanity hook: if pending now exceeds
 *     `total - done` (e.g. force-stop + new tasks accumulated), we re-snap
 *     the campaign so the denominator reflects current reality.
 *
 * Storage is injected via [CampaignStore] so the state machine is JVM-testable
 * without Android. Production wires [SharedPreferencesCampaignStore].
 */
class PushCampaign(private val store: CampaignStore) {

    /** Backing storage for two ints (`done`, `total`) or absent. */
    interface CampaignStore {
        /** Returns `(done, total)` if a campaign is active, else `null`. */
        fun load(): Pair<Int, Int>?
        fun save(done: Int, total: Int)
        fun clear()
    }

    /** Returns the current `(done, total)` or `null` if no campaign is active. */
    fun snapshot(): Pair<Int, Int>? = store.load()

    /**
     * Starts a new campaign with the given total. No-op if a campaign is
     * already active — the original total is preserved so cumulative progress
     * stays anchored to the user's first-seen denominator.
     */
    fun start(total: Int) {
        require(total > 0) { "campaign total must be positive, got $total" }
        if (store.load() != null) return
        store.save(done = 0, total = total)
    }

    /**
     * Increments `done` by one. No-op if no campaign is active. Capped at
     * `total` to prevent overshoot if a task completes after a stale-reset.
     */
    fun recordCompletion() {
        val current = store.load() ?: return
        val (done, total) = current
        val next = (done + 1).coerceAtMost(total)
        store.save(done = next, total = total)
    }

    /** Clears the campaign back to Idle. Called when pending reaches 0. */
    fun clear() {
        store.clear()
    }

    /**
     * Sanity check before reusing an existing campaign. If `currentPending`
     * (the number of pending tasks observed at the start of a new run) is
     * larger than the remaining work the active campaign accounts for
     * (`total - done`), the campaign is stale — likely from a force-stop /
     * day-rollover during which new tasks accumulated. Resets and re-snaps
     * with `currentPending` as the new total.
     *
     * Returns `true` if a reset happened (caller should treat as a fresh
     * campaign start), `false` otherwise.
     */
    fun resetIfStale(currentPending: Int): Boolean {
        val current = store.load() ?: return false
        val (done, total) = current
        val expectedRemaining = total - done
        if (currentPending > expectedRemaining) {
            store.clear()
            if (currentPending > 0) store.save(done = 0, total = currentPending)
            return true
        }
        return false
    }

    companion object {
        const val PREFS_NAME: String = "hams_push_campaign"
        private const val KEY_DONE: String = "done"
        private const val KEY_TOTAL: String = "total"

        /** Convenience: produce a SharedPreferences-backed campaign for prod. */
        fun fromContext(context: Context): PushCampaign =
            PushCampaign(SharedPreferencesCampaignStore(context))
    }

    /**
     * SharedPreferences-backed [CampaignStore]. Two ints, no migration. A
     * cleared campaign removes both keys so `load()` returns `null` cleanly.
     */
    class SharedPreferencesCampaignStore(context: Context) : CampaignStore {
        private val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        override fun load(): Pair<Int, Int>? {
            if (!prefs.contains(KEY_TOTAL)) return null
            val done = prefs.getInt(KEY_DONE, 0)
            val total = prefs.getInt(KEY_TOTAL, 0)
            if (total <= 0) return null
            return done to total
        }

        override fun save(done: Int, total: Int) {
            prefs.edit()
                .putInt(KEY_DONE, done)
                .putInt(KEY_TOTAL, total)
                .apply()
        }

        override fun clear() {
            prefs.edit()
                .remove(KEY_DONE)
                .remove(KEY_TOTAL)
                .apply()
        }
    }
}
