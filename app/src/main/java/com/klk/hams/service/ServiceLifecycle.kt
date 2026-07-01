package com.klk.hams.service

/**
 * Pure stop-decision for [HamsForegroundService] (push-reliability upgrade,
 * 2026-05-22). The service has two reasons to be alive — a task is active
 * (counting mode) or unsynced tasks exist (push-monitor mode). When neither
 * holds there is no work to do and the service should stop.
 *
 * Kept as a free function with no Android types so it is unit-testable.
 */
fun shouldStopService(hasActiveTask: Boolean, pendingCount: Int): Boolean =
    !hasActiveTask && pendingCount == 0
