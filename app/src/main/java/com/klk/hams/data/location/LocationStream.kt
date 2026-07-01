package com.klk.hams.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executor
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.klk.hams.AppConfig
import com.klk.hams.data.model.LocationSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * App-scoped continuous location stream.
 *
 * Owned by [com.klk.hams.HamsApp]. Lifecycle is ref-counted: callers
 * call [start] / [stop] with a unique reason string ([REASON_FOREGROUND],
 * [REASON_TASK_ACTIVE]).
 *
 * Priority is dynamic (Task 20):
 *   - whenever any reason is held (foreground OR task active) →
 *     PRIORITY_HIGH_ACCURACY at 1 s cadence. Foreground BALANCED was observed
 *     to produce visible pill-state flicker at the staleness boundary; HIGH
 *     keeps the snapshot age tight.
 *   - when no reason is held the stream is fully stopped (no updates).
 *
 * A watchdog poll fires a single-shot getCurrentLocation if no callback has
 * arrived in [AppConfig.LOCATION_STREAM_WATCHDOG_MS], to recover from a
 * stalled FusedLocation stream (e.g. after coming out of weak-signal cover).
 *
 * Press path reads [snapshotFlow]`.value` synchronously; never suspends.
 */
class LocationStream(context: Context) {

    private val appContext = context.applicationContext
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(appContext)
    private val locationManager: LocationManager? =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    // Satellites used in the current fix, tracked via GnssStatus. FusedLocation
    // does NOT populate Location.extras["satellites"] (it was always 0 → Wialon
    // showed coords as "(0)"). The GnssStatus callback is the supported source
    // for the satellites-used-in-fix count. Updated off the GNSS callback,
    // read synchronously by the press path via toSnapshot().
    @Volatile private var satsUsedInFix: Int = 0
    private var gnssRegistered = false

    private val _snapshotFlow = MutableStateFlow<LocationSnapshot?>(null)
    val snapshotFlow: StateFlow<LocationSnapshot?> = _snapshotFlow.asStateFlow()

