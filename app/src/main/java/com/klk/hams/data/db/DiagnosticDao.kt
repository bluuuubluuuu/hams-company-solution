package com.klk.hams.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.klk.hams.data.model.DiagnosticEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DiagnosticDao {
    @Insert suspend fun insert(row: DiagnosticEntity): Long

    @Query("SELECT * FROM diagnostics ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int = 100): List<DiagnosticEntity>

    @Query("SELECT * FROM diagnostics WHERE pushed = 0 ORDER BY timestamp ASC, id ASC LIMIT :limit")
    suspend fun pending(limit: Int = Int.MAX_VALUE): List<DiagnosticEntity>

    @Query("SELECT COUNT(*) FROM diagnostics WHERE pushed = 0")
    suspend fun countPending(): Int

    @Query("SELECT COUNT(*) FROM diagnostics WHERE pushed = 0")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT pushed FROM diagnostics WHERE id = :id")
    suspend fun pushedState(id: Long): Int?

    // Snapshot of every unpushed row, taken before a release drain so the caller
    // can reject whatever did not land. Any row still pending after the drain
    // belongs to the unit being left and must never push under the next one.
    @Query("SELECT id FROM diagnostics WHERE pushed = 0 ORDER BY timestamp ASC, id ASC")
    suspend fun pendingIds(): List<Long>

    @Query("UPDATE diagnostics SET pushed = 1 WHERE id = :id")
    suspend fun markPushed(id: Long)

    @Query("UPDATE diagnostics SET pushed = 2 WHERE id = :id")
    suspend fun markRejected(id: Long)

    // Retention sweep (Req 4a) deletes diagnostics rows older than the
    // configured cutoff. screen_on/off fire frequently, so this table grows
    // unbounded without it. No FK, nothing cascades.
    @Query("DELETE FROM diagnostics WHERE created_at < :cutoffIso")
    suspend fun deleteOlderThan(cutoffIso: String): Int
}
