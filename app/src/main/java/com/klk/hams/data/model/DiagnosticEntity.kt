package com.klk.hams.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "diagnostics")
data class DiagnosticEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "type")        val type: String,
    @ColumnInfo(name = "timestamp")   val timestamp: String,
    @ColumnInfo(name = "battery_pct") val batteryPct: Double?,
    @ColumnInfo(name = "created_at")  val createdAt: String,
    @ColumnInfo(name = "pushed")      val pushed: Int = 0,
    @ColumnInfo(name = "lat_decimal") val latDecimal: Double? = null,
    @ColumnInfo(name = "lon_decimal") val lonDecimal: Double? = null,
    @ColumnInfo(name = "hdop")        val hdop: Double? = null,
    @ColumnInfo(name = "satellites")  val satellites: Int? = null,
    @ColumnInfo(name = "speed_kmh")   val speedKmh: Int? = null,
)
