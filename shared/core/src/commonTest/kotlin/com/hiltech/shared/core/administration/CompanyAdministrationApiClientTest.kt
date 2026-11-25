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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanyAdministrationApiClientTest {
    private val organization = "11111111-1111-1111-1111-111111111111"
    private val employee = "22222222-2222-2222-2222-222222222222"
    private val identity = "33333333-3333-3333-3333-333333333333"
    private val operation = "44444444-4444-4444-4444-444444444444"
    private val installation = "55555555-5555-5555-5555-555555555555"
    private val policy = "66666666-6666-6666-6666-666666666666"
    private val binding = "77777777-7777-7777-7777-777777777777"

    @Test
    fun responsibilityDelegateCanOpenAdministrationWithoutPolicyAdministration() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertTrue(request.url.encodedPath.endsWith("/administration/capabilities"))
            respond("""{"organizationId":"$organization","canManageResponsibilities":true,"canViewEmployeeResponsibilities":true,
                "canManageApprovalPolicies":false,"managedAuthorityKeys":["SALES_VIEW"],"delegableAuthorityKeys":[]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val capabilities = client(http).capabilities(organization, installation)
        assertTrue(capabilities.available)
        assertFalse(capabilities.canManageApprovalPolicies)
        assertEquals(listOf("SALES_VIEW"), capabilities.managedAuthorityKeys)
        http.close()
    }

    @Test
    fun minimalEmployeeDirectorySupportsNamesWithoutPrivateHrDetail() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertTrue(request.url.encodedPath.endsWith("/administration/employees"))
            assertEquals("Synthetic", request.url.parameters["query"])
            respond("""{"items":[{"employeeId":"$employee","organizationId":"$organization","version":3,"employeeCode":"SYNTH",
                "displayName":"Synthetic employee","employeeState":"ACTIVE","accountStatus":"CURRENT"}],"hasMore":false}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val employee = client(http).employees(organization, "Synthetic", installation).items.single()
        assertEquals("Synthetic employee", employee.displayName)
        assertEquals("CURRENT", employee.accountStatus)
        assertEquals(3L, employee.version)
        http.close()
    }

    @Test
    fun existingTeamSetupAndPendingCountRemainVisible() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/organizations/$organization/administration/review-workflows/PAYROLL_SOURCE", request.url.encodedPath)
            assertEquals(installation, request.headers["X-Device-Installation-Id"])
            respond("""{"organizationId":"$organization","status":"READY","policyId":"$policy","policyRevision":2,"policyVersion":1,
                "bindingId":"$binding","bindingVersion":3,"reviewerPrincipalType":"TEAM","reviewerDisplayName":"Synthetic reviewers",
                "reviewerEmployeeId":null,"pendingReviews":4,"asOf":"2026-11-25T12:00:00Z","workflow":"PAYROLL_SOURCE"}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val result = client(http).reviewSetup(organization, "PAYROLL_SOURCE", installation)
        assertEquals("TEAM", result.reviewerPrincipalType)
        assertEquals("Synthetic reviewers", result.reviewerDisplayName)
        assertEquals(4, result.pendingReviews)
        http.close()
    }

    @Test
    fun reviewerSearchUsesEncodedHumanQueryAndDomainScope() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertTrue(request.url.encodedPath.endsWith("/FINANCE_PAYROLL_RESULT/reviewer-candidates"))
            assertEquals("أحمد & 100%", request.url.parameters["query"])
            respond("""{"items":[{"employeeId":"$employee","identityId":"$identity","employeeVersion":3,
                "displayName":"Synthetic reviewer","employeeCode":"SYNTH-REVIEWER"}],"hasMore":false}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val result = client(http).reviewerCandidates(organization, "FINANCE_PAYROLL_RESULT", "أحمد & 100%", installation)
        assertEquals("Synthetic reviewer", result.items.single().displayName)
        assertEquals(3, result.items.single().employeeVersion)
        http.close()
    }

    @Test
    fun savedPolicyChangeRetriesSameVersionsAccountWorkflowAndOperation() = runBlocking {
        val bodies = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            assertTrue(request.url.encodedPath.endsWith("/PAYROLL_SOURCE"))
            assertEquals(operation, request.headers["Idempotency-Key"])
            bodies += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            if (bodies.size == 1) throw IllegalStateException("Synthetic response lost")
            respond(receipt(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val api = client(http)
        assertFailsWith<HiltechApiException> { api.configureReview(intent(), installation) }
        val restored = Json.decodeFromString(CompanyReviewSetupIntent.serializer(), Json.encodeToString(CompanyReviewSetupIntent.serializer(), intent()))
        assertTrue(api.configureReview(restored, installation).replayed)
        assertEquals(bodies[0], bodies[1])
        val request = Json.parseToJsonElement(bodies[0]).jsonObject
        assertEquals(identity, request.getValue("expectedReviewerIdentityId").jsonPrimitive.content)
        assertEquals("7", request.getValue("expectedBindingVersion").jsonPrimitive.content)
        assertEquals("4", request.getValue("expectedPolicyVersion").jsonPrimitive.content)
        assertFalse("stepMode" in request || "approvalRequired" in request || "authorityKey" in request)
        http.close()
    }

    @Test
    fun arbitraryWorkflowAndUnpairedVersionsAreRejectedBeforeSending() = runBlocking {
        var sent = false
        val http = HttpClient(MockEngine { sent = true; error("No call expected") })
        val api = client(http)
        assertFailsWith<IllegalArgumentException> { api.configureReview(intent().copy(workflow = "OWNER_FINAL"), installation) }
        assertFailsWith<IllegalArgumentException> { api.configureReview(intent().copy(expectedBindingVersion = null), installation) }
        assertFalse(sent)
        http.close()
    }

    @Test
    fun receiptForAnotherWorkflowCannotConfirmThePendingRequest() = runBlocking {
        val http = HttpClient(MockEngine { respond(receipt("FINANCE_PAYROLL_RESULT"), HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json")) })
        assertFailsWith<IllegalArgumentException> { client(http).configureReview(intent(), installation) }
        http.close()
    }

    @Test
    fun ownPeopleActivationHasNoArbitraryRecipientOrAuthorityAndRetriesSameOperation() = runBlocking {
        val bodies = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/organizations/$organization/administration/own-people-administration", request.url.encodedPath)
            assertEquals(operation, request.headers["Idempotency-Key"])
            bodies += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            if (bodies.size == 1) throw IllegalStateException("Synthetic response lost")
            respond(ownReceipt(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val api = client(http)
        val intent = OwnPeopleAdministrationIntent(operation, organization)
        assertFailsWith<HiltechApiException> { api.activateOwnPeopleAdministration(intent, installation) }
        val restored = Json.decodeFromString(OwnPeopleAdministrationIntent.serializer(), Json.encodeToString(OwnPeopleAdministrationIntent.serializer(), intent))
        val result = api.activateOwnPeopleAdministration(restored, installation)
        assertTrue(result.replayed)
        assertTrue(result.current)
        assertFalse(result.accessReady)
        assertEquals(bodies[0], bodies[1])
        assertEquals(setOf("operationId"), Json.parseToJsonElement(bodies[0]).jsonObject.keys)
        http.close()
    }

    @Test
    fun anotherAuthorityReceiptCannotConfirmOwnPeopleActivation() = runBlocking {
        val http = HttpClient(MockEngine { respond(ownReceipt("OWNER_FINAL"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
        assertFailsWith<IllegalArgumentException> {
            client(http).activateOwnPeopleAdministration(OwnPeopleAdministrationIntent(operation, organization), installation)
        }
        http.close()
    }

    private fun ownReceipt(authority: String = "PEOPLE_ADMIN") = """{"operationId":"$operation","organizationId":"$organization",
        "identityId":"$identity","authorityKey":"$authority","bindingId":"$binding","current":true,"accessReady":false,"version":1,"replayed":true}"""

    private fun intent() = CompanyReviewSetupIntent(operation, organization, "PAYROLL_SOURCE", employee, identity,
        3, "Synthetic reviewer", policy, 4, binding, 7, 2)
    private fun receipt(workflow: String = "PAYROLL_SOURCE") = """{"operationId":"$operation","organizationId":"$organization",
        "policyId":"$policy","policyRevision":3,"bindingId":"$binding","current":true,"replayed":true,"workflow":"$workflow"}"""
    private fun client(http: HttpClient) = CompanyAdministrationApiClient(HiltechApiClient(http, "https://example.invalid",
        accessTokenProvider = { "synthetic-token" }, correlationIdProvider = { "synthetic-correlation" }))
}
