package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinanceApLedgerEvidenceReportApiClientTest {
    private val org="11111111-1111-1111-1111-111111111111"
    private val period="22222222-2222-2222-2222-222222222222"
    private val supplier="33333333-3333-3333-3333-333333333333"
    private val install="44444444-4444-4444-4444-444444444444"
    private fun client(http:HttpClient)=FinanceApLedgerEvidenceReportApiClient(
        HiltechApiClient(
            http,"https://finance.hiltech.test",
            accessTokenProvider={"private-finance-token"},
            correlationIdProvider={"finance-evidence-report"},
        )
    )
    @Test fun privatePostedApIsNotTheRemainingSupplierBalance()=runBlocking {
        var count=0
        val http=HttpClient(MockEngine { req->
            count++
            assertEquals("GET",req.method.value)
            assertEquals(
                "/v1/finance/organizations/$org/supplier-ap-evidence-report",
                req.url.encodedPath,
            )
            assertEquals(
                "Bearer private-finance-token",req.headers[HttpHeaders.Authorization],
            )
            respond(
                """
                {"organizationId":"$org","fiscalPeriodId":null,
                 "observedAt":"2026-11-23T14:00:00Z",
                 "sourceAvailable":true,
                 "sourceStatus":"POSTED_SUPPLIER_AP_HISTORICAL_GROSS_NOT_OUTSTANDING",
                 "groups":[{"supplierOrganizationId":"$supplier",
                 "currency":"EGP","postedJournalCount":2,
                 "historicallyRecognizedGross":"5500.000000",
                 "mostRecentPostingAt":"2026-11-23T12:00:00Z",
                 "sourceOnly":true,"settledAmount":null,
                 "outstandingBalance":null,"overdueBalance":null,
                 "bankConfirmedAmount":null}],
                 "hasMoreGroups":false,
                 "isCompanyWideTrialBalance":false,
                 "companyOutstandingPayables":null,
                 "companySupplierPayments":null,
                 "companyBankReconciledTotal":null}
                """.trimIndent(),HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType,"application/json"),
            )
        })
        val result=client(http).get(org,install)
        assertEquals(1,count)
        assertTrue(result.sourceAvailable)
        assertEquals("5500.000000",result.groups.single().historicallyRecognizedGross)
        assertNull(result.groups.single().outstandingBalance)
        assertNull(result.groups.single().bankConfirmedAmount)
        assertNull(result.companyOutstandingPayables)
        assertFalse(result.isCompanyWideTrialBalance)
    }
    @Test fun missingLedgerSourceCannotMeanZeroCorporateLiability()=runBlocking {
        val http=HttpClient(MockEngine { req->
            assertEquals(
                "/v1/finance/organizations/$org/supplier-ap-evidence-report",
                req.url.encodedPath,
            )
            respond(
                """
                {"organizationId":"$org","observedAt":"2026-11-23T14:00:00Z",
                 "sourceAvailable":false,
                 "sourceStatus":"POSTED_AP_LEDGER_SOURCE_NOT_INTEGRATED",
                 "groups":[],"companyOutstandingPayables":null,
                 "companySupplierPayments":null,
                 "companyBankReconciledTotal":null}
                """.trimIndent(),HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType,"application/json"),
            )
        })
        val result=client(http).get(org,install)
        assertFalse(result.sourceAvailable)
        assertTrue(result.groups.isEmpty())
        assertNull(result.companyOutstandingPayables)
    }
    @Test fun invalidPeriodCannotEscapeTheAccountingRoute()=runBlocking {
        var calls=0
        val http=HttpClient(MockEngine { calls++; error("Never called") })
        assertFailsWith<IllegalArgumentException> {
            client(http).get(org,install,"../../all-users")
        }
        assertEquals(0,calls)
    }
}
