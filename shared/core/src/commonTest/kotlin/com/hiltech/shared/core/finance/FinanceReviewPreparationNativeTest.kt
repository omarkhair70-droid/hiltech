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

class FinanceReviewPreparationNativeTest {
    private val org = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val wrongOrg = "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff"
    private val source = "11111111-2222-4333-8444-555555555555"
    private val proposal = "22222222-3333-4444-8555-666666666666"
    private val operation = "33333333-4444-4555-8666-777777777777"
    private val install = "44444444-5555-4666-8777-888888888888"

    private fun apis(client: HttpClient) = FinanceEvidenceReadApis(
        HiltechApiClient(client, "https://finance.hiltech.test",
            accessTokenProvider = { "authorized-company-finance-actor" },
            correlationIdProvider = { "finance-review-native-qa" }),
    )

    @Test
    fun invalidAndUnknownSourceCannotStartReviewAction() {
        val valid = FinanceReviewDraft(sourceId = source, reference = "REAL-RECON-SOURCE")
        assertTrue(financeReviewDraftIsValid(FinanceReviewKind.BANK_RECONCILIATION, valid))
        assertFalse(financeReviewDraftIsValid(
            FinanceReviewKind.BANK_RECONCILIATION, valid.copy(sourceId = "../../admin")))
        assertFalse(financeReviewDraftIsValid(
            FinanceReviewKind.BANK_RECONCILIATION, valid.copy(reference = "short")))
        assertFalse(financeReviewDraftIsValid(
            FinanceReviewKind.CASH_ALLOCATION, valid.copy(bankLineId = "bad", amount = "100")))
        assertFalse(financeReviewDraftIsValid(
            FinanceReviewKind.CASH_ALLOCATION, valid.copy(
                bankLineId = source, amount = "0", sourceKind = "AR")))
        assertTrue(financeReviewDraftIsValid(
            FinanceReviewKind.CASH_ALLOCATION, valid.copy(
                bankLineId = source, amount = "0.000001", sourceKind = "AP")))
        assertTrue(financeReviewDraftIsValid(
            FinanceReviewKind.CASH_COUNT, valid.copy(amount = "0")))
        assertFalse(financeReviewCanRequest("PENDING", null))
        assertFalse(financeReviewCanRequest("PROPOSED", source))
        assertTrue(financeReviewCanRequest("PROPOSED", null))
        assertFalse(financeReviewIsAmount("-1", allowZero = true))
    }

    @Test
    fun cashCountRequiresCurrentCashboxSourceBeforeOnlyPreparingWitnessProposal() = runBlocking {
        val paths = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            assertEquals("Bearer authorized-company-finance-actor",
                request.headers[HttpHeaders.Authorization])
            when (paths.size) {
                1 -> {
                    assertEquals("GET", request.method.value)
                    assertEquals("/v1/finance/organizations/$org/cashboxes/$source/balance",
                        request.url.encodedPath)
                    respond(
                        """{"cashboxId":"$source","organizationId":"$org",
                            "code":"CB-101","currency":"EGP",
                            "custodianUserId":"$source","cashOnHand":"100.000000",
                            "voucherCount":1}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                2 -> {
                    assertEquals("POST", request.method.value)
                    assertEquals(operation, request.headers["Idempotency-Key"])
                    assertEquals("/v1/finance/organizations/$org/cash-counts/prepare",
                        request.url.encodedPath)
                    respond(
                        """{"proposalId":"$proposal","organizationId":"$org",
                           "cashboxId":"$source","currency":"EGP",
                           "countedAmount":"99.000000","ledgerAmount":"100.000000",
                           "varianceAmount":"-1.000000","voucherCount":1,
                           "state":"PROPOSED","ledgerAdjusted":false,
                           "bankTransferInitiated":false}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                else -> error("No more Finance requests allowed")
            }
        })
        val result = financePrepareReviewProposal(
            apis(client), org, install,
            FinanceReviewAttempt(
                FinanceReviewKind.CASH_COUNT,
                FinanceReviewDraft(
                    sourceId = source, amount = "99.000000",
                    reference = "WITNESSED-COUNT-SOURCE"),
                operation,
            ),
        )
        assertEquals(2, paths.size)
        assertEquals(proposal, result.id)
        assertEquals("PROPOSED", result.state)
        assertTrue(result.details.any { it.contains("الفرق") })
        assertFalse(result.details.any { it.contains("تم دفع") })
    }

