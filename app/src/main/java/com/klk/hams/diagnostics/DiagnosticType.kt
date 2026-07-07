package com.klk.hams.diagnostics

import android.content.Intent

enum class DiagnosticType(val wire: String) {
    BOOT("boot"),
    SHUTDOWN("shutdown"),
    SCREEN_ON("screen_on"),
    SCREEN_OFF("screen_off"),
    POWER_CONNECTED("power_connected"),
    POWER_DISCONNECTED("power_disconnected"),
    START_MOVING("start_moving"),
    STOP_MOVING("stop_moving"),
    GPS_LOST("gps_lost"),
    GPS_RECOVERY("gps_recovery"),
    BINDING_RELEASED("binding_released"),
    BINDING_TAKEN("binding_taken");

    companion object {
        fun fromAction(action: String?): DiagnosticType? = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> BOOT
            Intent.ACTION_SHUTDOWN -> SHUTDOWN
            Intent.ACTION_SCREEN_ON -> SCREEN_ON
            Intent.ACTION_SCREEN_OFF -> SCREEN_OFF
            Intent.ACTION_POWER_CONNECTED -> POWER_CONNECTED
            Intent.ACTION_POWER_DISCONNECTED -> POWER_DISCONNECTED
            else -> null
        }
    }
}
