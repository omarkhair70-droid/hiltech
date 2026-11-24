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

class FinanceCompletionOperatorTransportTest {
    private val org = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val op = "55555555-6666-4777-8888-999999999999"
    private val recordId = "44444444-5555-4666-8777-888888888888"
    private data class Fixture(
        val kind: FinanceCompletionKind, val queue: String, val status: String,
        val command: String, val approved: String, val terminal: String,
    )
    private val fixtures = listOf(
        Fixture(FinanceCompletionKind.AR_INVOICE, "/v1/finance/organizations/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/invoice-issuance-proposals/operator-queue", "/v1/finance/organizations/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/invoice-issuance-proposals/11111111-2222-4333-8444-555555555555", "/v1/finance/organizations/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/invoice-issuance-proposals/11111111-2222-4333-8444-555555555555/record",
            """{"proposalId":"11111111-2222-4333-8444-555555555555","candidateId":"22222222-3333-4444-8555-666666666666","organizationId":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","invoiceNumber":"INV-100","approvalRequestId":"33333333-4444-4555-8666-777777777777","state":"APPROVED"}""",
            """{"proposalId":"11111111-2222-4333-8444-555555555555","candidateId":"22222222-3333-4444-8555-666666666666","organizationId":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","invoiceNumber":"INV-100","approvalRequestId":"33333333-4444-4555-8666-777777777777","state":"RECORDED","issuedInvoiceId":"44444444-5555-4666-8777-888888888888"}"""),
        Fixture(FinanceCompletionKind.AP_JOURNAL, "/v1/finance/organizations/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/ap-journal-proposals/operator-queue", "/v1/finance/organizations/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/ap-journal-proposals/11111111-2222-4333-8444-555555555555", "/v1/finance/organizations/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/ap-journal-proposals/11111111-2222-4333-8444-555555555555/post",
            """{"proposalId":"11111111-2222-4333-8444-555555555555","payableCandidateId":"22222222-3333-4444-8555-666666666666","sourceVersion":2,"ledgerFingerprintSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","invoiceAmount":"100.00","currency":"EGP","postingReviewRequestId":"33333333-4444-4555-8666-777777777777","state":"APPROVED"}""",
            """{"proposalId":"11111111-2222-4333-8444-555555555555","payableCandidateId":"22222222-3333-4444-8555-666666666666","sourceVersion":2,"ledgerFingerprintSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","invoiceAmount":"100.00","currency":"EGP","postingReviewRequestId":"33333333-4444-4555-8666-777777777777","state":"POSTED_ACCOUNTS_PAYABLE","postedJournalId":"44444444-5555-4666-8777-888888888888"}"""),
        Fixture(FinanceCompletionKind.AR_JOURNAL, "/v1/finance/organizations/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/ar-journal-proposals/operator-queue", "/v1/finance/organizations/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/ar-journal-proposals/11111111-2222-4333-8444-555555555555", "/v1/finance/organizations/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/ar-journal-proposals/11111111-2222-4333-8444-555555555555/post",
            """{"proposalId":"11111111-2222-4333-8444-555555555555","issuedInvoiceId":"22222222-3333-4444-8555-666666666666","organizationId":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","sourceVersion":2,"ledgerFingerprintSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","amount":"100.00","currency":"EGP","approvalRequestId":"33333333-4444-4555-8666-777777777777","state":"APPROVED"}""",
            """{"proposalId":"11111111-2222-4333-8444-555555555555","issuedInvoiceId":"22222222-3333-4444-8555-666666666666","organizationId":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","sourceVersion":2,"ledgerFingerprintSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","amount":"100.00","currency":"EGP","approvalRequestId":"33333333-4444-4555-8666-777777777777","state":"POSTED_ACCOUNTS_RECEIVABLE","postedJournalId":"44444444-5555-4666-8777-888888888888"}"""),
        Fixture(FinanceCompletionKind.FUNDING, "/v1/finance/organizations/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/employee-funding-proposals/operator-queue", "/v1/finance/organizations/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/employee-funding-proposals/11111111-2222-4333-8444-555555555555", "/v1/finance/organizations/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/employee-funding-proposals/11111111-2222-4333-8444-555555555555/record",
            """{"proposalId":"11111111-2222-4333-8444-555555555555","organizationId":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","kind":"FINANCIAL_IMPREST","draftId":"22222222-3333-4444-8555-666666666666","employeeId":"66666666-7777-4888-8999-aaaaaaaaaaaa","amount":"100.00","currency":"EGP","bankStatementLineId":"22222222-3333-4444-8555-666666666666","approvalRequestId":"33333333-4444-4555-8666-777777777777","state":"APPROVED"}""",
            """{"proposalId":"11111111-2222-4333-8444-555555555555","organizationId":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","kind":"FINANCIAL_IMPREST","draftId":"22222222-3333-4444-8555-666666666666","employeeId":"66666666-7777-4888-8999-aaaaaaaaaaaa","amount":"100.00","currency":"EGP","bankStatementLineId":"22222222-3333-4444-8555-666666666666","approvalRequestId":"33333333-4444-4555-8666-777777777777","state":"RECORDED","fundingRecordId":"44444444-5555-4666-8777-888888888888"}"""),
        Fixture(FinanceCompletionKind.SETTLEMENT, "/v1/finance/organizations/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/employee-settlement-proposals/operator-queue", "/v1/finance/organizations/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/employee-settlement-proposals/11111111-2222-4333-8444-555555555555", "/v1/finance/organizations/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/employee-settlement-proposals/11111111-2222-4333-8444-555555555555/record",
            """{"proposalId":"11111111-2222-4333-8444-555555555555","organizationId":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","fundingRecordId":"22222222-3333-4444-8555-666666666666","employeeId":"66666666-7777-4888-8999-aaaaaaaaaaaa","action":"IMPREST_RECEIPTS_ACCEPTED","amount":"100.00","currency":"EGP","approvalRequestId":"33333333-4444-4555-8666-777777777777","state":"APPROVED"}""",
            """{"proposalId":"11111111-2222-4333-8444-555555555555","organizationId":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","fundingRecordId":"22222222-3333-4444-8555-666666666666","employeeId":"66666666-7777-4888-8999-aaaaaaaaaaaa","action":"IMPREST_RECEIPTS_ACCEPTED","amount":"100.00","currency":"EGP","approvalRequestId":"33333333-4444-4555-8666-777777777777","state":"RECORDED","settlementRecordId":"44444444-5555-4666-8777-888888888888"}"""),
        Fixture(FinanceCompletionKind.REIMBURSEMENT, "/v1/finance/organizations/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/expense-reimbursements/operator-queue", "/v1/finance/organizations/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/expense-reimbursements/11111111-2222-4333-8444-555555555555", "/v1/finance/organizations/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/expense-reimbursements/11111111-2222-4333-8444-555555555555/record",
            """{"proposalId":"11111111-2222-4333-8444-555555555555","organizationId":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","expenseDraftId":"22222222-3333-4444-8555-666666666666","employeeId":"66666666-7777-4888-8999-aaaaaaaaaaaa","bankStatementLineId":"22222222-3333-4444-8555-666666666666","amount":"100.00","currency":"EGP","approvalRequestId":"33333333-4444-4555-8666-777777777777","state":"APPROVED"}""",
            """{"proposalId":"11111111-2222-4333-8444-555555555555","organizationId":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","expenseDraftId":"22222222-3333-4444-8555-666666666666","employeeId":"66666666-7777-4888-8999-aaaaaaaaaaaa","bankStatementLineId":"22222222-3333-4444-8555-666666666666","amount":"100.00","currency":"EGP","approvalRequestId":"33333333-4444-4555-8666-777777777777","state":"RECORDED","reimbursementRecordId":"44444444-5555-4666-8777-888888888888"}"""),
        Fixture(FinanceCompletionKind.PERIOD_CLOSE, "/v1/finance/organizations/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/periods/close/operator-queue", "/v1/finance/organizations/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/periods/11111111-2222-4333-8444-555555555555/close", "/v1/finance/organizations/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/periods/11111111-2222-4333-8444-555555555555/close",
            """{"periodId":"11111111-2222-4333-8444-555555555555","organizationId":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","ledgerSnapshotSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","journalCount":20,"sourceVersion":2,"approvalRequestId":"33333333-4444-4555-8666-777777777777","state":"APPROVED"}""",
            """{"periodId":"11111111-2222-4333-8444-555555555555","organizationId":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","ledgerSnapshotSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","journalCount":20,"sourceVersion":2,"approvalRequestId":"33333333-4444-4555-8666-777777777777","state":"CLOSED","periodClosureId":"44444444-5555-4666-8777-888888888888"}"""),
    )

