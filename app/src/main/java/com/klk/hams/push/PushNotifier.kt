package com.klk.hams.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.klk.hams.MainActivity

/**
 * Builds notifications for push state transitions.
 *
 * Pure builder logic ([contentFor], [terminalContent]) is separated from the
 * Android Notification API ([build]) so the text and persistence rules can be
 * unit-tested without an instrumented harness.
 *
 * Sound rule (2026-05-13 field feedback): progress updates are silent; only
 * the single terminal notification at the end of a worker run alerts.
 */
object PushNotifier {

    const val CHANNEL_ID = "hams_push_channel"

    /**
     * In-flight FGS notification id. Used with `setForeground(ForegroundInfo)`
     * so WorkManager can attach the worker's foreground-service notification.
     * WorkManager will REMOVE the notification at this id when the worker
     * returns (`stopForeground(STOP_FOREGROUND_REMOVE)`), so terminals MUST
     * be posted at a different id — see [NOTIFICATION_ID_TERMINAL].
     */
    const val NOTIFICATION_ID = 2001

    /**
     * Terminal-outcome notification id (2026-05-15). Distinct from the FGS
     * id so WorkManager's automatic teardown does not wipe the
     * "5 tasks uploaded ✓" banner moments after we post it. PushWorker uses
     * this id for the final `notify()` call after `engine.run()` returns.
     */
    const val NOTIFICATION_ID_TERMINAL = 2002

    /**
     * Notification content + display rules.
     *
     * @property title  full notification title text
     * @property persistent  true → `setOngoing(true)` (foreground / sticky);
     *                       false → `setAutoCancel(true)` (user can swipe)
     * @property progressDone / progressTotal  drives the progress bar when
     *                                          total > 0; for the new
     *                                          task-level progress, these are
     *                                          task counts, not events.
     * @property silent  true → `setSilent(true)` so the notification posts
     *                   without sound/vibration/heads-up (used for in-flight
     *                   progress updates so only the terminal notification rings).
     * @property timeoutMs  if non-null, the system auto-cancels after this
     *                      many ms (`setTimeoutAfter`). Used for the "all
     *                      clean" terminal which fades after 8 s.
     */
    data class Content(
        val title: String,
        val persistent: Boolean,
        val progressDone: Int = 0,
        val progressTotal: Int = 0,
        val silent: Boolean = false,
        val timeoutMs: Long? = null,
    )

    /**
     * Map a controller-level `PushUiState` to notification content.
     * Used for in-flight progress only — terminal notifications are built
     * directly by [PushWorker] via [terminalContent] because they need
     * richer information (rejected count, task tally) than `PushUiState`
     * carries.
     */
    fun contentFor(state: PushUiState): Content? = when (state) {
        is PushUiState.Idle -> null
        is PushUiState.PendingWifi -> null  // silent — in-app chip is the only signal
        is PushUiState.Pushing -> {
            // Note: total/done now mean tasks, not events (2026-05-13 change).
            // "Uploading task X of Y" — X is the task currently being processed
            // (= done + 1, capped at total).
            //
            // Visibility tweak (2026-05-15 field feedback): the first progress
            // post per worker run alerts (heads-up) so the worker notices the
            // push has started; subsequent ticks are silent so the climb
            // doesn't flood the shade. `done > 0` flips us into silent mode
            // after the first task drains.
            val current = (state.done + 1).coerceAtMost(state.total.coerceAtLeast(1))
            Content(
                title = "HAMS — Uploading task $current of ${state.total}",
                persistent = true,
                progressDone = state.done,
                progressTotal = state.total,
                silent = state.done > 0,
            )
        }
        is PushUiState.Completed -> terminalAllClean(state.tasks)
        is PushUiState.Failed -> Content(
            title = "HAMS — Upload paused: ${state.reason} (${state.pending} left)",
            persistent = true,
            silent = false,
        )
    }

    // Terminal-notification policy (2026-05-15, second iteration after field
    // feedback): all four outcomes — A clean / B partial-retry / C all-paused
    // / D rejected — are now posted with the same render rules:
    //   persistent = false   → setOngoing(false), user can swipe to clear
    //   timeoutMs  = null    → no auto-dismiss; banner stays until cleared
    //   silent     = false   → rings once when posted
    // Same model as a WhatsApp / Gmail message: ring on arrival, sit in the
    // shade until the user actively clears it. The previous mixed model
    // (A auto-dismiss; B/C/D ongoing/non-swipeable) hid outcomes during real
    // shift use — workers missed A's checkmark and couldn't clear B/C/D
    // banners themselves.

    /** Outcome A: all tasks uploaded successfully. Rings once, stays until cleared. */
    fun terminalAllClean(tasksUploaded: Int): Content = Content(
        title = "HAMS — $tasksUploaded tasks uploaded successfully ✓",
        persistent = false,
        silent = false,
    )

    /**
     * Outcome B: some tasks uploaded, others still pending due to a transport
     * failure that WorkManager will retry. Rings once, stays until cleared.
     * If the next worker run starts before the user clears, the in-flight
     * Pushing notification replaces this content at the same NOTIFICATION_ID.
     */
    fun terminalPartialRetry(tasksUploaded: Int, tasksTotal: Int, tasksRemaining: Int): Content = Content(
        title = "HAMS — $tasksUploaded of $tasksTotal tasks uploaded · $tasksRemaining remaining · will retry on Wi-Fi",
        persistent = false,
        silent = false,
    )

    /** Outcome C: transport / login failed before any task could complete. */
    fun terminalAllPaused(tasksUnsent: Int): Content = Content(
        title = "HAMS — Upload paused · $tasksUnsent tasks unsent · will retry on Wi-Fi",
        persistent = false,
        silent = false,
    )

    /**
     * Outcome D: server rejected some events (frame-format or device-id error).
     * Those tasks are stuck (`push_status='failed'`) — they need supervisor
     * attention. Rings once, stays until cleared.
     */
    fun terminalRejected(tasksUploaded: Int, tasksTotal: Int, tasksRejected: Int): Content = Content(
        title = "HAMS — $tasksUploaded of $tasksTotal tasks uploaded · $tasksRejected rejected by server — check device setup",
        persistent = false,
        silent = false,
    )

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HAMS push status",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Wialon push progress and completion"
            }
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    fun build(context: Context, content: Content): Notification {
        val tap = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(content.title)
            .setContentIntent(tap)
            .setOngoing(content.persistent)
            .setAutoCancel(!content.persistent)
            .setSilent(content.silent)
        if (content.progressTotal > 0) {
            builder.setProgress(content.progressTotal, content.progressDone, false)
        }
        content.timeoutMs?.let { builder.setTimeoutAfter(it) }
        return builder.build()
    }
}
