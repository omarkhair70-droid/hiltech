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

class FinanceArJournalPostingApiClientTest {
    private val org="11111111-1111-1111-1111-111111111111"
    private val invoice="22222222-2222-2222-2222-222222222222"
    private val proposal="33333333-3333-3333-3333-333333333333"
    private val debit="44444444-4444-4444-4444-444444444444"
    private val credit="55555555-5555-5555-5555-555555555555"
    private val period="66666666-6666-6666-6666-666666666666"
    private val operation="77777777-7777-7777-7777-777777777777"
    private val installation="88888888-8888-8888-8888-888888888888"
    private fun service(http:HttpClient)=FinanceArJournalPostingApiClient(
        HiltechApiClient(
            http,"https://finance.hiltech.test",
            accessTokenProvider={"real-finance-token"},
            correlationIdProvider={"finance-correlation"},
        ),
    )
    @Test fun guardedArProposalDoesNotClaimCollections()=runBlocking {
        val http=HttpClient(MockEngine { req->
            assertEquals("POST",req.method.value)
            assertEquals(
                "/v1/finance/organizations/$org/issued-invoices/$invoice/ar-journal-proposals",
                req.url.encodedPath,
            )
            assertEquals("Bearer real-finance-token",req.headers[HttpHeaders.Authorization])
            respond(
                """{"proposalId":"$proposal","issuedInvoiceId":"$invoice",
                  "organizationId":"$org","sourceVersion":89712,
                  "ledgerFingerprintSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "amount":"100.000000","currency":"EGP",
                  "state":"SEALED_AWAITING_APPROVAL",
                  "receivableRecognized":false,"cashCollected":false,
                  "bankReconciled":false}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType,"application/json"),
            )
        })
        val result=service(http).propose(
            org,invoice,FinanceArJournalProposalDto(
                operationId=operation,fiscalPeriodId=period,
                accountingTreatmentReference="REAL-COMPANY-CHART",
                taxDispositionReference="TAX_REVIEWED_NO_TAX_EVIDENCE",
                lines=listOf(
                    FinanceArJournalAllocationDto(debit,"DEBIT","100.000000"),
                    FinanceArJournalAllocationDto(credit,"CREDIT","100.000000"),
                ),
            ),installation,
        )
        assertEquals("SEALED_AWAITING_APPROVAL",result.state)
        assertFalse(result.receivableRecognized)
        assertFalse(result.cashCollected)
        assertFalse(result.bankReconciled)
    }
    @Test fun approvedJournalReceiptIsStillNotCash()=runBlocking {
        val http=HttpClient(MockEngine { req->
            assertEquals(
                "/v1/finance/organizations/$org/ar-journal-proposals/$proposal/post",
                req.url.encodedPath,
            )
            respond(
                """{"proposalId":"$proposal","issuedInvoiceId":"$invoice",
                  "organizationId":"$org","sourceVersion":89712,
                  "ledgerFingerprintSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "amount":"100.000000","currency":"EGP",
                  "state":"POSTED_ACCOUNTS_RECEIVABLE",
                  "postedJournalId":"99999999-9999-9999-9999-999999999999",
                  "receivableRecognized":true,"cashCollected":false,
                  "bankReconciled":false}""",
                HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"),
            )
        })
        val receipt=service(http).post(org,proposal,operation,installation)
        assertTrue(receipt.receivableRecognized)
        assertFalse(receipt.cashCollected)
        assertFalse(receipt.bankReconciled)
    }
}
