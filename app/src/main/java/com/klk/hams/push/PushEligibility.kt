package com.klk.hams.push

/**
 * Push policy for V6 events under dictionary v1.2 (2026-04-30).
 *
 * Applied at insert time to set the row's `pushed` column; the runtime push
 * engine then trusts that column without re-deciding.
 *
 * Outbound-approved Wialon event_code values (the only ones that may queue):
 *  - 179 — FFB plus / cut. Always queue.
 *  - 180 — FFB productive minus / correction. Queue only when `work_count > 0`
 *          after the decrement (Rule 1 in the dictionary). Self-cancelling
 *          minus pairs stay local.
 *  -  35 — Periodic beacon ("heartbeat" in HAMS code/UI; P99L "Track By Time
 *          Interval"). Always queue.
 *
 * Local-only HAMS event codes — these may exist in SQLite for audit/UI/state
 * purposes but must never queue for outbound push:
 *  - 281 (new task marker), 283/284 (auto-save), 291/292 (battery edges),
 *    293 (GPS degraded), 279/280 (legacy dev counting codes).
 *
 * Coordinate validity is enforced separately by `IPSFrameBuilder` at frame-build
 * time — rows with missing lat/lon never produce a frame.
 */
object PushEligibility {

    /**
     * Whether a freshly-captured event with this `event_code` and post-event
     * `work_count` should be inserted with `pushed = 0` (queued for push) versus
     * `pushed = 1` (local-only).
     */
    fun shouldQueueForPush(eventCode: Int, workCount: Int): Boolean = when (eventCode) {
        179  -> true                 // FFB plus / cut
        180  -> workCount > 0        // FFB correction: skip self-cancelling pairs
        35   -> true                 // periodic beacon
        else -> false                // everything else (281, 283, 284, 291, 292, 293,
                                     // 279, 280, unknowns) is local-only
    }

    /**
     * Convenience accessor returning the `pushed` value to insert with.
     * 0 = queue for push, 1 = local-only.
     */
    fun pushedFlagOnInsert(eventCode: Int, workCount: Int): Int =
        if (shouldQueueForPush(eventCode, workCount)) 0 else 1
}
