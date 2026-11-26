package com.hiltech.shared.core.payroll

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

class PayrollSourcesApiClientTest {
    private val period = "11111111-1111-4111-8111-111111111111"
    private val employee = "22222222-2222-4222-8222-222222222222"
    private val operation = "33333333-3333-4333-8333-333333333333"
    private val recordId = "44444444-4444-4444-8444-444444444444"
    private val installation = "55555555-5555-4555-8555-555555555555"
    private val request = PayrollSourceRequestDto(operation, "APPROVED_TIME", employee,
        sourceReference = "Synthetic attendance register", currency = "EGP", noInputs = true)

    @Test
    fun explicitNoInputAttestationRetainsOperationAndPayloadAfterLostResponse() = runBlocking {
        val bodies = mutableListOf<String>()
        val http = HttpClient(MockEngine { call ->
            assertEquals("/v1/payroll/periods/$period/sources", call.url.encodedPath)
            assertEquals(operation, call.headers["Idempotency-Key"])
            assertEquals(installation, call.headers["X-Device-Installation-Id"])
            bodies += (call.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            if (bodies.size == 1) error("Synthetic lost response")
            respond(Json.encodeToString(PayrollSourceCommandDto.serializer(), PayrollSourceCommandDto(record(), true)),
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val api = client(http)
        assertFailsWith<HiltechApiException> { api.saveSource(period, request, installation) }
        val result = api.saveSource(period, request, installation)
        assertTrue(result.replayed)
        assertTrue(result.record.noInputs)
        assertEquals(bodies[0], bodies[1])
        http.close()
    }

    @Test
    fun sourceOrEmployeeSubstitutionInSuccessResponseCannotBeAccepted() = runBlocking {
        val http = HttpClient(MockEngine {
            respond(Json.encodeToString(PayrollSourceCommandDto.serializer(), PayrollSourceCommandDto(record().copy(employeeId = recordId), false)),
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertFailsWith<IllegalArgumentException> { client(http).saveSource(period, request, installation) }
        http.close()
    }

    @Test
    fun amountFormattingCanVaryButMoneyOrSourceRevisionCannot() {
        val earned = request.copy(domain = "COMPENSATION", noInputs = false,
            components = listOf(PayrollSourceComponentDto("BASE", "EARNING", "100.00", description = "Synthetic base pay")))
        val receipt = record().copy(domain = "COMPENSATION", noInputs = false,
            components = earned.components.map { it.copy(amount = "100") })
        assertTrue(payrollSourceReceiptMatches(period, earned, receipt))
        assertFalse(payrollSourceReceiptMatches(period, earned, receipt.copy(components = receipt.components.map { it.copy(amount = "101") })))
        assertFalse(payrollSourceReceiptMatches(period, earned, receipt.copy(replacesRecordId = recordId)))
    }

    private fun record() = PayrollSourceRecordDto(recordId, period, request.domain, employee, 1, null,
        request.sourceReference, "EGP", true, emptyList(), null, null, null, null, null,
        "66666666-6666-4666-8666-666666666666", "2026-11-25T12:00:00Z", "DRAFT", null)
    private fun client(http: HttpClient) = Payroll9aApiClient(HiltechApiClient(http, "https://example.invalid",
        accessTokenProvider = { "synthetic-token" }, correlationIdProvider = { "synthetic-correlation" }))
}
