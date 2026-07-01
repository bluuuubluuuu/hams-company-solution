package com.klk.hams.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.klk.hams.data.model.DiagnosticEntity

@Dao
interface DiagnosticDao {
    @Insert suspend fun insert(row: DiagnosticEntity): Long

    @Query("SELECT * FROM diagnostics ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int = 100): List<DiagnosticEntity>

    // Retention sweep (Req 4a) — deletes diagnostics rows older than the
    // configured cutoff. screen_on/off fire frequently, so this table grows
    // unbounded without it. No FK, nothing cascades.
    @Query("DELETE FROM diagnostics WHERE created_at < :cutoffIso")
    suspend fun deleteOlderThan(cutoffIso: String): Int
}
