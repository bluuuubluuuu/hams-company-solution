package com.klk.hams.push

import com.klk.hams.data.model.DiagnosticEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryPushEngineTest {
    private fun row(id: Long, type: String) = DiagnosticEntity(
        id = id,
        type = type,
        timestamp = "2026-07-02T01:17:06Z",
        batteryPct = 90.0,
        createdAt = "x",
    )

    @Test fun drains_pending_and_marks_uploaded() = runBlocking {
        val uploaded = mutableListOf<Long>()
        val repo = object : TelemetryRepository {
            var rows = mutableListOf(row(1, "boot"), row(2, "start_moving"))
            override suspend fun pendingTelemetry(limit: Int) = rows.toList()
            override suspend fun markTelemetryUploaded(id: Long) {
                uploaded += id
                rows.removeAll { it.id == id }
            }
            override suspend fun markTelemetryRejected(id: Long) {}
        }
        val sender = object : IpsSender {
            override suspend fun openAndLogin() = Result.success(Unit)
            override suspend fun sendDataFrame(frame: String) = Result.success(Unit)
            override fun close() {}
        }

        val engine = TelemetryPushEngine(
            repo,
            { sender },
            frameBuilder = IPSFrameBuilder::telemetryFrame,
            interMessageDelayMs = 0,
        )

        val state = engine.run()

        assertEquals(listOf(1L, 2L), uploaded)
        assertTrue(state is PushState.Success)
    }

    @Test fun frameRejection_marks_rejected_and_continues() = runBlocking {
        val rejected = mutableListOf<Long>()
        val uploaded = mutableListOf<Long>()
        val repo = object : TelemetryRepository {
            var rows = mutableListOf(row(1, "mystery"), row(2, "boot"))
            override suspend fun pendingTelemetry(limit: Int) = rows.toList()
            override suspend fun markTelemetryUploaded(id: Long) {
                uploaded += id
                rows.removeAll { it.id == id }
            }
            override suspend fun markTelemetryRejected(id: Long) {
                rejected += id
                rows.removeAll { it.id == id }
            }
        }
        val sender = object : IpsSender {
            override suspend fun openAndLogin() = Result.success(Unit)
            override suspend fun sendDataFrame(frame: String) = Result.success(Unit)
            override fun close() {}
        }

        TelemetryPushEngine(
            repo,
            { sender },
            IPSFrameBuilder::telemetryFrame,
            interMessageDelayMs = 0,
        ).run()

        assertEquals(listOf(1L), rejected)
        assertEquals(listOf(2L), uploaded)
    }
}
