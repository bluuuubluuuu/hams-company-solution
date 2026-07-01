package com.klk.hams.push

import com.klk.hams.data.model.EventEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [PushEngine] using fake [PushRepository] and fake [IpsSender]
 * implementations. No network, no Room, no Android. Frame-builder failures
 * are exercised via injected error events; success frames are returned
 * verbatim by the fake builder so we can assert ordering.
 */
class PushEngineTest {

    // ---- 1. nothing to push ----

    @Test fun noPendingEventsReturnsSuccessZeroAndDoesNotTouchSender() {
        val repo = FakeRepo(pending = emptyList())
        val sender = FakeSender()
        val engine = newEngine(repo, sender)

        val state = runBlocking { engine.run() }

        assertEquals(PushState.Success(0), state)
        assertEquals(0, sender.loginCalls)
        assertEquals(0, sender.sendFrames.size)
        assertEquals(0, sender.closeCalls)
        assertTrue(repo.uploaded.isEmpty())
        assertTrue(repo.rejected.isEmpty())
        assertTrue(repo.taskFinalizeCalls.isEmpty())
    }

    // ---- 2 & 3. happy paths ----

    @Test fun singleValidEventUploadsAndFinalizesTask() {
        val event = plus(id = 10, taskId = 1)
        val repo = FakeRepo(pending = listOf(event))
        val sender = FakeSender(dataResults = listOf(Result.success(Unit)))
        val engine = newEngine(repo, sender)

        val state = runBlocking { engine.run() }

        assertEquals(PushState.Success(1), state)
        assertEquals(1, sender.loginCalls)
        assertEquals(listOf("FRAME-10"), sender.sendFrames)
        assertEquals(listOf(10L), repo.uploaded)
        assertTrue(repo.rejected.isEmpty())
        assertEquals(listOf(1L), repo.taskFinalizeCalls)
        assertEquals(1, sender.closeCalls)
    }

    @Test fun multipleValidEventsUploadInOrder() {
        val events = listOf(
            plus(id = 1, taskId = 1),
            plus(id = 2, taskId = 1),
            plus(id = 3, taskId = 2),
        )
        val repo = FakeRepo(pending = events)
        val sender = FakeSender(
            dataResults = List(events.size) { Result.success(Unit) },
        )
        val engine = newEngine(repo, sender)

        val state = runBlocking { engine.run() }

        assertEquals(PushState.Success(3), state)
        assertEquals(listOf("FRAME-1", "FRAME-2", "FRAME-3"), sender.sendFrames)
        assertEquals(listOf(1L, 2L, 3L), repo.uploaded)
        // Each touched task id finalised at most once; order preserved.
        assertEquals(listOf(1L, 2L), repo.taskFinalizeCalls)
    }

    // ---- 4. frame-builder rejection (e.g. missing coords) ----

    @Test fun frameBuilderMissingCoordinatesMarksRejectedAndContinues() {
        val a = plus(id = 1, taskId = 1)
        val bad = plus(id = 2, taskId = 1, latDecimal = null) // builder will reject
        val c = plus(id = 3, taskId = 2)
        val repo = FakeRepo(pending = listOf(a, bad, c))
        val sender = FakeSender(
            dataResults = listOf(Result.success(Unit), Result.success(Unit)),
        )
        val engine = newEngine(repo, sender)

        val state = runBlocking { engine.run() }

        assertEquals(PushState.Partial(pushed = 2, failed = 1, total = 3), state)
        // Bad event never reached the wire.
        assertEquals(listOf("FRAME-1", "FRAME-3"), sender.sendFrames)
        assertEquals(listOf(1L, 3L), repo.uploaded)
        assertEquals(1, repo.rejected.size)
        assertEquals(2L, repo.rejected.single().first)
        assertTrue(
            "reason should mention coordinates",
            repo.rejected.single().second.contains("coordinate", ignoreCase = true) ||
                repo.rejected.single().second.contains("latitude", ignoreCase = true),
        )
        // Both tasks touched.
        assertEquals(listOf(1L, 2L), repo.taskFinalizeCalls)
    }

