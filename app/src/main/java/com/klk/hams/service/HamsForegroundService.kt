package com.klk.hams.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.klk.hams.HamsApp
import com.klk.hams.MainActivity
import com.klk.hams.data.location.LocationStream
import com.klk.hams.diagnostics.GpsLockTransition
import com.klk.hams.diagnostics.MotionDetector
import com.klk.hams.provisioning.ProvisioningStore
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class HamsForegroundService : Service() {

    private val batteryEdgeObserver by lazy { BatteryEdgeObserver(applicationContext) }
    private val heartbeat by lazy { HeartbeatScheduler(applicationContext) }
    private val deviceEventReceiver = com.klk.hams.diagnostics.DeviceEventReceiver()
    private val motionDetector = MotionDetector()
    private val gpsLockDetector = GpsLockTransition()

    private val wifiPushMonitor by lazy {
        WifiPushMonitor(applicationContext) {
            if (ProvisioningStore.fromContext(applicationContext).isProvisioned()) {
                (application as HamsApp).pushController.enqueueAuto()
            } else {
                android.util.Log.d(TAG, "WifiPushMonitor: device is not provisioned; skipping auto-push")
            }
        }
    }

    private val serviceScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )
    @Volatile private var gpsHeld = false
    @Volatile private var headless = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        // Shutdown-40 guarantee: mark the session live so an unclean power-off is
        // detectable on the next boot (see ShutdownTracker). NOT cleared in
        // onDestroy - a normal service stop is not a device shutdown; only
        // ACTION_SHUTDOWN clears it.
        com.klk.hams.diagnostics.ShutdownTracker.markSessionStarted(applicationContext)
        observeLastSeen()
        batteryEdgeObserver.register()
        heartbeat.start()
        observeActiveTaskForGps()
        observeTelemetryDetectors()
        wifiPushMonitor.register()
        observePushStateForNotification()
        val diagFilter = android.content.IntentFilter().apply {
            com.klk.hams.diagnostics.DeviceEventReceiver.ACTIONS.forEach { addAction(it) }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, deviceEventReceiver, diagFilter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /**
     * Hold the HIGH_ACCURACY GPS stream only while a task is active. When no
     * task is active the service may still be alive as a push-monitor — it must
     * not keep GPS warm then.
     */
    private fun observeActiveTaskForGps() {
        val app = application as HamsApp
        serviceScope.launch {
            app.repository.observeActiveTask().collect { task ->
                val shouldHold = task != null
                if (shouldHold && !gpsHeld) {
                    app.locationStream.start(LocationStream.REASON_TASK_ACTIVE)
                    gpsHeld = true
                } else if (!shouldHold && gpsHeld) {
                    app.locationStream.stop(LocationStream.REASON_TASK_ACTIVE)
                    gpsHeld = false
                }
            }
        }
    }

    /**
     * Periodically stamp `last_seen` while the service is alive. If the device
     * later powers off uncleanly (no ACTION_SHUTDOWN), the next boot backfills a
     * shutdown dated at this last stamp. Written immediately, then on a cadence.
     */
    private fun observeLastSeen() {
        serviceScope.launch {
            while (isActive) {
                com.klk.hams.diagnostics.ShutdownTracker.updateLastSeen(
                    applicationContext, com.klk.hams.time.Clock.nowUtcIso()
                )
                delay(LAST_SEEN_TICK_MS)
            }
        }
    }

    private fun observeTelemetryDetectors() {
        val app = application as HamsApp
        // Motion: event-driven. Speed only arrives with a fresh fix, and the
        // stream emits nothing while stopped, so this is naturally scoped to
        // active streaming (a stale speed can never re-fire a motion transition).
        serviceScope.launch {
            app.locationStream.snapshotFlow.collect { snapshot ->
                if (snapshot == null) return@collect
                val nowMs = android.os.SystemClock.elapsedRealtime()
                motionDetector.onSpeed(snapshot.speedKmh, nowMs)?.let { type ->
                    app.repository.recordDiagnostic(type, readBatteryPct(), snapshot)
                }
            }
        }
        // GPS lock: periodic tick. GPS_LOST needs a growing snapshot age even when
        // NO new fix arrives - the StateFlow does not re-emit during signal loss -
        // so we poll the current age, mirroring the UI's STALE_RECHECK_MS re-check.
        // Only while the stream is actually held (active task / foreground); a task
        // simply ending (stream stop) must not look like GPS loss.
        serviceScope.launch {
            while (isActive) {
                if (app.locationStream.isStreaming()) {
                    val nowMs = android.os.SystemClock.elapsedRealtime()
                    val snapshot = app.locationStream.snapshotFlow.value
                    val ageMs = if (snapshot != null) nowMs - snapshot.capturedAtMs else Long.MAX_VALUE
                    gpsLockDetector.onSnapshotAge(ageMs, nowMs)?.let { type ->
                        app.repository.recordDiagnostic(type, readBatteryPct(), snapshot)
                    }
                }
                delay(GPS_LOCK_TICK_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Recording FFB counts"),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification("Recording FFB counts"))
            }
        } catch (se: SecurityException) {
            // Android 14+ FGS type validation can reject the promotion when the
            // caller is not in a foreground-eligible state (e.g. process started
            // from background, location permission not in 'while-in-use' window).
            // Fail cleanly — do NOT crash. START_NOT_STICKY so the system does
            // not retry this start in a loop.
            android.util.Log.w(TAG, "startForeground refused: $se — stopping service cleanly", se)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        heartbeat.stop()
        batteryEdgeObserver.unregister()
        wifiPushMonitor.unregister()
        serviceScope.cancel()
        if (gpsHeld) {
            (application as HamsApp).locationStream.stop(LocationStream.REASON_TASK_ACTIVE)
            gpsHeld = false
        }
        runCatching { unregisterReceiver(deviceEventReceiver) }
        super.onDestroy()
    }

    /**
     * App swiped from recents. Save the active task synchronously, then decide:
     * if unsynced tasks remain, KEEP RUNNING as a headless push-monitor (the
     * core push-reliability fix — the service must outlive the swipe so its
     * Wi-Fi monitor still fires). If nothing is pending, stop.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val app = application as HamsApp
        val battery = readBatteryPct()
        val location = app.locationStream.snapshotFlow.value
        val pendingAfterSave = runBlocking {
            app.repository.saveActiveTask("auto_killed", location, battery)
            app.repository.observePendingTaskCount().first()
        }
        // After the save there is no active task. shouldStopService collapses to
        // "pendingCount == 0".
        if (shouldStopService(hasActiveTask = false, pendingCount = pendingAfterSave)) {
            android.util.Log.d(TAG, "onTaskRemoved: no pending work — stopping service")
            stopSelf()
        } else {
            if (headless) {
                android.util.Log.d(TAG, "onTaskRemoved: re-fired while already headless — observer already running")
                return
            }
            android.util.Log.d(TAG, "onTaskRemoved: $pendingAfterSave pending — staying alive as push-monitor")
            headless = true
            observePendingForHeadlessStop()
        }
    }

    /**
     * While headless (app swiped away, service alive only as a push-monitor),
     * stop the service once the backlog has fully drained.
     */
    private fun observePendingForHeadlessStop() {
        val app = application as HamsApp
        serviceScope.launch {
            app.repository.observePendingTaskCount().collect { count ->
                if (headless && shouldStopService(hasActiveTask = false, pendingCount = count)) {
                    android.util.Log.d(TAG, "headless: backlog drained — stopping service")
                    stopSelf()
                }
            }
        }
    }

    private fun readBatteryPct(): Double {
        val bm = getSystemService(BatteryManager::class.java)
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        return level.toDouble()
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HAMS Task Recorder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /**
     * Drive the service's single ongoing notification (id 1001) through the
     * push lifecycle so the worker sees one evolving notification:
     * "waiting for Wi-Fi" -> "Uploading X of N" -> back to the counting text.
     */
    private fun observePushStateForNotification() {
        val app = application as HamsApp
        serviceScope.launch {
            app.pushController.uiStateFlow.collect { state ->
                val text = when (state) {
                    is com.klk.hams.push.PushUiState.PendingWifi ->
                        "${state.pendingTasks} task(s) waiting for network"
                    is com.klk.hams.push.PushUiState.Pushing ->
                        "Uploading task ${(state.done + 1).coerceAtMost(state.total.coerceAtLeast(1))} of ${state.total}"
                    else -> "Recording FFB counts"
                }
                val nm = androidx.core.app.NotificationManagerCompat.from(this@HamsForegroundService)
                // POST_NOTIFICATIONS may be denied on Android 13+; notify then can
                // throw SecurityException. Swallow it — a missed status update is
                // harmless. (Explicit catch so lint's MissingPermission is satisfied.)
                try {
                    nm.notify(NOTIFICATION_ID, buildNotification(text))
                } catch (_: SecurityException) {
                }
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "hams_service_channel"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "HAMS_PUSH"

        /** GPS-lock detector poll cadence - matches the UI's STALE_RECHECK_MS so
         *  GPS_LOST/GPS_RECOVERY fire on staleness even without new fixes. */
        private const val GPS_LOCK_TICK_MS: Long = 1_000

        /** Liveness-stamp cadence for the shutdown-40 backfill (see ShutdownTracker).
         *  Bounds backfill date accuracy to ~1 min. */
        private const val LAST_SEEN_TICK_MS: Long = 60_000

        /** True while the service is alive — read by PushWorker to decide whether
         *  it needs its own foreground notification. */
        @Volatile var isRunning: Boolean = false
            private set
    }
}
