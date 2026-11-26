package com.hiltech.shared.core.service

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
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WarrantyCoverageReadinessNativeContractTest {
    private val project = "11111111-1111-4111-8111-111111111111"
    private val ticket = "22222222-2222-4222-8222-222222222222"
    private val installation = "88888888-8888-4888-8888-888888888888"

    @Test
    fun managerReadRequiresCanonicalTicketAndNeverInventsWarranty() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/v1/projects/$project/support-tickets/$ticket/warranty-readiness",
                request.url.encodedPath)
            assertEquals("Bearer phase11-token", request.headers[HttpHeaders.Authorization])
            assertEquals(installation, request.headers["X-Device-Installation-Id"])
            respond(
                content = """
                {
                    "projectId":"$project",
                    "ticketId":"$ticket",
                    "contractId":null,
                    "clientPoId":null,
                    "commercialSourceState":"P10_SOURCE_NOT_DEPLOYED",
                    "writtenWarrantyTermsPresent":false,
                    "coverageDecisionState":"NOT_EVALUATED",
                    "approvalState":"NOT_APPROVED",
                    "coveredAssetOrSiteState":"NOT_VERIFIED",
                    "blockerCodes":["P10_SOURCE_NOT_DEPLOYED","COMPANY_WARRANTY_POLICY_NOT_APPROVED"],
                    "warrantyApproved":false,
                    "slaAuthorized":false,
                    "clientMayBeNotified":false,
                    "billableServiceAuthorized":false,
                    "clientVisibility":"INTERNAL_ONLY"
                }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val client = WarrantyCoverageReadinessApiClient(HiltechApiClient(
            client = http,
            baseUrl = "https://api.hiltech.test",
            accessTokenProvider = { "phase11-token" },
            correlationIdProvider = { "phase11-corr" },
        ))
        val result = client.read(project, ticket, installation)
        assertEquals("NOT_EVALUATED", result.coverageDecisionState)
        assertTrue("P10_SOURCE_NOT_DEPLOYED" in result.blockerCodes)
        assertFalse(result.warrantyApproved)
        assertFalse(result.slaAuthorized)
        assertFalse(result.clientMayBeNotified)
        assertEquals("INTERNAL_ONLY", result.clientVisibility)
        assertFailsWith<IllegalArgumentException> {
            client.read(project, "not-a-uuid", installation)
        }
    }
}