    // ---- 5 & 6. permanent IPS rejection codes ----

    @Test fun frameRejectedAckMarksRejectedAndContinues() {
        val a = plus(id = 1, taskId = 1)
        val b = plus(id = 2, taskId = 1)
        val c = plus(id = 3, taskId = 1)
        val repo = FakeRepo(pending = listOf(a, b, c))
        val sender = FakeSender(
            dataResults = listOf(
                Result.success(Unit),
                Result.failure(WialonException(WialonError.FrameRejected)),
                Result.success(Unit),
            ),
        )
        val engine = newEngine(repo, sender)

        val state = runBlocking { engine.run() }

        assertEquals(PushState.Partial(pushed = 2, failed = 1, total = 3), state)
        assertEquals(listOf("FRAME-1", "FRAME-2", "FRAME-3"), sender.sendFrames)
        assertEquals(listOf(1L, 3L), repo.uploaded)
        assertEquals(2L, repo.rejected.single().first)
        assertEquals(listOf(1L), repo.taskFinalizeCalls)
    }

    @Test fun paramsRejectedAckMarksRejectedAndContinues() {
        val a = plus(id = 1, taskId = 1)
        val b = plus(id = 2, taskId = 1)
        val repo = FakeRepo(pending = listOf(a, b))
        val sender = FakeSender(
            dataResults = listOf(
                Result.failure(WialonException(WialonError.ParamsRejected)),
                Result.success(Unit),
            ),
        )
        val engine = newEngine(repo, sender)

        val state = runBlocking { engine.run() }

        assertEquals(PushState.Partial(pushed = 1, failed = 1, total = 2), state)
        assertEquals(1L, repo.rejected.single().first)
        assertEquals(listOf(2L), repo.uploaded)
        assertEquals(listOf(1L), repo.taskFinalizeCalls)
    }

    // ---- 7. transport failures stop the batch ----

    @Test fun transportFailureStopsBatchAndReturnsFailed() {
        val a = plus(id = 1, taskId = 1)
        val b = plus(id = 2, taskId = 1)
        val c = plus(id = 3, taskId = 1)
        val repo = FakeRepo(pending = listOf(a, b, c))
        val sender = FakeSender(
            dataResults = listOf(
                Result.success(Unit),
                Result.failure(WialonException(WialonError.Transport(java.io.IOException("boom")))),
                // never reached
            ),
        )
        val engine = newEngine(repo, sender)

        val state = runBlocking { engine.run() }

        assertTrue("expected Failed, got $state", state is PushState.Failed)
        val failed = state as PushState.Failed
        assertEquals(1, failed.pushed)
        assertEquals(0, failed.failed)
        // Frame for c (#3) was never sent.
        assertEquals(listOf("FRAME-1", "FRAME-2"), sender.sendFrames)
        assertEquals(listOf(1L), repo.uploaded)
        assertTrue(repo.rejected.isEmpty())
        // Task 1 was touched by the successful upload of #1 — finalisation still attempted.
        assertEquals(listOf(1L), repo.taskFinalizeCalls)
        // Socket cleanup must run on transport failure.
        assertEquals(1, sender.closeCalls)
    }

    @Test fun timeoutStopsBatchAsFailed() {
        val a = plus(id = 1, taskId = 1)
        val b = plus(id = 2, taskId = 1)
        val repo = FakeRepo(pending = listOf(a, b))
        val sender = FakeSender(
            dataResults = listOf(
                Result.failure(WialonException(WialonError.Timeout)),
            ),
        )
        val engine = newEngine(repo, sender)

        val state = runBlocking { engine.run() }

        assertTrue(state is PushState.Failed)
        assertEquals(0, (state as PushState.Failed).pushed)
        assertTrue(repo.uploaded.isEmpty())
        assertTrue(repo.rejected.isEmpty())
    }

