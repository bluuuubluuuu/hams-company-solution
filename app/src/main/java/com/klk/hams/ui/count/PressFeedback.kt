package com.klk.hams.ui.count

/**
 * The outcome of a +/- press, as reported by [CountViewModel] to the UI.
 *
 * Field feedback 2026-08-19: workers reported "no vibration". The cause was not
 * the vibrator — feedback used to fire on touch, before [CountViewModel.onPlus]
 * had decided anything, and a press refused for stale GPS produced no count and
 * (because the button was disabled) no touch event at all. A worker with the
 * handset on an armband could not tell a counted press from a swallowed one.
 *
 * Feedback is now driven by this outcome instead of by the touch, so a
 * confirming buzz always means a row was written, and a refusal is announced
 * rather than silent.
 */
sealed interface PressFeedback {
    /** A 179 row was stored. */
    data object PlusRecorded : PressFeedback

    /** A 180 row was stored. */
    data object MinusRecorded : PressFeedback

    /**
     * The press was rejected and nothing was stored — no GPS lock, no fresh
     * snapshot, at the per-task ceiling, count already 0, or push in progress.
     */
    data object Refused : PressFeedback
}
