package com.klk.hams.data.repository

import com.klk.hams.data.db.DiagnosticDao
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticPushStateContractTest {
    @Test fun diagnosticDao_exposes_pushed_state_lookup() {
        assertTrue(DiagnosticDao::class.java.methods.any { it.name == "pushedState" })
    }

    @Test fun taskRepository_exposes_diagnostic_pushed_state_passthrough() {
        assertTrue(TaskRepository::class.java.methods.any { it.name == "diagnosticPushedState" })
    }
}
