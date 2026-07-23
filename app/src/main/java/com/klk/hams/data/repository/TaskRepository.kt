package com.klk.hams.data.repository

import androidx.room.withTransaction
import com.klk.hams.AppConfig
import com.klk.hams.data.db.AppDatabase
import com.klk.hams.data.model.EventEntity
import com.klk.hams.data.model.LocationSnapshot
import com.klk.hams.data.model.Task
import com.klk.hams.push.PushEligibility
import com.klk.hams.push.TelemetryRepository
import com.klk.hams.time.Clock
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TaskRepository(
    private val db: AppDatabase,
    private val clock: com.klk.hams.time.Clock = Clock,
    private val deviceIdProvider: () -> String = { AppConfig.DEVICE_UNIQUE_ID },
) : TelemetryRepository {
    private val taskDao = db.taskDao()
    private val eventDao = db.eventDao()
    private val diagnosticDao = db.diagnosticDao()

    /** Set by HamsApp to enqueue auto-push after every task finalization. */
    @Volatile
    var onTaskFinalized: (() -> Unit)? = null

    /**
     * Device/behaviour telemetry row. Pushed via the diagnostics telemetry drain.
     *
     * [timestampIso] overrides the event time only (not `created_at`): used to
     * backfill a missed shutdown dated at the last-seen instant while keeping the
     * row's creation time fresh so the retention sweep does not purge it early.
     *
     * [lostTasks] / [lostCuts] are null for every diagnostic except the release
     * markers (302 work_stranded, 304 device_unbound, 2026-07-23) — only those
     * carry a leftover figure, and the frame builder omits the params when null.
     */
    suspend fun recordDiagnostic(
        type: com.klk.hams.diagnostics.DiagnosticType,
        batteryPct: Double?,
        snapshot: LocationSnapshot? = null,
        timestampIso: String? = null,
        pushed: Int = 0,
        lostTasks: Int? = null,
        lostCuts: Int? = null,
    ): Long {
        val now = clock.nowUtcIso()
        return diagnosticDao.insert(
            com.klk.hams.data.model.DiagnosticEntity(
                type = type.wire,
                timestamp = timestampIso ?: now,
                batteryPct = batteryPct,
                createdAt = now,
                pushed = pushed,
                latDecimal = snapshot?.latDecimal,
                lonDecimal = snapshot?.lonDecimal,
                hdop = snapshot?.hdop,
                satellites = snapshot?.satellites,
                speedKmh = snapshot?.speedKmh,
                lostTasks = lostTasks,
                lostCuts = lostCuts,
            )
        )
    }

    /**
     * Records a + press. If no active task exists, lazily creates the task row,
     * inserts the 281 (new_task) marker event (pushed=1), then inserts the + event -
     * all in a single DB transaction.
     *
     * Returns null when the active task is already at MAX_COUNT_PER_TASK; the
     * transaction is the source of truth for the cap and the UI guard is only
     * a fast path. Without this, fast repeated taps near the cap can race past it.
     */
    suspend fun recordPlus(location: LocationSnapshot, batteryPct: Double): EventEntity? {
        val now = clock.nowUtcIso()
        return db.withTransaction {
            val existing = taskDao.getActiveTask()
            if (existing != null && existing.netCount >= AppConfig.MAX_COUNT_PER_TASK) {
                return@withTransaction null
            }
            val (taskId, currentPlus, currentMinus) = if (existing != null) {
                Triple(existing.id, existing.plusCount, existing.minusCount)
            } else {
                // Lazy creation: task row + 281 marker
                val seq = getNextTaskSeqInternal(now)
                val id = taskDao.insert(
                    Task(
                        deviceId = deviceIdProvider(),
                        taskSeq = seq,
                        taskDate = mytDateOf(now),
                        startedAt = now,
                        plusCount = 0,
                        minusCount = 0,
                        netCount = 0,
                        pushStatus = "active",
                        createdAt = now,
                        updatedAt = now
                    )
                )
                eventDao.insert(
                    EventEntity(
                        taskId = id,
                        eventType = "new_task",
                        eventCode = AppConfig.EVENT_CODE_NEW_TASK,
                        timestamp = now,
                        latDecimal = location.latDecimal,
                        lonDecimal = location.lonDecimal,
                        hdop = location.hdop,
                        satellites = location.satellites,
                        speed = location.speedKmh,
                        batteryPct = batteryPct,
                        workCount = 0,
                        countAfter = 0,
                        pushed = PushEligibility.pushedFlagOnInsert(AppConfig.EVENT_CODE_NEW_TASK, 0),
                        createdAt = now
                    )
                )
                Triple(id, 0, 0)
            }

            val newPlusCount = currentPlus + 1
            val newNetCount = newPlusCount - currentMinus

            val event = EventEntity(
                taskId = taskId,
                eventType = "plus",
                eventCode = AppConfig.EVENT_CODE_PLUS,
                timestamp = now,
                latDecimal = location.latDecimal,
                lonDecimal = location.lonDecimal,
                hdop = location.hdop,
                satellites = location.satellites,
                speed = location.speedKmh,
                batteryPct = batteryPct,
                workCount = newNetCount,
                countAfter = newNetCount,
                pushed = PushEligibility.pushedFlagOnInsert(AppConfig.EVENT_CODE_PLUS, newNetCount),
                createdAt = now
            )
            val eventId = eventDao.insert(event)
            taskDao.updateCounts(taskId, newPlusCount, currentMinus, newNetCount, now)
            event.copy(id = eventId)
        }
    }

    /**
     * Records a - press. Returns null if count is already 0 (blocked at UI level too).
     * If the resulting work_count == 0, sets pushed=1 (self-cancelling pair, stays local).
     */
    suspend fun recordMinus(location: LocationSnapshot, batteryPct: Double): EventEntity? {
        val now = clock.nowUtcIso()
        return db.withTransaction {
            val task = taskDao.getActiveTask() ?: return@withTransaction null
            if (task.netCount <= 0) return@withTransaction null

            val newMinusCount = task.minusCount + 1
            val newNetCount = task.plusCount - newMinusCount
            val pushed = PushEligibility.pushedFlagOnInsert(AppConfig.EVENT_CODE_MINUS, newNetCount)

            val event = EventEntity(
                taskId = task.id,
                eventType = "minus",
                eventCode = AppConfig.EVENT_CODE_MINUS,
                timestamp = now,
                latDecimal = location.latDecimal,
                lonDecimal = location.lonDecimal,
                hdop = location.hdop,
                satellites = location.satellites,
                speed = location.speedKmh,
                batteryPct = batteryPct,
                workCount = newNetCount,
                countAfter = newNetCount,
                pushed = pushed,
                createdAt = now
            )
            val eventId = eventDao.insert(event)
            taskDao.updateCounts(task.id, task.plusCount, newMinusCount, newNetCount, now)
            event.copy(id = eventId)
        }
    }

    /**
     * Finalises the active task and (for auto_killed / auto_wifi) writes a
     * **local-only** audit row (event_code 283 / 284). Zero-count tasks are
     * never saved.
     *
     * Under dictionary v1.2 these audit rows are not outbound-approved Wialon
     * event_code values: PushEligibility marks them `pushed = 1`, EventDao's
     * allow-list excludes them, and IPSFrameBuilder rejects them with
     * `UnknownEventCode`. They exist purely so we keep a "what happened, where,
     * with what battery" record on the device.
     *
     * Returns the saved task's id when an active counted task was finalised,
     * or null when there was no active task or the active task had zero count.
     * Manual saves do not write an audit row but still return the task id.
     */
    suspend fun saveActiveTask(
        saveType: String,
        location: LocationSnapshot?,
        batteryPct: Double
    ): Long? {
        val now = clock.nowUtcIso()
        // Codex finding #3 fix (2026-05-15): onTaskFinalized must fire AFTER
        // withTransaction returns. Otherwise PushWorker can start before the
        // commit and read pendingTasks() as empty, stranding the new task.
        val savedId: Long? = db.withTransaction {
            val task = taskDao.getActiveTask() ?: return@withTransaction null
            if (task.netCount <= 0) return@withTransaction null  // never persist empty tasks

            val eventCode = when (saveType) {
                "auto_killed" -> AppConfig.EVENT_CODE_AUTO_SAVE_KILL
                "auto_wifi"   -> AppConfig.EVENT_CODE_AUTO_SAVE_WIFI
                else          -> null  // "manual" — no audit row generated
            }

            if (eventCode != null) {
                val event = EventEntity(
                    taskId = task.id,
                    eventType = "auto_save",
                    eventCode = eventCode,
                    timestamp = now,
                    latDecimal = location?.latDecimal,
                    lonDecimal = location?.lonDecimal,
                    hdop = location?.hdop,
                    satellites = location?.satellites,
                    speed = location?.speedKmh,
                    batteryPct = batteryPct,
                    workCount = task.netCount,
                    countAfter = task.netCount,
                    pushed = PushEligibility.pushedFlagOnInsert(eventCode, task.netCount),
                    createdAt = now
                )
                eventDao.insert(event)
            }

            taskDao.finalizeTask(task.id, "pending", now, saveType, now)
            task.id
        }
        if (savedId != null) onTaskFinalized?.invoke()
        return savedId
    }

    /**
     * Records a periodic beacon event (event_code 35). Pushable per dictionary
     * v1.2; insert-marked via [PushEligibility].
     *
     * The location parameter is non-null by design. Heartbeat 35 is the only
     * pushable event source in this set, so a null-coordinate row would land
     * in `EventDao.getPending` (`pushed = 0 AND event_code IN (179, 180, 35)`)
     * and then be rejected by `IPSFrameBuilder` with `MissingCoordinates` —
     * polluting the queue every cycle. Callers without a valid fix must skip
     * the call entirely; battery-only telemetry rides the next 179 / 180 / 35
     * row that does have coordinates.
     *
     * Returns null when no active task exists. Heartbeats are scoped to active
     * tasks because the `events.task_id` column is NOT NULL; idle-period
     * battery visibility resumes the next time the worker starts a task.
     */
    suspend fun recordHeartbeat(
        location: LocationSnapshot,
        batteryPct: Double
    ): EventEntity? {
        val now = clock.nowUtcIso()
        return db.withTransaction {
            val task = taskDao.getActiveTask() ?: return@withTransaction null
            val event = EventEntity(
                taskId = task.id,
                eventType = "heartbeat",
                eventCode = AppConfig.EVENT_CODE_HEARTBEAT,
                timestamp = now,
                latDecimal = location.latDecimal,
                lonDecimal = location.lonDecimal,
                hdop = location.hdop,
                satellites = location.satellites,
                speed = location.speedKmh,
                batteryPct = batteryPct,
                workCount = task.netCount,
                countAfter = task.netCount,
                pushed = PushEligibility.pushedFlagOnInsert(
                    AppConfig.EVENT_CODE_HEARTBEAT, task.netCount
                ),
                createdAt = now
            )
            val id = eventDao.insert(event)
            event.copy(id = id)
        }
    }

    /**
     * Records a battery-edge audit row (event_code 291 warn or 292 critical).
     * Local-only under v1.2 — [PushEligibility] insert-marks the row
     * `pushed = 1`. Battery itself still rides every pushed 179/180/35 message
     * via the `battery` param.
     *
     * Returns null when no active task exists; the caller (`BatteryEdgeObserver`)
     * is responsible for persisting the bucket state regardless, so an idle
     * downward crossing is not retroactively logged when a later task starts.
     */
    suspend fun recordBatteryEdge(
        eventCode: Int,
        batteryPct: Double,
        location: LocationSnapshot?
    ): EventEntity? {
        require(
            eventCode == AppConfig.EVENT_CODE_BATTERY_WARN ||
                eventCode == AppConfig.EVENT_CODE_BATTERY_CRITICAL
        ) { "recordBatteryEdge expects 291 or 292, got $eventCode" }
        val now = clock.nowUtcIso()
        return db.withTransaction {
            val task = taskDao.getActiveTask() ?: return@withTransaction null
            val event = EventEntity(
                taskId = task.id,
                eventType = "battery",
                eventCode = eventCode,
                timestamp = now,
                latDecimal = location?.latDecimal,
                lonDecimal = location?.lonDecimal,
                hdop = location?.hdop,
                satellites = location?.satellites,
                speed = location?.speedKmh,
                batteryPct = batteryPct,
                workCount = task.netCount,
                countAfter = task.netCount,
                pushed = PushEligibility.pushedFlagOnInsert(eventCode, task.netCount),
                createdAt = now
            )
            val id = eventDao.insert(event)
            event.copy(id = id)
        }
    }

    /**
     * Records a GPS-degraded audit row (event_code 293). Local-only under
     * v1.2; insert-marked `pushed = 1` by [PushEligibility].
     *
     * Returns null when no active task exists. The detector
     * ([GpsDegradedDetector]) is the leading-edge gate; this method does not
     * itself dedupe.
     */
    suspend fun recordGpsDegraded(
        location: LocationSnapshot?,
        batteryPct: Double
    ): EventEntity? {
        val now = clock.nowUtcIso()
        return db.withTransaction {
            val task = taskDao.getActiveTask() ?: return@withTransaction null
            val event = EventEntity(
                taskId = task.id,
                eventType = "gps_poor",
                eventCode = AppConfig.EVENT_CODE_GPS_DEGRADED,
                timestamp = now,
                latDecimal = location?.latDecimal,
                lonDecimal = location?.lonDecimal,
                hdop = location?.hdop,
                satellites = location?.satellites,
                speed = location?.speedKmh,
                batteryPct = batteryPct,
                workCount = task.netCount,
                countAfter = task.netCount,
                pushed = PushEligibility.pushedFlagOnInsert(
                    AppConfig.EVENT_CODE_GPS_DEGRADED, task.netCount
                ),
                createdAt = now
            )
            val id = eventDao.insert(event)
            event.copy(id = id)
        }
    }

    fun observeActiveTask(): Flow<Task?> = taskDao.observeActiveTask()

    fun observePendingTaskCount(): Flow<Int> = taskDao.observePendingTaskCount()

    suspend fun pendingTasks(): List<Task> = taskDao.pendingTasks()

    /** For "Next Task #N" display in the UI before the task row is created. */
    suspend fun getNextTaskSeq(): Int = getNextTaskSeqInternal(clock.nowUtcIso())

    /**
     * If there is an active task whose [Task.taskDate] is older than today's MYT
     * date, finalize it so today's first + press lazy-creates a fresh task at
     * task_seq=1 for the new day.
     *
     * Behaviour:
     *   - active task with `taskDate == today` → no-op, returns null.
     *   - active task with stale `taskDate` and `netCount > 0` → finalize as
     *     `save_type="auto_rollover"`, `push_status="pending"` so any unpushed
     *     179/180/35 events from yesterday still drain when Wi-Fi reconnects.
     *   - active task with stale `taskDate` and `netCount == 0` → finalize as
     *     `push_status="discarded"`, no audit event row.
     *
     * No audit event row is written for rollovers — there is no real location
     * or battery snapshot at app-launch time, and the task row itself is the
     * audit. Returns the rolled-over task id, or null when nothing was stale.
     */
    suspend fun rolloverActiveTaskIfStale(): Long? {
        val now = clock.nowUtcIso()
        val today = mytDateOf(now)
        // Codex finding #3 fix (2026-05-15): onTaskFinalized fires AFTER
        // the transaction commits — see saveActiveTask for the rationale.
        val savedId: Long? = db.withTransaction {
            val active = taskDao.getActiveTask() ?: return@withTransaction null
            if (active.taskDate == today) return@withTransaction null
            val newStatus = if (active.netCount > 0) "pending" else "discarded"
            taskDao.finalizeTask(active.id, newStatus, now, "auto_rollover", now)
            active.id
        }
        if (savedId != null) onTaskFinalized?.invoke()
        return savedId
    }

    /** Finalizes the in-progress task so counted cuts can flush before a
     *  release logout. Non-zero tasks become pending; empty tasks are discarded. */
    suspend fun finalizeActiveTaskForRelease(): Long? {
        val now = clock.nowUtcIso()
        return db.withTransaction {
            val active = taskDao.getActiveTask() ?: return@withTransaction null
            val status = if (active.netCount > 0) "pending" else "discarded"
            taskDao.finalizeTask(active.id, status, now, "auto_released", now)
            active.id
        }
    }

    // --- Push flow surface (consumed by Phase 2 PushEngine) ---

    /**
     * Events queued for push, ordered for replay (`timestamp ASC, id ASC`).
     *
     * Per dictionary v1.2 the underlying `EventDao.getPending` allow-list
     * returns only outbound-approved rows: `event_code IN (179, 180, 35) AND
     * NOT (event_code = 180 AND work_count <= 0)`. Local-only rows (281, 283,
     * 284, 291, 292, 293, 279, 280) are insert-marked `pushed = 1` and would
     * be filtered by the `pushed = 0` clause regardless.
     */
    suspend fun pendingPushableEvents(limit: Int = Int.MAX_VALUE): List<EventEntity> =
        eventDao.getPending(limit)

    override suspend fun pendingTelemetry(limit: Int) =
        diagnosticDao.pending(limit)

    suspend fun countPendingTelemetry(): Int =
        diagnosticDao.countPending()

    fun observePendingTelemetryCount(): Flow<Int> =
        diagnosticDao.observePendingCount()

    override suspend fun markTelemetryUploaded(id: Long) =
        diagnosticDao.markPushed(id)

    override suspend fun markTelemetryRejected(id: Long) =
        diagnosticDao.markRejected(id)

    suspend fun diagnosticPushedState(id: Long): Int? =
        diagnosticDao.pushedState(id)

    /** Leftover accounting at release time (302 work_stranded, 2026-07-23). */
    data class UnsentWork(val tasks: Int, val cuts: Int)

    /**
     * What this device would fail to deliver if it unbound right now.
     *
     * Both figures are counted over `events`, not `tasks`, so already-stranded
     * work (`pushed = 2`) is never re-reported on a later release. `cuts` counts
     * `event_code = 179` only — that is the harvest figure a report would have
     * produced, and matching it keeps the loss metric and the harvest metric on
     * the same arithmetic.
     */
    suspend fun countUnsentWork(): UnsentWork = UnsentWork(
        tasks = eventDao.countUnsentTasks(),
        cuts = eventDao.countUnsentCuts(),
    )

    /** Unpushed telemetry ids, snapshotted before a release drain. */
    suspend fun pendingTelemetryIds(): List<Long> = diagnosticDao.pendingIds()

    /**
     * Permanently rejects every still-pending event row and drives
     * `push_status = 'pending'` tasks to their terminal state. These are two
     * different row sets: `eventDao.strandAllPending()` is global (`WHERE
     * pushed = 0`), so it also strands the rows of a still-`active` task,
     * while `taskDao.pendingTasks()` only advances tasks already in
     * `'pending'`. Called at release **only when the 302/304 marker
     * landed**, so the receipt and the kill are atomic — destroying harvest
     * with no record is worse than misfiling it.
     *
     * **Precondition: callers MUST finalize the active task before calling
     * this.** If an active task's rows are still `pushed = 0` when this
     * runs, they are stranded (`pushed = 2`) but that task's `push_status`
     * is left `'active'` — it is not driven terminal here, and the returned
     * count still includes its rows. It resolves on the next push run, when
     * `PushWorker`'s terminal-state sweep drives it to `'failed'`.
     *
     * Rows are marked `pushed = 2`, not deleted. They survive in SQLite for
     * `AppConfig.SQLITE_RETENTION_DAYS` and are recoverable by a DB pull.
     *
     * Returns the number of event rows stranded.
     */
    suspend fun strandUnsentWork(): Int = db.withTransaction {
        val affected = taskDao.pendingTasks().map { it.id }
        val stranded = eventDao.strandAllPending()
        for (taskId in affected) markTaskTerminalState(taskId)
        stranded
    }

    /** Marks an event as successfully uploaded. */
    suspend fun markEventUploaded(eventId: Long) {
        eventDao.markPushed(listOf(eventId), 1)
    }

    /**
     * Marks an event as permanently rejected by the gateway (e.g. malformed
     * frame, params block error). The reason is logged; no SQLite column
     * stores it yet - add one when push diagnostics graduate from logs.
     */
    suspend fun markEventRejected(eventId: Long, reason: String) {
        System.err.println("[TaskRepository] event $eventId rejected: $reason")
        eventDao.markPushed(listOf(eventId), 2)
    }

    /**
     * Transitions a pending task to its terminal state (Codex finding #2 fix,
     * 2026-05-15):
     *   - `'failed'`   if any event for the task was permanently rejected
     *                   (`pushed = 2`). Wialon never accepted that data; the
     *                   task must NOT be reported as uploaded.
     *   - `'uploaded'` if no pending rows remain and no rejected rows exist.
     *   - no-op        if pending rows still remain for the task.
     *
     * Active or already-terminal tasks are left alone (the guard lives in
     * `taskDao.setPushStatusIfPending`).
     */
    suspend fun markTaskTerminalState(taskId: Long) {
        db.withTransaction {
            val pending = eventDao.countPushablePendingForTask(taskId)
            if (pending > 0) return@withTransaction
            val rejected = eventDao.countRejectedForTask(taskId)
            val status = if (rejected > 0) "failed" else "uploaded"
            taskDao.setPushStatusIfPending(taskId, status, clock.nowUtcIso())
        }
    }

    /**
     * Retention sweep (Codex adversarial-fix design §4, 2026-05-15). Deletes
     * `tasks` rows in a terminal state (`uploaded` / `failed` / `discarded`)
     * whose `created_at` is older than [retentionDays] from the current UTC
     * instant. Events cascade-delete via the FK in [EventEntity].
     *
     * `active` and `pending` tasks are NEVER purged, regardless of age — they
     * represent in-progress or unsynced work.
     *
     * Returns the number of task rows deleted (caller may log).
     */
    suspend fun purgeStaleTerminalTasks(
        retentionDays: Int = AppConfig.SQLITE_RETENTION_DAYS
    ): Int {
        require(retentionDays > 0) { "retentionDays must be positive, got $retentionDays" }
        val cutoff = java.time.Instant.now()
            .minus(retentionDays.toLong(), java.time.temporal.ChronoUnit.DAYS)
            .toString()
        return taskDao.deleteStaleTerminal(cutoff)
    }

    /**
     * Retention sweep for the local `diagnostics` table (Req 4a). Deletes rows
     * whose `created_at` is older than [retentionDays] from the current UTC
     * instant. Diagnostics are local-only audit (never pushed), so age is the
     * only retention criterion. Returns the number of rows deleted.
     */
    suspend fun purgeStaleDiagnostics(
        retentionDays: Int = AppConfig.SQLITE_RETENTION_DAYS
    ): Int {
        require(retentionDays > 0) { "retentionDays must be positive, got $retentionDays" }
        val cutoff = java.time.Instant.now()
            .minus(retentionDays.toLong(), java.time.temporal.ChronoUnit.DAYS)
            .toString()
        return diagnosticDao.deleteOlderThan(cutoff)
    }

    /**
     * First step of the Wi-Fi push flow: if an active task has count > 0,
     * finalise it as "auto_wifi". Writes a local-only 284 audit row (never
     * pushed under dictionary v1.2) and flips the task's push_status to
     * "pending" so the engine can drain its outbound rows.
     *
     * Returns the saved task's id when something was saved, null on no-op
     * (no active task or zero count). The push engine never receives an
     * outbound event from this call — it triggers on `getPending()`.
     */
    @Deprecated(
        "Task 2.8 spec: push and task lifecycle are independent. Push only operates on " +
            "tasks already in push_status='pending'. Active-task finalization happens via " +
            "manual save (NEW TASK 5s), app swipe (auto_killed), or day rollover (auto_rollover). " +
            "Do not call from new code.",
        level = DeprecationLevel.WARNING
    )
    suspend fun autoSaveActiveOnWifi(
        batteryPct: Double,
        location: LocationSnapshot?
    ): Long? = saveActiveTask("auto_wifi", location, batteryPct)

    private suspend fun getNextTaskSeqInternal(utcIsoTimestamp: String): Int {
        val zone = ZoneId.of("Asia/Kuala_Lumpur")
        val mytDate: LocalDate = Instant.parse(utcIsoTimestamp).atZone(zone).toLocalDate()
        val dayStart = mytDate.atStartOfDay(zone).toInstant().toString()
        val nextDayStart = mytDate.plusDays(1).atStartOfDay(zone).toInstant().toString()
        return taskDao.getMaxTaskSeqForDay(dayStart, nextDayStart) + 1
    }

    /** UTC ISO-8601 timestamp -> MYT calendar day "YYYY-MM-DD". */
    private fun mytDateOf(utcIsoTimestamp: String): String =
        Instant.parse(utcIsoTimestamp)
            .atZone(ZoneId.of("Asia/Kuala_Lumpur"))
            .toLocalDate()
            .toString()
}
