package com.hiltech.shared.core.administration

import com.hiltech.shared.core.network.HiltechApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class FinanceConfigurationApiClientTest {
    private val org="aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val operation="11111111-2222-4333-8444-555555555555"
    private val account="22222222-3333-4444-8555-666666666666"
    private val intent=FinanceConfigurationIntent(operation,"ACCOUNT","SYNTHETIC-CHART","AR","Receivables","CUSTOMER_RECEIVABLE","EGP")
    private val row=FinanceConfigurationRecordDto(account,"ACCOUNT","APPROVED","SYNTHETIC-CHART","AR","Receivables","CUSTOMER_RECEIVABLE","EGP")

    @Test fun sameSourceConfigurationIsSentAndConfirmed()=runBlocking {
        val receipt=FinanceConfigurationReceiptDto(operation,org,row,false)
        val http=HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Post,request.method)
            assertEquals("/v1/organizations/$org/administration/financial-configuration",request.url.encodedPath)
            assertEquals(operation,request.headers["Idempotency-Key"])
            respond(Json.encodeToString(FinanceConfigurationReceiptDto.serializer(),receipt),HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try {
            val client=FinanceConfigurationApiClient(HiltechApiClient(http,"https://finance.test",{"test-token"},{"test-correlation"}))
            assertEquals(receipt,client.create(org,intent,"synthetic-install"))
        } finally {http.close()}
        assertFalse(financeConfigurationReceiptMatches(org,intent,receipt.copy(record=row.copy(currency="USD"))))
        assertFalse(financeConfigurationReceiptMatches(org,intent,receipt.copy(organizationId=account)))
    }
    @Test fun onlyTypedApprovedSetupCanBePrepared() {
        assertTrue(financeConfigurationIntentValid(intent))
        assertFalse(financeConfigurationIntentValid(intent.copy(authorizationReference="")))
        val period=FinanceConfigurationIntent(operation,"PERIOD","SYNTHETIC-PERIOD",startDate="2026-11-01",endDate="2026-11-30")
        assertTrue(financeConfigurationIntentValid(period))
        assertFalse(financeConfigurationIntentValid(period.copy(endDate="2026-11-31")))
        val bank=FinanceConfigurationIntent(operation,"BANK","SYNTHETIC-BANK","TEST:****1234","Synthetic bank",currency="EGP")
        assertTrue(financeConfigurationIntentValid(bank))
        assertFalse(financeConfigurationIntentValid(bank.copy(code="1234567890123456")))
        assertFalse(financeConfigurationIntentValid(bank.copy(accountKind="EXPENSE")))
    }
}
