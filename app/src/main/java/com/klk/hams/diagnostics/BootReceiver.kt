package com.klk.hams.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.SystemClock
import com.klk.hams.HamsApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Manifest-declared receiver: records a 'boot' diagnostic on device power-on.
 *
 * Dedupe guard: some OEM ROMs (observed on the ALI-NX1 test device) re-broadcast
 * ACTION_BOOT_COMPLETED on app cold-start / un-force-stop, which would log phantom
 * reboots (and push false event_code 29 to Wialon). The approximate boot wall-clock
 * instant - `currentTimeMillis() - elapsedRealtime()` - is stable within a boot
 * session, so a re-broadcast matches the already-recorded boot (within a small
 * tolerance for NTP drift) and is skipped. A genuine reboot resets uptime, yielding
 * a new, later instant that is recorded.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { recordBootIfNew(context) } finally { pending.finish() }
        }
    }

    companion object {
        const val BOOT_PREFS = "hams_boot"
        const val KEY_LAST_BOOT_INSTANT = "last_boot_instant_ms"
        const val BOOT_DEDUPE_TOLERANCE_MS = 60_000L

        /**
         * Records a 'boot' diagnostic once per boot session, deduped by the boot
         * instant. Called both by the receiver (on ACTION_BOOT_COMPLETED) and by
         * [HamsApp.onCreate] as a fallback - the receiver is unreliable when the app
         * was in the stopped state (force-stop / swipe-kill) or when an aggressive
         * OEM ROM blocks boot broadcasts, so the first app run of a new boot session
         * records the boot the receiver missed. Whichever fires first wins; the other
         * sees the same stored instant and is skipped.
         */
        suspend fun recordBootIfNew(context: Context) {
            val prefs = context.getSharedPreferences(BOOT_PREFS, Context.MODE_PRIVATE)
            val bootInstantMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            val lastInstantMs = prefs.getLong(KEY_LAST_BOOT_INSTANT, Long.MIN_VALUE)
            if (isPhantomBoot(bootInstantMs, lastInstantMs)) return
            // Synchronous commit: rapid duplicate broadcasts must see the stored value.
            prefs.edit().putLong(KEY_LAST_BOOT_INSTANT, bootInstantMs).commit()
            val repository = (context.applicationContext as HamsApp).repository
            // Shutdown-40 guarantee: if the previous session never recorded a clean
            // shutdown, emit a backfilled 40 dated at last-seen - before the boot,
            // so the office sees "device went off -> came back". Runs once per
            // genuine boot (the phantom guard above dedupes; the flag is consumed).
            val backfillIso = ShutdownTracker.consumeBackfillOnBoot(context)
            if (backfillIso != null) {
                repository.recordDiagnostic(DiagnosticType.SHUTDOWN, batteryPct = null, timestampIso = backfillIso)
            }
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }?.toDouble()
            repository.recordDiagnostic(DiagnosticType.BOOT, pct)
        }

        /**
         * True when [newInstantMs] is within [toleranceMs] of an already-recorded
         * boot instant - i.e. the same boot session re-broadcasting. A prior of
         * [Long.MIN_VALUE] (never recorded) is never a phantom.
         */
        internal fun isPhantomBoot(
            newInstantMs: Long,
            lastInstantMs: Long,
            toleranceMs: Long = BOOT_DEDUPE_TOLERANCE_MS,
        ): Boolean =
            lastInstantMs != Long.MIN_VALUE && abs(newInstantMs - lastInstantMs) < toleranceMs
    }
}
