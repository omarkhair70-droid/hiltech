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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmployeeAccountAccessApiClientTest {
    private val organization = "11111111-1111-1111-1111-111111111111"
    private val employee = "22222222-2222-2222-2222-222222222222"
    private val identity = "33333333-3333-3333-3333-333333333333"
    private val operation = "44444444-4444-4444-4444-444444444444"
    private val installation = "55555555-5555-5555-5555-555555555555"
    private val membership = "66666666-6666-6666-6666-666666666666"
    private val previousOperation = "77777777-7777-7777-7777-777777777777"
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun currentAccountSourceContainsTheExactHistoryAndVersions() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/employees/$employee/account-access", request.url.encodedPath)
            assertEquals(installation, request.headers["X-Device-Installation-Id"])
            respond(current(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val result = client(http).current(employee, installation)
        assertTrue(result.canReissue)
        assertEquals(identity, result.account?.identityId)
        assertEquals(3L, result.account?.identityVersion)
        assertEquals(2L, result.account?.membershipVersion)
        assertEquals(previousOperation, result.latestReissue?.operationId)
        assertEquals("UNCONFIRMED", result.latestReissue?.status)
        http.close()
    }

    @Test
    fun ownAccountCandidateUsesVerifiedDisplayNameWithoutAnEditableIdentityTarget() = runBlocking {
        val http = HttpClient(MockEngine {
            respond("""{"employeeId":"$employee","organizationId":"$organization","employeeVersion":7,
                "ownAccountCandidate":{"displayName":"Synthetic administrator","identityVersion":3,"membershipId":"$membership","membershipVersion":2}}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val result = client(http).current(employee, installation)
        assertEquals("Synthetic administrator", result.ownAccountCandidate?.displayName)
        assertFalse(result.canReissue)
        assertNull(result.account)
        http.close()
    }

    @Test
    fun ownLinkWireRequestCannotSubstituteAnIdentitySubjectIssuerOrDisplayName() = runBlocking {
        val http = HttpClient(MockEngine { sent ->
            assertEquals("/v1/employees/$employee/own-account-link", sent.url.encodedPath)
            assertEquals(operation, sent.headers["Idempotency-Key"])
            val body = json.parseToJsonElement((sent.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
            assertEquals(setOf("operationId", "baseEmployeeVersion", "baseIdentityVersion", "expectedMembershipId", "baseMembershipVersion"), body.keys)
            assertEquals(membership, body.getValue("expectedMembershipId").jsonPrimitive.content)
            assertEquals("3", body.getValue("baseIdentityVersion").jsonPrimitive.content)
            respond(linkReceipt(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertTrue(client(http).linkOwn(linkIntent(), installation).linked)
        http.close()
    }

    @Test
    fun lostResetResponseRetriesTheSameIntentWithoutSecretMaterialInSavedState() = runBlocking {
        val bodies = mutableListOf<String>()
        val operations = mutableListOf<String?>()
        val http = HttpClient(MockEngine { sent ->
            assertEquals("/v1/employees/$employee/credential-reissues", sent.url.encodedPath)
            bodies += (sent.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            operations += sent.headers["Idempotency-Key"]
            if (bodies.size == 1) throw IllegalStateException("Synthetic lost response")
            respond(receipt(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val api = client(http)
        assertFailsWith<HiltechApiException> { api.reissue(intent(), installation) }
        val saved = Json.encodeToString(EmployeeCredentialReissueIntent.serializer(), intent())
        val restored = Json.decodeFromString(EmployeeCredentialReissueIntent.serializer(), saved)
        val result = api.reissue(restored, installation)
        assertTrue(result.replayed)
        assertNull(result.temporaryCredential)
        assertEquals(bodies[0], bodies[1])
        assertEquals<List<String?>>(listOf(operation, operation), operations)
        val body = json.parseToJsonElement(bodies[0]).jsonObject
        assertEquals(previousOperation, body.getValue("previousOperationId").jsonPrimitive.content)
        assertEquals("2", body.getValue("previousOperationVersion").jsonPrimitive.content)
        assertFalse(body.keys.any { it in setOf("temporaryCredential", "password", "secret", "issuer", "subject") })
        assertFalse(saved.contains("synthetic-one-time-password"))
        http.close()
    }

    @Test
    fun firstSuccessfulReceiptMayContainASecretOnlyInTheTransientResponse() = runBlocking {
        val http = HttpClient(MockEngine {
            respond(receipt(replayed = false, secret = "synthetic-one-time-password"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val result = client(http).reissue(intent(), installation)
        assertEquals("synthetic-one-time-password", result.temporaryCredential)
        assertFalse(result.toString().contains("synthetic-one-time-password"))
        http.close()
    }

    @Test
    fun unexpectedSecretOnReplayOrUnconfirmedOutcomeIsRejected() = runBlocking {
        for ((status, replay) in listOf("ISSUED" to true, "REJECTED" to false, "UNCONFIRMED" to false)) {
            val http = HttpClient(MockEngine {
                respond(receipt(status = status, replayed = replay, secret = "synthetic-unexpected-password"),
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            })
            assertFailsWith<IllegalArgumentException> { client(http).reissue(intent(), installation) }
            http.close()
        }
    }

    @Test
    fun wrongEmployeeAccountOrOperationReceiptCannotConfirmPasswordReplacement() = runBlocking {
        for (bad in listOf(receipt().replace("\"operationId\":\"$operation\"", "\"operationId\":\"$previousOperation\""),
            receipt().replace("\"employeeId\":\"$employee\"", "\"employeeId\":\"$identity\""),
            receipt().replace("\"identityId\":\"$identity\"", "\"identityId\":\"$employee\""))) {
            val http = HttpClient(MockEngine { respond(bad, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
            assertFailsWith<IllegalArgumentException> { client(http).reissue(intent(), installation) }
            http.close()
        }
    }

    @Test
    fun incompleteVersionsHistoryAndReasonNeverReachTheServer() = runBlocking {
        var sent = 0
        val http = HttpClient(MockEngine { sent += 1; error("No request expected") })
        val api = client(http)
        for (invalid in listOf(intent().copy(reason = " "), intent().copy(baseIdentityVersion = 0), intent().copy(baseMembershipVersion = 0),
            intent().copy(previousOperationId = null), intent().copy(previousOperationVersion = null))) {
            assertFailsWith<IllegalArgumentException> { api.reissue(invalid, installation) }
        }
        assertEquals(0, sent)
        http.close()
    }

    @Test
    fun malformedCurrentAccountCannotEnableAResetAction() = runBlocking {
        for (bad in listOf(current().replace("\"employeeId\":\"$employee\"", "\"employeeId\":\"$identity\""),
            current().replace("\"identityVersion\":3", "\"identityVersion\":0"),
            """{"employeeId":"$employee","organizationId":"$organization","employeeVersion":7,"canReissue":true}""")) {
            val http = HttpClient(MockEngine { respond(bad, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
            assertFailsWith<IllegalArgumentException> { client(http).current(employee, installation) }
            http.close()
        }
    }

    @Test
    fun busyBeforeFirstDispatchAllowsRefreshButCannotEraseAnEarlierUnknownOperation() {
        val failure = failure("ACCOUNT_CREDENTIAL_COMMAND_BUSY", retryable = true)
        assertTrue(accountAccessFailureDisposition(false, failure).rejectionConfirmed)
        assertFalse(accountAccessFailureDisposition(false, failure).hasUnknownOutcome)
        assertTrue(accountAccessFailureDisposition(true, failure).hasUnknownOutcome)
        assertFalse(accountAccessFailureDisposition(true, failure).rejectionConfirmed)
    }

    @Test
    fun accessChangedAfterDispatchAlwaysRetainsTheUnknownOperationForRecovery() {
        val result = accountAccessFailureDisposition(false, failure("ACCOUNT_CREDENTIAL_ACCESS_CHANGED_AFTER_DISPATCH", retryable = true))
        assertTrue(result.hasUnknownOutcome)
        assertFalse(result.rejectionConfirmed)
    }

    private fun client(http: HttpClient) = EmployeeAccountAccessApiClient(HiltechApiClient(http, "https://example.invalid",
        accessTokenProvider = { "synthetic-token" }, correlationIdProvider = { "synthetic-correlation" }))
    private fun intent() = EmployeeCredentialReissueIntent(operation, employee, organization, 7, identity, 3, membership, 2,
        previousOperation, 2, "Synthetic lost handoff")
    private fun linkIntent() = LinkOwnEmployeeAccountIntent(operation, employee, organization, 7, 3, membership, 2, "Synthetic administrator")
    private fun current() = """{"employeeId":"$employee","organizationId":"$organization","employeeVersion":7,"canReissue":true,
        "account":{"identityId":"$identity","identityVersion":3,"membershipId":"$membership","membershipVersion":2},
        "latestReissue":{"operationId":"$previousOperation","status":"UNCONFIRMED","version":2}}"""
    private fun linkReceipt() = """{"operationId":"$operation","employeeId":"$employee","organizationId":"$organization","employeeVersion":8,"linked":true,"replayed":false}"""
    private fun receipt(status: String = "ISSUED", replayed: Boolean = true, secret: String? = null) = """{"operationId":"$operation","employeeId":"$employee",
        "organizationId":"$organization","identityId":"$identity","status":"$status","version":2,"replayed":$replayed${secret?.let { ",\"temporaryCredential\":\"$it\"" }.orEmpty()}}"""
    private fun failure(code: String, retryable: Boolean) = HiltechApiException(code, "synthetic-correlation", 409, retryable,
        ErrorEnvelope(code, "Synthetic response", "synthetic-correlation", retryable = retryable), "Synthetic response")
}