    @Test fun unknownAckStopsBatchAsFailed() {
        val a = plus(id = 1, taskId = 1)
        val b = plus(id = 2, taskId = 1)
        val repo = FakeRepo(pending = listOf(a, b))
        val sender = FakeSender(
            dataResults = listOf(
                Result.failure(WialonException(WialonError.Unexpected("#AD#42"))),
            ),
        )
        val engine = newEngine(repo, sender)

        val state = runBlocking { engine.run() }

        assertTrue(state is PushState.Failed)
        assertTrue(repo.uploaded.isEmpty())
        assertTrue(repo.rejected.isEmpty())
    }

    // ---- 8. login failure short-circuits the run ----

    @Test fun loginFailureReturnsFailedAndDoesNotSend() {
        val a = plus(id = 1, taskId = 1)
        val repo = FakeRepo(pending = listOf(a))
        val sender = FakeSender(
            loginResult = Result.failure(WialonException(WialonError.LoginRejected)),
        )
        val engine = newEngine(repo, sender)

        val state = runBlocking { engine.run() }

        assertTrue(state is PushState.Failed)
        assertEquals(1, sender.loginCalls)
        assertTrue(sender.sendFrames.isEmpty())
        assertTrue(repo.uploaded.isEmpty())
        assertTrue(repo.rejected.isEmpty())
        assertTrue(repo.taskFinalizeCalls.isEmpty())
        // Failed login still triggers close so callers can re-create the client.
        assertEquals(1, sender.closeCalls)
    }

    // ---- 9. local-only-but-leaked codes are still rejected safely ----

    @Test fun unknownEventCodeFromBuilderIsMarkedRejectedNotSent() {
        // If repo (incorrectly) returned a 281 row, the engine should not crash;
        // IPSFrameBuilder rejects with UnknownEventCode and the engine continues.
        val leaked = plus(id = 50, taskId = 5).copy(eventCode = 281)
        val ok = plus(id = 51, taskId = 5)
        val repo = FakeRepo(pending = listOf(leaked, ok))
        val sender = FakeSender(dataResults = listOf(Result.success(Unit)))
        val engine = newEngine(repo, sender)

        val state = runBlocking { engine.run() }

        assertEquals(PushState.Partial(pushed = 1, failed = 1, total = 2), state)
        assertEquals(listOf("FRAME-51"), sender.sendFrames)
        assertEquals(50L, repo.rejected.single().first)
        assertNotEquals("", repo.rejected.single().second)
    }

    // ---- 2.7B: chunking, inter-message delay, retry/backoff, pre-flight ----

    @Test fun chunking25EventsWithChunkSize10Uses3Sessions() {
        val events = (1..25L).map { plus(id = it, taskId = if (it <= 13) 1 else 2) }
        val repo = FakeRepo(pending = events)
        val createdSenders: MutableList<FakeSender> = mutableListOf()
        val factory: () -> IpsSender = {
            // Each chunk gets a fresh sender that ack-OKs every frame it sees.
            FakeSender(dataResults = List(10) { Result.success(Unit) })
                .also { createdSenders.add(it) }
        }
        val engine = newEngineWithFactory(repo, factory, chunkSize = 10)

        val state = runBlocking { engine.run() }

        assertEquals(PushState.Success(25), state)
        assertEquals(3, createdSenders.size) // 10 + 10 + 5
        assertEquals(1, createdSenders[0].loginCalls)
        assertEquals(1, createdSenders[1].loginCalls)
        assertEquals(1, createdSenders[2].loginCalls)
        assertEquals(10, createdSenders[0].sendFrames.size)
        assertEquals(10, createdSenders[1].sendFrames.size)
        assertEquals(5, createdSenders[2].sendFrames.size)
        // close called once per chunk via try/finally.
        assertEquals(1, createdSenders[0].closeCalls)
        assertEquals(1, createdSenders[1].closeCalls)
        assertEquals(1, createdSenders[2].closeCalls)
        // 25 uploads, two distinct task ids touched, finalised once each.
        assertEquals(25, repo.uploaded.size)
        assertEquals(listOf(1L, 2L), repo.taskFinalizeCalls)
    }

