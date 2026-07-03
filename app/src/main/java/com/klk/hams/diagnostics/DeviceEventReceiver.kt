package com.klk.hams.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import com.klk.hams.HamsApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Context-registered receiver for implicit lifecycle broadcasts (screen, power,
 * shutdown). Manifest registration is not allowed for these on Android 8+, so
 * HamsForegroundService registers/unregisters this over its own lifetime.
 */
class DeviceEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val type = DiagnosticType.fromAction(intent.action) ?: return
        val battery = readBatteryPct(context)
        val app = context.applicationContext as HamsApp
        val repo = app.repository
        // goAsync() keeps the broadcast alive until the DB write finishes.
        // Critical for ACTION_SHUTDOWN (best-effort delivery) where a fire-and-
        // forget coroutine would be killed with the process (Codex review 2026-06-29).
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val snapshot = app.locationStream.snapshotFlow.value
                repo.recordDiagnostic(type, battery, snapshot)
                // Clean shutdown observed: the real 40 is recorded, so the next
                // boot must not backfill one (see ShutdownTracker).
                if (type == DiagnosticType.SHUTDOWN) {
                    ShutdownTracker.markCleanShutdown(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun readBatteryPct(context: Context): Double? {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (pct in 0..100) pct.toDouble() else null
    }

    companion object {
        val ACTIONS = arrayOf(
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF,
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            Intent.ACTION_SHUTDOWN,
        )
    }
}
