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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FinanceEvidenceNativeTransportTest {
    private val org = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val proposal = "11111111-2222-4333-8444-555555555555"
    private val wrongProposal = "22222222-3333-4444-8555-666666666666"
    private val install = "33333333-4444-4555-8666-777777777777"

    private fun readApis(http: HttpClient) = FinanceEvidenceReadApis(
        HiltechApiClient(
            http,
            "https://finance.hiltech.test",
            accessTokenProvider = { "real-finance-actor-token" },
            correlationIdProvider = { "finance-native-read-source" },
        )
    )

    @Test
    fun bankIntakeIsGetOnlyAndClaimsNoReconciliationOrBankTransfer() = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine { request ->
            calls++
            assertEquals("GET", request.method.value)
            assertEquals(
                "/v1/finance/organizations/" + org + "/bank-statement-intakes/" + proposal,
                request.url.encodedPath,
            )
            assertEquals("Bearer real-finance-actor-token", request.headers[HttpHeaders.Authorization])
            respond(
                """{"proposalId":"11111111-2222-4333-8444-555555555555",
                   "organizationId":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                   "bankAccountId":"22222222-3333-4444-8555-666666666666",
                   "statementHash":"source-sha","lineHash":"line-sha",
                   "lineCount":2,"state":"RECORDED",
                   "internalBankStatementRecorded":true,
                   "bankTransactionInitiated":false,"bankReconciled":false}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val result = financeReadSourceSnapshot(
            readApis(http), FinanceReadKind.BANK_INTAKE, org, proposal, install,
        )
        assertEquals(1, calls)
        assertEquals("RECORDED", result.state)
        assertTrue(result.facts.any { it.contains("لا (حسب المصدر)") })
        assertTrue(result.facts.any { it.contains("عدد الحركات") })
    }

    @Test
    fun aSourceRecordForAnotherProposalCannotBeRenderedAsRequested() = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine { request ->
            calls++
            assertEquals("GET", request.method.value)
            respond(
                """{"proposalId":"22222222-3333-4444-8555-666666666666",
                   "organizationId":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                   "sourceKind":"AR",
                   "sourceJournalId":"22222222-3333-4444-8555-666666666666",
                   "bankStatementLineId":"22222222-3333-4444-8555-666666666666",
                   "amount":"100.000000","currency":"EGP","state":"PROPOSED"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        assertFailsWith<IllegalStateException> {
            financeReadSourceSnapshot(
                readApis(http), FinanceReadKind.BANK_ALLOCATION,
                org, proposal, install,
            )
        }
        assertEquals(1, calls)
    }

    @Test
    fun unavailableOriginalFinanceSourceIsNotInventedAsZeroBalance() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("GET", request.method.value)
            respond("forbidden", HttpStatusCode.Forbidden)
        })
        assertFailsWith<Exception> {
            financeReadSourceSnapshot(
                readApis(http), FinanceReadKind.CASHBOX, org, wrongProposal, install,
            )
        }
    }
}
