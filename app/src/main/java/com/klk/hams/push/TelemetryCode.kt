package com.klk.hams.push

/**
 * Telemetry action -> outbound Wialon event_code (Option B, 2026-07-02).
 * Sourced from the MeiTrack P99G GPRS event-code list; power connect/disconnect
 * are HAMS-custom because P99G has no charging event. The code goes straight
 * into the frame's `event_code` param; there is no `diag_type`.
 */
object TelemetryCode {
    private val TABLE = mapOf(
        "boot" to 29,
        "shutdown" to 40,
        "gps_lost" to 24,
        "gps_recovery" to 25,
        "stop_moving" to 41,
        "start_moving" to 42,
        "screen_on" to 27,
        "screen_off" to 26,
        "power_connected" to 43,
        "power_disconnected" to 44,
        "binding_released" to 301,
        "device_bound" to 303,
        "device_unbound" to 304,
    )

    fun eventCodeFor(type: String): Int? = TABLE[type]
}