    @Test fun interMessageDelayCalledBetweenFramesNotAfterFinal() {
        val events = listOf(
            plus(id = 1, taskId = 1),
            plus(id = 2, taskId = 1),
            plus(id = 3, taskId = 1),
        )
        val repo = FakeRepo(pending = events)
        val sender = FakeSender(dataResults = List(3) { Result.success(Unit) })
        val delayer = TestDelayer()
        val engine = newEngineWithFactory(
            repo = repo,
            factory = { sender },
            chunkSize = 10,
            interMessageDelayMs = 75L,
            delayer = delayer,
        )

        runBlocking { engine.run() }

        // 3 frames → 2 inter-message gaps, each 75ms; nothing trailing.
        assertEquals(listOf(75L, 75L), delayer.calls)
    }

    @Test fun interMessageDelayResetsBetweenChunks() {
        // 4 events with chunkSize=2 → chunks of 2, 2. Each chunk has 1
        // inter-message gap (idx 0→1). No leading delay on a new chunk.
        val events = (1..4L).map { plus(id = it, taskId = 1) }
        val repo = FakeRepo(pending = events)
        val factory: () -> IpsSender = {
            FakeSender(dataResults = List(2) { Result.success(Unit) })
        }
        val delayer = TestDelayer()
        val engine = newEngineWithFactory(
            repo = repo,
            factory = factory,
            chunkSize = 2,
            interMessageDelayMs = 50L,
            delayer = delayer,
        )

        runBlocking { engine.run() }

        // 2 chunks * 1 gap = 2 delayer calls; no inter-chunk delay (no retry).
        assertEquals(listOf(50L, 50L), delayer.calls)
    }

    @Test fun transportFailureRetriesAndEventuallySucceeds() {
        val events = listOf(plus(id = 1, taskId = 1), plus(id = 2, taskId = 1))
        val repo = FakeRepo(pending = events)
        val firstSender = FakeSender(
            dataResults = listOf(Result.failure(WialonException(WialonError.Transport(java.io.IOException("boom"))))),
        )
        val secondSender = FakeSender(
            dataResults = listOf(Result.success(Unit), Result.success(Unit)),
        )
        val senders = mutableListOf<IpsSender>(firstSender, secondSender)
        val factory: () -> IpsSender = { senders.removeAt(0) }
        val delayer = TestDelayer()
        val engine = newEngineWithFactory(
            repo = repo,
            factory = factory,
            chunkSize = 10,
            interMessageDelayMs = 0L,
            delayer = delayer,
            maxAttempts = 3,
            backoffScheduleMs = listOf(11L, 22L, 33L),
        )

        val state = runBlocking { engine.run() }

        assertEquals(PushState.Success(2), state)
        // First attempt aborted on first send; second attempt re-fetches and uploads both.
        assertEquals(listOf("FRAME-1"), firstSender.sendFrames)
        assertEquals(listOf("FRAME-1", "FRAME-2"), secondSender.sendFrames)
        // Backoff sleep recorded once between attempt 1 and attempt 2.
        assertEquals(listOf(11L), delayer.calls)
        assertEquals(listOf(1L, 2L), repo.uploaded)
    }

