package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.network.ErrorEnvelope
import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechApiException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class PurchaseOrderReviewApiClientTest {
    private val candidate = "11111111-1111-1111-1111-111111111111"
    private val requestId = "22222222-2222-2222-2222-222222222222"
    private val operation = "33333333-3333-3333-3333-333333333333"
    private val delivery = "44444444-4444-4444-4444-444444444444"
    private val installation = "55555555-5555-5555-5555-555555555555"
    private val detail = PurchaseOrderReviewDto(candidate, "66666666-6666-6666-6666-666666666666", 2,
        requestId, 1, "PENDING", 3, "Synthetic supplier", "SUP-0001", "Synthetic item", "ITEM-1", "4", "EA", "25", "0", "100", "EGP",
        "WORK_ORDER", "Synthetic installation", 4, "QUOTE-SYNTHETIC", 2, "2026-12-31", 3,
        "Synthetic delivery", "Synthetic payment", "Synthetic warranty", "Synthetic independent quotation selection", "Synthetic purchaser", "2026-11-25T12:00:00Z",
        true, true, false, false, true, false, PurchaseReviewProofDto("77777777-7777-7777-7777-777777777777", "a".repeat(64), "application/pdf", "quotation.pdf", 200, true))
    private val json = Json
    private fun client(http: HttpClient) = PurchaseOrderReviewApiClient(HiltechApiClient(http, "https://api.hiltech.test", { "reviewer-token" }, { "synthetic-review" }))

    @Test fun pageAndDisplayedReceiptPinTheSameSourceWithoutChangingApprovalState() = runBlocking {
        var opens = 0
        val page = PurchaseQuotePageDto(delivery, candidate, requestId, detail.exactSource(), 0, 2, "synthetic-png")
        val http = HttpClient(MockEngine { call ->
            assertEquals("Bearer reviewer-token", call.headers[HttpHeaders.Authorization])
            assertEquals(installation, call.headers["X-Device-Installation-Id"])
            val body = (call.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            val response = when {
                call.url.encodedPath.endsWith("/quote-pages") -> {
                    assertNull(call.headers["Idempotency-Key"])
                    assertEquals(PurchaseQuotePageRequestDto(detail.exactSource(), 0), json.decodeFromString<PurchaseQuotePageRequestDto>(body))
                    json.encodeToString(PurchaseQuotePageDto.serializer(), page)
                }
                call.url.encodedPath.endsWith("/quote-openings") -> {
                    opens++
                    assertEquals(operation, call.headers["Idempotency-Key"])
                    assertEquals(OpenPurchaseReviewQuoteDto(operation, delivery, detail.exactSource()), json.decodeFromString<OpenPurchaseReviewQuoteDto>(body))
                    json.encodeToString(PurchaseQuoteOpeningReceiptDto.serializer(), PurchaseQuoteOpeningReceiptDto(operation, candidate, requestId, true, false))
                }
                else -> error("A page/opening request must not send a decision")
            }
            respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val api = client(http)
        assertEquals(page, api.quotationPage(detail, 0, installation))
        assertEquals(0, opens)
        assertTrue(api.recordOpened(candidate, requestId, OpenPurchaseReviewQuoteDto(operation, delivery, detail.exactSource()), installation).opened)
        assertEquals(1, opens)
    }

    @Test fun mismatchedDocumentSourceAndPageAreRejectedBeforeUse() = runBlocking {
        val wrong = PurchaseQuotePageDto(delivery, candidate, requestId, detail.exactSource().copy(expectedSha256 = "b".repeat(64)), 0, 1, "synthetic")
        val http = HttpClient(MockEngine { respond(json.encodeToString(PurchaseQuotePageDto.serializer(), wrong), HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json")) })
        assertFailsWith<IllegalArgumentException> { client(http).quotationPage(detail, 0, installation) }
    }

    @Test fun decisionCarriesFrozenVersionsAndAcceptsTruthfulSupersededReceipt() = runBlocking {
        val command = DecidePurchaseReviewDto(operation, detail.exactSource(), "APPROVE", "Synthetic reviewed original")
        val http = HttpClient(MockEngine { call ->
            assertEquals("/v1/sourcing/po-candidates/$candidate/reviews/$requestId/decisions", call.url.encodedPath)
            assertEquals(operation, call.headers["Idempotency-Key"])
            assertEquals(command, json.decodeFromString<DecidePurchaseReviewDto>((call.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()))
            respond(json.encodeToString(PurchaseReviewDecisionReceiptDto.serializer(), PurchaseReviewDecisionReceiptDto(operation, candidate, requestId, "SUPERSEDED", false)),
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertEquals("SUPERSEDED", client(http).decide(candidate, requestId, command, installation).approvalState)
    }

    @Test fun anotherActorsOrPendingStatusCannotMasqueradeAsAnOwnCompletedDecision() = runBlocking {
        val http = HttpClient(MockEngine { respond(json.encodeToString(PurchaseReviewCompletedDto.serializer(),
            PurchaseReviewCompletedDto(candidate, requestId, "APPROVED", "2026-11-25T12:00:00Z", false)), HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json")) })
        assertFailsWith<IllegalArgumentException> { client(http).completed(requestId, installation) }
    }

    @Test fun uncertainDecisionKeepsExactOperationThroughReauthenticationFailure() {
        val session = PurchaseReviewCommandSession()
        val command = PurchaseReviewCommand.Decide(candidate, requestId, DecidePurchaseReviewDto(operation, detail.exactSource(), "APPROVE"))
        assertTrue(session.begin(command))
        session.failed(command, IllegalStateException("synthetic lost response"))
        assertSame(command, session.retry())
        session.failed(command, HiltechApiException("REAUTH_REQUIRED", "synthetic", 428, false,
            ErrorEnvelope("REAUTH_REQUIRED", "Synthetic expired confirmation", "synthetic"), "Synthetic"))
        assertTrue(session.requiresReauthentication)
        assertSame(command, session.pending)
        assertFalse(session.begin(command.copy(payload = command.payload.copy(operationId = delivery))))
        assertSame(command, session.retry())
        session.succeeded(command, PurchaseReviewDecisionReceiptDto(operation, candidate, requestId, "APPROVED", true))
        assertNull(session.pending)
        assertEquals("APPROVED", session.receipt?.approvalState)
    }

    @Test fun uncertainBuyerCorrectionRetainsPredecessorRevisionAndCannotCreateAnotherProposal() {
        val session = PurchaseCandidateCommandSession()
        val command = PurchaseCandidateCommand.Propose(candidate, delivery, ProposePurchaseOrderCandidateDto(operation, delivery, 2,
            "Synthetic corrected supplier selection", requestId, 1))
        assertTrue(session.begin(command))
        session.failed(command, IllegalStateException("synthetic lost response"))
        assertSame(command, session.retry())
        assertEquals(requestId, (session.pending as PurchaseCandidateCommand.Propose).payload.replacesCandidateId)
        assertFalse(session.begin(command.copy(payload = command.payload.copy(expectedCandidateRevision = 2))))
        session.succeeded(command)
        assertNull(session.pending)
    }
}
