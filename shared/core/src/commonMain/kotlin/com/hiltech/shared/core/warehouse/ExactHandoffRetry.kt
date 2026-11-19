package com.hiltech.shared.core.warehouse

/**
 * Retains the exact prepared command in the current operator workspace if
 * transport fails after submission. A retry cannot silently reread newer
 * versions and issue a different payload (or switch the accountable employee).
 *
 * The caller serializes commands. Server idempotency and version/authority
 * checks remain authoritative. This is not a persistent offline command queue.
 */
internal class ExactHandoffRetry {
    private data class Pending(val intent: List<Any?>, val send: suspend () -> String)
    private val pending = mutableMapOf<String, Pending>()

    suspend fun execute(
        reservationId: String,
        intent: List<Any?>,
        prepare: suspend () -> (suspend () -> String),
    ): String {
        val existing = pending[reservationId]
        check(existing == null || existing.intent == intent) {
            "Unconfirmed handoff. Retry the original intent or reconcile authoritative custody before a new handoff."
        }
        val command = existing ?: Pending(intent.toList(), prepare()).also { pending[reservationId] = it }
        val receipt = command.send()
        pending.remove(reservationId)
        return receipt
    }
}
