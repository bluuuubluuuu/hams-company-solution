package com.klk.hams.ui.onboarding

import com.klk.hams.provisioning.shouldAutoPush

/**
 * Shared post-bind step for the pairing flow: persist the unit id, then kick an
 * auto-push if this device already has pending tasks, and signal the caller that
 * provisioning is complete.
 */
suspend fun handleProvisioningSuccess(
    uniqueId: String,
    save: (String) -> Unit,
    pendingTasks: suspend () -> Int,
    enqueueAuto: () -> Unit,
    onProvisioned: () -> Unit,
    recordBound: suspend () -> Unit = {},
) {
    save(uniqueId)
    // 303 device_bound: push the bind marker to Wialon now (bind is online).
    recordBound()
    val pending = pendingTasks()
    if (shouldAutoPush(isProvisioned = true, pending = pending)) {
        enqueueAuto()
    }
    onProvisioned()
}
