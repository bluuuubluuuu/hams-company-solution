package com.klk.hams.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "diagnostics")
data class DiagnosticEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "type")        val type: String,        // see DiagnosticType.wire
    @ColumnInfo(name = "timestamp")   val timestamp: String,   // ISO 8601 UTC
    @ColumnInfo(name = "battery_pct") val batteryPct: Double?, // nullable; null when unavailable
    @ColumnInfo(name = "created_at")  val createdAt: String,
)
