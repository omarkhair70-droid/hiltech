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
import kotlin.test.assertTrue

class FinanceApReversalApiClientTest {
    private val org = "11111111-1111-1111-1111-111111111111"
    private val journal = "22222222-2222-2222-2222-222222222222"
    private val proposal = "33333333-3333-3333-3333-333333333333"
    private val period = "44444444-4444-4444-4444-444444444444"
    private val operation = "55555555-5555-5555-5555-555555555555"
    private val installation = "66666666-6666-6666-6666-666666666666"

    private fun client(http: HttpClient) = FinanceApReversalApiClient(
        HiltechApiClient(
            http, "https://finance.hiltech.test",
            accessTokenProvider = { "authorized-finance-token" },
            correlationIdProvider = { "reversal-correlation" },
        ),
    )

    @Test fun proposalIsNotAnActualCashOrLedgerReversal() = runBlocking {
        val http = HttpClient(MockEngine { req ->
            assertEquals("POST", req.method.value)
            assertEquals(
                "/v1/finance/organizations/" + org + "/ap-journals/" + journal +
                    "/reversal-proposals",
                req.url.encodedPath,
            )
            assertEquals(
                "Bearer authorized-finance-token",
                req.headers[HttpHeaders.Authorization],
            )
            respond(
                """{"proposalId":"33333333-3333-3333-3333-333333333333",
                   "originalJournalId":"22222222-2222-2222-2222-222222222222",
                   "targetPeriodId":"44444444-4444-4444-4444-444444444444",
                   "sourceVersion":321,"state":"SEALED_AWAITING_APPROVAL",
                   "currency":"EGP","grossAmount":"250.000000",
                   "financialLedgerReversed":false,"bankPaymentReversed":false,
                   "moneyTransferred":false}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val receipt = client(http).propose(
            org, journal, period, "INDEPENDENT_REVIEW", operation, installation,
        )
        assertEquals("SEALED_AWAITING_APPROVAL", receipt.state)
        assertFalse(receipt.financialLedgerReversed)
        assertFalse(receipt.bankPaymentReversed)
        assertFalse(receipt.moneyTransferred)
    }

    @Test fun postedLedgerReversalNeverClaimsCashReversed() = runBlocking {
        val http = HttpClient(MockEngine { req ->
            assertEquals(
                "/v1/finance/organizations/" + org +
                    "/ap-reversal-proposals/" + proposal + "/post",
                req.url.encodedPath,
            )
            respond(
                """{"proposalId":"33333333-3333-3333-3333-333333333333",
                   "originalJournalId":"22222222-2222-2222-2222-222222222222",
                   "targetPeriodId":"44444444-4444-4444-4444-444444444444",
                   "sourceVersion":321,"state":"POSTED_REVERSAL",
                   "currency":"EGP","grossAmount":"250.000000",
                   "financialLedgerReversed":true,"bankPaymentReversed":false,
                   "moneyTransferred":false}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val receipt = client(http).post(org, proposal, operation, installation)
        assertTrue(receipt.financialLedgerReversed)
        assertFalse(receipt.bankPaymentReversed)
        assertFalse(receipt.moneyTransferred)
    }
}