    private val lock = Any()
    private val activeReasons: MutableSet<String> = mutableSetOf()
    private var streaming = false
    private var currentPriority: Int = -1
    @Volatile private var lastFixElapsedRealtimeMs: Long = 0L

    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (streaming) {
                // When no fix has EVER arrived (lastFixElapsedRealtimeMs == 0)
                // treat the stream as infinitely stale so the poke fires. The
                // old `> 0` guard meant a cold start that never got a first GPS
                // fix (e.g. indoors, where the continuous HIGH_ACCURACY stream
                // sees 0 satellite fixes) never triggered recovery and the GPS
                // pill stayed yellow forever — even though a good network/fused
                // fix was available via getCurrentLocation.
                val ref = lastFixElapsedRealtimeMs
                val age = if (ref > 0L) SystemClock.elapsedRealtime() - ref else Long.MAX_VALUE
                if (age > AppConfig.LOCATION_STREAM_WATCHDOG_MS) {
                    pokeSingleShot()
                }
            }
            watchdogHandler.postDelayed(this, AppConfig.LOCATION_STREAM_WATCHDOG_CHECK_MS)
        }
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation
            if (loc == null) {
                Log.d(TAG, "onLocationResult: lastLocation NULL (${result.locations.size} locs)")
                return
            }
            applyFix(loc)
        }
    }

    /** Posts to the main looper; used to run the GnssStatus callback. */
    private val mainExecutor = Executor { command -> watchdogHandler.post(command) }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
            }
            satsUsedInFix = used
        }
    }

    fun start(reason: String) {
        synchronized(lock) {
            val wasEmpty = activeReasons.isEmpty()
            activeReasons.add(reason)
            val newPriority = computePriority()
            if (wasEmpty) {
                requestUpdates(newPriority)
                registerGnss()
                streaming = true
                currentPriority = newPriority
                watchdogHandler.postDelayed(
                    watchdogRunnable,
                    AppConfig.LOCATION_STREAM_WATCHDOG_CHECK_MS
                )
                seedFromLastLocation()
                Log.d(TAG, "start: streaming begun reason=$reason priority=$newPriority")
            } else if (newPriority != currentPriority) {
                fusedClient.removeLocationUpdates(callback)
                requestUpdates(newPriority)
                currentPriority = newPriority
            }
        }
    }

    fun stop(reason: String) {
        synchronized(lock) {
            activeReasons.remove(reason)
            if (activeReasons.isEmpty()) {
                if (streaming) {
                    fusedClient.removeLocationUpdates(callback)
                    unregisterGnss()
                    watchdogHandler.removeCallbacks(watchdogRunnable)
                    streaming = false
                    currentPriority = -1
                }
                return
            }
            val newPriority = computePriority()
            if (streaming && newPriority != currentPriority) {
                fusedClient.removeLocationUpdates(callback)
                requestUpdates(newPriority)
                currentPriority = newPriority
            }
        }
    }

    fun isStreaming(): Boolean = synchronized(lock) { streaming }

    private fun computePriority(): Int =
        if (activeReasons.isNotEmpty()) {
            // Task 20: foreground OR task-active both use HIGH_ACCURACY to keep
            // snapshot age tight and kill staleness-boundary pill flicker.
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

    @SuppressLint("MissingPermission")
    private fun registerGnss() {
        if (gnssRegistered) return
        val lm = locationManager ?: return
        try {
            lm.registerGnssStatusCallback(mainExecutor, gnssCallback)
            gnssRegistered = true
        } catch (e: SecurityException) {
            Log.e(TAG, "registerGnssStatusCallback denied", e)
        }
    }

    private fun unregisterGnss() {
        if (!gnssRegistered) return
        locationManager?.unregisterGnssStatusCallback(gnssCallback)
        gnssRegistered = false
        satsUsedInFix = 0
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates(priority: Int) {
        val req = LocationRequest.Builder(priority, AppConfig.LOCATION_STREAM_INTERVAL_MS)
            .setMinUpdateIntervalMillis(AppConfig.LOCATION_STREAM_FASTEST_MS)
            .setMinUpdateDistanceMeters(0f)
            .build()
        fusedClient.requestLocationUpdates(req, callback, Looper.getMainLooper())
            .addOnSuccessListener { Log.d(TAG, "requestLocationUpdates registered ok") }
            .addOnFailureListener { e -> Log.e(TAG, "requestLocationUpdates FAILED", e) }
    }

    /**
     * On stream start, immediately seed the snapshot from the OS-cached
     * lastLocation so the press path doesn't have to wait for the first
     * callback. Only used if the cached fix is itself fresh enough.
     */
    @SuppressLint("MissingPermission")
    private fun seedFromLastLocation() {
        fusedClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && _snapshotFlow.value == null) {
                val snap = loc.toSnapshot()
                val age = SystemClock.elapsedRealtime() - snap.capturedAtMs
                Log.d(TAG, "seed lastLocation: age=${age}ms (limit=${AppConfig.LOCATION_STREAM_STALENESS_MS})")
                if (age <= AppConfig.LOCATION_STREAM_STALENESS_MS) {
                    _snapshotFlow.value = snap
                    lastFixElapsedRealtimeMs = SystemClock.elapsedRealtime()
                }
            } else {
                Log.d(TAG, "seed lastLocation: ${if (loc == null) "NULL" else "snapshot already set"}")
            }
        }.addOnFailureListener { e -> Log.e(TAG, "lastLocation FAILED", e) }
    }

    /**
     * Fallback single-shot fix used by the watchdog when the stream goes
     * quiet for longer than [AppConfig.LOCATION_STREAM_WATCHDOG_MS]. Always
     * uses HIGH_ACCURACY — the watchdog only fires when something is wrong,
     * so battery cost of a single shot is acceptable.
     */
    @SuppressLint("MissingPermission")
    private fun pokeSingleShot() {
        Log.d(TAG, "watchdog: stream quiet > ${AppConfig.LOCATION_STREAM_WATCHDOG_MS}ms — single-shot poke")
        val cts = CancellationTokenSource()
        fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { loc ->
                if (loc != null) applyFix(loc)
                else Log.d(TAG, "watchdog poke: getCurrentLocation returned NULL")
            }
            .addOnFailureListener { e -> Log.e(TAG, "watchdog poke FAILED", e) }
    }

    private fun applyFix(loc: Location) {
        lastFixElapsedRealtimeMs = SystemClock.elapsedRealtime()
        val snap = loc.toSnapshot()
        _snapshotFlow.value = snap
        Log.d(TAG, "applyFix: lat=${loc.latitude} lon=${loc.longitude} acc=${if (loc.hasAccuracy()) loc.accuracy else -1f} hdop=${snap.hdop} sats=${snap.satellites}")
    }

    private fun Location.toSnapshot(): LocationSnapshot = LocationSnapshot(
        latDecimal = latitude,
        lonDecimal = longitude,
        hdop = if (hasAccuracy() && accuracy > 0f)
            (accuracy / 5.0).coerceIn(0.5, 99.9) else null,
        // GnssStatus-derived satellites-used-in-fix; FusedLocation's
        // extras["satellites"] was never populated (always 0 → Wialon "(0)").
        satellites = satsUsedInFix.takeIf { it > 0 },
        speedKmh = speedKmh(hasSpeed(), speed),
        capturedAtMs = elapsedRealtimeNanos / 1_000_000
    )

    companion object {
        private const val TAG = "HAMS_GPS"
        const val REASON_FOREGROUND = "foreground"
        const val REASON_TASK_ACTIVE = "task_active"

        /** Returns true if [snapshot] is fresh enough for the press path to record. */
        fun isFresh(snapshot: LocationSnapshot?): Boolean =
            isFreshAt(snapshot, SystemClock.elapsedRealtime())

        /** Pure: GPS ground speed m/s -> integer km/h, or null when the fix has no speed. */
        fun speedKmh(hasSpeed: Boolean, speedMs: Float): Int? =
            if (hasSpeed) (speedMs * 3.6).roundToInt() else null

        /** Test seam: same logic as [isFresh] with caller-supplied "now" in ms. */
        internal fun isFreshAt(snapshot: LocationSnapshot?, nowMs: Long): Boolean {
            if (snapshot == null) return false
            val age = nowMs - snapshot.capturedAtMs
            return age in 0..AppConfig.LOCATION_STREAM_STALENESS_MS
        }
    }
}
