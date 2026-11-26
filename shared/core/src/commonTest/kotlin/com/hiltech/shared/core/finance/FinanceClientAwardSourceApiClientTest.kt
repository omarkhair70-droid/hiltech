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
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FinanceClientAwardSourceApiClientTest {
    private val organization = "11111111-1111-1111-1111-111111111111"
    private val opportunity = "22222222-2222-2222-2222-222222222222"
    private val project = "33333333-3333-3333-3333-333333333333"
    private val client = "44444444-4444-4444-4444-444444444444"
    private val quote = "55555555-5555-5555-5555-555555555555"
    private val contract = "66666666-6666-6666-6666-666666666666"
    private val clientPo = "77777777-7777-7777-7777-777777777777"
    private val installation = "88888888-8888-8888-8888-888888888888"

    @Test fun noIntegratedCommercialSourceIsNotZeroReceivables() = runBlocking {
        var count = 0
        val http = HttpClient(MockEngine { request ->
            count++
            assertEquals("GET", request.method.value)
            assertEquals(
                "/v1/finance/organizations/$organization/client-award-sources",
                request.url.encodedPath,
            )
            assertEquals("Bearer finance-reviewer", request.headers[HttpHeaders.Authorization])
            respond(
                """
                {"organizationId":"$organization",
                 "observedAt":"2026-11-23T13:00:00Z",
                 "sourceAvailable":false,
                 "sourceStatus":"COMMERCIAL_FINANCE_AWARD_SOURCE_NOT_INTEGRATED",
                 "rows":[],"queryLimit":100,"hasMoreSources":false,
                 "reportedCompanyReceivableTotal":null,
                 "reportedCompanyRevenueTotal":null,
                 "reportedCustomerCollectionsTotal":null}
                """.trimIndent(), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val api = HiltechApiClient(
            http, "https://finance.hiltech.test",
            accessTokenProvider = { "finance-reviewer" },
            correlationIdProvider = { "finance-ar-coverage" },
        )
        val outcome = FinanceClientAwardSourceApiClient(api).list(organization, installation)
        assertEquals(1, count)
        assertFalse(outcome.sourceAvailable)
        assertTrue(outcome.rows.isEmpty())
        assertNull(outcome.reportedCompanyReceivableTotal)
        assertNull(outcome.reportedCompanyRevenueTotal)
        assertNull(outcome.reportedCustomerCollectionsTotal)
    }

    @Test fun releasedAwardIsSourceNotIssuedInvoiceOrCollectedRevenue() = runBlocking {
        var hits = 0
        val http = HttpClient(MockEngine { request ->
            hits++
            assertEquals("GET", request.method.value)
            assertEquals(
                "/v1/finance/organizations/$organization/client-award-sources/$opportunity",
                request.url.encodedPath,
            )
            respond(
                """
                {"organizationId":"$organization",
                 "opportunityId":"$opportunity","projectId":"$project",
                 "clientOrganizationId":"$client","quoteVersionId":"$quote",
                 "quoteRevision":2,"quoteRecordVersion":3,
                 "contractId":"$contract","clientPoId":"$clientPo",
                 "sellAmount":"12000.000000","currency":"EGP",
                 "paymentTerms":"Billed only after signed milestones",
                 "sourceFingerprintSha256":"${"a".repeat(64)}",
                 "financeReviewRequired":true,
                 "customerInvoiceIssued":false,
                 "receivableRecognized":false,
                 "billingMilestonesVerified":false,
                 "taxTreatmentVerified":false,
                 "amountDueOrOverdueKnown":false,
                 "cashCollected":false,"cashReconciled":false}
                """.trimIndent(), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val api = HiltechApiClient(
            http, "https://finance.hiltech.test",
            accessTokenProvider = { "finance-reviewer" },
            correlationIdProvider = { "finance-ar-coverage" },
        )
        val outcome = FinanceClientAwardSourceApiClient(api)
            .detail(organization, opportunity, installation)
        assertEquals(1, hits)
        assertEquals("12000.000000", outcome.sellAmount)
        assertEquals(2, outcome.quoteRevision)
        assertTrue(outcome.financeReviewRequired)
        assertFalse(outcome.customerInvoiceIssued)
        assertFalse(outcome.receivableRecognized)
        assertFalse(outcome.billingMilestonesVerified)
        assertFalse(outcome.taxTreatmentVerified)
        assertFalse(outcome.amountDueOrOverdueKnown)
        assertFalse(outcome.cashCollected)
        assertFalse(outcome.cashReconciled)
    }

    @Test fun malformedClientSourceIdCannotBeInjectedIntoFinanceUrl() = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine { calls++; error("No request for bad UUID") })
        val api = HiltechApiClient(
            http, "https://finance.hiltech.test",
            accessTokenProvider = { "finance-reviewer" },
            correlationIdProvider = { "finance-ar-coverage" },
        )
        assertFailsWith<IllegalArgumentException> {
            FinanceClientAwardSourceApiClient(api).detail(
                organization, "../../payables", installation,
            )
        }
        assertEquals(0, calls)
    }
}
