package com.klk.hams.provisioning

import com.klk.hams.HamsApp
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
 * - 304 device_unbound: worker released a device (pushed to that unit BEFORE the
 *   binding is cleared; if the Wialon gateway is unreachable it is marked
 *   non-resendable so it can never later land on a different unit).
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

    /** Record + push 304 to [uniqueId] (the unit being released) BEFORE the
     *  caller clears the binding. If it does not land, mark it rejected so the
     *  pending row can never later push under the fallback/next unit. */
    suspend fun recordAndPushUnbound(app: HamsApp, uniqueId: String) {
        val id = app.repository.recordDiagnostic(
            type = DiagnosticType.DEVICE_UNBOUND,
            batteryPct = BindingRevalidator.readBatteryPct(app),
            snapshot = app.locationStream.snapshotFlow.value,
            pushed = 0,
        )
        drainTelemetry(app, uniqueId)
        if (app.repository.diagnosticPushedState(id) != 1) {
            app.repository.markTelemetryRejected(id)
        }
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