    @Test fun maxAttemptsExhaustedReturnsFailedAndLeavesRemainingPending() {
        val events = listOf(plus(id = 1, taskId = 1), plus(id = 2, taskId = 1))
        val repo = FakeRepo(pending = events)
        // Every attempt yields a sender that aborts on the first send.
        val factory: () -> IpsSender = {
            FakeSender(
                dataResults = listOf(
                    Result.failure(WialonException(WialonError.Timeout)),
                ),
            )
        }
        val delayer = TestDelayer()
        val engine = newEngineWithFactory(
            repo = repo,
            factory = factory,
            chunkSize = 10,
            delayer = delayer,
            maxAttempts = 3,
            backoffScheduleMs = listOf(7L, 13L),
        )

        val state = runBlocking { engine.run() }

        assertTrue("expected Failed, got $state", state is PushState.Failed)
        assertEquals(0, (state as PushState.Failed).pushed)
        assertEquals(0, state.failed)
        // Two backoff sleeps between 3 attempts. 13L is the last entry; it would
        // be reused if more attempts were configured.
        assertEquals(listOf(7L, 13L), delayer.calls)
        // Nothing marked uploaded or rejected → both rows stay pending.
        assertTrue(repo.uploaded.isEmpty())
        assertTrue(repo.rejected.isEmpty())
    }

    @Test fun preFlightCalledOnceBeforePendingQuery() {
        val events = listOf(plus(id = 1, taskId = 1))
        val repo = FakeRepo(pending = events).withFetchTrace()
        val sender = FakeSender(dataResults = listOf(Result.success(Unit)))
        val preFlightCalls = intArrayOf(0)
        val engine = PushEngine(
            repo = repo,
            senderFactory = { sender },
            frameBuilder = ::testFrameBuilder,
            chunkSize = 10,
            interMessageDelayMs = 0L,
            maxAttempts = 1,
            backoffScheduleMs = listOf(1L),
            delayer = { _ -> },
            preFlight = {
                preFlightCalls[0]++
                repo.recordPreFlight()
            },
        )

        runBlocking { engine.run() }

        assertEquals(1, preFlightCalls[0])
        // First trace entry must be "preFlight"; "fetch" follows.
        assertEquals(listOf("preFlight", "fetch"), repo.trace)
    }

    @Test fun permanentRejectionDoesNotTriggerRetry() {
        val events = listOf(plus(id = 1, taskId = 1), plus(id = 2, taskId = 1))
        val repo = FakeRepo(pending = events)
        val sender = FakeSender(
            dataResults = listOf(
                Result.failure(WialonException(WialonError.FrameRejected)),
                Result.success(Unit),
            ),
        )
        val delayer = TestDelayer()
        val engine = newEngineWithFactory(
            repo = repo,
            factory = { sender },
            delayer = delayer,
            maxAttempts = 3,
            backoffScheduleMs = listOf(99L, 99L),
        )

        val state = runBlocking { engine.run() }

        assertEquals(PushState.Partial(pushed = 1, failed = 1, total = 2), state)
        // No backoff sleeps — permanent rejection completes the chunk normally.
        assertTrue("unexpected delays: ${delayer.calls}", delayer.calls.isEmpty())
        assertEquals(listOf(2L), repo.uploaded)
        assertEquals(1L, repo.rejected.single().first)
    }

    @Test fun loginRejectedFailsFastWithoutRetry() {
        val events = listOf(plus(id = 1, taskId = 1))
        val repo = FakeRepo(pending = events)
        val sender = FakeSender(
            loginResult = Result.failure(WialonException(WialonError.LoginRejected)),
        )
        val delayer = TestDelayer()
        val factoryCalls = intArrayOf(0)
        val factory: () -> IpsSender = {
            factoryCalls[0]++
            sender
        }
        val engine = newEngineWithFactory(
            repo = repo,
            factory = factory,
            delayer = delayer,
            maxAttempts = 5,
            backoffScheduleMs = listOf(1L, 2L, 3L, 4L),
        )

        val state = runBlocking { engine.run() }

        assertTrue(state is PushState.Failed)
        assertEquals(1, factoryCalls[0]) // exactly one chunk attempt
        assertEquals(1, sender.loginCalls)
        assertTrue(sender.sendFrames.isEmpty())
        assertTrue("LoginRejected must not back off", delayer.calls.isEmpty())
    }

