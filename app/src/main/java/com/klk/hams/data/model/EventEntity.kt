package com.klk.hams.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "events",
    foreignKeys = [ForeignKey(
        entity = Task::class,
        parentColumns = ["id"],
        childColumns = ["task_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("task_id"), Index("pushed")]
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "task_id")     val taskId: Long,
    @ColumnInfo(name = "event_type")  val eventType: String,
    @ColumnInfo(name = "event_code")  val eventCode: Int,
    @ColumnInfo(name = "timestamp")   val timestamp: String,
    @ColumnInfo(name = "lat_decimal") val latDecimal: Double?,
    @ColumnInfo(name = "lon_decimal") val lonDecimal: Double?,
    @ColumnInfo(name = "hdop")        val hdop: Double?,
    @ColumnInfo(name = "satellites")  val satellites: Int?,
    @ColumnInfo(name = "speed")       val speed: Int? = null,
    @ColumnInfo(name = "battery_pct") val batteryPct: Double,
    @ColumnInfo(name = "work_count")  val workCount: Int,
    @ColumnInfo(name = "count_after") val countAfter: Int,
    @ColumnInfo(name = "pushed")      val pushed: Int = 0,
    @ColumnInfo(name = "created_at")  val createdAt: String
)
