package com.klk.hams.push

/**
 * Terminal / progress states of a [PushEngine] run.
 *
 * - [Idle]      — no run in progress (UI default).
 * - [Running]   — a run is underway; emitted before [PushEngine.run] returns.
 *                 Phase 2 Task 2.7A returns one of the terminal values below
 *                 from `run()`; live progress streaming arrives with 2.7B.
 * - [Success]   — every pending event was uploaded.
 * - [Partial]   — batch completed, but some events were permanently rejected
 *                 (frame-builder failure or `#AD#` error/params response).
 * - [Failed]    — batch was stopped early by transport / login / unexpected
 *                 protocol failure. Remaining events were not marked.
 */
sealed class PushState {
    data object Idle : PushState()

    data class Running(val pushed: Int, val total: Int) : PushState()

    data class Success(val total: Int) : PushState()

    data class Partial(val pushed: Int, val failed: Int, val total: Int) : PushState()

    data class Failed(
        val reason: String,
        val pushed: Int = 0,
        val failed: Int = 0,
    ) : PushState()
}
