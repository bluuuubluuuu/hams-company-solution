package com.klk.hams.provisioning

import android.content.Context
import android.os.BatteryManager
import com.klk.hams.HamsApp
import com.klk.hams.diagnostics.DiagnosticType

enum class BindingDecision { PROCEED, RELEASED_FLUSH, BOUND_OTHER }

/**
 * Central binding re-check. `decide` is pure and unit-tested; suspend entry
 * points perform IO and side effects. Golden rule: self-unprovision only on
 * explicit Released / BoundOther. Keep and Bound never touch local state.
 */
class BindingRevalidator(
    private val app: HamsApp,
    private val store: ProvisioningStore = ProvisioningStore.fromContext(app),
    private val client: ProvisioningClient = ProvisioningClient(),
) {
    /** Launch + periodic check. Released defers the flush to PushWorker, which
     *  is the sole writer of the pushable 301 marker. */
    suspend fun revalidate() {
        val uid = store.uniqueIdOrNull() ?: return
        val fp = ProvisioningStore.deviceFingerprint(app) ?: return
        when (decide(client.verify(uid, fp))) {
            BindingDecision.PROCEED -> Unit
            BindingDecision.RELEASED_FLUSH -> app.pushController.enqueueAuto()
            BindingDecision.BOUND_OTHER -> {
                // Unit reassigned to another device: stop and log out, push nothing
                // (a message under this unit id would pollute the new owner). The
                // displacement is diagnosable server-side (check_binding), not here.
                revoke(REVOCATION_MESSAGE)
            }
        }
    }

    /** Records the diagnostic row for a binding transition, e.g. `301 binding_released`. */
    suspend fun recordBinding(type: DiagnosticType, pushed: Int): Long =
        app.repository.recordDiagnostic(
            type = type,
            batteryPct = readBatteryPct(app),
            snapshot = app.locationStream.snapshotFlow.value,
            pushed = pushed,
        )

    /**
     * Strands every unsent row, then clears the stored unit and flips the
     * observable flag so the open app drops to the pairing screen with the
     * banner.
     *
     * The strand is **not optional and not conditional**. The Wialon unit id is
     * a login credential in the frame, not a property of the phone: a row left
     * `pushed = 0` when the binding is dropped uploads under whatever unit this
     * handset binds to next, crediting one worker's harvest to another. The
     * ranking the human set: misfiling harvest onto the wrong worker is worse
     * than losing it. The OTP release paths enforce the same guarantee.
     * the device-initiated OTP paths — this is the server-initiated twin
     * (`bound_other`, and the belt-and-braces call after a released-flush).
     */
    suspend fun revoke(message: String) {
        app.repository.strandUnsentWork()
        store.clearBlocking()
        app.provisioningRevocation.value = message
    }

    companion object {
        const val REVOCATION_MESSAGE =
            "This device was unlinked by an administrator. Enter a new supervisor code to reconnect."

        fun decide(verify: VerifyResult): BindingDecision = when (verify) {
            VerifyResult.Bound -> BindingDecision.PROCEED
            is VerifyResult.Keep -> BindingDecision.PROCEED
            VerifyResult.Released -> BindingDecision.RELEASED_FLUSH
            VerifyResult.BoundOther -> BindingDecision.BOUND_OTHER
        }

        /** Revoke after a released-flush only when EVERYTHING flushed: all pending
         *  task cuts uploaded (allCutsFlushed) AND the 301 marker confirmed pushed.
         *  Guarding on both prevents logging out — and stranding the cuts on the
         *  now-unprovisioned phone — when the task push failed but the 301 slipped
         *  through on a recovered connection. */
        fun shouldRevokeAfterFlush(releasedFlush: Boolean, allCutsFlushed: Boolean, row301Pushed: Int?): Boolean =
            releasedFlush && allCutsFlushed && row301Pushed == 1

        fun readBatteryPct(context: Context): Double? {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            return if (pct in 0..100) pct.toDouble() else null
        }
    }
}
