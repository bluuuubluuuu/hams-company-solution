package com.klk.hams.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for [shouldStopService] — the pure stop-decision used by
 * HamsForegroundService. No Android dependency.
 */
class ServiceLifecycleTest {

    @Test fun stops_whenNoActiveTaskAndNoPending() {
        assertEquals(true, shouldStopService(hasActiveTask = false, pendingCount = 0))
    }

    @Test fun staysAlive_whenTaskActive() {
        assertEquals(false, shouldStopService(hasActiveTask = true, pendingCount = 0))
    }

    @Test fun staysAlive_whenPendingTasksExist() {
        assertEquals(false, shouldStopService(hasActiveTask = false, pendingCount = 3))
    }

    @Test fun staysAlive_whenBothTaskActiveAndPending() {
        assertEquals(false, shouldStopService(hasActiveTask = true, pendingCount = 5))
    }
}
