package com.klk.hams.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.klk.hams.data.model.Task
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Insert
    suspend fun insert(task: Task): Long

    @Query("SELECT * FROM tasks WHERE push_status = 'active' LIMIT 1")
    suspend fun getActiveTask(): Task?

    @Query("SELECT * FROM tasks WHERE push_status = 'active' LIMIT 1")
    fun observeActiveTask(): Flow<Task?>

    @Query(
        "SELECT COALESCE(MAX(task_seq), 0) FROM tasks " +
        "WHERE started_at >= :dayStartUtc AND started_at < :nextDayStartUtc"
    )
    suspend fun getMaxTaskSeqForDay(dayStartUtc: String, nextDayStartUtc: String): Int

    @Query(
        "UPDATE tasks SET plus_count = :plusCount, minus_count = :minusCount, " +
        "net_count = :netCount, updated_at = :updatedAt WHERE id = :taskId"
    )
    suspend fun updateCounts(
        taskId: Long,
        plusCount: Int,
        minusCount: Int,
        netCount: Int,
        updatedAt: String
    )

    @Query(
        "UPDATE tasks SET push_status = :status, ended_at = :endedAt, " +
        "save_type = :saveType, updated_at = :updatedAt WHERE id = :taskId"
    )
    suspend fun finalizeTask(
        taskId: Long,
        status: String,
        endedAt: String,
        saveType: String,
        updatedAt: String
    )

    @Query("SELECT * FROM tasks WHERE push_status = :status ORDER BY started_at ASC")
    suspend fun getByPushStatus(status: String): List<Task>

    // Used by markTaskTerminalState - only pending tasks are eligible to
    // transition to a terminal state. Active tasks must finish their save flow
    // first; uploaded/failed tasks are already terminal.
    @Query(
        "UPDATE tasks SET push_status = :status, updated_at = :updatedAt " +
        "WHERE id = :taskId AND push_status = 'pending'"
    )
    suspend fun setPushStatusIfPending(taskId: Long, status: String, updatedAt: String)

    @Query("SELECT COUNT(*) FROM tasks WHERE push_status = 'pending'")
    fun observePendingTaskCount(): Flow<Int>

    @Query("SELECT * FROM tasks WHERE push_status = 'pending' ORDER BY ended_at ASC")
    suspend fun pendingTasks(): List<Task>

    // Retention sweep (2026-05-15) — deletes terminal-state tasks older than
    // the configured cutoff. Active and pending tasks are NEVER purged.
    // Events cascade-delete via the FK in EventEntity.
    @Query(
        "DELETE FROM tasks " +
        "WHERE push_status IN ('uploaded','failed','discarded') " +
        "AND created_at < :cutoffIso"
    )
    suspend fun deleteStaleTerminal(cutoffIso: String): Int
}