    private fun apis(client: HttpClient) = FinanceEvidenceReadApis(
        HiltechApiClient(client, "https://finance.test",
            accessTokenProvider={ "finance-operator" },
            correlationIdProvider={ "finance-completion-test" }),
    )

    @Test
    fun allSevenRoutesReadExactApprovedSourceThenRecordOriginalCommand() = runBlocking {
        for (fixture in fixtures) {
            val requests = mutableListOf<String>()
            val engine = MockEngine { request ->
                requests += request.method.value + " " + request.url.encodedPath
                val json = when (requests.size) {
                    1 -> {
                        assertEquals(fixture.queue,request.url.encodedPath)
                        assertEquals("GET",request.method.value)
                        "["+fixture.approved+"]"
                    }
                    2 -> {
                        assertEquals(fixture.status,request.url.encodedPath)
                        assertEquals("GET",request.method.value)
                        fixture.approved
                    }
                    3 -> {
                        assertEquals(fixture.command,request.url.encodedPath)
                        assertEquals("POST",request.method.value)
                        assertEquals(op,request.headers["Idempotency-Key"])
                        fixture.terminal
                    }
                    else -> error("Unexpected additional Finance command")
                }
                respond(json,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
            }
            val client = HttpClient(engine)
            try {
                val source = financeCompletionQueue(apis(client),org,"install",fixture.kind).single()
                val result = financeCompleteApprovedSource(apis(client),org,"install",source,op)
                assertEquals(fixture.kind.terminal,result.state)
                assertEquals(recordId,result.resultId)
                assertEquals(3,requests.size)
                assertFalse(financeCompletionReady(result,org))
            } finally {client.close()}
        }
    }

    @Test
    fun changedApprovalOrPendingStateStopsBeforePost() = runBlocking {
        for (fixture in fixtures) {
            var requests = 0
            val client = HttpClient(MockEngine { request ->
                requests++
                assertEquals("GET",request.method.value)
                val json = if (requests==1) "["+fixture.approved+"]"
                    else fixture.approved.replace("\"APPROVED\"","\"PENDING\"")
                respond(json,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
            })
            try {
                val source = financeCompletionQueue(apis(client),org,"install",fixture.kind).single()
                assertFailsWith<IllegalStateException> {
                    financeCompleteApprovedSource(apis(client),org,"install",source,op)
                }
                assertEquals(2,requests)
                assertTrue(financeCompletionReady(source,org))
                assertFalse(financeCompletionReady(source,"different-company"))
            } finally {client.close()}
        }
    }
}
