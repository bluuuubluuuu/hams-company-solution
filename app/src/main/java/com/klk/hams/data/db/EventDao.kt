package com.klk.hams.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.klk.hams.data.model.EventEntity

@Dao
interface EventDao {

    @Insert
    suspend fun insert(event: EventEntity): Long

    // Phase 2 push engine reads this. Allow-list per dictionary v1.2 — only
    // outbound-approved Wialon event_code values may be returned:
    //   pushed = 0                                       pending uploads only
    //   event_code IN (179, 180, 35)                     outbound-approved trio
    //   NOT (event_code = 180 AND work_count <= 0)       skip self-cancelling minus
    // Tie-break on id so two events captured in the same millisecond push in
    // insertion order. Local-only codes (281, 283, 284, 291, 292, 293, 279, 280)
    // are insert-marked pushed=1 by PushEligibility and would already be
    // filtered by the pushed=0 clause; the allow-list is defence-in-depth.
    @Query(
        "SELECT * FROM events " +
        "WHERE pushed = 0 " +
        "AND event_code IN (179, 180, 35) " +
        "AND NOT (event_code = 180 AND work_count <= 0) " +
        "ORDER BY timestamp ASC, id ASC LIMIT :limit"
    )
    suspend fun getPending(limit: Int = 10): List<EventEntity>

    @Query("UPDATE events SET pushed = :pushed WHERE id IN (:ids)")
    suspend fun markPushed(ids: List<Long>, pushed: Int)

    @Query("SELECT COUNT(*) FROM events WHERE task_id = :taskId")
    suspend fun countForTask(taskId: Long): Int

    // Mirrors getPending's allow-list exactly so a task is "fully drained" iff
    // getPending would return zero rows for it.
    @Query(
        "SELECT COUNT(*) FROM events " +
        "WHERE task_id = :taskId " +
        "AND pushed = 0 " +
        "AND event_code IN (179, 180, 35) " +
        "AND NOT (event_code = 180 AND work_count <= 0)"
    )
    suspend fun countPushablePendingForTask(taskId: Long): Int

    // Used by markTaskTerminalState (Codex finding #2 fix, 2026-05-15) to
    // detect tasks that have permanently-rejected events. Mirrors getPending's
    // allow-list — rejection is only meaningful for outbound-approved codes.
    @Query(
        "SELECT COUNT(*) FROM events " +
        "WHERE task_id = :taskId " +
        "AND pushed = 2 " +
        "AND event_code IN (179, 180, 35) " +
        "AND NOT (event_code = 180 AND work_count <= 0)"
    )
    suspend fun countRejectedForTask(taskId: Long): Int

    // --- Release-time leftover accounting (302 work_stranded, 2026-07-23) ---

    // Tasks that still hold unsent rows. Counted over `events`, not `tasks`, so
    // already-stranded work (pushed = 2) is never re-reported on a later release.
    @Query("SELECT COUNT(DISTINCT task_id) FROM events WHERE pushed = 0")
    suspend fun countUnsentTasks(): Int

    // The harvest figure: 179 only. Counting the full pushable set would let
    // heartbeats dominate (a phone with 6 cuts and 400 beacons would report 406).
    @Query("SELECT COUNT(*) FROM events WHERE pushed = 0 AND event_code = 179")
    suspend fun countUnsentCuts(): Int

    // Marks every still-pending row permanently rejected. Applied at release
    // unconditionally, whether or not the 302/304 marker reached the gateway.
    // Covers 180 and 35 as well as 179 — they belong to the departing unit too.
    // Code-agnostic (WHERE pushed = 0), unlike countRejectedForTask's event_code
    // filter above. Safe today only because PushEligibility.shouldQueueForPush
    // keeps pushed = 0 a subset of getPending's allow-list (179/180/35) — if that
    // invariant ever breaks, a stranded task could read back as 'uploaded'.
    @Query("UPDATE events SET pushed = 2 WHERE pushed = 0")
    suspend fun strandAllPending(): Int

    // --- Deliver-before-strand snapshot accounting (Approach A, 2026-07-23) ---

    // The harvest snapshot taken BEFORE the release deliver step. Ordered like
    // getPending so a partial deliver drains oldest-first.
    @Query("SELECT id FROM events WHERE pushed = 0 AND event_code = 179 ORDER BY timestamp ASC, id ASC")
    suspend fun pendingCutIds(): List<Long>

    // Of the snapshot, how many did NOT reach Wialon (pushed != 1). Counts both
    // still-queued (0) and rejected (2), so a PushEngine rejection can't hide as
    // a clean 304. This is lost_cuts.
    @Query("SELECT COUNT(*) FROM events WHERE id IN (:ids) AND pushed != 1")
    suspend fun countNotUploadedAmong(ids: List<Long>): Int

    // Distinct tasks among the not-uploaded snapshot ids - for the operator sheet
    // ("N tasks / M cuts discarded"). Never goes on the wire.
    @Query("SELECT COUNT(DISTINCT task_id) FROM events WHERE id IN (:ids) AND pushed != 1")
    suspend fun countTasksNotUploadedAmong(ids: List<Long>): Int

    // Guarded uploaded-mark (review finding P1a). Only 0 -> 1 is a valid ack
    // transition. Without the `pushed = 0` guard, a PushWorker running
    // concurrently with the release path could flip a just-stranded row
    // (pushed = 2) back to 1 after the 302 already reported it lost, leaving
    // inconsistent state. This makes the strand-vs-worker interleaving benign:
    // once stranded, a row stays stranded.
    @Query("UPDATE events SET pushed = 1 WHERE id = :id AND pushed = 0")
    suspend fun markUploadedIfPending(id: Long)
}
