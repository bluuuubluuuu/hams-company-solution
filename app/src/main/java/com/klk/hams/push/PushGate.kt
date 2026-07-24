package com.klk.hams.push

import kotlinx.coroutines.sync.Mutex

/**
 * Serialises the two cut-senders so the same pending `179` row is never sent by
 * both - which would double-count a worker's harvest in Wialon.
 *
 *   - [PushWorker] holds it (`withLock`) around its engine drain.
 *   - The release-time deliver step (ProvisioningEvents) acquires it with
 *     `tryLock`: if the worker already holds it, the deliver step SKIPS - the
 *     worker is already sending those cuts - rather than sending them again.
 *
 * Process-wide single instance; both senders live in the same app process.
 */
object PushGate {
    val mutex = Mutex()
}
