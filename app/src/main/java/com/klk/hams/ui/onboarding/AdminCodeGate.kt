package com.klk.hams.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.klk.hams.provisioning.ReleaseResult
import com.klk.hams.ui.theme.FieldForest
import com.klk.hams.ui.theme.FieldForestOn
import com.klk.hams.ui.theme.FieldHairline
import com.klk.hams.ui.theme.FieldInk
import com.klk.hams.ui.theme.FieldInkSoft
import com.klk.hams.ui.theme.FieldScarlet

private const val ADMIN_CODE_MAX_FAILURES = 5
private const val ADMIN_CODE_LOCKOUT_MS = 60_000L

data class AdminCodeLockout(
    val failedAttempts: Int = 0,
    val lockedUntilMs: Long = 0L,
) {
    fun isLocked(nowMs: Long): Boolean = lockedUntilMs > nowMs

    fun remainingSeconds(nowMs: Long): Int =
        (((lockedUntilMs - nowMs).coerceAtLeast(0L) + 999L) / 1_000L).toInt()

    fun recordFailure(nowMs: Long): AdminCodeLockout {
        val nextFailures = if (isLocked(nowMs)) failedAttempts else failedAttempts + 1
        return if (nextFailures >= ADMIN_CODE_MAX_FAILURES) {
            copy(failedAttempts = 0, lockedUntilMs = nowMs + ADMIN_CODE_LOCKOUT_MS)
        } else {
            copy(failedAttempts = nextFailures)
        }
    }
}

fun releaseFailureMessage(result: ReleaseResult): String? = when (result) {
    ReleaseResult.NotFound -> "This device no longer owns that unit. Pair again or contact office admin."
    ReleaseResult.Unauthorized -> "Setup error (auth). Contact your supervisor."
    ReleaseResult.AdminAuthFailed -> "Supervisor code rejected. Check and retry."
    ReleaseResult.AdminNotConfigured -> "Supervisor code is not configured. Contact office admin."
    is ReleaseResult.Error -> "No connection. Connect and retry."
    ReleaseResult.Success -> null
}

@Composable
fun AdminCodeDialog(
    title: String,
    message: String,
    busy: Boolean,
    lockout: AdminCodeLockout,
    error: String?,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    nowMs: Long = System.currentTimeMillis(),
) {
    var code by remember { mutableStateOf("") }
    val locked = lockout.isLocked(nowMs)
    val lockoutText = if (locked) {
        "Too many attempts. Try again in ${lockout.remainingSeconds(nowMs)}s."
    } else {
        null
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title, color = FieldInk) },
        text = {
            Column {
                Text(message, color = FieldInkSoft)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.trim() },
                    label = { Text("Supervisor code") },
                    singleLine = true,
                    enabled = !busy && !locked,
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
                val problem = lockoutText ?: error
                if (problem != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(problem, color = FieldScarlet, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && !locked && code.isNotBlank(),
                onClick = { onSubmit(code) },
                colors = ButtonDefaults.textButtonColors(contentColor = FieldForest),
            ) {
                if (busy) {
                    CircularProgressIndicator(color = FieldForest, strokeWidth = 2.dp)
                } else {
                    Text("Continue", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text("Cancel", color = FieldInkSoft)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
