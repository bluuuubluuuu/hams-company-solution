package com.klk.hams.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.klk.hams.HamsApp
import com.klk.hams.provisioning.BindResult
import com.klk.hams.provisioning.ProvisioningClient
import com.klk.hams.provisioning.ProvisioningEvents
import com.klk.hams.provisioning.ProvisioningStore
import com.klk.hams.provisioning.ReleaseResult
import com.klk.hams.ui.theme.FieldForest
import com.klk.hams.ui.theme.FieldForestOn
import com.klk.hams.ui.theme.FieldHairline
import com.klk.hams.ui.theme.FieldInk
import com.klk.hams.ui.theme.FieldInkSoft
import com.klk.hams.ui.theme.FieldScarlet
import kotlinx.coroutines.launch

private sealed interface AdminSheetAction {
    data object BindNewUnit : AdminSheetAction
    data object ResetCurrentPairing : AdminSheetAction
}

/**
 * Hidden admin sheet (Req 1) — opened by long-pressing the battery status pill.
 * Re-bind this device to a different unit, or reset pairing back to the gate.
 * Field-instrument theme to match CountScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminSheet(
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    client: ProvisioningClient = ProvisioningClient(),
) {
    val context = LocalContext.current
    val store = remember { ProvisioningStore.fromContext(context) }
    val app = remember { context.applicationContext as HamsApp }
    val scope = rememberCoroutineScope()
    var newId by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Non-null = the "release first" alert is up; holds the currently-bound unit.
    var blockedBy by remember { mutableStateOf<String?>(null) }
    var adminAction by remember { mutableStateOf<AdminSheetAction?>(null) }
    var adminError by remember { mutableStateOf<String?>(null) }
    var adminLockout by remember { mutableStateOf(AdminCodeLockout()) }
    val currentId = store.uniqueIdOrNull() ?: "(unpaired)"

    // Release this device's current unit (fingerprint-scoped), clear local, return
    // to the gate. Shared by the Reset button and the fault-tolerance alert.
    fun rememberAdminFailure(message: String?) {
        adminError = message
        adminLockout = adminLockout.recordFailure(System.currentTimeMillis())
    }

    fun resetPairing(adminCode: String) {
        val id = store.uniqueIdOrNull()
        if (id == null) {
            status = "Already unpaired"
        } else {
            val fp = ProvisioningStore.deviceFingerprint(context) ?: run {
                status = "No device ID"
                return
            }
            busy = true
            scope.launch {
                // Shared by Success and NotFound: both end the binding, so both
                // must flush the marker under the OLD unit before store.clear().
                suspend fun finishRelease() {
                    ProvisioningEvents.flushAndRelease(app, id)
                    store.clear()
                    onReset()
                }

                when (val release = client.release(id, fp, adminCode)) {
                    ReleaseResult.Success -> finishRelease()
                    // 404/409 — not found, or not the owner. The binding still ends
                    // locally, and this is the messy path most likely to be carrying
                    // unsent work, so it gets the same marker (was silent before).
                    ReleaseResult.NotFound -> finishRelease()
                    ReleaseResult.AdminAuthFailed -> {
                        rememberAdminFailure(releaseFailureMessage(release))
                        busy = false
                    }
                    else -> {
                        status = releaseFailureMessage(release)
                        adminAction = null
                        busy = false
                    }
                }
            }
        }
    }

    fun bindNewUnit(adminCode: String) {
        val fp = ProvisioningStore.deviceFingerprint(context) ?: run {
            status = "No device ID"
            return
        }
        busy = true
        scope.launch {
            when (val r = client.manualClaim(newId, fp, adminCode)) {
                is BindResult.Success -> {
                    store.save(r.uniqueId)
                    // 303 device_bound: push to Wialon now (bind is online).
                    ProvisioningEvents.recordAndPushBound(app, r.uniqueId)
                    status = "Bound to ${r.uniqueId}"
                    adminAction = null
                }
                // Fault tolerance: the two-step rule blocks re-binding while
                // this device still owns a unit. Surface a clear alert that
                // names the blocker and offers the passkey-gated release.
                is BindResult.FingerprintInUse -> {
                    blockedBy = r.ownedUnit ?: "another unit"
                    adminAction = null
                }
                BindResult.AdminAuthFailed -> rememberAdminFailure(bindFailureMessage(r))
                else -> {
                    status = bindFailureMessage(r)
                    adminAction = null
                }
            }
            busy = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Admin · device pairing", style = MaterialTheme.typography.titleMedium, color = FieldInk)
            Text("Current unit: $currentId", color = FieldInkSoft)
            OutlinedTextField(
                value = newId,
                onValueChange = { newId = it.trim(); status = null },
                label = { Text("Re-bind to unit ID") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = FieldForest,
                    unfocusedBorderColor = FieldHairline,
                    focusedLabelColor = FieldForest,
                    cursorColor = FieldForest,
                    focusedTextColor = FieldInk,
                    unfocusedTextColor = FieldInk,
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = !busy && newId.isNotBlank(),
                    onClick = {
                        adminError = null
                        adminAction = AdminSheetAction.BindNewUnit
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FieldForest,
                        contentColor = FieldForestOn,
                    ),
                ) { Text("Bind") }

                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        adminError = null
                        adminAction = AdminSheetAction.ResetCurrentPairing
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = FieldScarlet),
                    border = BorderStroke(1.dp, FieldScarlet),
                ) { Text("Reset pairing") }
            }
            status?.let { Text(it, color = FieldInkSoft) }
        }
    }

    if (blockedBy != null) {
        AlertDialog(
            onDismissRequest = { blockedBy = null },
            title = { Text("Already bound", color = FieldInk) },
            text = {
                Text(
                    "This device is already bound to $blockedBy. Release it first, then bind the new unit.",
                    color = FieldInkSoft,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    blockedBy = null
                    adminError = null
                    adminAction = AdminSheetAction.ResetCurrentPairing
                }) {
                    Text("Reset pairing", color = FieldScarlet)
                }
            },
            dismissButton = {
                TextButton(onClick = { blockedBy = null }) { Text("Cancel", color = FieldInkSoft) }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    adminAction?.let { action ->
        AdminCodeDialog(
            title = "Supervisor approval",
            message = when (action) {
                AdminSheetAction.BindNewUnit -> "Enter the supervisor code to bind this device."
                AdminSheetAction.ResetCurrentPairing -> "Enter the supervisor code to reset this device pairing."
            },
            busy = busy,
            lockout = adminLockout,
            error = adminError,
            onDismiss = { if (!busy) { adminAction = null; adminError = null } },
            onSubmit = { code ->
                adminError = null
                when (action) {
                    AdminSheetAction.BindNewUnit -> bindNewUnit(code)
                    AdminSheetAction.ResetCurrentPairing -> resetPairing(code)
                }
            },
        )
    }
}
