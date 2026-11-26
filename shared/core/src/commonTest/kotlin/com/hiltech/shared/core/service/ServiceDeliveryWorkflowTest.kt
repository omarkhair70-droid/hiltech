package com.hiltech.shared.core.service

import com.hiltech.shared.core.evidence.*
import com.hiltech.shared.core.network.ErrorEnvelope
import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechApiException
import com.hiltech.shared.core.procurement.ServiceSelectedFile
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class ServiceDeliveryWorkflowTest {
    private val org = "11111111-1111-1111-1111-111111111111"
    private val project = "22222222-2222-2222-2222-222222222222"
    private val operation = "33333333-3333-3333-3333-333333333333"
    private val device = "44444444-4444-4444-4444-444444444444"
    private val proposal = "55555555-5555-5555-5555-555555555555"
    private val evidenceId = "66666666-6666-6666-6666-666666666666"
    private val slot = "77777777-7777-7777-7777-777777777777"
    private val receipt = "88888888-8888-8888-8888-888888888888"
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private fun api(engine: MockEngine) = HiltechApiClient(HttpClient(engine), "https://api.hiltech.test", { "synthetic-service-token" }, { "service-delivery-test" })
    private fun client(engine: MockEngine) = ServiceDeliveryApiClient(api(engine))

    @Test fun actualIncidentCommandKeepsTheProjectAndSavedRequestAndRejectsForeignOrAlteredReceipts() = runBlocking {
        val actor = slot
        val draft = ServiceDeliveryIncidentDraft("Synthetic installation incident", "Observed equipment issue at the named site", "MAJOR", "P2")
        assertTrue(draft.complete()); assertFalse(draft.copy(priority = "").complete()); assertFalse(draft.copy(severity = "custom-id").complete())
        val request = CreateInternalSupportTicketDto(operation, 7, draft.title, draft.description, draft.severity, draft.priority)
        val saved = ServiceDeliveryIntent(operation, org, project, "SUPPORT_TICKET", draft.title, ticket = request)
        val restored = Json.decodeFromString<ServiceDeliveryIntent>(Json.encodeToString(saved))
        val expected = InternalSupportTicketDto(evidenceId, project, org, draft.title, draft.description, "MAJOR", "P2", "OPEN",
            coverageState = "NOT_EVALUATED", slaState = "NOT_CONFIGURED", clientVisibility = "INTERNAL_ONLY", openedByUserId = actor,
            createdAt = "2026-11-25T12:00:00Z", duplicateReplay = true)
        val bodies = mutableListOf<String>()
        val service = client(MockEngine { call ->
            assertEquals("/v1/projects/$project/service-tickets", call.url.encodedPath)
            assertEquals(operation, call.headers["Idempotency-Key"])
            bodies += (call.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            if (bodies.size == 1) respond("Unconfirmed", HttpStatusCode.ServiceUnavailable)
            else respond(Json.encodeToString(expected), HttpStatusCode.OK, headers)
        })
        assertFailsWith<Exception> { service.openIncident(project, actor, requireNotNull(restored.ticket), device) }
        assertTrue(service.openIncident(project, actor, requireNotNull(restored.ticket), device).duplicateReplay)
        assertEquals(bodies[0], bodies[1]); assertEquals(request, Json.decodeFromString<CreateInternalSupportTicketDto>(bodies[1]))
        for (bad in listOf(expected.copy(projectId = slot), expected.copy(openedByUserId = org), expected.copy(priorityCode = "P1"), expected.copy(title = "Different incident"))) {
            assertFailsWith<Exception> { client(MockEngine { respond(Json.encodeToString(bad), HttpStatusCode.OK, headers) }).openIncident(project, actor, request, device) }
        }
    }

    @Test fun unknownResponseRetryKeepsExactProposalScopeDatesAndOperation() = runBlocking {
        val details = ServiceDeliveryDetails(scopeType = "SITE", scopeId = slot, startsOn = "2026-01-01", endsOn = "2026-12-31", writtenTerms = "Actual source terms",
            agreementEvidenceId = evidenceId, reason = "Exact original agreement checked")
        val request = ServiceDeliveryProposeRequest(operation, "WARRANTY_TERMS", 2, details)
        val intent = ServiceDeliveryIntent(operation, org, project, "PROPOSE", "Synthetic warranty terms", proposal = request)
        val restored = Json.decodeFromString<ServiceDeliveryIntent>(Json.encodeToString(intent))
        val bodies = mutableListOf<String>()
        val service = client(MockEngine { call ->
            assertEquals("/v1/projects/$project/service-delivery/proposals", call.url.encodedPath)
            assertEquals(operation, call.headers["Idempotency-Key"]); assertEquals(device, call.headers["X-Device-Installation-Id"])
            bodies += (call.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            if (bodies.size == 1) respond("unavailable", HttpStatusCode.ServiceUnavailable)
            else respond(Json.encodeToString(ServiceDeliveryReceipt(operation, org, project, proposal, "SUBMITTED_FOR_REVIEW", true)), HttpStatusCode.OK, headers)
        })
        assertFailsWith<Exception> { service.propose(org, project, requireNotNull(restored.proposal), device) }
        assertTrue(service.propose(org, project, requireNotNull(restored.proposal), device).replayed)
        assertEquals(bodies[0], bodies[1]); assertEquals(request, Json.decodeFromString<ServiceDeliveryProposeRequest>(bodies[1]))
    }

    @Test fun staleCloseCannotAcceptRecordedDecisionOrForeignProjectReceipt() = runBlocking {
        val request = ServiceDeliveryReviewRequest(operation, "CLOSE_STALE", null)
        val expected = ServiceDeliveryReceipt(operation, org, project, proposal, "SUPERSEDED", false)
        val correct = client(MockEngine { respond(Json.encodeToString(expected), HttpStatusCode.OK, headers) })
        assertEquals("SUPERSEDED", correct.review(org, project, proposal, request, device).result)
        for (bad in listOf(expected.copy(result = "RECORDED"), expected.copy(projectId = slot), expected.copy(recordId = slot))) {
            val wrong = client(MockEngine { respond(Json.encodeToString(bad), HttpStatusCode.OK, headers) })
            assertFailsWith<Exception> { wrong.review(org, project, proposal, request, device) }
        }
    }

    @Test fun renderedOriginalAndDisplayReceiptStayBoundToSelectedProofAndProposal() = runBlocking {
        val proof = ServiceDeliveryProofDto(evidenceId, "Synthetic original", "AGREEMENT", "application/pdf", 100, "a".repeat(64))
        val page = ServiceDeliveryEvidencePageDto(org, project, proposal, evidenceId, proof.title, proof.contentType, proof.sha256, 0, 3, "aGVsbG8=", receipt)
        val requests = mutableListOf<String>()
        val service = client(MockEngine { call ->
            assertEquals(HttpMethod.Post, call.method); requests += call.url.encodedPath
            if (call.url.encodedPath.endsWith("/pages/0")) respond(Json.encodeToString(page), HttpStatusCode.OK, headers)
            else respond(Json.encodeToString(ServiceDeliveryDisplayDto(org, project, proposal, evidenceId, receipt, true)), HttpStatusCode.OK, headers)
        })
        val delivered = service.evidencePage(org, project, proposal, proof, 0, device)
        assertEquals(1, requests.size)
        assertTrue(service.confirmDisplayed(delivered, device).confirmed)
        assertEquals("/v1/service/organizations/$org/delivery-reviews/$proposal/display-confirmations/$receipt", requests.last())
        for (bad in listOf(page.copy(proposalId = slot), page.copy(organizationId = slot), page.copy(originalSha256 = "b".repeat(64)), page.copy(viewReceiptId = null))) {
            assertFailsWith<Exception> { client(MockEngine { respond(Json.encodeToString(bad), HttpStatusCode.OK, headers) }).evidencePage(org, project, proposal, proof, 0, device) }
        }
    }

    @Test fun finalizedUploadWithLostResponseRecoversFromExactMetadataWithoutReselectingOrDuplicatingFile() = runBlocking {
        var ready = false; var reserves = 0; var uploads = 0; var finalizes = 0
        val file = ServiceSelectedFile("image/png", "synthetic.png", byteArrayOf(1,2,3), "a".repeat(64), "2026-11-25T12:00:00Z")
        var saved = ServiceDeliveryUploadIntent(operation, proposal, receipt, org, project, "AGREEMENT", "Synthetic agreement",
            file.originalFileName, file.contentType, file.bytes.size.toLong(), file.sha256, file.capturedAt)
        fun metadata() = EvidenceMetadataDto(evidenceId, device, "SERVICE_DELIVERY_DOCUMENT", slot,
            evidenceRequirementKey = "SERVICE_DELIVERY_AGREEMENT", evidenceTypeCode = "SERVICE_DELIVERY_AGREEMENT", contentType = file.contentType,
            sizeBytes = file.bytes.size.toLong(), sha256 = file.sha256, storageState = if (ready) "READY" else "RESERVED", classificationCode = "HIGHLY_RESTRICTED", clientVisibilityMode = "INTERNAL_ONLY", evidenceVersion = 1)
        val engine = MockEngine { call -> when {
            call.method == HttpMethod.Put -> { uploads++; respond("", HttpStatusCode.OK) }
            call.url.encodedPath.endsWith("/service-delivery/documents") -> respond(Json.encodeToString(ServiceDeliveryDocumentSlotDto(slot, org, project, "AGREEMENT", "Synthetic agreement", "SERVICE_DELIVERY_AGREEMENT", false)), HttpStatusCode.OK, headers)
            call.url.encodedPath == "/v1/evidence/reservations" -> {
                reserves++; respond(Json.encodeToString(ReserveEvidenceUploadResponseDto(metadata(), EvidenceUploadTargetDto("https://storage.hiltech.test/synthetic", emptyMap(), "2026-11-25T12:15:00Z", 3), "synthetic", false)), HttpStatusCode.OK, headers)
            }
            call.url.encodedPath.endsWith("/finalize") -> { finalizes++; ready = true; respond("unconfirmed", HttpStatusCode.ServiceUnavailable) }
            call.url.encodedPath == "/v1/evidence/$evidenceId" -> respond(Json.encodeToString(metadata()), HttpStatusCode.OK, headers)
            else -> error("Unexpected path: " + call.url.encodedPath)
        } }
        val network = api(engine)
        val coordinator = ServiceDeliveryUploadCoordinator(ServiceDeliveryApiClient(network), EvidenceApiClient(network, HttpClient(engine)))
        assertFailsWith<Exception> { coordinator.upload(saved, file, device) { saved = it } }
        assertEquals(evidenceId, saved.evidenceId)
        val restored = Json.decodeFromString<ServiceDeliveryUploadIntent>(Json.encodeToString(saved))
        assertEquals("READY", coordinator.upload(restored, null, device) { saved = it }.storageState)
        assertEquals(1, reserves); assertEquals(1, uploads); assertEquals(1, finalizes)
        assertFalse(serviceDeliveryFileMatches(restored, file.copy(sha256 = "b".repeat(64))))
    }

    @Test fun roleDenialAfterUnknownResultAndReauthenticationDoNotDiscardFrozenIntent() {
        fun failure(code: String, status: Int) = HiltechApiException(code, "synthetic", status, false, ErrorEnvelope(code, "synthetic", "synthetic"), "synthetic")
        val denied = failure("SERVICE_DELIVERY_NOT_FOUND", 404)
        assertTrue(serviceDeliveryFailure(false, denied).rejected)
        assertTrue(serviceDeliveryFailure(true, denied).unknown)
        assertFalse(serviceDeliveryFailure(true, denied).rejected)
        assertFalse(serviceDeliveryFailure(false, failure("REAUTH_REQUIRED", 403)).rejected)
        assertFalse(serviceDeliveryFailure(false, failure("REAUTH_REQUIRED", 403)).unknown)
        assertTrue(serviceDeliveryFailure(true, failure("REAUTH_REQUIRED", 403)).unknown)
        assertTrue(serviceDeliveryFailure(false, IllegalStateException("Incomplete response")).unknown)
    }

    @Test fun nativeFormsUseExplicitDatesAndScopeAndNeverInferWarrantyOrClientAcceptance() {
        val terms = ServiceDeliveryDetails(agreementEvidenceId = evidenceId, contractReference = "AGREEMENT-1", scopeId = slot, scopeType = "SITE", coveredItem = "Installed unit",
            startsOn = "2026-01-01", endsOn = "2026-12-31", writtenTerms = "Actual source terms", scopeConfirmed = true, reason = "Explicit written agreement reviewed")
        assertTrue(serviceDeliveryFormComplete("WARRANTY_TERMS", terms))
        assertFalse(serviceDeliveryFormComplete("WARRANTY_TERMS", terms.copy(startsOn = null)))
        assertFalse(serviceDeliveryFormComplete("WARRANTY_TERMS", terms.copy(endsOn = "2025-12-31")))
        assertFalse(serviceDeliveryFormComplete("HANDOVER_CLIENT_RESPONSE", terms))
        val incomplete = ServiceDeliveryDetails(ticketId = slot, coverageDecision = "INSUFFICIENT_INFORMATION", incidentDescription = "Cause under investigation", reason = "Original incident proof has not been supplied")
        assertTrue(serviceDeliveryFormComplete("WARRANTY_ASSESSMENT", incomplete))
        assertFalse(serviceDeliveryFormComplete("WARRANTY_ASSESSMENT", incomplete.copy(coverageDecision = "COVERED")))
    }
}
