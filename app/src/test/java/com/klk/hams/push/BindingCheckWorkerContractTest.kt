package com.klk.hams.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BindingCheckWorkerContractTest {
    @Test fun worker_uses_unique_periodic_work_name() {
        assertEquals("hams-binding-check", BindingCheckWorker.WORK_NAME)
    }

    @Test fun push_guard_is_resettable_for_worker_skip() {
        PushWorker.pushInProgress = true
        PushWorker.pushInProgress = false

        assertFalse(PushWorker.pushInProgress)
    }
}
