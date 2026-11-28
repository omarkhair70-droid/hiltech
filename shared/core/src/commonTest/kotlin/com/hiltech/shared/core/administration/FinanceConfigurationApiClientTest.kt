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
    @Test fun financingSetupSeparatesCapitalFromLoansAndNeverClaimsBankReceipt() {
        val financing=FinanceConfigurationIntent(operation,"FINANCING","SYNTHETIC-FINANCING-AUTH",
            code="AGREEMENT-01",name="Synthetic funder",currency="EGP",financingKind="OWNER_CAPITAL",
            financingAccountId=account,authorizedAmount="1000.250000")
        assertTrue(financeConfigurationIntentValid(financing))
        assertEquals("EQUITY",financingControlKind(financing.financingKind))
        assertEquals("OTHER_LIABILITY",financingControlKind("OWNER_LOAN"))
        assertEquals("OTHER_LIABILITY",financingControlKind("EXTERNAL_LOAN"))
        assertFalse(financeConfigurationIntentValid(financing.copy(financingKind="REVENUE")))
        assertFalse(financeConfigurationIntentValid(financing.copy(authorizedAmount="0")))
        assertFalse(financeConfigurationIntentValid(financing.copy(financingAccountId=null)))
        assertFalse(financeConfigurationIntentValid(intent.copy(financingKind="OWNER_CAPITAL")))
        val saved=FinanceConfigurationRecordDto(account,"FINANCING","APPROVED",financing.authorizationReference,
            financing.code,financing.name,currency="EGP",financingKind="OWNER_CAPITAL",financingAccountId=account,authorizedAmount="1000.25")
        val receipt=FinanceConfigurationReceiptDto(operation,org,saved,false)
        assertTrue(financeConfigurationReceiptMatches(org,financing,receipt))
        assertFalse(financeConfigurationReceiptMatches(org,financing,receipt.copy(record=saved.copy(financingKind="OWNER_LOAN"))))
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
    @Test fun openingEmployeeResidualMustNotBeAnAggregateOrAnotherFunding() {
        val employee="44444444-5555-4666-8777-888888888888"
        val row=FinanceOpeningEmployeeObligationDto(employee,"SYNTHETIC-OLD-IMPREST","2026-10-01","60.25")
        val line=FinanceOpeningLineDto(account,"DEBIT","60.25","SYNTHETIC-CUSTODY-SCHEDULE",employeeObligations=listOf(row))
        assertTrue(openingLineValid(line))
        assertEquals("60.250000",openingEmployeeTotal(line.employeeObligations))
        assertFalse(openingLineValid(line.copy(amount="60.24")))
        assertFalse(openingLineValid(line.copy(employeeObligations=listOf(row,row))))
        assertFalse(openingLineValid(line.copy(bankStatementId=employee)))
        assertFalse(openingLineValid(line.copy(side="CREDIT")))
    }
    @Test fun openingPayrollResidualRequiresCreditAndExactIndividualSchedule() {
        val employee="44444444-5555-4666-8777-888888888888"
        val row=FinanceOpeningEmployeeObligationDto(employee,"SYNTH-OLD-PAYROLL","2026-09-30","150.25")
        val line=FinanceOpeningLineDto(account,"CREDIT","150.25","SYNTHETIC-PAYROLL-SCHEDULE",payrollObligations=listOf(row))
        assertTrue(openingLineValid(line))
        assertFalse(openingLineValid(line.copy(side="DEBIT")))
        assertFalse(openingLineValid(line.copy(amount="150.24")))
        assertFalse(openingLineValid(line.copy(employeeObligations=listOf(row))))
        assertFalse(openingLineValid(line.copy(bankStatementId=employee)))
        assertFalse(openingLineValid(line.copy(amount="300.50",payrollObligations=listOf(row,row.copy(documentReference="SYNTH-OTHER-REF")))))
    }
}
