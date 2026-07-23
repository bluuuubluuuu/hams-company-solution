package com.klk.hams.provisioning

import com.klk.hams.HamsApp
import com.klk.hams.data.repository.TaskRepository
import com.klk.hams.diagnostics.DiagnosticType
import com.klk.hams.push.TelemetryPushEngine
import com.klk.hams.push.WialonIPSClient

/**
 * Bind/unbind lifecycle markers pushed to Wialon at the moment the behaviour is
 * detected. Both OTP bind (manual_claim) and OTP unbind (release) are inherently
 * online — they just reached the n8n webhook — so an inline push to the Wialon
 * IPS gateway right then reliably lands.
 *
 * - 303 device_bound: worker paired a device (pushed to the just-bound unit).
 * - 302 work_stranded / 304 device_unbound: worker released a device (one or the
 *   other, pushed to that unit BEFORE the binding is cleared; every telemetry row
 *   the Wialon gateway does not accept is marked non-resendable so none of them
 *   can later land on a different unit).
 */
object ProvisioningEvents {

    /** Record + push 303 to [uniqueId] (the unit just bound). Left pending on a
     *  gateway miss — the device stays bound, so it retries under the same unit. */
    suspend fun recordAndPushBound(app: HamsApp, uniqueId: String) {
        app.repository.recordDiagnostic(
            type = DiagnosticType.DEVICE_BOUND,
            batteryPct = BindingRevalidator.readBatteryPct(app),
            snapshot = app.locationStream.snapshotFlow.value,
            pushed = 0,
        )
        drainTelemetry(app, uniqueId)
    }

    /**
     * Pure: which marker a device-initiated release emits. Mutually exclusive —
     * a release sends 302 or 304, never both. Gated on `cuts`, not `tasks`:
     * 302 means harvest was lost, and a task holding only unsent beacons has
     * none to lose.
     */
    fun releaseTypeFor(unsent: TaskRepository.UnsentWork): DiagnosticType =
        if (unsent.cuts > 0) DiagnosticType.WORK_STRANDED else DiagnosticType.DEVICE_UNBOUND

    /**
     * Record + push the release marker to [uniqueId] (the unit being left)
     * BEFORE the caller clears the binding.
     *
     * Emits 302 `work_stranded` or 304 `device_unbound`, both carrying the real
     * `lostTasks` / `lostCuts` counts from [unsent] — a clean release is a
     * positive assertion rather than an absence. The only guarantee tied to
     * the marker choice is `lostCuts == 0` on a 304 (that is precisely
     * [releaseTypeFor]'s routing condition); `lostTasks` may still be
     * non-zero on a 304, meaning no harvest was lost but a task still holds
     * an unsent beacon.
     *
     * Every telemetry row that fails to land is marked rejected, not just this
     * one: `drainTelemetry` sends the whole pending table, and any row left
     * `pushed = 0` here would push under the NEXT unit after `store.clear()`.
     *
     * @return true if the marker reached the gateway. The caller strands the
     *   cut rows only on true — killing harvest with no receipt is worse than
     *   misfiling it.
     */
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
            lostTasks = unsent.tasks,
            lostCuts = unsent.cuts,
        )
        val pendingIds = app.repository.pendingTelemetryIds()
        drainTelemetry(app, uniqueId)
        for (rowId in pendingIds) {
            if (app.repository.diagnosticPushedState(rowId) != 1) {
                app.repository.markTelemetryRejected(rowId)
            }
        }
        // Re-read (not the same value as the loop's checks): the loop above may have
        // just flipped this row to pushed = 2 via markTelemetryRejected, so the state
        // must be read again here, after rejection, for the returned bool to be correct.
        return app.repository.diagnosticPushedState(id) == 1
    }

    /**
     * The complete device-initiated release sequence, shared by every call site
     * so the ordering cannot drift between them:
     *
     *   1. finalize the active task — it is invisible to every count and every
     *      flush until it becomes pending (issue A3)
     *   2. count what would not be delivered
     *   3. push 302 or 304 to [uniqueId], the unit being LEFT — this must happen
     *      before the caller stores a new unit or clears the binding
     *   4. strand the rows, only if the marker landed
     *
     * @return true if the marker reached the gateway.
     */
    suspend fun flushAndRelease(app: HamsApp, uniqueId: String): Boolean {
        app.repository.finalizeActiveTaskForRelease()
        val unsent = app.repository.countUnsentWork()
        val landed = recordAndPushRelease(app, uniqueId, unsent)
        if (landed) app.repository.strandUnsentWork()
        return landed
    }

    private suspend fun drainTelemetry(app: HamsApp, uniqueId: String) {
        try {
            TelemetryPushEngine(
                repo = app.repository,
                senderFactory = { WialonIPSClient(uniqueId = uniqueId) },
            ).run()
        } catch (_: Throwable) {
            // best-effort at behaviour time; a pending row retries later
        }
    }
}
