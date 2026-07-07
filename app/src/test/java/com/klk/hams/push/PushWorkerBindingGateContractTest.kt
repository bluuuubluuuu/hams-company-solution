package com.klk.hams.push

import com.klk.hams.data.repository.TaskRepository
import org.junit.Assert.assertTrue
import org.junit.Test

class PushWorkerBindingGateContractTest {
    @Test fun taskRepository_exposes_release_finalizer() {
        assertTrue(TaskRepository::class.java.methods.any { it.name == "finalizeActiveTaskForRelease" })
    }

    @Test fun pushWorker_exposes_pushInProgress_guard() {
        assertTrue(PushWorker::class.java.declaredFields.any { it.name == "pushInProgress" })
    }
}
