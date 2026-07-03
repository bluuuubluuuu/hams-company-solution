package com.klk.hams.push

import com.klk.hams.AppConfig
import com.klk.hams.data.model.DiagnosticEntity
import kotlinx.coroutines.delay

interface TelemetryRepository {
    suspend fun pendingTelemetry(limit: Int = Int.MAX_VALUE): List<DiagnosticEntity>
    suspend fun markTelemetryUploaded(id: Long)
    suspend fun markTelemetryRejected(id: Long)
}

class TelemetryPushEngine(
    private val repo: TelemetryRepository,
    private val senderFactory: () -> IpsSender,
    private val frameBuilder: (DiagnosticEntity) -> Result<String> = IPSFrameBuilder::telemetryFrame,
    private val chunkSize: Int = AppConfig.BATCH_SIZE,
    private val interMessageDelayMs: Long = AppConfig.BATCH_DELAY_MS,
    private val delayer: suspend (Long) -> Unit = { if (it > 0) delay(it) },
) {
    suspend fun run(): PushState {
        val pending = repo.pendingTelemetry()
        if (pending.isEmpty()) return PushState.Success(0)
        var uploaded = 0
        var failed = 0

        for (chunk in pending.chunked(chunkSize)) {
            val sender = senderFactory()
            try {
                if (sender.openAndLogin().isFailure) return terminal(uploaded, failed)
                for ((idx, row) in chunk.withIndex()) {
                    if (idx > 0 && interMessageDelayMs > 0) delayer(interMessageDelayMs)
                    val frame = frameBuilder(row)
                    if (frame.isFailure) {
                        repo.markTelemetryRejected(row.id)
                        failed++
                        continue
                    }
                    val sendResult = sender.sendDataFrame(frame.getOrThrow())
                    if (sendResult.isSuccess) {
                        repo.markTelemetryUploaded(row.id)
                        uploaded++
                    } else {
                        val err = (sendResult.exceptionOrNull() as? WialonException)?.error
                        if (err is WialonError.FrameRejected || err is WialonError.ParamsRejected) {
                            repo.markTelemetryRejected(row.id)
                            failed++
                        } else {
                            return terminal(uploaded, failed)
                        }
                    }
                }
            } finally {
                sender.close()
            }
        }
        return terminal(uploaded, failed)
    }

    private fun terminal(uploaded: Int, failed: Int): PushState =
        if (failed == 0) {
            PushState.Success(uploaded)
        } else {
            PushState.Partial(uploaded, failed, uploaded + failed)
        }
}
