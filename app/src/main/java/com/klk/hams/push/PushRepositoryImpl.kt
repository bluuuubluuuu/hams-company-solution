package com.klk.hams.push

import com.klk.hams.data.model.EventEntity
import com.klk.hams.data.repository.TaskRepository

/**
 * Adapts [TaskRepository] to [PushRepository] for [PushEngine].
 *
 * All methods delegate. Task 2.8 keeps the engine ignorant of Room/Android —
 * everything DB-shaped lives behind this interface.
 */
class PushRepositoryImpl(
    private val repo: TaskRepository
) : PushRepository {

    override suspend fun pendingPushableEvents(limit: Int): List<EventEntity> =
        repo.pendingPushableEvents(limit)

    override suspend fun markEventUploaded(eventId: Long) {
        repo.markEventUploaded(eventId)
    }

    override suspend fun markEventRejected(eventId: Long, reason: String) {
        repo.markEventRejected(eventId, reason)
    }

    override suspend fun markTaskTerminalState(taskId: Long) {
        repo.markTaskTerminalState(taskId)
    }
}
