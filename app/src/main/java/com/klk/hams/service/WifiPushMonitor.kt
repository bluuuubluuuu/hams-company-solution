package com.klk.hams.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Pure capability check — extracted so the accept/reject decision is
 * unit-testable without Android. A network is pushable when it is Wi-Fi
 * transport, unmetered, and internet-validated.
 */
fun isPushableNetwork(wifi: Boolean, unmetered: Boolean, validated: Boolean): Boolean =
    wifi && unmetered && validated

/**
 * Push-reliability upgrade (2026-05-22). Registers a [ConnectivityManager]
 * network callback for validated unmetered Wi-Fi and invokes [onPushableUp]
 * each time such a network becomes available. Owned by
 * [HamsForegroundService]; lives as long as the service.
 *
 * This is the live replacement for WorkManager's constraint-deferred job,
 * which aggressive OEMs purge during idle. A registered callback in a live
 * foreground-service process is not purged the same way.
 */
class WifiPushMonitor(
    private val context: Context,
    private val onPushableUp: () -> Unit,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (isPushableNetwork(wifi, unmetered, validated)) {
                Log.d("HAMS_PUSH", "WifiPushMonitor: pushable Wi-Fi up — triggering push")
                onPushableUp()
            }
        }
    }

    fun register() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            .build()
        cm?.registerNetworkCallback(request, callback)
        registered = true
    }

    fun unregister() {
        if (!registered) return
        runCatching { cm?.unregisterNetworkCallback(callback) }
        registered = false
    }
}
