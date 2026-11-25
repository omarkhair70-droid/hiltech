package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.evidence.EvidenceApiClient
import com.hiltech.shared.core.network.HiltechApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import com.hiltech.shared.core.network.HiltechApiException
import io.ktor.http.content.TextContent

class SupplierQuoteDocumentUploaderTest {
    private val rfq = "11111111-1111-1111-1111-111111111111"
    private val supplier = "22222222-2222-2222-2222-222222222222"
    private val slot = "33333333-3333-3333-3333-333333333333"
    private val evidenceId = "44444444-4444-4444-4444-444444444444"
    private val session = "55555555-5555-5555-5555-555555555555"
    private val device = "66666666-6666-6666-6666-666666666666"
    private val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val bytes = "abc".encodeToByteArray()
    private fun meta(target: String, state: String) = """
        {"evidenceId":"$evidenceId","uploadSessionId":"$session",
         "targetType":"SUPPLIER_QUOTE_DOCUMENT","targetId":"$target",
         "evidenceRequirementKey":"SUPPLIER_QUOTE_BINARY",
         "evidenceTypeCode":"SUPPLIER_QUOTE","contentType":"application/pdf",
         "sizeBytes":3,"sha256":"$sha","storageState":"$state",
         "classificationCode":"HIGHLY_RESTRICTED",
         "clientVisibilityMode":"INTERNAL_ONLY","evidenceVersion":1}
    """.trimIndent()
    private fun input() = SupplierQuoteDocumentUploadInput(
        rfqId = rfq, supplierOrganizationId = supplier,
        slotOperationId = "77777777-7777-7777-7777-777777777777",
        reserveOperationId = "88888888-8888-8888-8888-888888888888",
        finalizeOperationId = "99999999-9999-9999-9999-999999999999",
        contentType = "application/pdf", originalFileName = "supplier.pdf",
        sha256 = sha, bytes = bytes, capturedAt = "2026-11-22T09:00:00Z",
    )

