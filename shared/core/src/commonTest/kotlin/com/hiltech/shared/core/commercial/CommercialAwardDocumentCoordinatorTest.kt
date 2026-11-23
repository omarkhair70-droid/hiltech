package com.hiltech.shared.core.commercial

import com.hiltech.shared.core.evidence.EvidenceApiClient
import com.hiltech.shared.core.network.HiltechApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Canonical shared-client contract. Does not claim to exercise actual object storage. */
class CommercialAwardDocumentCoordinatorTest {
    @Test
    fun reserveUploadFinalizeThenConfirmOnlyReadySignedDocument() = runBlocking {
        val contractId = "11111111-1111-4111-8111-111111111111"
        val evidenceId = "22222222-2222-4222-8222-222222222222"
        val reserveId = "33333333-3333-4333-8333-333333333333"
        val finalizeId = "44444444-4444-4444-8444-444444444444"
        val confirmId = "55555555-5555-4555-8555-555555555555"
        val sessionId = "66666666-6666-4666-8666-666666666666"
        val installation = "77777777-7777-4777-8777-777777777777"
        val sha = "a".repeat(64)
        val requests = mutableListOf<Pair<String,String?>>()
        fun metadata(state: String): String = """
          {"evidenceId":"$evidenceId","uploadSessionId":"$sessionId",
           "targetType":"COMMERCIAL_CONTRACT","targetId":"$contractId",
           "evidenceTypeCode":"COMMERCIAL_SIGNED_CONTRACT",
           "contentType":"application/pdf","sizeBytes":3,
           "sha256":"$sha","storageState":"$state",
           "classificationCode":"HIGHLY_RESTRICTED",
           "clientVisibilityMode":"INTERNAL_ONLY","evidenceVersion":1}
        """.trimIndent()
        val http = HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            requests += path to req.headers["Idempotency-Key"]
            when (path) {
                "/v1/evidence/reservations" -> {
                    assertEquals(HttpMethod.Post, req.method)
                    assertEquals(reserveId, req.headers["Idempotency-Key"])
                    assertEquals("Bearer test-token", req.headers[HttpHeaders.Authorization])
                    respond(
                        """{"evidence":#RESERVED#,
                            "upload":{"uploadUrl":"https://store.test/upload",
                              "requiredHeaders":{},"expiresAt":"2026-11-23T23:59:00Z",
                              "expectedSizeBytes":3},
                            "correlationId":"corr-test","replayed":false}""".replace("#RESERVED#",metadata("RESERVED")),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                "/upload" -> {
                    assertEquals(HttpMethod.Put, req.method)
                    respond("",HttpStatusCode.OK)
                }
                "/v1/evidence/$evidenceId/finalize" -> {
                    assertEquals(finalizeId,req.headers["Idempotency-Key"])
                    respond(
                        """{"evidence":#READY#,"correlationId":"corr-test",
                             "replayed":false}""".replace("#READY#",metadata("READY")),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                "/v1/evidence/$evidenceId" -> {
                    assertEquals(HttpMethod.Get,req.method)
                    respond(metadata("READY"),HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType,"application/json"))
                }
                "/v1/commercial/contracts/$contractId/confirm-signed" -> {
                    assertEquals(confirmId,req.headers["Idempotency-Key"])
                    respond(
                        """{"id":"$contractId","replayed":false,
                            "state":"SIGNED","correlationId":"corr-test"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> error("Unexpected endpoint $path")
            }
        })
        val api=HiltechApiClient(
            http,"https://hiltech.test",
            accessTokenProvider={"test-token"},correlationIdProvider={"corr-test"},
        )
        val coordinator=CommercialAwardDocumentCoordinator(
            EvidenceApiClient(api,http),CommercialApiClient(api),installation,
        )
        val input=CommercialAwardDocumentUpload(
            document=CommercialAwardDocumentType.SIGNED_CONTRACT,
            targetId=contractId,
            originalFileName="signed-contract.pdf",
            contentType="application/pdf",
            bytes=byteArrayOf(0x25,0x50,0x44),
            sha256=sha,
            capturedAtUtc="2026-11-23T00:00:00Z",
            reserveOperationId=reserveId,
            finalizeOperationId=finalizeId,
            confirmOperationId=confirmId,
        )
        val reservation=coordinator.reserve(input)
        assertEquals("RESERVED",reservation.evidence.storageState)
        val ready=coordinator.uploadAndFinalize(input,reservation)
        assertEquals("READY",ready.storageState)
        val recovered = coordinator.readyFromMetadata(input,evidenceId)
        assertEquals(ready.evidenceId,recovered.evidenceId)
        assertFailsWith<IllegalArgumentException> {
            coordinator.confirm(input,ready.copy(storageState="QUARANTINED"))
        }
        assertEquals("SIGNED",coordinator.confirm(input,recovered).state)
        assertEquals(listOf(
            "/v1/evidence/reservations","/upload",
            "/v1/evidence/$evidenceId/finalize",
            "/v1/evidence/$evidenceId",
            "/v1/commercial/contracts/$contractId/confirm-signed",
        ),requests.map{it.first})
        assertEquals(reserveId,requests[0].second)
        assertNull(requests[1].second)
        assertEquals(finalizeId,requests[2].second)
        assertNull(requests[3].second)
        assertEquals(confirmId,requests[4].second)
        http.close()
    }
}
