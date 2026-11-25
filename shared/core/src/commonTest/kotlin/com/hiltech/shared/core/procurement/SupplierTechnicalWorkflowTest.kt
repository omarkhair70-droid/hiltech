package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.evidence.*
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class SupplierTechnicalWorkflowTest {
    private val org = "11111111-1111-4111-8111-111111111111"
    private val quote = "22222222-2222-4222-8222-222222222222"
    private val operation = "33333333-3333-4333-8333-333333333333"
    private val device = "44444444-4444-4444-8444-444444444444"
    private val packet = "55555555-5555-4555-8555-555555555555"
    private val proof = "66666666-6666-4666-8666-666666666666"
    private val slot = "77777777-7777-4777-8777-777777777777"
    private val receipt = "88888888-8888-4888-8888-888888888888"
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private val fields = SupplierTechnicalPacketFields(offeredSpecification = "Exact component rating and mounting specification", deviations = "No deviations",
        supplierSource = "Manufacturer technical sheet", sourceReference = "SOURCE-001", sourceDate = "2026-11-25", nonFinancialConfirmed = true, correctionReason = "Original technical source recorded")
    private fun api(engine: MockEngine) = HiltechApiClient(HttpClient(engine), "https://api.hiltech.test", { "synthetic-token" }, { "technical-test" })
    private fun client(engine: MockEngine) = SupplierTechnicalApiClient(api(engine))
    private fun described() = SupplierTechnicalQuoteDto(quote, org, slot, proof, "Synthetic supplier", "SUP-000123", "QUOTE-001", 1,
        receipt, "Synthetic item", "ITEM-001", operation, 3, "4", "EA", "4", "EA", "WAREHOUSE_REPLENISHMENT", "Exact required specification", "Synthetic receiving location", true, true)

    @Test fun queueSearchRetainsHumanNamesAndRejectsForeignCompanyRows() = runBlocking {
        val row = SupplierTechnicalQueueItemDto(described(), 0, "NOT_ASSESSED")
        val expected = SupplierTechnicalQueueDto(org, false, true, listOf(row), null)
        val service = client(MockEngine { call ->
            assertEquals("/v1/sourcing/organizations/$org/technical-review", call.url.encodedPath)
            assertEquals("SUP%_", call.url.parameters["search"])
            respond(Json.encodeToString(expected), HttpStatusCode.OK, headers)
        })
        assertEquals("Synthetic supplier", service.queue(org, device, "SUP%_").items.single().quote.supplierName)
        val foreign = expected.copy(items = listOf(row.copy(quote = row.quote.copy(organizationId = device))))
        assertFailsWith<Exception> { client(MockEngine { respond(Json.encodeToString(foreign), HttpStatusCode.OK, headers) }).queue(org, device) }
    }

    @Test fun frozenPublicationRetryRetainsExactSourceDateAndOriginalRequest() = runBlocking {
        val payload = SupplierTechnicalPublishRequest(operation, 2, fields)
        val expected = SupplierTechnicalPacketReceiptDto(operation, org, quote, packet, 3, "PUBLISHED_FOR_TECHNICAL_REVIEW", true)
        val bodies = mutableListOf<String>()
        val service = client(MockEngine { call ->
            assertEquals("/v1/sourcing/quotes/$quote/technical-packets", call.url.encodedPath)
            assertEquals(operation, call.headers["Idempotency-Key"])
            bodies += (call.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            if (bodies.size == 1) respond("Unconfirmed", HttpStatusCode.ServiceUnavailable)
            else respond(Json.encodeToString(expected), HttpStatusCode.OK, headers)
        })
        val command = SupplierTechnicalCommand.Publish(org, quote, "Synthetic item · Supplier", payload)
        assertTrue(service.commands.begin(command))
        val failure = assertFailsWith<Exception> { service.publish(org, quote, payload, device) }
        service.commands.failed(command, failure)
        assertSame(command, service.commands.retry())
        assertTrue(service.publish(org, quote, payload, device).replayed)
        service.commands.succeeded(command)
        assertNull(service.commands.pending)
        assertEquals(bodies[0], bodies[1]); assertEquals(payload, Json.decodeFromString<SupplierTechnicalPublishRequest>(bodies[1]))
        for (bad in listOf(expected.copy(organizationId = slot), expected.copy(quoteId = slot), expected.copy(packetRevision = 4)))
            assertFailsWith<Exception> { client(MockEngine { respond(Json.encodeToString(bad), HttpStatusCode.OK, headers) }).publish(org, quote, payload, device) }
    }

    @Test fun technicalAssessmentSendsTheExactPacketAndRejectsMismatchedDecisionReceipts() = runBlocking {
        val request = SupplierTechnicalAssessmentCommand(operation, 3, "COMPLIANT", "The current supplier specification meets the requirement", packet, true)
        val expected = SupplierTechnicalAssessmentReceipt(receipt, quote, 3, "COMPLIANT", false)
        val service = client(MockEngine { call ->
            assertEquals("/v1/sourcing/quotes/$quote/technical-assessments", call.url.encodedPath)
            assertEquals(operation, call.headers["Idempotency-Key"])
            assertEquals(request, Json.decodeFromString<SupplierTechnicalAssessmentCommand>((call.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()))
            respond(Json.encodeToString(expected), HttpStatusCode.OK, headers)
        })
        assertEquals(expected, service.assess(org, quote, request, device))
        for (bad in listOf(expected.copy(quoteEvidenceId = slot), expected.copy(reviewRevision = 4), expected.copy(decision = "NON_COMPLIANT")))
            assertFailsWith<Exception> { client(MockEngine { respond(Json.encodeToString(bad), HttpStatusCode.OK, headers) }).assess(org, quote, request, device) }
    }

    @Test fun privatePageAndDisplayReceiptAreSeparateAndStayBoundToQuotePacketAndOriginal() = runBlocking {
        val original = SupplierTechnicalEvidenceDto(proof, "Technical original", "image/png", 100, "a".repeat(64))
        val page = SupplierTechnicalEvidencePageDto(org, quote, packet, proof, original.title, original.contentType, original.sha256, 0, 2, "aGVsbG8=", receipt)
        val paths = mutableListOf<String>()
        val service = client(MockEngine { call ->
            paths += call.url.encodedPath
            if (call.url.encodedPath.endsWith("/pages/0")) respond(Json.encodeToString(page), HttpStatusCode.OK, headers)
            else respond(Json.encodeToString(SupplierTechnicalDisplayDto(org, quote, packet, proof, receipt, true)), HttpStatusCode.OK, headers)
        })
        val delivered = service.page(org, quote, packet, original, 0, device)
        assertEquals(1, paths.size)
        assertTrue(service.confirmDisplayed(delivered, device).confirmed)
        assertEquals("/v1/sourcing/quotes/$quote/technical-packets/$packet/display-confirmations/$receipt", paths.last())
        for (bad in listOf(page.copy(organizationId = slot), page.copy(packetId = slot), page.copy(originalSha256 = "b".repeat(64)), page.copy(viewReceiptId = null)))
            assertFailsWith<Exception> { client(MockEngine { respond(Json.encodeToString(bad), HttpStatusCode.OK, headers) }).page(org, quote, packet, original, 0, device) }
    }

    @Test fun aLostFinalizeResponseRetriesOnlyFinalizeAfterSuccessfulPutCheckpoint() = runBlocking {
        var ready = false; var reserves = 0; var puts = 0; var finalizes = 0
        val finalizeBodies = mutableListOf<String>()
        val file = ServiceSelectedFile("image/png", "synthetic-technical.png", byteArrayOf(1,2,3), "a".repeat(64), "2026-11-25T12:00:00Z")
        var saved = SupplierTechnicalUploadIntent(operation, packet, receipt, org, quote, "Synthetic source", "Technical original", file.originalFileName,
            file.contentType, 3, file.sha256, file.capturedAt)
        fun metadata() = EvidenceMetadataDto(proof, device, "SUPPLIER_TECHNICAL_DOCUMENT", slot,
            evidenceRequirementKey = "SUPPLIER_TECHNICAL_SOURCE", evidenceTypeCode = "SUPPLIER_TECHNICAL_SOURCE", contentType = file.contentType,
            sizeBytes = 3, sha256 = file.sha256, storageState = if (ready) "READY" else "RESERVED", classificationCode = "HIGHLY_RESTRICTED", clientVisibilityMode = "INTERNAL_ONLY", evidenceVersion = 1)
        val engine = MockEngine { call -> when {
            call.method == HttpMethod.Put -> { puts++; respond("", HttpStatusCode.OK) }
            call.url.encodedPath.endsWith("/technical-documents") -> respond(Json.encodeToString(SupplierTechnicalDocumentSlotDto(operation, org, quote, slot, "Technical original", false)), HttpStatusCode.OK, headers)
            call.url.encodedPath == "/v1/evidence/reservations" -> {
                reserves++; respond(Json.encodeToString(ReserveEvidenceUploadResponseDto(metadata(), EvidenceUploadTargetDto("https://storage.hiltech.test/technical", emptyMap(), "2026-11-25T12:15:00Z", 3), "synthetic", false)), HttpStatusCode.OK, headers)
            }
            call.url.encodedPath.endsWith("/finalize") -> {
                finalizes++; assertEquals(receipt, call.headers["Idempotency-Key"])
                finalizeBodies += (call.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                if (finalizes == 1) respond("Unconfirmed", HttpStatusCode.ServiceUnavailable)
                else { ready = true; respond(Json.encodeToString(FinalizeEvidenceResponseDto(metadata(), "synthetic", true)), HttpStatusCode.OK, headers) }
            }
            call.url.encodedPath == "/v1/evidence/$proof" -> respond(Json.encodeToString(metadata()), HttpStatusCode.OK, headers)
            else -> error("Unexpected synthetic path ${call.url.encodedPath}")
        } }
        val network = api(engine)
        val uploader = SupplierTechnicalUploadCoordinator(SupplierTechnicalApiClient(network), EvidenceApiClient(network, HttpClient(engine)))
        assertFailsWith<Exception> { uploader.upload(saved, file, device) { saved = it } }
        assertTrue(saved.uploaded); assertEquals(proof, saved.evidenceId)
        val restored = Json.decodeFromString<SupplierTechnicalUploadIntent>(Json.encodeToString(saved))
        assertEquals("READY", uploader.upload(restored, null, device) { saved = it }.storageState)
        assertEquals(1, reserves); assertEquals(1, puts); assertEquals(2, finalizes)
        assertEquals(finalizeBodies[0], finalizeBodies[1])
        assertEquals("READY", uploader.upload(saved, null, device) { saved = it }.storageState)
        assertEquals(2, finalizes)
    }

    @Test fun revokedRoleAfterUnconfirmedResultKeepsTheOriginalCommandAndNeverUnlocksAnotherQuote() {
        val state = SupplierTechnicalCommandSession()
        val command = SupplierTechnicalCommand.Publish(org, quote, "Synthetic quote", SupplierTechnicalPublishRequest(operation, 0, fields))
        state.begin(command); state.failed(command, IllegalStateException("Response lost after commit")); state.retry()
        state.failed(command, rejection("SUPPLIER_TECHNICAL_NOT_VISIBLE", 404))
        assertSame(command, state.pending)
        assertFalse(state.begin(command.copy(quoteId = slot)))
        assertSame(command, state.retry())
        state.failed(command, rejection("REAUTH_REQUIRED", 403))
        assertSame(command, state.retry())
        state.succeeded(command.copy(quoteId = slot)); assertTrue(state.busy)
        state.succeeded(command); assertNull(state.pending)
        assertTrue(state.begin(command.copy(payload = command.payload.copy(expectedLatestRevision = 1))))
    }

    @Test fun currentDefinitiveRejectionPermitsCorrectionAndTextSourceDoesNotRequireAttachment() {
        assertTrue(supplierTechnicalFieldsValid(fields))
        assertFalse(supplierTechnicalFieldsValid(fields.copy(nonFinancialConfirmed = false)))
        assertFalse(supplierTechnicalFieldsValid(fields.copy(sourceReference = "")))
        val state = SupplierTechnicalCommandSession()
        val command = SupplierTechnicalCommand.Publish(org, quote, "Synthetic quote", SupplierTechnicalPublishRequest(operation, 0, fields))
        state.begin(command); state.failed(command, rejection("SUPPLIER_TECHNICAL_SOURCE_CHANGED", 409))
        assertNull(state.pending)
        assertTrue(state.begin(command.copy(payload = command.payload.copy(expectedLatestRevision = 1))))
    }

    private fun rejection(code: String, status: Int) = HiltechApiException(code, "synthetic", status, false, ErrorEnvelope(code, "Synthetic rejection", "synthetic", false), "Synthetic rejection")
}
