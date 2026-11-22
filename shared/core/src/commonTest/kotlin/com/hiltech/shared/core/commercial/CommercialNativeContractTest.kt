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
