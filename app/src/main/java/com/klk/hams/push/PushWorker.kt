package com.klk.hams.push

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.klk.hams.AppConfig
import com.klk.hams.HamsApp
import com.klk.hams.diagnostics.DiagnosticType
import com.klk.hams.provisioning.BindingDecision
import com.klk.hams.provisioning.BindingRevalidator
import com.klk.hams.provisioning.ProvisioningClient
import com.klk.hams.provisioning.ProvisioningStore

/**
 * WorkManager worker that runs [PushEngine.run] on a background thread,
 * with foreground-service notification for visibility while pushing.
 *
 * Result mapping:
 *   - [PushState.Success] / [PushState.Partial] -> [Result.success]
 *   - [PushState.Failed] -> [Result.retry] (WorkManager handles backoff)
 *   - Any thrown exception -> [Result.retry]
 *
 * The engine is built fresh per worker run to avoid stale singletons across
 * worker process restarts.
 */
class PushWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Defensive: worker may fire in a process where HamsApp.onCreate has
        // not yet created the push channel. ensureChannel is idempotent.
        PushNotifier.ensureChannel(applicationContext)

        val provisioningStore = ProvisioningStore.fromContext(applicationContext)
        if (!provisioningStore.isProvisioned()) {
            Log.d(TAG, "doWork: device is not provisioned; skipping push")
            return Result.success(workDataOf("tasks" to 0))
        }

        pushInProgress = true
        return try {
        val app = applicationContext as HamsApp
        val repo = app.repository
        val revalidator = app.bindingRevalidator
        var releasedFlush = false
        var releasedRowId: Long? = null

        val fingerprint = ProvisioningStore.deviceFingerprint(applicationContext)
        if (fingerprint != null) {
            val uniqueId = provisioningStore.resolveUniqueId()
            when (BindingRevalidator.decide(ProvisioningClient().verify(uniqueId, fingerprint))) {
                BindingDecision.PROCEED -> Unit
                BindingDecision.RELEASED_FLUSH -> {
                    repo.finalizeActiveTaskForRelease()
                    releasedRowId = revalidator.recordBinding(DiagnosticType.BINDING_RELEASED, pushed = 0)
                    releasedFlush = true
                }
                BindingDecision.BOUND_OTHER -> {
                    revalidator.recordBinding(DiagnosticType.BINDING_TAKEN, pushed = 1)
                    revalidator.revoke(BindingRevalidator.REVOCATION_MESSAGE)
                    Log.d(TAG, "doWork: binding taken by another device; logging out, no push")
                    return Result.success(workDataOf("tasks" to 0))
                }
            }
        }

        val pendingTaskIdsBefore = repo.pendingTasks().map { it.id }
        val pendingBeforeCount = pendingTaskIdsBefore.size
        val hasTelemetry = repo.countPendingTelemetry() > 0

        // Phase 3.3 — cross-run campaign tracker. Stable denominator across
        // multiple PushWorker runs while [PushEngine.taskBatchLimit] caps each
        // run to N tasks. Clear stale campaign state if no work exists.
        val campaign = PushCampaign.fromContext(applicationContext)

        if (pendingBeforeCount == 0 && !hasTelemetry) {
            Log.d(TAG, "doWork: no pending tasks or telemetry, returning success(0)")
            campaign.clear()
            return Result.success(workDataOf("tasks" to 0))
        }

        val provisionedId = provisioningStore.resolveUniqueId()
        val senderFactory = { WialonIPSClient(uniqueId = provisionedId) }

        if (pendingBeforeCount == 0) {
            Log.d(TAG, "doWork: no pending tasks; draining telemetry only")
            campaign.clear()
            return try {
                val telemetryState = TelemetryPushEngine(
                    repo = repo,
                    senderFactory = senderFactory,
                ).run()
                Log.d(TAG, "doWork: telemetry drain -> $telemetryState")
                if (BindingRevalidator.shouldRevokeAfterFlush(
                        releasedFlush,
                        releasedRowId?.let { repo.diagnosticPushedState(it) },
                    )
                ) {
                    revalidator.revoke(BindingRevalidator.REVOCATION_MESSAGE)
                    Log.d(TAG, "doWork: released binding flushed; logging out")
                }
                when (telemetryState) {
                    is PushState.Success -> Result.success(workDataOf("tasks" to 0))
                    is PushState.Partial -> Result.success(workDataOf("tasks" to 0))
                    is PushState.Failed -> Result.retry()
                    else -> Result.retry()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "doWork: telemetry drain failed: $t - retrying via WorkManager backoff", t)
                Result.retry()
            }
        }

        // Sanity check on resume: if pending exceeds what the campaign expects
        // (force-stop / new tasks since last run), re-snap to current reality.
        campaign.resetIfStale(pendingBeforeCount)
        // No-op when a campaign is already active — original total wins so the
        // user-visible "X of 43" denominator stays anchored.
        campaign.start(pendingBeforeCount)

        // Promote to foreground BEFORE long work so the OS doesn't kill us at the
        // 10s expedited deadline. Android 14+ rejects FGS type=NONE, so declare
        // dataSync explicitly (matches FOREGROUND_SERVICE_DATA_SYNC permission in manifest).
        // Initial progress uses campaign numbers so subsequent runs resume at
        // "Uploading task 11 of 43" rather than restarting at 1.
        val (initDone, initTotal) = campaign.snapshot() ?: (0 to pendingBeforeCount)
        val pushingInitial = PushUiState.Pushing(total = initTotal, done = initDone)
        val initialContent = PushNotifier.contentFor(pushingInitial)!!
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        val serviceAlive = com.klk.hams.service.HamsForegroundService.isRunning
        if (!serviceAlive) {
            setForeground(
                ForegroundInfo(
                    PushNotifier.NOTIFICATION_ID,
                    PushNotifier.build(applicationContext, initialContent),
                    fgsType
                )
            )
        }

        // Constructor parameter names verified against PushEngine.kt:73-84 and
        // WialonIPSClient.kt:29-36 (see docs/superpowers/specs/2026-05-08-push-integration-checklist.md §3).
        // onProgress fires once per fully-drained task (engine guarantee since
        // 2026-05-13). Each callback increments the campaign and re-renders the
        // foreground notification + publishes WorkInfo progress so the user
        // sees campaign-cumulative "X of 43" rather than per-run "X of 10".
        val engine = PushEngine(
            repo = PushRepositoryImpl(repo),
            senderFactory = senderFactory,
            taskBatchLimit = AppConfig.PUSH_TASK_BATCH_LIMIT,
            chunkSize = AppConfig.BATCH_SIZE,
            interMessageDelayMs = AppConfig.BATCH_DELAY_MS,
            maxAttempts = AppConfig.MAX_RETRY_ATTEMPTS,
            onProgress = { _, _ ->
                campaign.recordCompletion()
                val (done, total) = campaign.snapshot() ?: (0 to initTotal)
                val progressContent = PushNotifier.contentFor(
                    PushUiState.Pushing(total = total, done = done)
                )
                if (!serviceAlive && progressContent != null) {
                    setForeground(
                        ForegroundInfo(
                            PushNotifier.NOTIFICATION_ID,
                            PushNotifier.build(applicationContext, progressContent),
                            fgsType
                        )
                    )
                }
                // Publish progress for PushController's in-app overlay.
                setProgress(
                    workDataOf(
                        PushController.PROGRESS_DONE_KEY to done,
                        PushController.PROGRESS_TOTAL_KEY to total
                    )
                )
            }
        )

        return try {
            val result = engine.run()
            Log.d(TAG, "doWork: engine returned $result")

            // Per-task push_status sweep.
            for (taskId in pendingTaskIdsBefore) {
                repo.markTaskTerminalState(taskId)
            }

            // User-visible outcome uses campaign numbers (cumulative across
            // runs), not the per-run slice. Terminal phrasing matches the
            // user's mental model: "X of 43 done · Y remaining".
            val stillPendingIds = repo.pendingTasks().map { it.id }.toSet()
            val tasksUploadedThisRun = pendingTaskIdsBefore.count { it !in stillPendingIds }
            val pendingAfter = stillPendingIds.size

            val (campDone, campTotal) = campaign.snapshot() ?: (tasksUploadedThisRun to pendingBeforeCount)
            val campRemaining = (campTotal - campDone).coerceAtLeast(0)

            val terminalContent = when (result) {
                is PushState.Success -> {
                    if (pendingAfter == 0) {
                        // Outcome A: campaign fully drained. Clear and ring once.
                        campaign.clear()
                        PushNotifier.terminalAllClean(campTotal)
                    } else {
                        // Outcome B: this run drained its 10-task slice cleanly
                        // but more remain. HamsApp's completion observer will
                        // self-enqueue the next worker run.
                        PushNotifier.terminalPartialRetry(
                            tasksUploaded = campDone,
                            tasksTotal = campTotal,
                            tasksRemaining = campRemaining,
                        )
                    }
                }
                is PushState.Partial -> {
                    // Outcome D: server rejected some events. Affected tasks
                    // stay pending forever (next fetch skips pushed = 2 rows).
                    PushNotifier.terminalRejected(
                        tasksUploaded = campDone,
                        tasksTotal = campTotal,
                        tasksRejected = campRemaining,
                    )
                }
                is PushState.Failed -> {
                    if (campDone > 0) {
                        // Outcome B: some tasks already drained in earlier runs
                        // or earlier in this run; transport halted.
                        PushNotifier.terminalPartialRetry(
                            tasksUploaded = campDone,
                            tasksTotal = campTotal,
                            tasksRemaining = campRemaining,
                        )
                    } else {
                        // Outcome C: nothing in the campaign got through.
                        PushNotifier.terminalAllPaused(campRemaining)
                    }
                }
                else -> null
            }
            // Post the terminal notification at NOTIFICATION_ID_TERMINAL (a
            // separate id from the FGS one, 2026-05-15). If we reused
            // NOTIFICATION_ID, WorkManager's stopForeground(REMOVE) when this
            // worker returns would wipe the banner within milliseconds.
            // The brief co-existence with the in-flight FGS notification is
            // intentional — Android collapses them into one channel slot and
            // the FGS one disappears as soon as Result.success is returned.
            terminalContent?.let { c ->
                try {
                    NotificationManagerCompat.from(applicationContext)
                        .notify(PushNotifier.NOTIFICATION_ID_TERMINAL, PushNotifier.build(applicationContext, c))
                } catch (se: SecurityException) {
                    // POST_NOTIFICATIONS not granted on Android 13+. Silent fail.
                    Log.w(TAG, "terminal notification suppressed: $se")
                }
            }

            val telemetryState = TelemetryPushEngine(
                repo = repo,
                senderFactory = senderFactory,
            ).run()
            Log.d(TAG, "doWork: telemetry drain -> $telemetryState")
            if (BindingRevalidator.shouldRevokeAfterFlush(
                    releasedFlush,
                    releasedRowId?.let { repo.diagnosticPushedState(it) },
                )
            ) {
                revalidator.revoke(BindingRevalidator.REVOCATION_MESSAGE)
                Log.d(TAG, "doWork: released binding flushed; logging out")
            }

            when (result) {
                is PushState.Success -> Result.success(workDataOf("tasks" to tasksUploadedThisRun))
                is PushState.Partial -> Result.success(workDataOf("tasks" to tasksUploadedThisRun))
                is PushState.Failed -> Result.retry()
                else -> Result.retry()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "doWork: $t — retrying via WorkManager backoff", t)
            Result.retry()
        }
        } finally {
            pushInProgress = false
        }
    }

    companion object {
        @Volatile
        @JvmField
        var pushInProgress: Boolean = false

        private const val TAG = "HAMS_PUSH"
    }
}
