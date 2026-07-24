package com.klk.hams.provisioning

import com.klk.hams.AppConfig
import com.klk.hams.HamsApp
import com.klk.hams.data.repository.TaskRepository
import com.klk.hams.diagnostics.DiagnosticType
import com.klk.hams.push.PushGate
import com.klk.hams.push.TelemetryPushEngine
import com.klk.hams.push.WialonIPSClient
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Lifecycle markers for OTP bind and release. */
object ProvisioningEvents {

    /** Record and push 303 to the unit just bound. */
    suspend fun recordAndPushBound(app: HamsApp, uniqueId: String) {
        app.repository.recordDiagnostic(
            type = DiagnosticType.DEVICE_BOUND,
            batteryPct = BindingRevalidator.readBatteryPct(app),
            snapshot = app.locationStream.snapshotFlow.value,
            pushed = 0,
        )
        drainTelemetry(app, uniqueId)
    }

    fun releaseTypeFor(unsent: TaskRepository.UnsentWork): DiagnosticType =
        if (unsent.cuts > 0) DiagnosticType.WORK_STRANDED else DiagnosticType.DEVICE_UNBOUND

    /** Record and push the 302 or 304 marker under the unit being left. */
    suspend fun recordAndPushRelease(
        app: HamsApp,
        uniqueId: String,
        unsent: TaskRepository.UnsentWork,
    ): Boolean {
        val id = app.repository.recordDiagnostic(
            type = releaseTypeFor(unsent),
            batteryPct = BindingRevalidator.readBatteryPct(app),
            snapshot = app.locationStream.snapshotFlow.value,
            pushed = 0,
            lostTasks = null,
            lostCuts = unsent.cuts.takeIf { it > 0 },
        )
        val pendingIds = app.repository.pendingTelemetryIds()
        drainTelemetry(app, uniqueId)
        for (rowId in pendingIds) {
            if (app.repository.diagnosticPushedState(rowId) != 1) {
                app.repository.markTelemetryRejected(rowId)
            }
        }
        return app.repository.diagnosticPushedState(id) == 1
    }

    data class ReleaseOutcome(val landed: Boolean, val lost: TaskRepository.UnsentWork)

    /**
     * Phase 1: finalize and snapshot pending cut ids while the phone still owns
     * the unit. Delivery is skipped unless the caller's ownership preflight passed.
     */
    suspend fun deliverBeforeRelease(
        app: HamsApp,
        uniqueId: String,
        deliver: Boolean,
    ): List<Long> {
        app.repository.finalizeActiveTaskForRelease()
        val snapshot = app.repository.pendingCutIds()
        if (deliver) {
            try {
                withTimeout(AppConfig.DELIVER_BUDGET_MS) {
                    PushGate.mutex.withLock {
                        buildReleaseDeliveryEngine(app, uniqueId).run()
                    }
                }
            } catch (_: TimeoutCancellationException) {
                // Partial delivery is measured by the snapshot after this returns.
            }
        }
        return snapshot
    }

    /** Phase 2: count the remaining snapshot rows, marker, then always strand. */
    suspend fun markAndStrand(
        app: HamsApp,
        uniqueId: String,
        snapshot: List<Long>,
    ): ReleaseOutcome = runMarkAndStrand(
        countLost = { app.repository.lostAmong(snapshot) },
        pushMarker = { lost -> recordAndPushRelease(app, uniqueId, lost) },
        strandUnsentWork = { app.repository.strandUnsentWork() },
    )

    /** JVM-testable count, marker, and unconditional strand sequence. */
    suspend fun runMarkAndStrand(
        countLost: suspend () -> TaskRepository.UnsentWork,
        pushMarker: suspend (TaskRepository.UnsentWork) -> Boolean,
        strandUnsentWork: suspend () -> Unit,
    ): ReleaseOutcome {
        val lost = countLost()
        val landed = pushMarker(lost)
        strandUnsentWork()
        return ReleaseOutcome(landed = landed, lost = lost)
    }

    private fun buildReleaseDeliveryEngine(app: HamsApp, uniqueId: String) =
        com.klk.hams.push.PushEngine(
            repo = com.klk.hams.push.PushRepositoryImpl(app.repository),
            senderFactory = { WialonIPSClient(uniqueId = uniqueId) },
            maxAttempts = 1,
            backoffScheduleMs = listOf(0L),
        )

    private suspend fun drainTelemetry(app: HamsApp, uniqueId: String) {
        try {
            TelemetryPushEngine(
                repo = app.repository,
                senderFactory = { WialonIPSClient(uniqueId = uniqueId) },
            ).run()
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            throw c
        } catch (_: Throwable) {
            // Best effort at release time; rejected rows cannot cross to a new unit.
        }
    }
}
