package com.klk.hams.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.klk.hams.HamsApp
import com.klk.hams.provisioning.BindResult
import com.klk.hams.provisioning.ProvisioningClient
import com.klk.hams.provisioning.ProvisioningEvents
import com.klk.hams.provisioning.ProvisioningStore
import com.klk.hams.provisioning.ReleaseResult
import com.klk.hams.provisioning.VerifyResult
import com.klk.hams.ui.theme.FieldForest
import com.klk.hams.ui.theme.FieldForestOn
import com.klk.hams.ui.theme.FieldHairline
import com.klk.hams.ui.theme.FieldInk
import com.klk.hams.ui.theme.FieldInkSoft
import com.klk.hams.ui.theme.FieldScarlet
import com.klk.hams.ui.theme.FieldSlate
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Pure: map a failed bind to a worker-facing message, or null on success. */
fun bindFailureMessage(result: BindResult): String? = when (result) {
    BindResult.NotFound -> "Unknown unit ID. Check and retry."
    is BindResult.FingerprintInUse ->
        "This device is already bound to ${result.ownedUnit ?: "another unit"}. Reset pairing first, then bind."
    BindResult.AlreadyBound -> "That unit is bound to another device. Use the admin dashboard to move it."
    BindResult.Draining -> "This unit is finishing release from another device. Try again in a few minutes."
    BindResult.Unauthorized -> "Setup error (auth). Contact your supervisor."
    BindResult.AdminAuthFailed -> "Supervisor code rejected. Check and retry."
    BindResult.AdminNotConfigured -> "Supervisor code is not configured. Contact office admin."
    is BindResult.Error -> "No connection. Connect and retry."
    is BindResult.Success -> null
}

private sealed interface PairingAdminAction {
    data object BindTypedUnit : PairingAdminAction
    data class ReleaseAndBind(val ownedUnit: String) : PairingAdminAction
}

/**
 * First-launch pairing gate (Req 1). Blocks all app features until an admin keys
 * in the Wialon unit id and it binds. Field-instrument theme to match CountScreen.
 */
