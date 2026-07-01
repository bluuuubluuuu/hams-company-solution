package com.klk.hams.push

import java.time.Instant

/**
 * Controller-layer push state surfaced to the UI and to system notifications.
 * Distinct from [PushState], which is the engine's per-run terminal result.
 *
 * Transitions (Task 2.8 spec §5):
 *   Idle ──new pending task──> PendingWifi
 *   PendingWifi ──Wi-Fi up + worker starts──> Pushing
 *   Pushing ──all done──> Completed
 *   Pushing ──network drop──> PendingWifi
 *   Pushing ──fatal──> Failed
 *   Completed ──5s──> Idle
 *   Failed ──user retry──> PendingWifi
 */
sealed interface PushUiState {

    /** Helper for UI: should the screen lock/dim during this state? */
    val isLockable: Boolean
        get() = this is PendingWifi || this is Pushing

    data object Idle : PushUiState

    data class PendingWifi(val pendingTasks: Int) : PushUiState

    data class Pushing(val total: Int, val done: Int) : PushUiState

    data class Completed(val tasks: Int, val at: Instant) : PushUiState

    data class Failed(val reason: String, val pending: Int) : PushUiState
}
