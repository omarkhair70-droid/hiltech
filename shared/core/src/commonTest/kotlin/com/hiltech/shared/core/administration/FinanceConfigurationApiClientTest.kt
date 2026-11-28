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
    @Test fun openingScheduleUsesExactDecimalTotals() {
        val credit="33333333-4444-4555-8666-777777777777"
        val second="44444444-5555-4666-8777-888888888888"
        val debitLine=FinanceOpeningLineDto(account,"DEBIT","0.1","SYNTHETIC-ASSET")
        val debitTwo=FinanceOpeningLineDto(second,"DEBIT","0.2","SYNTHETIC-ASSET-TWO")
        val creditLine=FinanceOpeningLineDto(credit,"CREDIT","0.300000","SYNTHETIC-EQUITY")
        val opening=FinanceOpeningInputDto(operation,org,"EGP","SYNTHETIC-AUTH","SYNTHETIC-SCHEDULE",listOf(debitLine,debitTwo,creditLine))
        assertTrue(openingInputValid(opening))
        assertEquals("0.300000",openingTotal(opening.lines,"DEBIT"))
        assertFalse(openingInputValid(opening.copy(lines=listOf(debitLine,debitTwo,creditLine.copy(amount="0.299999")))))
        assertFalse(openingInputValid(opening.copy(lines=listOf(debitLine,debitLine,creditLine))))
        assertEquals("999999999999999999.999999",openingTotal(listOf(debitLine.copy(amount="999999999999999999.999999")),"DEBIT"))
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
    @Test fun openingObligationsRequireExactResidualAmountsAndHistoricalDocumentIdentity() {
        val party="11111111-2222-4333-8444-555555555555"
        val account="22222222-3333-4444-8555-666666666666"
        val row=FinanceOpeningObligationDto(party,"SYNTHETIC-OLD-INVOICE","2026-10-01","100.10","2026-11-01")
        val second=row.copy(documentReference="SYNTHETIC-OTHER-INVOICE",amount="0.20")
        val line=FinanceOpeningLineDto(account,"DEBIT","100.30","SYNTHETIC-SCHEDULE",obligations=listOf(row,second))
        assertTrue(openingLineValid(line))
        assertEquals("100.300000",openingObligationTotal(line.obligations))
        assertFalse(openingLineValid(line.copy(amount="100.31")))
        assertFalse(openingLineValid(line.copy(obligations=listOf(row,row))))
        assertFalse(openingLineValid(line.copy(obligations=listOf(row.copy(dueDate="2026-09-30")))))
        assertFalse(openingLineValid(line.copy(bankStatementId=party)))
    }
    @Test fun stockValuesUseExactQuantityAndReviewedAmountRatherThanAnInventedPrice() {
        val movement="33333333-4444-4555-8666-777777777777"
        val source=FinanceOpeningStockValueDto(movement,"10.123456","PC","a".repeat(64),"125.50","SYNTHETIC-VALUATION")
        val line=FinanceOpeningLineDto(account,"DEBIT","125.50","SYNTHETIC-STOCK-SCHEDULE",stockValues=listOf(source))
        assertTrue(openingLineValid(line))
        assertEquals("125.500000",openingStockTotal(line.stockValues))
        assertFalse(openingLineValid(line.copy(stockValues=listOf(source.copy(quantity="0")))))
        assertFalse(openingLineValid(line.copy(stockValues=listOf(source.copy(valuationReference="")))))
        assertFalse(openingLineValid(line.copy(stockValues=listOf(source.copy(amount="125.49")))))
        assertFalse(openingLineValid(line.copy(side="CREDIT")))
    }
}
