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
import kotlin.test.assertTrue

class FinanceApJournalPostingApiClientTest {
    private val org="11111111-1111-1111-1111-111111111111"
    private val candidate="22222222-2222-2222-2222-222222222222"
    private val proposal="33333333-3333-3333-3333-333333333333"
    private val accountDebit="44444444-4444-4444-4444-444444444444"
    private val accountCredit="55555555-5555-5555-5555-555555555555"
    private val period="66666666-6666-6666-6666-666666666666"
    private val operation="77777777-7777-7777-7777-777777777777"
    private val journal="88888888-8888-8888-8888-888888888888"
    private val installation="99999999-9999-9999-9999-999999999999"

    private fun client(http:HttpClient)=FinanceApJournalPostingApiClient(
        HiltechApiClient(
            http,"https://finance.hiltech.test",
            accessTokenProvider={"real-finance-token"},
            correlationIdProvider={"real-finance-correlation"},
        ),
    )

    @Test fun accountantProposesExactDoubleEntryNoMoneyMovement()=runBlocking {
        var calls=0
        val http=HttpClient(MockEngine { call->
            calls++
            assertEquals("POST",call.method.value)
            assertEquals(
                "/v1/finance/organizations/$org/payable-candidates/$candidate/journal-proposals",
                call.url.encodedPath,
            )
            assertEquals(
                "Bearer real-finance-token",call.headers[HttpHeaders.Authorization],
            )
            respond(
                """
                {"proposalId":"$proposal","payableCandidateId":"$candidate",
                "sourceVersion":12345,"ledgerFingerprintSha256":"${"a".repeat(64)}",
                "invoiceAmount":"116.000000","currency":"EGP",
                "state":"SEALED_AWAITING_POSTING_REVIEW",
                "recognizedAccountsPayable":false,"paymentApproved":false,
                "moneyTransferred":false,"bankReconciled":false}
                """.trimIndent(),HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType,"application/json"),
            )
        })
        val r=client(http).propose(
            org,candidate,
            FinanceApJournalProposalDto(
                operationId=operation,fiscalPeriodId=period,
                accountingTreatmentReference="COMPANY-APPROVED-GL-001",
                accountingTaxDisposition="TAX_REVIEWED_NO_RECOVERABLE_TAX",
                lines=listOf(
                    FinanceApJournalAllocationDto(accountDebit,"DEBIT","116.000000"),
                    FinanceApJournalAllocationDto(accountCredit,"CREDIT","116.000000"),
                ),
            ),installation,
        )
        assertEquals(1,calls)
        assertFalse(r.recognizedAccountsPayable)
        assertFalse(r.moneyTransferred)
        assertEquals("SEALED_AWAITING_POSTING_REVIEW",r.state)
    }

    @Test fun independentlyApprovedDoubleEntryCanPostButNeverMeansBankTransfer()=runBlocking {
        var calls=0
        val http=HttpClient(MockEngine { call->
            calls++
            assertEquals("POST",call.method.value)
            assertEquals(
                "/v1/finance/organizations/$org/ap-journal-proposals/$proposal/post",
                call.url.encodedPath,
            )
            respond(
                """
                {"proposalId":"$proposal","payableCandidateId":"$candidate",
                "sourceVersion":12345,"ledgerFingerprintSha256":"${"a".repeat(64)}",
                "invoiceAmount":"116.000000","currency":"EGP",
                "state":"POSTED_ACCOUNTS_PAYABLE",
                "postedJournalId":"$journal",
                "recognizedAccountsPayable":true,"paymentApproved":false,
                "moneyTransferred":false,"bankReconciled":false}
                """.trimIndent(),HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType,"application/json"),
            )
        })
        val r=client(http).commitReviewedJournal(
            org,proposal,operation,installation,
        )
        assertEquals(1,calls)
        assertEquals(journal,r.postedJournalId)
        assertTrue(r.recognizedAccountsPayable)
        assertFalse(r.paymentApproved)
        assertFalse(r.moneyTransferred)
        assertFalse(r.bankReconciled)
    }

    @Test fun malformedAllocationIsRejectedBeforeReachingFinanceApi()=runBlocking {
        var calls=0
        val http=HttpClient(MockEngine {
            calls++
            error("No request for invalid allocation.")
        })
        assertFailsWith<IllegalArgumentException> {
            client(http).propose(
                org,candidate,
                FinanceApJournalProposalDto(
                    operationId=operation,fiscalPeriodId=period,
                    accountingTreatmentReference="COMPANY-APPROVED-GL-001",
                    accountingTaxDisposition="TAX_REVIEWED_NO_RECOVERABLE_TAX",
                    lines=listOf(
                        FinanceApJournalAllocationDto(accountDebit,"DEBIT","100.0000001"),
                        FinanceApJournalAllocationDto(accountCredit,"CREDIT","100"),
                    ),
                ),installation,
            )
        }
        assertEquals(0,calls)
    }
}
