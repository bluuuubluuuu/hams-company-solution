package com.klk.hams.push

import com.klk.hams.AppConfig
import com.klk.hams.data.model.EventEntity
import kotlinx.coroutines.delay

/**
 * Push-flow surface consumed by [PushEngine]. Mirrors the methods on
 * `TaskRepository` that the engine actually needs, so the engine can be
 * exercised on the JVM with fakes — no Room, no Android.
 */
interface PushRepository {
    /** Pending pushable events, ordered for deterministic replay. */
    suspend fun pendingPushableEvents(limit: Int = Int.MAX_VALUE): List<EventEntity>

    /** Mark an event as uploaded (`pushed = 1`). */
    suspend fun markEventUploaded(eventId: Long)

    /** Mark an event as permanently rejected (`pushed = 2`); reason is for diagnostics. */
    suspend fun markEventRejected(eventId: Long, reason: String)

    /** Flip the task's `push_status` to `uploaded` or `failed` per spec §3.2. */
    suspend fun markTaskTerminalState(taskId: Long)
}

/**
 * Single-session IPS transport surface used by [PushEngine]. The Phase 2
 * Task 2.6 [WialonIPSClient] satisfies this interface; tests substitute a
 * fake without touching sockets. A fresh sender is created per chunk so
 * each chunk gets its own login frame and socket lifetime.
 */
interface IpsSender {
    suspend fun openAndLogin(): Result<Unit>
    suspend fun sendDataFrame(frame: String): Result<Unit>
    fun close()
}

/**
 * Phase 2 Task 2.7 — full batch orchestration over [IpsSender].
 *
 * Flow per `run()`:
 *
 * 1. [preFlight] hook (no-op default). Caller injects pre-push local save
 *    here; it must not create outbound custom event codes.
 * 2. Outer attempt loop, up to [maxAttempts]:
 *    a. Fetch pending pushable events from [repo].
 *    b. Empty → return Success/Partial based on prior attempts.
 *    c. Chunk by [chunkSize]; each chunk gets a fresh [IpsSender] from
 *       [senderFactory], its own login frame, and is closed before the next
 *       chunk opens.
 *    d. Within a chunk, send frames sequentially with [interMessageDelayMs]
 *       between frames (no leading or trailing delay). Per-event:
 *       - frame-builder failure / `#AD#-1`/`#AD#0`/`#AD#10..14`/`#AD#15`
 *         → `markEventRejected`, continue.
 *       - `#AD#1` → `markEventUploaded`.
 *       - Transport / Timeout / Unexpected / NotConnected → abort chunk,
 *         break out to retry-with-backoff.
 *       - `LoginRejected` / `LoginPasswordError` (during chunk login) →
 *         fail-fast, no retry.
 * 3. Between attempts, sleep [backoffScheduleMs] (clamped to last entry).
 *    Re-fetching pending lets newly-inserted events join the next attempt.
 * 4. After [maxAttempts] failed attempts, return [PushState.Failed]. Any
 *    rows already marked uploaded/rejected stay marked; remaining rows
 *    stay `pushed = 0` for the next `run()`.
 *
 * Touched task ids are deduped (`LinkedHashSet`) and finalised via
 * `markTaskTerminalState` after every attempt that
 * progressed at least one event, regardless of terminal state.
 *
 * **Out of scope (Task 2.8):** Wi-Fi monitor, runtime call site,
 * `StateFlow<PushState>` for UI/notification.
 */
