package com.hiltech.shared.core.people

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

class EmployeeAccountSetupTest {
    private val employee = "11111111-1111-1111-1111-111111111111"
    private val organization = "22222222-2222-2222-2222-222222222222"
    private val operation = "33333333-3333-3333-3333-333333333333"
    private val installation = "44444444-4444-4444-4444-444444444444"
    private val identity = "55555555-5555-5555-5555-555555555555"
    private val invitation = "66666666-6666-6666-6666-666666666666"

    @Test
    fun nativeAccountSetupUsesServerEligibilityForActiveStaff() {
        for (status in listOf("READY_TO_INVITE", "CONTINUE_INVITATION")) {
            assertTrue(accountSetupCanSubmit(availability(status)))
            assertFalse(accountSetupCanSubmit(availability(status).copy(deliveryModes = emptyList())))
        }
        for (status in listOf("LINKED", "ACCOUNT_RESTRICTED", "ACCOUNT_ATTENTION", "NOT_ELIGIBLE", "DELIVERY_UNAVAILABLE", "UNKNOWN")) {
            assertFalse(accountSetupCanSubmit(availability(status)))
        }
    }

    @Test
    fun serverPendingInvitationIsReadWithItsExactOperationAndDeliveryMethod() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/employees/$employee/identity-invitations/availability", request.url.encodedPath)
            assertEquals(installation, request.headers["X-Device-Installation-Id"])
            respond("""{"employeeId":"$employee","organizationId":"$organization","status":"CONTINUE_INVITATION",
                "deliveryModes":["EMAIL_ACTION_LINK"],"pending":{"operationId":"$operation",
                "requestedLogin":"synthetic@example.test","deliveryMode":"EMAIL_ACTION_LINK"}}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val result = client(http).identityInvitationAvailability(employee, installation)
        assertTrue(accountSetupCanSubmit(result))
        assertEquals(operation, result.pending?.operationId)
        assertEquals("synthetic@example.test", result.pending?.requestedLogin)
        http.close()
    }

    @Test
    fun lostCreationResponseRetriesFrozenOperationLoginVersionAndDelivery() = runBlocking {
        val bodies = mutableListOf<String>()
        val http = HttpClient(MockEngine { sent ->
            assertEquals("/v1/employees/$employee/identity-invitations", sent.url.encodedPath)
            assertEquals(operation, sent.headers["Idempotency-Key"])
            assertEquals(installation, sent.headers["X-Device-Installation-Id"])
            bodies += (sent.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            if (bodies.size == 1) throw IllegalStateException("Synthetic response lost")
            respond(receipt(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val api = client(http)
        assertFailsWith<HiltechApiException> { api.provisionIdentity(employee, request(), installation) }
        val restored = Json.decodeFromString(ProvisionEmployeeIdentityRequestDto.serializer(),
            Json.encodeToString(ProvisionEmployeeIdentityRequestDto.serializer(), request()))
        assertTrue(api.provisionIdentity(employee, restored, installation).replayed)
        assertEquals(bodies[0], bodies[1])
        http.close()
    }

    @Test
    fun mismatchedOperationReceiptCannotConfirmAnotherAccountSetup() = runBlocking {
        val http = HttpClient(MockEngine {
            respond(receipt(operationId = identity), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertFailsWith<IllegalArgumentException> { client(http).provisionIdentity(employee, request(), installation) }
        http.close()
    }

    @Test
    fun wrongEmployeeAvailabilityIsRejected() = runBlocking {
        val http = HttpClient(MockEngine {
            respond("""{"employeeId":"$identity","organizationId":"$organization","status":"READY_TO_INVITE","deliveryModes":["EMAIL_ACTION_LINK"]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertFailsWith<IllegalArgumentException> { client(http).identityInvitationAvailability(employee, installation) }
        http.close()
    }

    @Test
    fun unsupportedDeliveryIsRejectedBeforeSending() = runBlocking {
        var sends = 0
        val http = HttpClient(MockEngine { sends += 1; error("No request expected") })
        assertFailsWith<IllegalArgumentException> { client(http).provisionIdentity(employee, request().copy(deliveryMode = "PLAIN_PASSWORD"), installation) }
        assertEquals(0, sends)
        http.close()
    }

    private fun availability(status: String) = EmployeeIdentityInvitationAvailabilityDto(employee, organization, status, listOf("EMAIL_ACTION_LINK"))
    private fun request() = ProvisionEmployeeIdentityRequestDto(operation, 7, "synthetic@example.test", "EMAIL_ACTION_LINK")
    private fun client(http: HttpClient) = OnboardingApiClient(HiltechApiClient(http, "https://example.invalid",
        accessTokenProvider = { "synthetic-token" }, correlationIdProvider = { "synthetic-correlation" }))
    private fun receipt(operationId: String = operation) = """{"invitationId":"$invitation","operationId":"$operationId",
        "employeeId":"$employee","organizationId":"$organization","state":"READY","deliveryMode":"EMAIL_ACTION_LINK",
        "deliveryState":"SENT","userIdentityId":"$identity","version":2,"replayed":true,"correlationId":"synthetic-correlation"}"""
}
