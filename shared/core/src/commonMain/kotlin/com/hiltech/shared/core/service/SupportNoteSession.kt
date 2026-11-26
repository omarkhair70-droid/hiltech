package com.hiltech.shared.core.service

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechApiException

internal data class SupportNoteTarget(val projectId: String, val ticketId: String, val identityId: String)
internal data class SupportNoteIntent(val target: SupportNoteTarget, val request: CreateInternalSupportNoteDto)

internal fun supportNotesMatch(target: SupportNoteTarget, notes: List<InternalSupportNoteDto>): Boolean =
    notes.all { it.ticketId.equals(target.ticketId, ignoreCase = true) && it.clientVisibility == "INTERNAL_ONLY" } &&
        notes.map { it.noteId.lowercase() }.distinct().size == notes.size

internal fun supportNoteReceiptMatches(intent: SupportNoteIntent, note: InternalSupportNoteDto): Boolean =
    supportNotesMatch(intent.target, listOf(note)) &&
        note.createdByUserId.equals(intent.target.identityId, ignoreCase = true) &&
        note.body == intent.request.body

/**
 * Retain private drafts and unresolved commands only across a verified return
 * to the same actor and organization. Each new API client still uses the new
 * authenticated session boundary; this object holds no credentials or grants.
 * Explicit sign-out and a fresh sign-in discard the previous logical session.
 */
class SupportNoteContinuity {
    private var verifiedScope: Pair<String, String>? = null
    private var state = SupportNoteSession()

    fun bindVerifiedIdentity(identityId: String, organizationId: String?) {
        val next = scope(identityId, organizationId)
        if (next == null || next != verifiedScope) {
            state = SupportNoteSession()
            verifiedScope = next
        }
    }

    fun clear() {
        verifiedScope = null
        state = SupportNoteSession()
    }

    fun client(api: HiltechApiClient, identityId: String?, organizationId: String?): SupportDeskApiClient {
        val requested = scope(identityId, organizationId)
        return if (requested != null && requested == verifiedScope)
            SupportDeskApiClient(api, state) else SupportDeskApiClient(api)
    }

    private fun scope(identityId: String?, organizationId: String?): Pair<String, String>? =
        if (identityId.isNullOrBlank() || organizationId.isNullOrBlank()) null
        else identityId to organizationId
}

/** In-memory only; exposed through a currently authenticated native client. */
internal class SupportNoteSession {
    private var drafts by mutableStateOf<Map<SupportNoteTarget, String>>(emptyMap())
    var pending by mutableStateOf<SupportNoteIntent?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var receipt by mutableStateOf<SupportNoteTarget?>(null)
        private set
    var rejectedTarget by mutableStateOf<SupportNoteTarget?>(null)
        private set
    var requiresReauthentication by mutableStateOf(false)
        private set
    private var unknown = false

    fun draft(target: SupportNoteTarget): String = drafts[target].orEmpty()

    fun edit(target: SupportNoteTarget, text: String) {
        if (pending != null || busy) return
        drafts = if (text.isEmpty()) drafts - target else drafts + (target to text)
        if (receipt == target) receipt = null
        if (rejectedTarget == target) rejectedTarget = null
    }

    fun begin(target: SupportNoteTarget, newOperationId: () -> String): SupportNoteIntent? {
        if (busy || pending != null) return null
        val body = draft(target).trim()
        if (body.length !in 1..8000) return null
        return SupportNoteIntent(target, CreateInternalSupportNoteDto(newOperationId(), body)).also {
            pending = it
            busy = true
            receipt = null
            rejectedTarget = null
            unknown = false
            requiresReauthentication = false
        }
    }

    fun retry(target: SupportNoteTarget): SupportNoteIntent? =
        pending?.takeIf { !busy && it.target == target }?.also {
            busy = true
            requiresReauthentication = false
        }

    fun succeed(intent: SupportNoteIntent, note: InternalSupportNoteDto) {
        check(supportNoteReceiptMatches(intent, note)) { "Support note receipt scope mismatch" }
        if (!busy || pending != intent) return
        drafts = drafts - intent.target
        receipt = intent.target
        rejectedTarget = null
        pending = null
        busy = false
        unknown = false
        requiresReauthentication = false
    }

    fun fail(intent: SupportNoteIntent, failure: Throwable) {
        if (!busy || pending != intent) return
        val api = failure as? HiltechApiException
        requiresReauthentication = api?.code in setOf("REAUTH_REQUIRED", "UNAUTHENTICATED")
        val disposition = serviceDeliveryFailure(unknown, failure)
        if (disposition.rejected && api?.httpStatus != 408) {
            pending = null
            rejectedTarget = intent.target
            unknown = false
        } else {
            // An interrupted request or ambiguous receipt must be retried unchanged.
            unknown = unknown || disposition.unknown || api?.httpStatus == 408
        }
        busy = false
    }
}
