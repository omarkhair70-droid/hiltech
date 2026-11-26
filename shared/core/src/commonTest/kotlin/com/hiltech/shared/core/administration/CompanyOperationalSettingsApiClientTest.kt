package com.hiltech.shared.core.administration

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanyOperationalSettingsApiClientTest {
    private val organization = "11111111-1111-1111-1111-111111111111"
    private val operation = "22222222-2222-2222-2222-222222222222"
    private val installation = "33333333-3333-3333-3333-333333333333"

    @Test fun settingsOnlyAdministratorCanReachTheCompanyWorkspaceWithoutPolicyOrPeopleAccess() = runBlocking {
        val http = HttpClient(MockEngine {
            respond("""{"organizationId":"$organization","canManageResponsibilities":false,"canViewEmployeeResponsibilities":false,
                "canManageApprovalPolicies":false,"managedAuthorityKeys":[],"delegableAuthorityKeys":[],"canManageCompanySettings":true}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val result = CompanyAdministrationApiClient(api(http)).capabilities(organization, installation)
        assertTrue(result.available)
        assertFalse(result.canManageApprovalPolicies)
        assertFalse(result.canViewEmployeeResponsibilities)
        http.close()
    }

    @Test fun currentSetupRetainsReadOnlyDeploymentSourceAndNamedHistory() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/organizations/$organization/administration/purchase-order-numbering", request.url.encodedPath)
            respond("""{"organizationId":"$organization","source":"DEPLOYMENT","prefix":"OLD-PO","version":0,
                "ready":true,"exampleCode":"OLD-PO-000001","changedAt":null,"history":[]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val result = CompanyOperationalSettingsApiClient(api(http)).purchaseOrderNumbering(organization, installation)
        assertEquals("DEPLOYMENT", result.source)
        assertEquals("OLD-PO", result.prefix)
        http.close()
    }

    @Test fun lostResponseKeepsExactOperationVersionPrefixAndReasonForRetry() = runBlocking {
        val bodies = mutableListOf<String>()
        val keys = mutableListOf<String?>()
        val http = HttpClient(MockEngine { request ->
            bodies += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            keys += request.headers["Idempotency-Key"]
            if (bodies.size == 1) throw IllegalStateException("Synthetic lost response")
            respond(receipt(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val client = CompanyOperationalSettingsApiClient(api(http))
        assertFailsWith<HiltechApiException> { client.configurePurchaseOrderNumbering(intent(), installation) }
        val restored = Json.decodeFromString(CompanyPurchaseOrderNumberingIntent.serializer(),
            Json.encodeToString(CompanyPurchaseOrderNumberingIntent.serializer(), intent()))
        val result = client.configurePurchaseOrderNumbering(restored, installation)
        assertTrue(result.replayed)
        assertEquals(bodies[0], bodies[1])
        assertEquals<List<String?>>(listOf(operation, operation), keys)
        assertTrue(bodies[0].contains("Synthetic prefix adoption"))
        assertTrue(bodies[0].contains("OLD-PO"))
        http.close()
    }

    @Test fun responseForAnotherRevisionOrPrefixCannotBeReportedAsSuccess() = runBlocking {
        val http = HttpClient(MockEngine {
            respond(receipt().replace("\"version\":1", "\"version\":2"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertFailsWith<IllegalArgumentException> { CompanyOperationalSettingsApiClient(api(http)).configurePurchaseOrderNumbering(intent(), installation) }
        http.close()
    }

    @Test fun emptyInvalidAndAmbiguousPrefixFormatsCannotBeSubmitted() = runBlocking {
        var sends = 0
        val http = HttpClient(MockEngine { sends += 1; error("No request expected") })
        for (prefix in listOf("", "P", "PO--X", "PO-", "PO/2026", "A".repeat(25), "رقم")) {
            assertFalse(validCompanyPurchaseOrderPrefix(prefix))
            assertFailsWith<IllegalArgumentException> { CompanyOperationalSettingsApiClient(api(http)).configurePurchaseOrderNumbering(intent().copy(prefix = prefix), installation) }
        }
        assertTrue(validCompanyPurchaseOrderPrefix("HT-PO"))
        assertEquals(0, sends)
        http.close()
    }
    private fun intent() = CompanyPurchaseOrderNumberingIntent(operation, organization, 0, "DEPLOYMENT", "OLD-PO", "HT-PO", "Synthetic prefix adoption")
    private fun receipt() = """{"operationId":"$operation","organizationId":"$organization","version":1,"prefix":"HT-PO",
        "current":true,"replayed":true,"resultCode":"PURCHASE_ORDER_NUMBERING_CONFIGURED"}"""
    private fun api(http: HttpClient) = HiltechApiClient(http, "https://example.invalid", accessTokenProvider = { "synthetic-token" }, correlationIdProvider = { "synthetic-numbering" })
}