    @Test fun quotationOnlyBecomesUsableAfterCanonicalReady() = runBlocking {
        var uploads = 0
        val apiHttp = HttpClient(MockEngine { call ->
            assertEquals("Bearer buyer-token", call.headers[HttpHeaders.Authorization])
            when (call.url.encodedPath) {
                "/v1/sourcing/rfqs/$rfq/document-slots" ->
                    respond("""{"documentSlotId":"$slot","rfqId":"$rfq",
                        "supplierOrganizationId":"$supplier","replayed":false}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                "/v1/evidence/reservations" ->
                    respond("""{"evidence":${meta(slot, "RESERVED")},
                        "upload":{"uploadUrl":"https://private-storage.test/file",
                        "requiredHeaders":{},"expiresAt":"2026-11-22T09:10:00Z",
                        "expectedSizeBytes":3},"correlationId":"test","replayed":false}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                "/v1/evidence/$evidenceId/finalize" ->
                    respond("""{"evidence":${meta(slot, "READY")},
                        "correlationId":"test","replayed":false}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> error("Unexpected endpoint: " + call.url.encodedPath)
            }
        })
        val binary = HttpClient(MockEngine { call ->
            assertEquals("https://private-storage.test/file", call.url.toString())
            assertEquals("PUT", call.method.value)
            uploads++
            respond("", HttpStatusCode.OK)
        })
        val api = HiltechApiClient(apiHttp, "https://api.hiltech.test",
            accessTokenProvider = { "buyer-token" },
            correlationIdProvider = { "private-quote-test" })
        val result = SupplierQuoteDocumentUploader(
            SupplierSourcingApiClient(api), EvidenceApiClient(api, binary),
        ).upload(input(), device)
        assertEquals(1, uploads)
        assertEquals("evidence:$evidenceId", result.evidenceReference)
        assertEquals(slot, result.documentSlotId)
        assertEquals(sha, result.sha256)
    }

    @Test fun crossSupplierEvidenceScopeStopsBeforePrivatePut() = runBlocking {
        var uploads = 0
        val apiHttp = HttpClient(MockEngine { call ->
            when (call.url.encodedPath) {
                "/v1/sourcing/rfqs/$rfq/document-slots" ->
                    respond("""{"documentSlotId":"$slot","rfqId":"$rfq",
                        "supplierOrganizationId":"$supplier","replayed":false}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                "/v1/evidence/reservations" ->
                    respond("""{"evidence":${meta(rfq, "RESERVED")},
                        "upload":{"uploadUrl":"https://private-storage.test/file",
                        "requiredHeaders":{},"expiresAt":"2026-11-22T09:10:00Z",
                        "expectedSizeBytes":3},"correlationId":"test","replayed":false}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> error("Unexpected endpoint")
            }
        })
        val binary = HttpClient(MockEngine {
            uploads++
            respond("", HttpStatusCode.OK)
        })
        val api = HiltechApiClient(apiHttp, "https://api.hiltech.test",
            accessTokenProvider = { "buyer-token" },
            correlationIdProvider = { "private-quote-test" })
        assertFailsWith<IllegalArgumentException> {
            SupplierQuoteDocumentUploader(
                SupplierSourcingApiClient(api), EvidenceApiClient(api, binary),
            ).upload(input(), device)
        }
        assertEquals(0, uploads)
    }

    @Test fun aLostFinalizeResponseResumesTheExactFinalizeWithoutASecondReservationOrUpload() = runBlocking {
        var slots = 0; var reservations = 0; var uploads = 0
        val finalizeBodies = mutableListOf<String>()
        val finalizeKeys = mutableListOf<String?>()
        val apiHttp = HttpClient(MockEngine { call ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when (call.url.encodedPath) {
                "/v1/sourcing/rfqs/$rfq/document-slots" -> {
                    slots++
                    respond("""{"documentSlotId":"$slot","rfqId":"$rfq","supplierOrganizationId":"$supplier","replayed":false}""", HttpStatusCode.OK, json)
                }
                "/v1/evidence/reservations" -> {
                    reservations++
                    respond("""{"evidence":${meta(slot, "RESERVED")},"upload":{"uploadUrl":"https://private-storage.test/file","requiredHeaders":{},"expiresAt":"2026-11-22T09:10:00Z","expectedSizeBytes":3},"correlationId":"test","replayed":false}""", HttpStatusCode.OK, json)
                }
                "/v1/evidence/$evidenceId/finalize" -> {
                    finalizeBodies += (call.body as TextContent).text
                    finalizeKeys += call.headers["Idempotency-Key"]
                    if (finalizeBodies.size == 1) respond("""{"code":"EVIDENCE_STORAGE_UNAVAILABLE","message":"Synthetic lost confirmation","correlationId":"synthetic","retryable":true}""", HttpStatusCode.ServiceUnavailable, json)
                    else respond("""{"evidence":${meta(slot, "READY")},"correlationId":"test","replayed":true}""", HttpStatusCode.OK, json)
                }
                else -> error("Unexpected request")
            }
        })
        val binary = HttpClient(MockEngine { uploads++; respond("", HttpStatusCode.OK) })
        val api = HiltechApiClient(apiHttp, "https://api.hiltech.test", { "synthetic-buyer" }, { "synthetic" })
        val uploader = SupplierQuoteDocumentUploader(SupplierSourcingApiClient(api), EvidenceApiClient(api, binary))
        val request = input()
        assertFailsWith<HiltechApiException> { uploader.upload(request, device) }
        assertEquals(request.finalizeOperationId, uploader.pendingInput?.finalizeOperationId)
        assertFailsWith<IllegalArgumentException> { uploader.upload(request.copy(bytes = "xyz".encodeToByteArray()), device) }
        val result = uploader.upload(request, device)
        assertEquals(1, slots); assertEquals(1, reservations); assertEquals(1, uploads)
        assertEquals(2, finalizeBodies.size); assertEquals(1, finalizeBodies.distinct().size)
        assertEquals<List<String?>>(listOf(request.finalizeOperationId, request.finalizeOperationId), finalizeKeys)
        assertEquals("evidence:$evidenceId", result.evidenceReference)
        assertEquals(result, uploader.verified(rfq, supplier)); assertNull(uploader.pendingInput)
        uploader.consumed(rfq, supplier); assertNull(uploader.verified(rfq, supplier))
        apiHttp.close(); binary.close()
    }
}
