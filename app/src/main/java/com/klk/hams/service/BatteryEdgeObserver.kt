package com.klk.hams.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import com.klk.hams.HamsApp
import com.klk.hams.push.BatteryEdgeDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Listens for `ACTION_BATTERY_CHANGED` while the foreground service is alive
 * and writes a local-only audit row (event_code 291 / 292) on the leading edge
 * of each downward bucket transition.
 *
 * Bucket state lives in SharedPreferences under [PREFS_NAME] / [KEY_LAST_BUCKET]
 * so it survives process death and double-fires aren't generated on app launch
 * if the bucket has not actually changed since the last run.
 *
 * Edge events are insert-marked `pushed = 1` by [com.klk.hams.push.PushEligibility]
 * and are never queued for outbound push under dictionary v1.2.
 */
class BatteryEdgeObserver(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val pct = intent.toPct() ?: return
            handlePct(pct)
        }
    }

    fun register() {
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
        scope.cancel()
    }

    private fun handlePct(pct: Int) {
        val curr = BatteryEdgeDetector.bucketOf(pct)
        // First-ever observation: prefs has no entry yet. Persist the current
        // bucket as the baseline and stay silent so we don't fire 291/292 just
        // because the app first woke up at an already-low battery level.
        val prev: Int? = if (prefs.contains(KEY_LAST_BUCKET)) {
            prefs.getInt(KEY_LAST_BUCKET, BatteryEdgeDetector.BUCKET_NORMAL)
        } else {
            null
        }

        // Always persist bucket state, even when there's no active task to
        // attach the audit row to; this keeps us from retroactively firing
        // an edge the next time a task starts.
        if (prev == null || curr != prev) {
            prefs.edit().putInt(KEY_LAST_BUCKET, curr).apply()
        }

        val edgeCode = BatteryEdgeDetector.edgeEvent(prev, curr) ?: return
        val app = context.applicationContext as? HamsApp ?: return
        scope.launch {
            app.repository.recordBatteryEdge(edgeCode, pct.toDouble(), null)
        }
    }

    private fun Intent.toPct(): Int? {
        val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level >= 0 && scale > 0) (level * 100 / scale) else null
    }

    companion object {
        const val PREFS_NAME = "hams"
        const val KEY_LAST_BUCKET = "last_battery_bucket"
    }
}
