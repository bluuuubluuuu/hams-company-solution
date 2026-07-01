package com.klk.hams.data.model

import android.os.SystemClock

data class LocationSnapshot(
    val latDecimal: Double,
    val lonDecimal: Double,
    val hdop: Double?,
    val satellites: Int?,
    val speedKmh: Int? = null,
    val capturedAtMs: Long = SystemClock.elapsedRealtime()
)
