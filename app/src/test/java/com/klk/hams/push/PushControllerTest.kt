package com.klk.hams.push

import androidx.work.WorkInfo
import com.klk.hams.AppConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class PushControllerTest {

    private fun fakeWorkInfo(
        state: WorkInfo.State,
        progressDone: Int = 0,
        progressTotal: Int = 0
    ): WorkInfo {
        val data = androidx.work.Data.Builder()
            .putInt("done", progressDone)
            .putInt("total", progressTotal)
            .build()
        return WorkInfo(
            UUID.randomUUID(),
            state,
            emptySet(),
            androidx.work.Data.EMPTY,
            data,
            0,
            0
        )
    }

    // -- Original 6 mapping tests (plan Task 8 Step 1) --

    @Test fun noWork_zeroPending_isIdle() {
        val mapped = PushController.mapToUiState(workInfo = null, pendingCount = 0, completedAt = null)
        assertEquals(PushUiState.Idle, mapped)
    }

    @Test fun noWork_pendingExist_isPendingWifi() {
        val mapped = PushController.mapToUiState(workInfo = null, pendingCount = 5, completedAt = null)
        assertEquals(PushUiState.PendingWifi(5), mapped)
    }

    @Test fun enqueued_isPendingWifi() {
        val mapped = PushController.mapToUiState(
            workInfo = fakeWorkInfo(WorkInfo.State.ENQUEUED),
            pendingCount = 3,
            completedAt = null
        )
        assertEquals(PushUiState.PendingWifi(3), mapped)
    }

    @Test fun running_withProgress_isPushing() {
        val mapped = PushController.mapToUiState(
            workInfo = fakeWorkInfo(WorkInfo.State.RUNNING, progressDone = 4, progressTotal = 10),
            pendingCount = 6,
            completedAt = null
        )
        assertEquals(PushUiState.Pushing(total = 10, done = 4), mapped)
    }

    @Test fun succeeded_recentlyCompleted_isCompleted() {
        val now = Instant.now()
        val mapped = PushController.mapToUiState(
            workInfo = fakeWorkInfo(WorkInfo.State.SUCCEEDED),
            pendingCount = 0,
            completedAt = now
        )
        assertTrue(mapped is PushUiState.Completed)
    }

    @Test fun failed_isFailed() {
        val mapped = PushController.mapToUiState(
            workInfo = fakeWorkInfo(WorkInfo.State.FAILED),
            pendingCount = 2,
            completedAt = null
        )
        val expected = PushUiState.Failed(reason = "see logs", pending = 2)
        assertEquals(expected, mapped)
    }

    // -- Amendment tests (spec §17) --

    /**
     * Rule #2 (§17.2): manual must skip the WorkManager enqueue when the
     * controller's current state is [PushUiState.Pushing], and otherwise enqueue.
     * Tested via the pure decision helper [PushController.shouldEnqueueManual].
     */
    @Test fun triggerManual_whilePushing_doesNotEnqueueAndFlipsFlagOnly() {
        // Pushing -> skip enqueue
        assertFalse(PushController.shouldEnqueueManual(PushUiState.Pushing(total = 10, done = 4)))
        // All other states -> enqueue
        assertTrue(PushController.shouldEnqueueManual(PushUiState.Idle))
        assertTrue(PushController.shouldEnqueueManual(PushUiState.PendingWifi(3)))
        assertTrue(PushController.shouldEnqueueManual(PushUiState.Failed("x", 1)))
        assertTrue(PushController.shouldEnqueueManual(PushUiState.Completed(1, Instant.EPOCH)))
    }

    /**
     * Rule #3 (§17.3): dismissManualOverlay must NOT call cancelUniqueWork.
     * The pure helper [PushController.dismissOverlayLogic] takes only the flag
     * and the budget job — its signature mechanically excludes WorkManager
     * access, and we assert it clears flag + cancels the job.
     */
    @Test fun dismissManualOverlay_doesNotCancelWorker() {
        val flag = MutableStateFlow(true)
        val job: Job = Job().apply { /* live */ }
        PushController.dismissOverlayLogic(flag, job)
        assertFalse(flag.value)
        assertTrue(job.isCancelled)
    }

    /**
     * §17.7: a 30-min UI-side budget timer flips manualPushActive to false
     * after [AppConfig.PUSH_MANUAL_TIMEOUT_MS] elapses. Worker is unaffected.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun manualBudgetTimer_clearsFlagAfterTimeout() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val flag = MutableStateFlow(true)

        val budgetJob = PushController.launchManualBudgetTimer(
            scope = scope,
            flag = flag,
            timeoutMs = AppConfig.PUSH_MANUAL_TIMEOUT_MS
        )
        assertNotNull(budgetJob)

        // Just before timeout: flag still true.
        scope.advanceTimeBy(AppConfig.PUSH_MANUAL_TIMEOUT_MS - 1)
        assertTrue(flag.value)

        // After timeout: flag flipped.
        scope.advanceTimeBy(2)
        scope.advanceUntilIdle()
        assertFalse(flag.value)
    }
}
