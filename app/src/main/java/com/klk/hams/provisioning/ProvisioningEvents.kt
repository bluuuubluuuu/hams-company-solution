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
     * @return true if the marker reached the gateway. The strand is
     *   unconditional either way; the flag only reports whether Wialon holds a
     *   receipt for the release.
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
     * What a completed release sequence reports back.
     *
     * @property landed true if the 302/304 marker reached the gateway. Never
     *   gates the strand; call sites use it to tell the operator whether Wialon
     *   holds a receipt.
     * @property lost the counts measured before the strand — what this release
     *   permanently discarded.
     */
    data class ReleaseOutcome(val landed: Boolean, val lost: TaskRepository.UnsentWork)

    /**
     * The ordered release sequence, expressed over its four steps so it is
     * testable on the JVM without an Android [HamsApp]. [flushAndRelease] is the
     * only production caller and passes the real repository/push functions.
     *
     * Step 4 runs on every path. The `landed` flag is data returned to the
     * caller, never a condition on the strand — see [flushAndRelease] for why
     * that ranking exists, and `ReleaseSequenceTest` for the guard that fails if
     * anyone re-wraps it in `if (landed)`.
     */
    suspend fun runReleaseSequence(
        finalizeActiveTask: suspend () -> Unit,
        countUnsentWork: suspend () -> TaskRepository.UnsentWork,
        pushMarker: suspend (TaskRepository.UnsentWork) -> Boolean,
        strandUnsentWork: suspend () -> Unit,
    ): ReleaseOutcome {
        finalizeActiveTask()
        val unsent = countUnsentWork()
        val landed = pushMarker(unsent)
        strandUnsentWork()
        return ReleaseOutcome(landed = landed, lost = unsent)
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
     *   4. strand the rows — unconditionally, whether or not step 3 landed
     *
     * The strand in step 4 is **unconditional**. The Wialon unit id is a login
     * credential in the frame, not a property of the phone: any row left
     * `pushed = 0` here uploads under whatever unit this handset binds to next,
     * crediting one worker's harvest to another. Stranding on every path — the
     * gateway-unreachable path included — makes that mis-attribution impossible.
     *
     * The cost when the marker did NOT land: the harvest is destroyed with no
     * Wialon receipt. The only record is local — the release diagnostics row,
     * which [recordAndPushRelease]'s rejection loop leaves at `pushed = 2`, plus
     * the stranded event rows themselves. Both are readable by a DB pull and are
     * retained for `AppConfig.SQLITE_RETENTION_DAYS`.
     *
     * The ranking this encodes: misfiling harvest onto the wrong worker is worse
     * than losing it.
     *
     * @return the marker's landed flag plus the counts this release discarded.
     *   Neither gates the strand; callers use them to report whether a receipt
     *   exists and how much harvest was destroyed.
     */
    suspend fun flushAndRelease(app: HamsApp, uniqueId: String): ReleaseOutcome =
        runReleaseSequence(
            finalizeActiveTask = { app.repository.finalizeActiveTaskForRelease() },
            countUnsentWork = { app.repository.countUnsentWork() },
            pushMarker = { unsent -> recordAndPushRelease(app, uniqueId, unsent) },
            strandUnsentWork = { app.repository.strandUnsentWork() },
        )

    private suspend fun drainTelemetry(app: HamsApp, uniqueId: String) {
        try {
            TelemetryPushEngine(
                repo = app.repository,
                senderFactory = { WialonIPSClient(uniqueId = uniqueId) },
            ).run()
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            // Never swallowed with the transport errors below: cancellation means
            // the caller's scope died mid-release, and silently continuing would
            // hide a half-finished sequence (the exact failure mode the
            // application-scoped launch at the UI call sites exists to prevent).
            throw c
        } catch (_: Throwable) {
            // best-effort at behaviour time; a pending row retries later
        }
    }
}
