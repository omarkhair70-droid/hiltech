package com.hiltech.shared.core.finance

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
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FinanceExpenseReceiptUploaderTest {
    private val draft = "11111111-1111-1111-1111-111111111111"
    private val foreignDraft = "22222222-2222-2222-2222-222222222222"
    private val evidenceId = "33333333-3333-3333-3333-333333333333"
    private val session = "44444444-4444-4444-4444-444444444444"
    private val device = "55555555-5555-5555-5555-555555555555"
    private val sha = "86edbaa24831badfa0a8b04bb410141e2ee4182b6d0014493fe262a7a331c20b"
    private val bytes = "%PDF-1.7".encodeToByteArray()

    private fun metadata(target: String, state: String) = """
        {"evidenceId":"$evidenceId","uploadSessionId":"$session",
         "targetType":"FINANCE_EXPENSE_RECEIPT","targetId":"$target",
         "evidenceRequirementKey":"FINANCE_EXPENSE_RECEIPT_BINARY",
         "evidenceTypeCode":"FINANCE_EXPENSE_RECEIPT",
         "contentType":"application/pdf","sizeBytes":8,"sha256":"$sha",
         "storageState":"$state","classificationCode":"HIGHLY_RESTRICTED",
         "clientVisibilityMode":"INTERNAL_ONLY","evidenceVersion":1}
    """.trimIndent()

    private fun selected() = FinanceSelectedExpenseReceipt(
        expenseDraftId = draft,
        reserveOperationId = "66666666-6666-6666-6666-666666666666",
        finalizeOperationId = "77777777-7777-7777-7777-777777777777",
        contentType = "application/pdf",
        originalFileName = "employee-receipt.pdf",
        bytes = bytes,
        sha256 = sha,
        capturedAt = "2026-11-23T12:00:00Z",
    )

    @Test fun privateReceiptUploadsAndOnlyCanonicalContentReadyIsReported() = runBlocking {
        var puts = 0
        val apiHttp = HttpClient(MockEngine { call ->
            assertEquals("Bearer employee-token", call.headers[HttpHeaders.Authorization])
            when (call.url.encodedPath) {
                "/v1/evidence/reservations" ->
                    respond("""{"evidence":${metadata(draft, "RESERVED")},
                        "upload":{"uploadUrl":"https://private-storage.test/receipt",
                        "requiredHeaders":{},"expiresAt":"2026-11-23T12:10:00Z",
                        "expectedSizeBytes":8},"correlationId":"test","replayed":false}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                "/v1/evidence/$evidenceId/finalize" ->
                    respond("""{"evidence":${metadata(draft, "READY")},
                        "correlationId":"test","replayed":false}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                "/v1/evidence/$evidenceId" ->
                    respond(metadata(draft, "READY"),
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> error("Unexpected route " + call.url.encodedPath)
            }
        })
        val binary = HttpClient(MockEngine { call ->
            assertEquals("https://private-storage.test/receipt", call.url.toString())
            assertEquals("PUT", call.method.value)
            puts++
            respond("", HttpStatusCode.OK)
        })
        val appApi = HiltechApiClient(
            apiHttp, "https://api.hiltech.test",
            accessTokenProvider = { "employee-token" },
            correlationIdProvider = { "finance-receipt" },
        )
        val client = FinanceExpenseReceiptUploader(EvidenceApiClient(appApi, binary))
        val uploaded = client.upload(selected(), device)
        assertEquals(1, puts)
        assertFalse(uploaded.quarantined)
        assertTrue(uploaded.uploadedReceiptReady)
        val verified = client.refresh(evidenceId, draft, device)
        assertTrue(verified.uploadedReceiptReady)
        assertFalse(verified.quarantined)
    }

    @Test fun forgedExpenseTargetPreventsSignedBinaryUpload() = runBlocking {
        var puts = 0
        val app = HttpClient(MockEngine { call ->
            when (call.url.encodedPath) {
                "/v1/evidence/reservations" ->
                    respond("""{"evidence":${metadata(foreignDraft, "RESERVED")},
                        "upload":{"uploadUrl":"https://private-storage.test/receipt",
                        "requiredHeaders":{},"expiresAt":"2026-11-23T12:10:00Z",
                        "expectedSizeBytes":8},"correlationId":"test","replayed":false}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> error("Unexpected route")
            }
        })
        val binary = HttpClient(MockEngine {
            puts++
            respond("", HttpStatusCode.OK)
        })
        val appApi = HiltechApiClient(
            app, "https://api.hiltech.test",
            accessTokenProvider = { "employee-token" },
            correlationIdProvider = { "finance-receipt" },
        )
        assertFailsWith<IllegalArgumentException> {
            FinanceExpenseReceiptUploader(EvidenceApiClient(appApi, binary))
                .upload(selected(), device)
        }
        assertEquals(0, puts)
    }
}
