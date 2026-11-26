package com.hiltech.shared.core.service

import com.hiltech.shared.core.network.ErrorEnvelope
import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechApiException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupportNoteSessionTest {
    private val target = SupportNoteTarget("project-a", "ticket-a", "employee-a")
    private val other = target.copy(ticketId = "ticket-b")
    private fun note(t: SupportNoteTarget = target, body: String = "Checked connection") =
        InternalSupportNoteDto("note-a", t.ticketId, body, "INTERNAL_ONLY", t.identityId,
            "2026-11-26T10:00:00Z", false)

    @Test
    fun unknownOutcomeRetainsTheExactOriginalRequestAndBlocksOtherTickets() {
        val session = SupportNoteSession()
        session.edit(target, "  Checked connection  ")
        val intent = assertNotNull(session.begin(target) { "operation-a" })
        assertEquals("Checked connection", intent.request.body)
        assertNull(session.begin(other) { error("No second operation") })
        session.fail(intent, IllegalStateException("lost response"))
        assertFalse(session.busy)
        assertEquals(intent, session.pending)
        session.edit(target, "Changed content")
        assertNull(session.retry(other))
        assertEquals(intent, session.retry(target))
        session.succeed(intent, note())
        assertNull(session.pending)
        assertEquals("", session.draft(target))
        assertEquals(target, session.receipt)
    }

    @Test
    fun wrongScopeOrContentCannotConfirmAWrite() {
        val session = SupportNoteSession()
        session.edit(target, "Checked connection")
        val intent = assertNotNull(session.begin(target) { "operation-a" })
        assertFailsWith<IllegalStateException> { session.succeed(intent, note(other)) }
        assertFailsWith<IllegalStateException> { session.succeed(intent, note(body = "other content")) }
        assertFalse(supportNotesMatch(target, listOf(note().copy(clientVisibility = "PUBLIC"))))
        assertFalse(supportNotesMatch(target, listOf(note(other))))
        assertFalse(supportNotesMatch(target, listOf(note(), note())))
        assertTrue(supportNotesMatch(target, listOf(note())))
        assertEquals(intent, session.pending)
        assertNull(session.receipt)
    }

    @Test
    fun separateAuthenticatedClientsCannotShareDraftsOrPendingNotes() {
        val first = SupportNoteSession()
        val second = SupportNoteSession()
        first.edit(target, "Checked connection")
        first.begin(target) { "operation-a" }
        assertEquals("", second.draft(target))
        assertNull(second.pending)
        assertNull(second.retry(target))
    }

    @Test
    fun rejectedFirstRequestPreservesDraftButLaterDenialCannotEraseUnknownOutcome() {
        val session = SupportNoteSession()
        val code = "SUPPORT_PROJECT_NOT_FOUND"
        val denial = HiltechApiException(code, "test-correlation", 404, false,
            ErrorEnvelope(code, "Unavailable", "test-correlation"), "Unavailable")
        session.edit(target, "Checked connection")
        val first = assertNotNull(session.begin(target) { "operation-a" })
        session.fail(first, denial)
        assertNull(session.pending)
        assertEquals(target, session.rejectedTarget)
        assertEquals("Checked connection", session.draft(target))
        val second = assertNotNull(session.begin(target) { "operation-b" })
        session.fail(second, IllegalStateException("response lost"))
        assertEquals(second, session.retry(target))
        session.fail(second, denial)
        assertEquals(second, session.pending)
        assertNull(session.receipt)
    }

    @Test
    fun authenticatedNoteRequestReplaysSameBodyAndKeyAfterLostResponse() = runBlocking {
        val source = SupportNoteTarget("11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333")
        val op = "44444444-4444-4444-8444-444444444444"
        val installation = "55555555-5555-4555-8555-555555555555"
        val body = "Checked connection"
        val recorded = note(source, body).copy(noteId = "66666666-6666-4666-8666-666666666666")
        val requests = mutableListOf<Pair<String?, String>>()
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/projects/${source.projectId}/service-tickets/${source.ticketId}/notes", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("Bearer synthetic-support-token", request.headers[HttpHeaders.Authorization])
            assertEquals(installation, request.headers["X-Device-Installation-Id"])
            requests += request.headers["Idempotency-Key"] to
                (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            if (requests.size == 1) respond("Service unavailable", HttpStatusCode.ServiceUnavailable)
            else respond(Json.encodeToString(InternalSupportNoteDto.serializer(), recorded.copy(duplicateReplay = true)),
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = SupportDeskApiClient(HiltechApiClient(http, "https://api.hiltech.test",
                { "synthetic-support-token" }, { "synthetic-correlation" }))
            val session = api.noteSession
            session.edit(source, body)
            val intent = assertNotNull(session.begin(source) { op })
            val failure = assertFailsWith<HiltechApiException> {
                api.addInternalNote(source.projectId, source.ticketId, intent.request, installation)
            }
            session.fail(intent, failure)
            val retry = assertNotNull(session.retry(source))
            val result = api.addInternalNote(source.projectId, source.ticketId, retry.request, installation)
            session.succeed(retry, result)
            assertEquals(2, requests.size)
            assertEquals(requests.first(), requests.last())
            assertEquals(op, requests.first().first)
            assertTrue(result.duplicateReplay)
            assertEquals(source, session.receipt)
            assertNull(session.pending)
        } finally {
            http.close()
        }
    }

    @Test
    fun verifiedReturnToSameActorAndOrganizationRetainsUnresolvedRequest() {
        val http = HttpClient(MockEngine { error("No network needed") })
        try {
            val continuation = SupportNoteContinuity()
            fun api() = HiltechApiClient(http, "https://api.hiltech.test", { "synthetic" }, { "synthetic" })
            continuation.bindVerifiedIdentity(target.identityId, "org-a")
            val first = continuation.client(api(), target.identityId, "org-a").noteSession
            first.edit(target, "Checked connection")
            val intent = assertNotNull(first.begin(target) { "operation-a" })
            first.fail(intent, IllegalStateException("response lost"))
            // The unauthenticated host must not expose old state.
            assertNull(continuation.client(api(), null, null).noteSession.pending)
            assertNull(continuation.client(api(), "employee-b", "org-a").noteSession.pending)
            continuation.bindVerifiedIdentity(target.identityId, "org-a")
            val current = continuation.client(api(), target.identityId, "org-a").noteSession
            assertEquals(intent, current.pending)
            assertEquals(intent, current.retry(target))
            current.succeed(intent, note())
            assertEquals(target, current.receipt)
        } finally {
            http.close()
        }
    }

    @Test
    fun actorOrganizationOrExplicitSessionChangeCannotResurrectPrivateState() {
        val http = HttpClient(MockEngine { error("No network needed") })
        try {
            val continuation = SupportNoteContinuity()
            fun api() = HiltechApiClient(http, "https://api.hiltech.test", { "synthetic" }, { "synthetic" })
            fun current() = continuation.client(api(), target.identityId, "org-a").noteSession
            continuation.bindVerifiedIdentity(target.identityId, "org-a")
            current().edit(target, "Checked connection")
            continuation.bindVerifiedIdentity(target.identityId, "org-b")
            continuation.bindVerifiedIdentity(target.identityId, "org-a")
            assertEquals("", current().draft(target))
            current().edit(target, "Checked connection")
            continuation.bindVerifiedIdentity("employee-b", "org-a")
            continuation.bindVerifiedIdentity(target.identityId, "org-a")
            assertEquals("", current().draft(target))
            val prior = current()
            prior.edit(target, "Checked connection")
            val intent = assertNotNull(prior.begin(target) { "operation-a" })
            continuation.clear()
            continuation.bindVerifiedIdentity(target.identityId, "org-a")
            prior.fail(intent, IllegalStateException("late response"))
            assertNull(current().pending)
            assertEquals("", current().draft(target))
        } finally {
            http.close()
        }
    }

    @Test
    fun invalidNoteDoesNotAllocateOperationAndDraftsStayWithTheirTicket() {
        val session = SupportNoteSession()
        session.edit(target, " ")
        assertNull(session.begin(target) { error("Do not allocate") })
        session.edit(target, "x".repeat(8001))
        assertNull(session.begin(target) { error("Do not allocate") })
        session.edit(target, "Checked connection")
        assertEquals("", session.draft(other))
        session.edit(other, "Other ticket")
        assertEquals("Checked connection", session.draft(target))
        assertEquals("Other ticket", session.draft(other))
    }
}
