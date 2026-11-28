package com.hiltech.shared.core.finance

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FinanceAccountingDeskApiClientTest {
    private val org="aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val period="11111111-2222-4333-8444-555555555555"
    private val journal="22222222-3333-4444-8555-666666666666"
    private val account="33333333-4444-4555-8666-777777777777"
    private val report=FinanceDeskLedgerDto(org,period,0,false,
        listOf(FinanceDeskMovementDto(account,"AR","Receivables","CUSTOMER_RECEIVABLE","EGP","150","0")),
        listOf(FinanceDeskJournalDto(journal,"AR","2026-11-26T12:00:00Z","EGP",
            listOf(FinanceDeskJournalLineDto(1,"AR","Receivables","DEBIT","150"),
                FinanceDeskJournalLineDto(2,"SALES","Revenue","CREDIT","150")))),
        listOf("AP","AR","AP_REVERSAL"),false,listOf(FinanceDeskCurrencyTotalDto("EGP","150","150",true)))

    @Test fun ledgerReadsAuthenticatedSourceAndPreservesPeriodAndCurrency()=runBlocking {
        val http=HttpClient(MockEngine { call ->
            assertEquals(HttpMethod.Get,call.method)
            assertEquals("/v1/finance/organizations/$org/accounting-desk/ledger",call.url.encodedPath)
            assertEquals(period,call.url.parameters["periodId"])
            assertEquals("0",call.url.parameters["offset"])
            assertEquals("synthetic-install",call.headers["X-Device-Installation-Id"])
            respond(Json.encodeToString(FinanceDeskLedgerDto.serializer(),report),HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try {
            val client=FinanceAccountingDeskApiClient(HiltechApiClient(http,"https://finance.test",
                {"synthetic-token"},{"synthetic-correlation"}))
            assertEquals(report,client.ledger(org,period,0,"synthetic-install"))
        } finally {http.close()}
    }

    @Test fun carriedBalancesNeverClaimVerifiedOpeningOrFullIncomeStatement() {
        val row=FinanceDeskAccountBalanceDto(account,"AR","Receivables","CUSTOMER_RECEIVABLE","EGP",
            "100","0","50","0","150","0")
        val sources=report.includedSources+financeSettlementTypes+financeEmployeeSettlementTypes
        val data=FinanceDeskBalancesDto(org,period,"2026-11-01","2026-11-30",sources,1,"2026-10-01",false,
            "PRIOR_POSTED_MOVEMENTS_ONLY",false,false,listOf(row),
            listOf(FinanceDeskBalanceTotalDto("EGP","100","0","50","0","150","0",false,"0","0","0")),
            positions=listOf(FinanceDeskPositionDto("EGP","150","0","0","0","0",false)))
        assertTrue(financeDeskBalancesMatches(data,org,period))
        assertFalse(financeDeskBalancesMatches(data.copy(isCompanyWideTrialBalance=true),org,period))
        assertFalse(financeDeskBalancesMatches(data.copy(openingBasis="VERIFIED_OPENING"),org,period))
        assertFalse(financeDeskBalancesMatches(data.copy(isCompleteIncomeStatement=true),org,period))
        assertFalse(financeDeskBalancesMatches(data.copy(isCompleteBalanceSheet=true),org,period))
        assertFalse(financeDeskBalancesMatches(data.copy(isCompleteCashFlowStatement=true),org,period))
        assertFalse(financeDeskBalancesMatches(data.copy(positions=emptyList()),org,period))
        assertFalse(financeDeskBalancesMatches(data.copy(periodId=journal),org,period))
        val recorded=data.copy(openingBasis="RECORDED_OPENING_PLUS_PRIOR_MOVEMENTS",recordedOpeningCurrencies=listOf("EGP"),includedSources=sources+"OPENING_BALANCE")
        assertTrue(financeDeskBalancesMatches(recorded,org,period))
        assertFalse(financeDeskBalancesMatches(recorded.copy(recordedOpeningCurrencies=emptyList()),org,period))
    }

    @Test fun reportCannotBeMisrepresentedAsCompanyWideOrCrossScope() {
        assertTrue(financeDeskLedgerMatches(report,org,period,0))
        assertFalse(financeDeskLedgerMatches(report.copy(organizationId=journal),org,period,0))
        assertFalse(financeDeskLedgerMatches(report.copy(isCompanyWideTrialBalance=true),org,period,0))
        assertFalse(financeDeskLedgerMatches(report.copy(includedSources=listOf("AP")),org,period,0))
        assertFalse(financeDeskLedgerMatches(report.copy(offset=50),org,period,0))
        assertFalse(financeDeskLedgerMatches(report.copy(currencyTotals=emptyList()),org,period,0))
    }
}
