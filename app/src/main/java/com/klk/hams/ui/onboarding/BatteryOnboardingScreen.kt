package com.klk.hams.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.klk.hams.ui.theme.FieldInk
import com.klk.hams.ui.theme.FieldInkSoft

@Composable
fun BatteryOnboardingScreen(
    onAllow: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.weight(0.3f))
        Text("Keep uploads running", style = MaterialTheme.typography.headlineLarge, color = FieldInk)
        Text(
            "HAMS sends your task counts to the server whenever Wi-Fi is available — even when the app is closed. " +
                "To make sure that keeps working, please allow HAMS to run without battery restrictions on the next screen.",
            style = MaterialTheme.typography.bodyLarge, color = FieldInkSoft
        )
        Text(
            "Some phone makers (Samsung, Xiaomi, Oppo, Honor, OnePlus and others) also have an extra “app launch” or “auto-start” setting — if uploads stop working later, check Settings → Battery → HAMS and enable auto-launch / background activity there too.",
            style = MaterialTheme.typography.bodyMedium, color = FieldInkSoft
        )
        Spacer(Modifier.weight(0.4f))
        Button(onClick = onAllow, modifier = Modifier.fillMaxWidth()) {
            Text("Allow", style = MaterialTheme.typography.labelLarge)
        }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("Skip for now", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.weight(0.1f))
    }
}
