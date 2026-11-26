package com.hiltech.shared.core.commercial

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
import kotlin.test.assertNull

class CommercialOpportunityCoordinatorTest {
    @Test
    fun nativeSalesDeskListsOnlySelectedOrganizationAndCreatesIdempotently() = runBlocking {
        val organizationId = "11111111-1111-4111-8111-111111111111"
        val clientId = "22222222-2222-4222-8222-222222222222"
        val opportunityId = "33333333-3333-4333-8333-333333333333"
        val operationId = "44444444-4444-4444-8444-444444444444"
        val installation = "55555555-5555-4555-8555-555555555555"
        val actions = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            assertEquals("Bearer token-test",request.headers[HttpHeaders.Authorization])
            assertEquals(installation,request.headers["X-Device-Installation-Id"])
            actions += request.url.encodedPath
            when(request.method) {
                HttpMethod.Get -> {
                    assertEquals(organizationId,request.url.parameters["organizationId"])
                    assertNull(request.headers["Idempotency-Key"])
                    respond(
                        """[{"id":"$opportunityId","title":"Existing opportunity",
                            "source":"RFP","state":"OPEN",
                            "client_organization_id":"$clientId"}]""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType,"application/json"),
                    )
                }
                HttpMethod.Post -> {
                    assertEquals(operationId,request.headers["Idempotency-Key"])
                    respond(
                        """{"id":"$opportunityId","state":"OPEN",
                            "replayed":false,"correlationId":"sales-test"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType,"application/json"),
                    )
                }
                else -> error("Unexpected HTTP method")
            }
        })
        val coordinator=CommercialDealCoordinator(
            CommercialApiClient(HiltechApiClient(
                http,"https://hiltech.test",
                accessTokenProvider={"token-test"},
                correlationIdProvider={"sales-test"},
            )),installation,
        )
        val list=coordinator.loadOpportunities(organizationId)
        assertEquals(opportunityId,list.single().id)
        assertEquals(clientId,list.single().clientOrganizationId)
        val created=coordinator.createOpportunity(
            CreateCommercialOpportunityDto(
                operationId=operationId,
                organizationId=organizationId,
                clientOrganizationId=clientId,
                title="New commercial opportunity",
                source="RFP",
            )
        )
        assertEquals("OPEN",created.state)
        assertEquals(listOf("/v1/commercial/opportunities","/v1/commercial/opportunities"),actions)
        http.close()
    }
}
