package com.hiltech.shared.core.commercial

import com.hiltech.shared.core.CommercialDealAction
import com.hiltech.shared.core.CommercialDraftLine
import com.hiltech.shared.core.validCommercialDecimal
import com.hiltech.shared.core.network.HiltechApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommercialNativeContractTest {
    @Test
    fun commercialSignedDocsReserveCanonicalRestrictedEvidenceTargets() {
        val seed = CommercialAwardDocumentUpload(
            document = CommercialAwardDocumentType.SIGNED_CONTRACT,
            targetId = "33333333-3333-4333-8333-333333333333",
            originalFileName = "signed.pdf",
            contentType = "application/pdf",
            bytes = byteArrayOf(1,2,3),
            sha256 = "a".repeat(64),
            capturedAtUtc = "2026-11-23T00:00:00Z",
            reserveOperationId = "11111111-1111-4111-8111-111111111111",
            finalizeOperationId = "22222222-2222-4222-8222-222222222222",
            confirmOperationId = "44444444-4444-4444-8444-444444444444",
        )
        val contract = commercialEvidenceRequest(seed)
        assertEquals("COMMERCIAL_CONTRACT", contract.targetType)
        assertEquals("COMMERCIAL_SIGNED_CONTRACT", contract.evidenceRequirementKey)
        assertEquals(contract.evidenceRequirementKey, contract.evidenceTypeCode)
        assertEquals(null,contract.workOrderId)
        assertEquals(3L,contract.sizeBytes)
        val clientPo = commercialEvidenceRequest(seed.copy(document=CommercialAwardDocumentType.CLIENT_PO))
        assertEquals("COMMERCIAL_CLIENT_PO",clientPo.targetType)
        assertEquals("COMMERCIAL_CLIENT_PO",clientPo.evidenceTypeCode)
        assertFailsWith<IllegalArgumentException> {
            commercialEvidenceRequest(seed.copy(contentType="text/plain"))
        }
        assertFailsWith<IllegalArgumentException> {
            commercialEvidenceRequest(seed.copy(sha256="fake-unsigned-file"))
        }
        assertFailsWith<IllegalArgumentException> {
            commercialEvidenceRequest(seed.copy(bytes=byteArrayOf()))
        }
    }

    @Test
    fun exactDecimalInputIsNotDoubleOrString() {
        assertTrue(validCommercialDecimal("0.2500", requirePositive = true))
        assertTrue(validCommercialDecimal("1234567890123456.9999"))
        assertFalse(validCommercialDecimal("0.0000", requirePositive = true))
        assertFalse(validCommercialDecimal("0.12345"))
        assertFalse(validCommercialDecimal("-1"))
        assertFalse(validCommercialDecimal("10000000000000000"))
        assertFalse(validCommercialDecimal("NaN"))
        assertEquals("0.2500", exactCommercialNumber("0.2500").content)
        assertEquals("1234567890123456.9999", exactCommercialNumber("1234567890123456.9999").content)
        assertFailsWith<IllegalArgumentException> { exactCommercialNumber("1.00001") }
    }

    @Test
    fun crossPlatformCoordinatorUsesOneAuthenticatedAndIdempotentCommandContract() = runBlocking {
        val operationId = "11111111-1111-4111-8111-111111111111"
        val installationId = "22222222-2222-4222-8222-222222222222"
        val opportunityId = "33333333-3333-4333-8333-333333333333"
        val events = mutableListOf<Pair<String, String>>()
        val http = HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            assertEquals("Bearer commercial-test-token", req.headers[HttpHeaders.Authorization])
            assertEquals(installationId, req.headers["X-Device-Installation-Id"])
            assertEquals(operationId, req.headers["Idempotency-Key"])
            val payload = (req.body as? TextContent)?.text.orEmpty()
            events += path to payload
            respond(
                """{"id":"$opportunityId","replayed":false,"state":"PRIVATE","correlationId":"corr-native"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType,"application/json"),
            )
        })
        val coordinator = CommercialDealCoordinator(
            CommercialApiClient(HiltechApiClient(
                http,"https://api.hiltech.test",
                accessTokenProvider = { "commercial-test-token" },
                correlationIdProvider = { "corr-native" },
            )),
            installationId,
        )
        val result = coordinator.dispatch(
            CommercialDealAction.SaveBoq(
                opportunityId,
                "44444444-4444-4444-8444-444444444444",
                "EGP",
                listOf(CommercialDraftLine(
                    lineCode="ABC",description="Fiber installation",
                    category="LABOR",quantity="0.2500",unit="hour",
                    internalUnitCost="12.1250",sellUnitPrice="20.5000",
                )),
            ),
            operationId,
        )
        assertEquals("PRIVATE",result.state)
        assertEquals("/v1/commercial/opportunities/$opportunityId/boqs",events.single().first)
        val body = events.single().second
        assertTrue(body.contains("\"quantity\":0.2500"))
        assertTrue(body.contains("\"internalUnitCost\":12.1250"))
        assertTrue(body.contains("\"sellUnitPrice\":20.5000"))
        assertFalse(body.contains("\"quantity\":\"0.2500\""))
        http.close()
    }

    @Test
    fun tenderSubmissionAndSiteVisitAreMappedToTypedCanonicalEndpoints() = runBlocking {
        val installation = "11111111-1111-4111-8111-111111111111"
        val tenderOperation = "22222222-2222-4222-8222-222222222222"
        val visitOperation = "55555555-5555-4555-8555-555555555555"
        val opportunity = "33333333-3333-4333-8333-333333333333"
        val existingSite = "44444444-4444-4444-8444-444444444444"
        val events = mutableListOf<Pair<String,String>>()
        val http = HttpClient(MockEngine { req ->
            assertTrue(req.headers["Idempotency-Key"] in setOf(tenderOperation,visitOperation))
            val body = (req.body as? TextContent)?.text.orEmpty()
            events += req.url.encodedPath to body
            respond(
                """{"id":"$opportunity","replayed":false,"state":"RECORDED","correlationId":"corr-test"}""",
                HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"),
            )
        })
        val coordinator = CommercialDealCoordinator(
            CommercialApiClient(HiltechApiClient(
                http,"https://api.hiltech.test",
                accessTokenProvider={"token"},
                correlationIdProvider={"corr-test"},
            )),installation,
        )
        coordinator.dispatch(
            CommercialDealAction.TenderRevision(
                opportunityId=opportunity,tenderType="RFP",
                receivedAt="2026-11-23T08:00:00Z",
                referenceNumber="RFP-123",submissionDeadline="2026-12-10T12:00:00Z",
                clarificationDeadline="",sourceChannel="official-portal",
                submissionState="SUBMITTED",
            ),tenderOperation,
        )
        coordinator.dispatch(
            CommercialDealAction.ScheduleVisit(
                opportunityId=opportunity,
                purpose="Inspect existing fiber pathway",
                scheduledAt="2026-12-10T08:00:00Z",
                siteId=existingSite,
            ),visitOperation,
        )
        assertEquals("/v1/commercial/opportunities/$opportunity/tenders",events[0].first)
        assertTrue(events[0].second.contains("\"submissionState\":\"SUBMITTED\""))
        assertEquals("/v1/commercial/opportunities/$opportunity/visits",events[1].first)
        assertTrue(events[1].second.contains("\"siteId\":\"$existingSite\""))
        http.close()
    }

    @Test
    fun authorizedDealDetailCanBeDecodedWithoutCostingProjection() {
        val response = """
        {
          "opportunity": {
            "id":"opp","title":"Site installation","state":"QUALIFIED","source":"RFQ",
            "version":2,"client_organization_id":"client"
          },
          "qualification":[{"id":"q","discovery_summary":"Existing customer scope",
            "open_questions":"Visit pending"}],
          "tenders":[],
          "visits":[],
          "scopeVersions":[],
          "quotes":[{"id":"quote","quote_code":"Q-1","version_number":1,
            "currency":"EGP","total_price":12000.5000,"state":"APPROVAL_PENDING"}],
          "contracts":[{"id":"contract","contract_code":"C-1",
            "quote_version_id":"quote","state":"DRAFT"}],
          "clientPurchaseOrders":[],
          "handoff":[]
        }
        """.trimIndent()
        val dto=Json { ignoreUnknownKeys=true }.decodeFromString<CommercialDealDetailDto>(response)
        assertEquals("QUALIFIED",dto.opportunity.state)
        assertEquals("12000.5000",dto.quotes.single().totalPrice.content)
        assertEquals("DRAFT",dto.contracts.single().state)
        val publicEncoded = Json.encodeToString(CommercialDealDetailDto.serializer(),dto)
        assertFalse("internal_unit_cost" in publicEncoded)
        assertFalse("margin" in publicEncoded)
    }
}
