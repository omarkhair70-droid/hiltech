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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResponsibilityDelegationApiClientTest {
    private val org = "11111111-1111-1111-1111-111111111111"
    private val employee = "22222222-2222-2222-2222-222222222222"
    private val identity = "33333333-3333-3333-3333-333333333333"
    private val operation = "44444444-4444-4444-4444-444444444444"
    private val binding = "55555555-5555-5555-5555-555555555555"

    @Test
    fun delegationRetriesKeepExplicitOnwardBitKeysAndOriginalAccount() = runBlocking {
        val bodies = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            assertEquals("/v1/employees/$employee/responsibility-administration", request.url.encodedPath)
            assertEquals(operation, request.headers["Idempotency-Key"])
            bodies += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            if (bodies.size == 1) throw IllegalStateException("Synthetic response lost")
            respond(receipt(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val api = client(http)
        assertFailsWith<HiltechApiException> { api.apply(intent(), "synthetic-device") }
        val restored = Json.decodeFromString(ResponsibilityDelegationIntent.serializer(), Json.encodeToString(ResponsibilityDelegationIntent.serializer(), intent()))
        assertTrue(api.apply(restored, "synthetic-device").replayed)
        assertEquals(bodies[0], bodies[1])
        val body = Json.parseToJsonElement(bodies[0]).jsonObject
        assertEquals("true", body.getValue("canDelegateAdministration").jsonPrimitive.content)
        assertEquals(identity, body.getValue("expectedIdentityId").jsonPrimitive.content)
        http.close()
    }

    @Test
    fun delegationCannotIncludeUnknownOrFinalApprovalKeys() = runBlocking {
        var sent = false
        val http = HttpClient(MockEngine { sent = true; error("No request expected") })
        assertFailsWith<IllegalArgumentException> { client(http).apply(intent().copy(authorityKeys = listOf("OWNER_FINAL")), "synthetic-device") }
        assertFalse(sent)
        http.close()
    }

    @Test
    fun anotherRecipientReceiptCannotResolveThisDelegation() = runBlocking {
        val http = HttpClient(MockEngine { respond(receipt().replace(identity, employee), HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json")) })
        assertFailsWith<IllegalArgumentException> { client(http).apply(intent(), "synthetic-device") }
        http.close()
    }

    private fun intent() = ResponsibilityDelegationIntent(operation, employee, org, identity, 3, "Synthetic employee", listOf("SALES_VIEW"), true, "GRANT")
    private fun receipt() = """{"operationId":"$operation","employeeId":"$employee","organizationId":"$org","identityId":"$identity",
        "resultCode":"RESPONSIBILITY_DELEGATION_GRANTED","bindings":[{"bindingId":"$binding","authorityKey":"SALES_VIEW",
        "canDelegateAdministration":true,"current":true,"version":1,"canEnd":false}],"replayed":true}"""
    private fun client(http: HttpClient) = ResponsibilityDelegationApiClient(HiltechApiClient(http, "https://example.invalid",
        accessTokenProvider = { "synthetic-token" }, correlationIdProvider = { "synthetic-correlation" }))
}