    @Test
    fun mismatchedCashboxScopeStopsBeforeAnyPost() = runBlocking {
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests++
            assertEquals("GET", request.method.value)
            respond(
                """{"cashboxId":"$source","organizationId":"$wrongOrg",
                    "code":"CB-102","currency":"EGP","custodianUserId":"$source",
                    "cashOnHand":"0","voucherCount":0}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertFailsWith<IllegalStateException> {
            financePrepareReviewProposal(
                apis(client), org, install,
                FinanceReviewAttempt(
                    FinanceReviewKind.CASH_COUNT,
                    FinanceReviewDraft(sourceId=source,amount="100",
                        reference="REAL-COUNT-SOURCE"),operation),
            )
        }
        assertEquals(1, requests)
    }

    @Test
    fun reReadBeforeReviewRejectsAlreadyChangedProposalWithoutPost() = runBlocking {
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests++
            assertEquals("GET", request.method.value)
            assertEquals("/v1/finance/organizations/$org/cash-allocation-proposals/$proposal",
                request.url.encodedPath)
            respond(
                """{"proposalId":"$proposal","organizationId":"$org",
                  "sourceKind":"AR","sourceJournalId":"$source",
                  "bankStatementLineId":"$source","amount":"100",
                  "currency":"EGP","state":"RECORDED",
                  "cashAllocatedToAccountingSource":true}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertFailsWith<IllegalStateException> {
            financeRequestIndependentReview(
                apis(client), org, install, FinanceReviewKind.CASH_ALLOCATION,
                proposal, operation,
            )
        }
        assertEquals(1, requests)
    }

    @Test
    fun originalSourceThenIndependentReviewOnlyNeverRecordsMoney() = runBlocking {
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests++
            val requestJson = if (requests == 1) {
                assertEquals("GET", request.method.value)
                assertEquals("/v1/finance/organizations/$org/bank-reconciliations/$proposal",
                    request.url.encodedPath)
                """{"proposalId":"$proposal","organizationId":"$org",
                   "intakeId":"$source","accountId":"$source",
                   "statementSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                   "matchingSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","currency":"EGP",
                   "lineCount":1,"totalCredit":"100","totalDebit":"0",
                   "totalMatched":"100","state":"PROPOSED"}"""
            } else {
                assertEquals(2, requests)
                assertEquals("POST", request.method.value)
                assertEquals(
                    "/v1/finance/organizations/$org/bank-reconciliations/$proposal/request-review",
                    request.url.encodedPath)
                assertEquals(operation, request.headers["Idempotency-Key"])
                """{"proposalId":"$proposal","organizationId":"$org",
                   "intakeId":"$source","accountId":"$source",
                   "statementSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                   "matchingSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","currency":"EGP",
                   "lineCount":1,"totalCredit":"100","totalDebit":"0",
                   "totalMatched":"100","state":"PENDING",
                   "approvalRequestId":"$source"}"""
            }
            respond(requestJson, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val receipt = financeRequestIndependentReview(
            apis(client), org, install,
            FinanceReviewKind.BANK_RECONCILIATION, proposal, operation,
        )
        assertEquals(2, requests)
        assertEquals("PENDING", receipt.state)
        assertEquals(source, receipt.approvalRequestId)
        assertFalse(financeReviewCanRequest(receipt.state, receipt.approvalRequestId))
    }
}
