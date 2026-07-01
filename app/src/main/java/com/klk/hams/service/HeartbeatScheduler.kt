package com.klk.hams.service

import android.content.Context
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import com.klk.hams.AppConfig
import com.klk.hams.HamsApp
import com.klk.hams.data.location.LocationStream
import com.klk.hams.push.GpsDegradedDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Self-scheduling heartbeat loop owned by [HamsForegroundService].
 *
 * Every [intervalMinutes] the scheduler:
 *   1. Reads battery percent via [BatteryManager.BATTERY_PROPERTY_CAPACITY].
 *   2. Reads the latest snapshot from [LocationStream] (continuous BALANCED stream
 *      owned by HamsApp). Snapshot must be fresh per LocationStream.isFresh().
 *   3. Feeds the fix's HDOP into [GpsDegradedDetector]. On the leading edge of
 *      a degraded state, writes a local-only 293 audit row.
 *   4. Writes a 35 heartbeat row (pushable per dictionary v1.2).
 *
 * Both writes are guarded by an active task in the repository — heartbeats and
 * GPS-degraded rows attach to whichever task is open at the time of the tick.
 *
 * `intervalMinutes <= 0` disables the loop entirely (per AppConfig contract).
 */
class HeartbeatScheduler(
    private val context: Context,
    private val intervalMinutes: Int = AppConfig.HEARTBEAT_INTERVAL_MINUTES
) {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gpsDetector = GpsDegradedDetector()

    private val tick = object : Runnable {
        override fun run() {
            scope.launch { fire() }
            handler.postDelayed(this, intervalMs())
        }
    }

    fun start() {
        if (intervalMinutes <= 0) return
        handler.postDelayed(tick, intervalMs())
    }

    fun stop() {
        handler.removeCallbacks(tick)
        scope.cancel()
    }

    private fun intervalMs(): Long = intervalMinutes.toLong() * 60_000L

    private suspend fun fire() {
        val app = context.applicationContext as? HamsApp ?: return
        val rawSnapshot = app.locationStream.snapshotFlow.value
        val location = if (LocationStream.isFresh(rawSnapshot)) rawSnapshot else null
        val battery = readBatteryPct()

        // GPS-degraded is local-only (293, pushed=1) and runs first so it
        // observes every tick's HDOP, including null-fix recoveries that
        // reset the latch.
        gpsDetector.observe(location?.hdop)?.let {
            app.repository.recordGpsDegraded(location, battery)
        }

        // Heartbeat 35 is pushable, so it requires a valid fix — see
        // TaskRepository.recordHeartbeat for the rationale. A 293 row in this
        // tick does NOT force a heartbeat insert; if there's no valid fix we
        // skip the heartbeat and let the next tick (or an actual + press) carry
        // battery to Wialon.
        if (location != null) {
            app.repository.recordHeartbeat(location, battery)
        }
    }

    private fun readBatteryPct(): Double {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return 0.0
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toDouble()
    }
}
