package com.klk.hams.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "device_id")   val deviceId: String,
    @ColumnInfo(name = "task_seq")    val taskSeq: Int,
    /** MYT calendar day the task was started, formatted YYYY-MM-DD. Aligned with task_seq's reset boundary. */
    @ColumnInfo(name = "task_date", defaultValue = "")
    val taskDate: String,
    @ColumnInfo(name = "started_at")  val startedAt: String,
    @ColumnInfo(name = "ended_at")    val endedAt: String? = null,
    @ColumnInfo(name = "plus_count")  val plusCount: Int = 0,
    @ColumnInfo(name = "minus_count") val minusCount: Int = 0,
    @ColumnInfo(name = "net_count")   val netCount: Int = 0,
    @ColumnInfo(name = "push_status") val pushStatus: String = "active",
    @ColumnInfo(name = "save_type")   val saveType: String? = null,
    @ColumnInfo(name = "created_at")  val createdAt: String,
    @ColumnInfo(name = "updated_at")  val updatedAt: String
)
