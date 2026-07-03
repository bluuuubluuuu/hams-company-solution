package com.klk.hams.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.klk.hams.AppConfig
import com.klk.hams.data.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * App-scope owner of the push pipeline state.
 *
 * Aggregates three sources into [uiStateFlow]:
 *   1. WorkManager's `WorkInfo` for the unique-named push work
 *   2. The pending-task count from the repository
 *   3. The completion timestamp (controller-local; reset on the next enqueue)
 *
 * Pure mapping logic lives in [mapToUiState] for unit testability.
 *
 * Cooperation contract per spec §17 (binding):
 *   - [enqueueAuto] uses [ExistingWorkPolicy.KEEP] (rule #1).
 *   - [triggerManual] skips the WorkManager enqueue when state is
 *     [PushUiState.Pushing] and only flips the manual flag (rule #2).
 *   - [dismissManualOverlay] never calls [WorkManager.cancelUniqueWork];
 *     it only clears the flag and cancels the budget timer (rule #3).
 *   - A 30-min UI-side budget timer auto-clears the flag (§17.7).
 */
class PushController(
    context: Context,
    private val repository: TaskRepository
) {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _completedAt = MutableStateFlow<Instant?>(null)
    private val _manualPushActive = MutableStateFlow(false)
    private var manualBudgetJob: Job? = null

    // User-facing "pending" = task/cut backlog ONLY. Telemetry (boot/screen/gps/...)
    // must NOT inflate the corner badge or the "waiting for Wi-Fi" count. Telemetry
    // still gets pushed - its triggers (HamsApp recovery's direct countPendingTelemetry,
    // WifiPushMonitor, PushWorker's hasTelemetry drain) do not depend on this flow.
    val pendingCountFlow: StateFlow<Int> =
        repository.observePendingTaskCount().stateIn(scope, SharingStarted.Eagerly, 0)

    val uiStateFlow: StateFlow<PushUiState> = combine(
        workInfoFlow(),
        pendingCountFlow,
        _completedAt
    ) { info, pending, completedAt ->
        mapToUiState(info, pending, completedAt)
    }.stateIn(scope, SharingStarted.Eagerly, PushUiState.Idle)

    val manualPushActive: StateFlow<Boolean> = _manualPushActive.asStateFlow()

    fun enqueueAuto() {
        val request = OneTimeWorkRequestBuilder<PushWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .addTag(TAG_AUTO)
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Spec §17.2 (rule #2): if the worker is currently [PushUiState.Pushing],
     * manual must NOT enqueue (would REPLACE a healthy in-flight upload).
     * It only attaches the overlay by flipping [manualPushActive] to true.
     * In all other states, enqueue with REPLACE.
     */
    fun triggerManual() {
        val current = uiStateFlow.value
        if (shouldEnqueueManual(current)) {
            val request = OneTimeWorkRequestBuilder<PushWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build()
                )
                .addTag(TAG_MANUAL)
                .build()
            workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
        _manualPushActive.value = true

        // §17.7 — 30-min UI-side budget timer; worker is unaffected.
        manualBudgetJob?.cancel()
        manualBudgetJob = launchManualBudgetTimer(
            scope = scope,
            flag = _manualPushActive,
            timeoutMs = AppConfig.PUSH_MANUAL_TIMEOUT_MS
        )
    }

    /**
     * Spec §17.3 (rule #3): UI-only dismiss. Does NOT cancel the worker.
     * Pending data continues to drain.
     */
    fun dismissManualOverlay() {
        dismissOverlayLogic(_manualPushActive, manualBudgetJob)
        manualBudgetJob = null
    }

    fun acknowledgeCompletion() {
        _completedAt.value = null
        // Push-reliability upgrade (2026-05-22) phase 4: the in-app "OK" also
        // clears the terminal notification banner so the worker dismisses the
        // outcome once, in one place.
        runCatching {
            androidx.core.app.NotificationManagerCompat
                .from(appContext)
                .cancel(com.klk.hams.push.PushNotifier.NOTIFICATION_ID_TERMINAL)
        }
    }

    private fun workInfoFlow(): kotlinx.coroutines.flow.Flow<WorkInfo?> =
        callbackFlow {
            // Simple fallback: poll every 1s. (Live observation via LiveData -> Flow
            // adapter requires lifecycle owner; for a singleton controller we keep
            // this minimal. Production migration to androidx-lifecycle-livedata-ktx
            // can use asFlow() if needed.)
            val job = launch {
                while (isActive) {
                    val infos = workManager.getWorkInfosForUniqueWork(WORK_NAME).get()
                    val latest = infos.maxByOrNull { it.runAttemptCount }
                    trySend(latest)
                    if (latest?.state == WorkInfo.State.SUCCEEDED) {
                        _completedAt.value = Instant.now()
                    }
                    delay(1_000)
                }
            }
            awaitClose { job.cancel() }
        }

    companion object {
        const val WORK_NAME = "hams-push"
        const val TAG_AUTO = "hams-push-auto"
        const val TAG_MANUAL = "hams-push-manual"
        const val PROGRESS_DONE_KEY = "done"
        const val PROGRESS_TOTAL_KEY = "total"

        /** Pure state-mapping function, no Android dependencies. Testable. */
        fun mapToUiState(
            workInfo: WorkInfo?,
            pendingCount: Int,
            completedAt: Instant?
        ): PushUiState = when {
            workInfo == null && pendingCount == 0 -> PushUiState.Idle
            workInfo == null -> PushUiState.PendingWifi(pendingCount)
            workInfo.state == WorkInfo.State.RUNNING -> {
                val total = workInfo.progress.getInt(PROGRESS_TOTAL_KEY, 0)
                val done = workInfo.progress.getInt(PROGRESS_DONE_KEY, 0)
                if (total > 0) PushUiState.Pushing(total, done) else PushUiState.PendingWifi(pendingCount)
            }
            workInfo.state == WorkInfo.State.SUCCEEDED -> {
                val tasks = workInfo.outputData.getInt("tasks", 0)
                PushUiState.Completed(tasks = tasks, at = completedAt ?: Instant.EPOCH)
            }
            workInfo.state == WorkInfo.State.FAILED ->
                PushUiState.Failed(reason = "see logs", pending = pendingCount)
            else -> PushUiState.PendingWifi(pendingCount)
        }

        /**
         * Pure decision helper for spec §17.2 (rule #2). Returns true if
         * `triggerManual` should enqueue a fresh REPLACE worker; false if it
         * should only flip the overlay flag.
         */
        fun shouldEnqueueManual(currentState: PushUiState): Boolean =
            currentState !is PushUiState.Pushing

        /**
         * Pure side-effect helper for spec §17.3 (rule #3). Signature
         * deliberately excludes [WorkManager] so the worker is mechanically
         * unreachable from this code path.
         */
        fun dismissOverlayLogic(flag: MutableStateFlow<Boolean>, job: Job?) {
            flag.value = false
            job?.cancel()
        }

        /**
         * Pure factory for the §17.7 30-min UI-side budget timer. Returns the
         * launched [Job] so the caller can cancel it on dismiss / re-trigger.
         */
        fun launchManualBudgetTimer(
            scope: CoroutineScope,
            flag: MutableStateFlow<Boolean>,
            timeoutMs: Long
        ): Job = scope.launch {
            delay(timeoutMs)
            if (flag.value) {
                flag.value = false
            }
        }
    }
}