@Composable
fun PairingScreen(
    onPaired: () -> Unit,
    client: ProvisioningClient = ProvisioningClient(),
    notice: String? = null,
) {
    val context = LocalContext.current
    val store = remember { ProvisioningStore.fromContext(context) }
    val app = context.applicationContext as HamsApp
    val scope = rememberCoroutineScope()
    val fingerprint = remember { ProvisioningStore.deviceFingerprint(context) }
    var unitId by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var ownedUnit by remember { mutableStateOf<String?>(null) }
    var adminAction by remember { mutableStateOf<PairingAdminAction?>(null) }
    var adminError by remember { mutableStateOf<String?>(null) }
    var adminLockout by remember { mutableStateOf(AdminCodeLockout()) }

    suspend fun completePairing(uniqueId: String) {
        handleProvisioningSuccess(
            uniqueId = uniqueId,
            save = store::save,
            pendingTasks = { app.repository.observePendingTaskCount().first() },
            enqueueAuto = app.pushController::enqueueAuto,
            onProvisioned = onPaired,
            recordBound = { ProvisioningEvents.recordAndPushBound(app, uniqueId) },
        )
    }

    fun rememberAdminFailure(message: String?) {
        adminError = message
        adminLockout = adminLockout.recordFailure(System.currentTimeMillis())
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (notice != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                ) {
                    Text(
                        text = notice,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            Text(
                "HAMS",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 36.sp,
                letterSpacing = 6.sp,
                color = FieldForest,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "Pair this device",
                style = MaterialTheme.typography.headlineMedium,
                color = FieldInk,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Enter the Wialon unit ID set for this device. An administrator sets this once.",
                style = MaterialTheme.typography.bodyMedium,
                color = FieldInkSoft,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = unitId,
                onValueChange = { unitId = it.trim(); error = null },
                label = { Text("Unit ID") },
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
            // Read-only reference so the admin can cross-check this device's
            // fingerprint against the units dashboard.
            if (fingerprint != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Device: ${fingerprint.take(8)}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = FieldSlate,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = FieldScarlet, style = MaterialTheme.typography.bodyMedium)
            }
            ownedUnit?.let { unit ->
                Spacer(Modifier.height(16.dp))
                Text(
                    "This device is already bound to $unit.",
                    color = FieldInk,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch { completePairing(unit) }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = FieldForest,
                            contentColor = FieldForestOn,
                        ),
                    ) { Text("Use $unit") }
                    OutlinedButton(
                        enabled = !busy && unitId.isNotBlank(),
                        onClick = {
                            adminError = null
                            adminAction = PairingAdminAction.ReleaseAndBind(unit)
                        },
                    ) { Text("Release & bind another") }
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                enabled = !busy && unitId.isNotBlank(),
                onClick = {
                    val fp = fingerprint
                    if (fp == null) {
                        error = "This device has no usable ID. Contact your supervisor."
                        return@Button
                    }
                    error = null
                    adminError = null
                    adminAction = PairingAdminAction.BindTypedUnit
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FieldForest,
                    contentColor = FieldForestOn,
                ),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        Modifier.size(22.dp),
                        color = FieldForestOn,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("PAIR", fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                }
            }
        }
    }

    adminAction?.let { action ->
        AdminCodeDialog(
            title = "Supervisor approval",
            message = when (action) {
                PairingAdminAction.BindTypedUnit -> "Enter the supervisor code to pair this device."
                is PairingAdminAction.ReleaseAndBind ->
                    "Enter the supervisor code to release ${action.ownedUnit} and bind the typed unit."
            },
            busy = busy,
            lockout = adminLockout,
            error = adminError,
            onDismiss = { adminAction = null; adminError = null },
            // Pairing: the code is for the unit being typed in. On a
            // release-and-bind it is the unit being released.
            onRequestCode = {
                client.requestOtp(
                    when (action) {
                        PairingAdminAction.BindTypedUnit -> unitId
                        is PairingAdminAction.ReleaseAndBind -> action.ownedUnit
                    }
                )
            },
            onSubmit = { code ->
                val fp = fingerprint
                if (fp == null) {
                    adminError = "This device has no usable ID. Contact your supervisor."
                    return@AdminCodeDialog
                }
                busy = true
                adminError = null
                scope.launch {
                    when (action) {
                        PairingAdminAction.BindTypedUnit -> {
                            when (val r = client.manualClaim(unitId, fp, code)) {
                                is BindResult.Success -> completePairing(r.uniqueId)
                                is BindResult.FingerprintInUse -> {
                                    ownedUnit = r.ownedUnit
                                    error = if (r.ownedUnit == null) bindFailureMessage(r) else null
                                    adminAction = null
                                    busy = false
                                }
                                BindResult.AdminAuthFailed -> {
                                    rememberAdminFailure(bindFailureMessage(r))
                                    busy = false
                                }
                                else -> {
                                    error = bindFailureMessage(r)
                                    adminAction = null
                                    busy = false
                                }
                            }
                        }
                        is PairingAdminAction.ReleaseAndBind -> {
                            val owned = client.verify(action.ownedUnit, fp) == VerifyResult.Bound
                            val snapshot = app.applicationScope.async {
                                ProvisioningEvents.deliverBeforeRelease(app, action.ownedUnit, deliver = owned)
                            }.await()
                            when (val release = client.release(action.ownedUnit, fp, code)) {
                                ReleaseResult.Success -> {
                                    // 302/304 for the unit we just released, BEFORE
                                    // re-binding. This is the release-then-rebind
                                    // sequence that produced the A1 mis-attribution
                                    // defect (2026-07-10): any row left pending here
                                    // would push under the unit claimed on the next
                                    // line. The release path strands them instead.
                                    //
                                    // Runs on the application scope so an Activity
                                    // recreation (armband flip — sensorPortrait, no
                                    // configChanges) cannot cut the sequence between
                                    // its marker push and its strand. The await keeps
                                    // the ordering: no manualClaim until the release
                                    // sequence has fully completed.
                                    app.applicationScope.async {
                                        ProvisioningEvents.markAndStrand(app, action.ownedUnit, snapshot)
                                    }.await()
                                    when (val bind = client.manualClaim(unitId, fp, code)) {
                                    is BindResult.Success -> completePairing(bind.uniqueId)
                                    BindResult.AdminAuthFailed -> {
                                        rememberAdminFailure(bindFailureMessage(bind))
                                        busy = false
                                    }
                                    else -> {
                                        error = bindFailureMessage(bind)
                                        adminAction = null
                                        busy = false
                                    }
                                    }
                                }
                                ReleaseResult.AdminAuthFailed -> {
                                    rememberAdminFailure(releaseFailureMessage(release))
                                    busy = false
                                }
                                else -> {
                                    error = releaseFailureMessage(release)
                                    adminAction = null
                                    busy = false
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}