class PushEngine(
    private val repo: PushRepository,
    private val senderFactory: () -> IpsSender,
    private val frameBuilder: (EventEntity) -> Result<String> = IPSFrameBuilder::dataFrame,
    private val batchLimit: Int = Int.MAX_VALUE,
    /**
     * Per-run cap on the number of distinct task IDs whose events this engine
     * will drain in one `run()`. Resilience knob (added Task 3.3a, 2026-05-15):
     * limits worker run duration so a Wi-Fi flap interrupts at most this many
     * tasks' worth of events. Default `Int.MAX_VALUE` preserves the
     * pre-3.3 "drain everything" behaviour for existing tests; production
     * callers (`PushWorker`) pass `10` per the Phase 3 plan. Filtering keeps
     * the first N distinct task IDs in fetch order — pending fetch already
     * orders by `timestamp ASC, id ASC`, so the cap honours oldest-task-first
     * draining. Inside this cap, [chunkSize] still bounds events per TCP
     * session unchanged.
     */
    private val taskBatchLimit: Int = Int.MAX_VALUE,
    private val chunkSize: Int = AppConfig.BATCH_SIZE,
    private val interMessageDelayMs: Long = AppConfig.BATCH_DELAY_MS,
    private val maxAttempts: Int = AppConfig.MAX_RETRY_ATTEMPTS,
    private val backoffScheduleMs: List<Long> = DEFAULT_BACKOFF_MS,
    private val delayer: suspend (Long) -> Unit = ::defaultDelay,
    private val preFlight: suspend () -> Unit = NO_PRE_FLIGHT,
    /**
     * Invoked when a **task** fully drains (its last pushable event got `#AD#1`),
     * with cumulative `(tasksDone, tasksTotal)`. `tasksTotal` is snapshotted from
     * the first attempt that finds pending events and stays fixed across retries.
     * Default no-op preserves existing tests/call sites.
     *
     * Semantics change (2026-05-13): callbacks are now per-task-completion, not
     * per-event-ack. `PushWorker` uses this to update the foreground notification
     * with task-level progress ("Uploading task 1 of 2" → "task 2 of 2" → terminal)
     * silently; only the terminal notification at the end of a worker run alerts.
     */
    private val onProgress: suspend (tasksDone: Int, tasksTotal: Int) -> Unit = NO_PROGRESS,
) {

    init {
        require(chunkSize > 0) { "chunkSize must be positive, got $chunkSize" }
        require(maxAttempts > 0) { "maxAttempts must be positive, got $maxAttempts" }
        require(taskBatchLimit > 0) { "taskBatchLimit must be positive, got $taskBatchLimit" }
        require(backoffScheduleMs.isNotEmpty()) { "backoffScheduleMs must not be empty" }
    }

    suspend fun run(): PushState {
        preFlight()

        var totalUploaded = 0
        var totalFailed = 0
        var lastReason = "unknown failure"
        val touchedTaskIds = LinkedHashSet<Long>()
        // Per-task progress (2026-05-13 field feedback): notify the caller only
        // when a task fully drains, not on every event ack. The denominator is
        // the number of distinct task_ids in the first non-empty pending
        // snapshot; it stays fixed across retries so notifications don't
        // reshuffle if a new event arrives mid-run.
        val taskEventsRemaining = mutableMapOf<Long, Int>()
        var tasksTotal = 0
        var tasksDone = 0

        for (attempt in 1..maxAttempts) {
            val pending = capByTaskBatchLimit(repo.pendingPushableEvents(batchLimit))
            if (taskEventsRemaining.isEmpty() && pending.isNotEmpty()) {
                for (e in pending) taskEventsRemaining.merge(e.taskId, 1, Int::plus)
                tasksTotal = taskEventsRemaining.size
            }
            if (pending.isEmpty()) {
                finalizeTouchedTasks(touchedTaskIds)
                return terminalState(totalUploaded, totalFailed)
            }

            var aborted = false
            var failFast = false

            chunkLoop@ for (chunk in pending.chunked(chunkSize)) {
                val sender = senderFactory()
                try {
                    val loginResult = sender.openAndLogin()
                    if (loginResult.isFailure) {
                        val cause = loginResult.exceptionOrNull()
                        lastReason = describe(cause)
                        if (isFailFast(cause)) failFast = true
                        aborted = true
                        break@chunkLoop
                    }
                    for ((idx, event) in chunk.withIndex()) {
                        if (idx > 0 && interMessageDelayMs > 0L) delayer(interMessageDelayMs)
                        val frameResult = frameBuilder(event)
                        if (frameResult.isFailure) {
                            val reason = describe(frameResult.exceptionOrNull())
                            repo.markEventRejected(event.id, reason)
                            touchedTaskIds.add(event.taskId)
                            totalFailed++
                            continue
                        }
                        val sendResult = sender.sendDataFrame(frameResult.getOrThrow())
                        if (sendResult.isSuccess) {
                            repo.markEventUploaded(event.id)
                            touchedTaskIds.add(event.taskId)
                            totalUploaded++
                            // Task-level progress: only notify when this task's
                            // last pushable event drains. If a new task arrives
                            // mid-run, its taskId won't be in the snapshot map —
                            // we still decrement what's known and report against
                            // the original total.
                            val remaining = (taskEventsRemaining[event.taskId] ?: 0) - 1
                            if (taskEventsRemaining.containsKey(event.taskId)) {
                                taskEventsRemaining[event.taskId] = remaining
                                if (remaining <= 0 && tasksTotal > 0) {
                                    tasksDone++
                                    onProgress(tasksDone, tasksTotal)
                                }
                            }
                            continue
                        }
                        val cause = sendResult.exceptionOrNull()
                        val err = (cause as? WialonException)?.error
                        if (err is WialonError.FrameRejected ||
                            err is WialonError.ParamsRejected
                        ) {
                            repo.markEventRejected(event.id, describe(cause))
                            touchedTaskIds.add(event.taskId)
                            totalFailed++
                            continue
                        }
                        // Transport / Timeout / Unexpected / NotConnected.
                        lastReason = describe(cause)
                        aborted = true
                        break@chunkLoop
                    }
                } finally {
                    sender.close()
                }
            }

            finalizeTouchedTasks(touchedTaskIds)

            if (!aborted) {
                return terminalState(totalUploaded, totalFailed)
            }
            if (failFast) {
                return PushState.Failed(lastReason, totalUploaded, totalFailed)
            }
            if (attempt < maxAttempts) {
                val backoff = backoffFor(attempt)
                if (backoff > 0L) delayer(backoff)
            }
        }

        return PushState.Failed(lastReason, totalUploaded, totalFailed)
    }

    /**
     * Truncate the pending list to events belonging to the first
     * [taskBatchLimit] distinct task IDs encountered (in fetch order). Fetch
     * order is `timestamp ASC, id ASC`, so the cap drains oldest tasks first.
     * Returns the input unchanged when the cap is `Int.MAX_VALUE`.
     */
    private fun capByTaskBatchLimit(pending: List<EventEntity>): List<EventEntity> {
        if (taskBatchLimit == Int.MAX_VALUE || pending.isEmpty()) return pending
        val keepTaskIds = LinkedHashSet<Long>()
        for (e in pending) {
            if (keepTaskIds.size >= taskBatchLimit) break
            keepTaskIds.add(e.taskId)
        }
        if (keepTaskIds.size < taskBatchLimit) return pending
        return pending.filter { it.taskId in keepTaskIds }
    }

    private fun terminalState(uploaded: Int, failed: Int): PushState =
        if (failed == 0) PushState.Success(uploaded)
        else PushState.Partial(uploaded, failed, uploaded + failed)

    private suspend fun finalizeTouchedTasks(taskIds: MutableSet<Long>) {
        if (taskIds.isEmpty()) return
        val snapshot = taskIds.toList()
        taskIds.clear()
        for (id in snapshot) repo.markTaskTerminalState(id)
    }

    private fun backoffFor(attempt: Int): Long {
        val idx = (attempt - 1).coerceIn(0, backoffScheduleMs.lastIndex)
        return backoffScheduleMs[idx]
    }

    private fun isFailFast(cause: Throwable?): Boolean {
        val err = (cause as? WialonException)?.error ?: return false
        return err is WialonError.LoginRejected || err is WialonError.LoginPasswordError
    }

    private fun describe(cause: Throwable?): String = when (cause) {
        null -> "unknown failure"
        is WialonException -> cause.error.toString()
        else -> cause.message ?: cause::class.java.simpleName
    }

    companion object {
        /**
         * Default exponential backoff between attempts (ms). Index `attempt-1`,
         * clamped to the last entry once exhausted. Aligned with `CLAUDE.md`
         * "30/60/120/240s, max 5 attempts" plus a final 300s fallback so a
         * 5-attempt run still has a defined wait before its terminal report.
         */
        val DEFAULT_BACKOFF_MS: List<Long> = listOf(
            30_000L, 60_000L, 120_000L, 240_000L, 300_000L,
        )

        /** No-op pre-flight hook; callers inject `repo.autoSaveActiveOnWifi(...)` etc. */
        val NO_PRE_FLIGHT: suspend () -> Unit = {}

        /** No-op progress callback (engine default when no caller wires foreground updates). */
        val NO_PROGRESS: suspend (Int, Int) -> Unit = { _, _ -> }

        private suspend fun defaultDelay(ms: Long) {
            if (ms > 0) delay(ms)
        }
    }
}
