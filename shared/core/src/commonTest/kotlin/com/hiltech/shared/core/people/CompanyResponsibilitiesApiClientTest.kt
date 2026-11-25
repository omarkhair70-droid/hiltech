package com.hiltech.shared.core.people

import com.hiltech.shared.core.network.ErrorEnvelope
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

class CompanyResponsibilitiesApiClientTest {
    private val organization = "11111111-1111-1111-1111-111111111111"
    private val employee = "22222222-2222-2222-2222-222222222222"
    private val identity = "33333333-3333-3333-3333-333333333333"
    private val operation = "44444444-4444-4444-4444-444444444444"
    private val installation = "55555555-5555-5555-5555-555555555555"
    private val binding = "66666666-6666-6666-6666-666666666666"

    @Test
    fun summaryRetainsExactCatalogScopeAndInheritedAssignmentSemantics() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/employees/$employee/company-responsibilities", request.url.encodedPath)
            assertEquals("GET", request.method.value)
            assertEquals(installation, request.headers["X-Device-Installation-Id"])
            respond("""{"employeeId":"$employee","organizationId":"$organization","employeeVersion":3,
                "linkedIdentityId":"$identity","accountStatus":"CURRENT","scope":"ORGANIZATION_CATALOG",
                "asOf":"2026-11-25T12:00:00Z","catalog":[{"authorityKey":"PAYROLL_READ","domain":"PAYROLL","canGrant":false}],
                "bindings":[{"bindingId":"$binding","authorityKey":"PAYROLL_READ","domain":"PAYROLL","assignmentSource":"TEAM",
                "canEnd":false,"current":true,"version":2}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val result = client(http).current(employee, installation)
        assertEquals("ORGANIZATION_CATALOG", result.scope)
        assertEquals("TEAM", result.bindings.single().assignmentSource)
        assertFalse(result.bindings.single().canEnd)
        http.close()
    }

    @Test
    fun lostResponseRetriesExactFrozenIdentityVersionOperationAndPayload() = runBlocking {
        val bodies = mutableListOf<String>()
        val operations = mutableListOf<String?>()
        val http = HttpClient(MockEngine { request ->
            assertEquals("POST", request.method.value)
            bodies += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            operations += request.headers["Idempotency-Key"]
            if (bodies.size == 1) throw IllegalStateException("Synthetic response lost")
            respond(receipt(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val api = client(http)
        assertFailsWith<HiltechApiException> { api.apply(intent(), installation) }
        val restored = Json.decodeFromString(EmployeeCompanyResponsibilityIntent.serializer(),
            Json.encodeToString(EmployeeCompanyResponsibilityIntent.serializer(), intent()))
        val result = api.apply(restored, installation)
        assertTrue(result.replayed)
        assertEquals(bodies[0], bodies[1])
        assertEquals<List<String?>>(listOf(operation, operation), operations)
        val body = Json.parseToJsonElement(bodies[0]).jsonObject
        assertEquals(identity, body.getValue("expectedIdentityId").jsonPrimitive.content)
        assertEquals("3", body.getValue("baseEmployeeVersion").jsonPrimitive.content)
        http.close()
    }

    @Test
    fun endUsesExactBindingAndVersionAndRejectsWrongReceipt() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/employees/$employee/company-responsibilities/$binding/end", request.url.encodedPath)
            val body = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
            assertEquals("7", body.getValue("baseBindingVersion").jsonPrimitive.content)
            respond(receipt(resultCode = "COMPANY_RESPONSIBILITY_ENDED", responseOperation = binding),
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertFailsWith<IllegalArgumentException> {
            client(http).apply(intent().copy(action = "END", bindingId = binding, baseBindingVersion = 7), installation)
        }
        http.close()
    }

    @Test
    fun finalApprovalAndDelegationAuthorityCannotBeSubmittedThroughOperationalCatalog() = runBlocking {
        var sends = 0
        val http = HttpClient(MockEngine { sends += 1; error("No request expected") })
        for (key in listOf("OWNER_FINAL", "RESPONSIBILITY_ADMINISTRATOR", "PAYROLL_PAY", "anything' OR 1=1")) {
            assertFailsWith<IllegalArgumentException> { client(http).apply(intent().copy(authorityKey = key), installation) }
        }
        assertEquals(0, sends)
        http.close()
    }

    @Test
    fun authoritativeFirstRejectionAllowsRefreshButLaterRejectionCannotEraseUnknownOutcome() {
        val failure = apiFailure("RESPONSIBILITY_ADMINISTRATION_DENIED", 403)
        val first = companyResponsibilityFailureDisposition(false, failure)
        assertTrue(first.rejectionConfirmed)
        assertFalse(first.hasUnknownOutcome)
        val later = companyResponsibilityFailureDisposition(true, failure)
        assertFalse(later.rejectionConfirmed)
        assertTrue(later.hasUnknownOutcome)
    }

    @Test
    fun missingEnvelopeDoesNotProveACommandWasRejected() {
        val failure = HiltechApiException("HTTP_403", null, 403, false, null, "Synthetic proxy response")
        assertTrue(companyResponsibilityFailureDisposition(false, failure).hasUnknownOutcome)
        assertFalse(companyResponsibilityFailureDisposition(false, failure).rejectionConfirmed)
    }

    @Test
    fun storedTerminalFailureResolvesAnEarlierUnknownOutcome() {
        val result = companyResponsibilityFailureDisposition(true, apiFailure("OPERATION_PREVIOUSLY_FAILED", 409))
        assertFalse(result.hasUnknownOutcome)
        assertTrue(result.rejectionConfirmed)
    }

    private fun intent() = EmployeeCompanyResponsibilityIntent(operation, employee, organization, 3, identity,
        "PEOPLE_ADMIN", "Synthetic employee", "GRANT")
    private fun client(http: HttpClient) = CompanyResponsibilitiesApiClient(HiltechApiClient(http, "https://example.invalid",
        accessTokenProvider = { "synthetic-token" }, correlationIdProvider = { "synthetic-correlation" }))
    private fun receipt(resultCode: String = "COMPANY_RESPONSIBILITY_GRANTED", responseOperation: String = operation) = """
        {"operationId":"$responseOperation","employeeId":"$employee","organizationId":"$organization","identityId":"$identity",
         "authorityKey":"PEOPLE_ADMIN","bindingId":"$binding","resultCode":"$resultCode","current":true,"version":1,"replayed":true}
    """.trimIndent()
    private fun apiFailure(code: String, status: Int) = HiltechApiException(code, "synthetic-correlation", status, false,
        ErrorEnvelope(code, "Synthetic rejection", "synthetic-correlation"), "Synthetic rejection")
}