    // ---- 3.3a: per-run task cap (taskBatchLimit) ----

    @Test fun taskBatchLimit_capsRunToFirstNDistinctTaskIds() {
        // 25 events spread over 25 tasks (one event per task). Cap = 10.
        val events = (1..25L).map { plus(id = it, taskId = it) }
        val repo = FakeRepo(pending = events)
        val factoryCalls = mutableListOf<FakeSender>()
        val factory: () -> IpsSender = {
            FakeSender(dataResults = List(10) { Result.success(Unit) })
                .also { factoryCalls.add(it) }
        }
        val engine = newEngineWithFactory(repo, factory, chunkSize = 10, taskBatchLimit = 10)

        val state = runBlocking { engine.run() }

        // Only first 10 task ids (1..10) drained this run.
        assertEquals(PushState.Success(10), state)
        assertEquals(listOf(1L,2L,3L,4L,5L,6L,7L,8L,9L,10L), repo.uploaded)
        assertEquals(listOf(1L,2L,3L,4L,5L,6L,7L,8L,9L,10L), repo.taskFinalizeCalls)
        // Events 11..25 stay pending (not in uploaded/rejected).
        assertTrue(repo.rejected.isEmpty())
    }

    @Test fun taskBatchLimit_keepsAllEventsOfAcceptedTaskIds() {
        // 3 tasks: task 1 has events [1,2,3], task 2 has [4,5], task 3 has [6,7].
        // Cap = 2 → drain tasks 1 and 2 fully (events 1..5), leave task 3.
        val events = listOf(
            plus(id = 1, taskId = 1), plus(id = 2, taskId = 1), plus(id = 3, taskId = 1),
            plus(id = 4, taskId = 2), plus(id = 5, taskId = 2),
            plus(id = 6, taskId = 3), plus(id = 7, taskId = 3),
        )
        val repo = FakeRepo(pending = events)
        val factory: () -> IpsSender = { FakeSender(dataResults = List(5) { Result.success(Unit) }) }
        val engine = newEngineWithFactory(repo, factory, chunkSize = 10, taskBatchLimit = 2)

        val state = runBlocking { engine.run() }

        assertEquals(PushState.Success(5), state)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), repo.uploaded)
        assertEquals(listOf(1L, 2L), repo.taskFinalizeCalls)
    }

    @Test fun taskBatchLimit_defaultMaxValueDrainsEverything() {
        val events = (1..15L).map { plus(id = it, taskId = it) }
        val repo = FakeRepo(pending = events)
        val factory: () -> IpsSender = { FakeSender(dataResults = List(15) { Result.success(Unit) }) }
        val engine = newEngineWithFactory(repo, factory, chunkSize = 20)  // no taskBatchLimit → default

        val state = runBlocking { engine.run() }

        assertEquals(PushState.Success(15), state)
        assertEquals(15, repo.uploaded.size)
    }

    @Test fun taskBatchLimit_rejectsNonPositive() {
        val repo = FakeRepo(pending = emptyList())
        val factory: () -> IpsSender = { FakeSender() }
        try {
            newEngineWithFactory(repo, factory, taskBatchLimit = 0)
            assertTrue("expected IllegalArgumentException for taskBatchLimit=0", false)
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // ---- 3.4: terminal-state semantics (Codex finding #2 fix) ----

    @Test fun taskWithRejectedFinalEvent_marksTaskFailedNotUploaded() {
        // One event for taskId=1; sender returns FrameRejected → engine marks
        // it pushed=2 (rejected). The fake's rejectedTaskIds reflects that.
        val event = plus(id = 10, taskId = 1)
        val repo = FakeRepo(pending = listOf(event))
        val sender = FakeSender(dataResults = listOf(
            Result.failure(WialonException(WialonError.FrameRejected))
        ))
        val engine = newEngine(repo, sender)
        // Seed the rejection so FakeRepo.markTaskTerminalState renders "failed".
        repo.rejectedTaskIds.add(1L)

        runBlocking { engine.run() }

        assertEquals("failed", repo.taskTerminalStates[1L])
        assertTrue(repo.uploaded.isEmpty())
        assertEquals(1, repo.rejected.size)
        assertEquals(10L, repo.rejected[0].first)
    }

    @Test fun taskWithMixedAcceptsAndRejects_marksTaskFailed() {
        // taskId=1 has two events: first succeeds (pushed=1), second rejected
        // (pushed=2). Terminal state must be "failed", not "uploaded".
        val a = plus(id = 1, taskId = 1)
        val b = plus(id = 2, taskId = 1)
        val repo = FakeRepo(pending = listOf(a, b))
        val sender = FakeSender(dataResults = listOf(
            Result.success(Unit),
            Result.failure(WialonException(WialonError.FrameRejected)),
        ))
        val engine = newEngine(repo, sender)
        repo.rejectedTaskIds.add(1L)

        runBlocking { engine.run() }

        assertEquals("failed", repo.taskTerminalStates[1L])
        assertEquals(listOf(1L), repo.uploaded)
        assertEquals(1, repo.rejected.size)
    }

    @Test fun taskWithAllAccepts_marksTaskUploaded() {
        // Regression: happy path still yields "uploaded".
        val a = plus(id = 1, taskId = 1)
        val b = plus(id = 2, taskId = 1)
        val repo = FakeRepo(pending = listOf(a, b))
        val sender = FakeSender(dataResults = listOf(
            Result.success(Unit), Result.success(Unit)
        ))
        val engine = newEngine(repo, sender)

        runBlocking { engine.run() }

        assertEquals("uploaded", repo.taskTerminalStates[1L])
        assertEquals(listOf(1L, 2L), repo.uploaded)
        assertTrue(repo.rejected.isEmpty())
    }

    // ---- helpers ----

    private fun newEngine(
        repo: FakeRepo,
        sender: FakeSender,
        chunkSize: Int = 1024,
        interMessageDelayMs: Long = 0L,
        delayer: TestDelayer = TestDelayer(),
        preFlight: TestPreFlight = TestPreFlight(),
        maxAttempts: Int = 1,
    ): PushEngine = PushEngine(
        repo = repo,
        senderFactory = { sender },
        frameBuilder = ::testFrameBuilder,
        chunkSize = chunkSize,
        interMessageDelayMs = interMessageDelayMs,
        maxAttempts = maxAttempts,
        backoffScheduleMs = listOf(1L), // never used when maxAttempts=1
        delayer = delayer::record,
        preFlight = { preFlight.invoke() },
    )

    private fun testFrameBuilder(event: EventEntity): Result<String> {
        if (event.latDecimal == null || event.lonDecimal == null) {
            return Result.failure(IPSFrameBuilder.FrameError.MissingCoordinates)
        }
        if (event.eventCode !in setOf(35, 179, 180)) {
            return Result.failure(IPSFrameBuilder.FrameError.UnknownEventCode(event.eventCode))
        }
        return Result.success("FRAME-${event.id}")
    }

    private fun plus(
        id: Long,
        taskId: Long,
        latDecimal: Double? = 2.27,
        lonDecimal: Double? = 103.28,
    ): EventEntity = EventEntity(
        id = id,
        taskId = taskId,
        eventType = "plus",
        eventCode = 179,
        timestamp = "2026-04-23T01:17:0${id % 10}Z",
        latDecimal = latDecimal,
        lonDecimal = lonDecimal,
        hdop = 1.5,
        satellites = 8,
        batteryPct = 91.0,
        workCount = id.toInt(),
        countAfter = id.toInt(),
        pushed = 0,
        createdAt = "2026-04-23T01:17:00Z",
    )

    private class FakeRepo(
        private val pending: List<EventEntity>,
    ) : PushRepository {
        val uploaded: MutableList<Long> = mutableListOf()
        val rejected: MutableList<Pair<Long, String>> = mutableListOf()
        // Per-task terminal-state intent recorded by FakeRepo. Maps task_id to
        // the status the engine asked us to set ("uploaded" or "failed").
        // Codex finding #2 fix (2026-05-15): the engine now distinguishes
        // these two outcomes via markTaskTerminalState.
        val taskFinalizeCalls: MutableList<Long> = mutableListOf()
        val taskTerminalStates: MutableMap<Long, String> = mutableMapOf()
        /** task_ids whose events are seeded as rejected for terminal-state tests. */
        val rejectedTaskIds: MutableSet<Long> = mutableSetOf()
        val trace: MutableList<String> = mutableListOf()
        private var traceEnabled: Boolean = false

        fun withFetchTrace(): FakeRepo = apply { traceEnabled = true }

        /** Hook used by the pre-flight test to record ordering. */
        fun recordPreFlight() {
            if (traceEnabled) trace.add("preFlight")
        }

        override suspend fun pendingPushableEvents(limit: Int): List<EventEntity> {
            if (traceEnabled) trace.add("fetch")
            return pending
        }

        override suspend fun markEventUploaded(eventId: Long) { uploaded.add(eventId) }

        override suspend fun markEventRejected(eventId: Long, reason: String) {
            rejected.add(eventId to reason)
        }

        override suspend fun markTaskTerminalState(taskId: Long) {
            taskFinalizeCalls.add(taskId)
            // Mirror real repo logic: if any rejected events exist for the
            // task in our fake state, the terminal state is "failed".
            // Otherwise — and the engine only calls us after a non-empty
            // attempt — it's "uploaded". The fake doesn't distinguish "still
            // pending"; tests must check that case via uploaded/rejected
            // counts directly.
            taskTerminalStates[taskId] =
                if (taskId in rejectedTaskIds) "failed" else "uploaded"
        }
    }

    private class TestDelayer {
        val calls: MutableList<Long> = mutableListOf()
        suspend fun record(ms: Long) { calls.add(ms) }
    }

    private class TestPreFlight {
        var calls: Int = 0
        suspend fun invoke() { calls++ }
    }

    private fun newEngineWithFactory(
        repo: FakeRepo,
        factory: () -> IpsSender,
        chunkSize: Int = 10,
        interMessageDelayMs: Long = 0L,
        delayer: TestDelayer = TestDelayer(),
        preFlight: TestPreFlight = TestPreFlight(),
        maxAttempts: Int = 1,
        backoffScheduleMs: List<Long> = listOf(1L),
        taskBatchLimit: Int = Int.MAX_VALUE,
    ): PushEngine = PushEngine(
        repo = repo,
        senderFactory = factory,
        frameBuilder = ::testFrameBuilder,
        taskBatchLimit = taskBatchLimit,
        chunkSize = chunkSize,
        interMessageDelayMs = interMessageDelayMs,
        maxAttempts = maxAttempts,
        backoffScheduleMs = backoffScheduleMs,
        delayer = delayer::record,
        preFlight = { preFlight.invoke() },
    )

    private class FakeSender(
        private val loginResult: Result<Unit> = Result.success(Unit),
        private val dataResults: List<Result<Unit>> = emptyList(),
    ) : IpsSender {
        var loginCalls: Int = 0
        var closeCalls: Int = 0
        val sendFrames: MutableList<String> = mutableListOf()
        private var sendIndex: Int = 0

        override suspend fun openAndLogin(): Result<Unit> {
            loginCalls++
            return loginResult
        }

        override suspend fun sendDataFrame(frame: String): Result<Unit> {
            sendFrames.add(frame)
            val r = dataResults.getOrNull(sendIndex)
                ?: error("FakeSender ran out of scripted data results at index $sendIndex")
            sendIndex++
            return r
        }

        override fun close() { closeCalls++ }
    }
}
