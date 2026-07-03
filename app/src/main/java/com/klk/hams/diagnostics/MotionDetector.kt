package com.klk.hams.diagnostics

import com.klk.hams.AppConfig

/**
 * Debounced hysteresis motion state machine. Feed it GPS speed samples; it emits
 * START_MOVING / STOP_MOVING on confirmed transitions.
 */
class MotionDetector(
    private val startKmh: Double = AppConfig.MOTION_START_SPEED_KMH,
    private val stopKmh: Double = AppConfig.MOTION_STOP_SPEED_KMH,
    private val debounceMs: Long = AppConfig.MOTION_DEBOUNCE_MS,
) {
    private enum class State { UNKNOWN, MOVING, STOPPED }

    private var state = State.UNKNOWN
    private var candidate = State.UNKNOWN
    private var candidateSinceMs = 0L

    fun onSpeed(speedKmh: Int?, atMs: Long): DiagnosticType? {
        val observed = when {
            speedKmh == null -> return null
            speedKmh.toDouble() >= startKmh -> State.MOVING
            speedKmh.toDouble() <= stopKmh -> State.STOPPED
            else -> return null
        }

        if (observed != candidate) {
            candidate = observed
            candidateSinceMs = atMs
            return null
        }
        if (observed == state) return null
        if (atMs - candidateSinceMs < debounceMs) return null

        if (state == State.UNKNOWN && observed == State.STOPPED) {
            state = State.STOPPED
            return null
        }

        state = observed
        return when (observed) {
            State.MOVING -> DiagnosticType.START_MOVING
            State.STOPPED -> DiagnosticType.STOP_MOVING
            State.UNKNOWN -> null
        }
    }
}
