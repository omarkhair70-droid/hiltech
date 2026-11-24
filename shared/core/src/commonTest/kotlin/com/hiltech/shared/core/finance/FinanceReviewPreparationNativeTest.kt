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
    @Test
    fun customerInvoicePreparationUsesOriginalCandidateAndEvidenceWithoutIssuing() = runBlocking {
        val draft = FinanceReviewDraft(sourceId=source, reference="INV-2026-001",
            documentEvidenceId=install)
        assertTrue(financeReviewDraftIsValid(FinanceReviewKind.INVOICE_ISSUANCE_PREPARE,draft))
        assertFalse(financeReviewDraftIsValid(FinanceReviewKind.INVOICE_ISSUANCE_PREPARE,
            draft.copy(documentEvidenceId="not-a-source")))
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests++
            assertEquals(1,requests)
            assertEquals("POST",request.method.value)
            assertEquals("/v1/finance/organizations/$org/invoice-candidates/$source/issuance-proposals",
                request.url.encodedPath)
            assertEquals(operation,request.headers["Idempotency-Key"])
            respond("""{"proposalId":"$proposal","candidateId":"$source",
                "organizationId":"$org","invoiceNumber":"INV-2026-001","state":"PROPOSED"}""",
                HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        val result=financePrepareReviewProposal(apis(client),org,install,
            FinanceReviewAttempt(FinanceReviewKind.INVOICE_ISSUANCE_PREPARE,draft,operation))
        assertEquals(proposal,result.id)
        assertEquals(FinanceReviewKind.INVOICE_ISSUANCE_PREPARE,result.kind)
        assertTrue(financeReviewCanRequest(result.state,result.approvalRequestId))
        assertEquals(1,requests)
    }

    @Test
    fun invoicePreparationRejectsDifferentCandidateOrPrematureIssuance() = runBlocking {
        for (returnedCandidate in listOf(wrongOrg,source)) {
            val client=HttpClient(MockEngine {
                respond("""{"proposalId":"$proposal","candidateId":"$returnedCandidate",
                    "organizationId":"$org","invoiceNumber":"INV-2026-001","state":"PROPOSED",
                    "recordedInternally":${returnedCandidate==source}}""",
                    HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
            })
            assertFailsWith<IllegalStateException> {
                financePrepareReviewProposal(apis(client),org,install,
                    FinanceReviewAttempt(FinanceReviewKind.INVOICE_ISSUANCE_PREPARE,
                        FinanceReviewDraft(sourceId=source,reference="INV-2026-001",
                            documentEvidenceId=install),operation))
            }
        }
    }

    @Test
    fun employeePreparationRequiresCorrectBankEvidenceAndOriginalApproval() {
        val funding=FinanceReviewDraft(sourceId=source,policyApprovalId=proposal,bankLineId=install)
        assertTrue(financeReviewDraftIsValid(FinanceReviewKind.EMPLOYEE_FUNDING,funding))
        assertFalse(financeReviewDraftIsValid(FinanceReviewKind.EMPLOYEE_FUNDING,funding.copy(policyApprovalId="")))
        val settlement=funding.copy(amount="12.5",documentEvidenceId=proposal,evidenceSha256="a".repeat(64))
        assertTrue(financeReviewDraftIsValid(FinanceReviewKind.EMPLOYEE_SETTLEMENT,settlement))
        val receipts=settlement.copy(settlementAction="IMPREST_RECEIPTS_ACCEPTED")
        assertFalse(financeReviewDraftIsValid(FinanceReviewKind.EMPLOYEE_SETTLEMENT,receipts))
        assertTrue(financeReviewDraftIsValid(FinanceReviewKind.EMPLOYEE_SETTLEMENT,receipts.copy(bankLineId="")))
        assertTrue(financeReviewDraftIsValid(FinanceReviewKind.EXPENSE_REIMBURSEMENT,settlement))
        assertFalse(financeReviewDraftIsValid(FinanceReviewKind.EXPENSE_REIMBURSEMENT,settlement.copy(amount="0")))
    }

    @Test
    fun journalAndPeriodReviewRequireTheirCanonicalInitialStates() {
        val journal=FinanceReviewDraft(sourceId=source,periodId=proposal,reference="ACCOUNTING-POLICY",
            taxDisposition="TAX-POLICY",lines=listOf(
                FinanceReviewJournalLine(source,"DEBIT","100"),
                FinanceReviewJournalLine(install,"CREDIT","100")))
        assertTrue(financeReviewDraftIsValid(FinanceReviewKind.AP_JOURNAL,journal))
        assertTrue(financeReviewDraftIsValid(FinanceReviewKind.AR_JOURNAL,journal))
        assertFalse(financeReviewDraftIsValid(FinanceReviewKind.AR_JOURNAL,journal.copy(lines=journal.lines.take(1))))
        assertTrue(financeReviewCanRequest(FinanceReviewKind.AP_JOURNAL,"SEALED_AWAITING_POSTING_REVIEW",null))
        assertTrue(financeReviewCanRequest(FinanceReviewKind.AR_JOURNAL,"SEALED_AWAITING_APPROVAL",null))
        assertTrue(financeReviewCanRequest(FinanceReviewKind.PERIOD_CLOSE,"NOT_REQUESTED",null))
        assertFalse(financeReviewCanRequest(FinanceReviewKind.PERIOD_CLOSE,"APPROVED",null))
        assertFalse(financeReviewCanRequest(FinanceReviewKind.AP_JOURNAL,"PROPOSED",null))
    }

    @Test
    fun employeeFundingPrepareUsesNativeSourceAndNeverRecords() = runBlocking {
        var requests=0
        val client=HttpClient(MockEngine { request ->
            requests++
            assertEquals(1,requests)
            assertEquals("POST",request.method.value)
            assertEquals("/v1/finance/organizations/$org/employee-funding/EMPLOYEE_ADVANCE/$source/prepare",request.url.encodedPath)
            assertEquals(operation,request.headers["Idempotency-Key"])
            respond("""{"proposalId":"$proposal","organizationId":"$org","kind":"EMPLOYEE_ADVANCE",
                "draftId":"$source","employeeId":"$source","amount":"50","currency":"EGP",
                "state":"PROPOSED","bankStatementLineId":"$install"}""",
                HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        val receipt=financePrepareReviewProposal(apis(client),org,install,FinanceReviewAttempt(
            FinanceReviewKind.EMPLOYEE_FUNDING,
            FinanceReviewDraft(sourceId=source,policyApprovalId=proposal,bankLineId=install),operation))
        assertEquals(proposal,receipt.id)
        assertEquals("PROPOSED",receipt.state)
        assertEquals(1,requests)
    }

    @Test
    fun periodCloseReviewReadsCurrentLedgerBeforeRequest() = runBlocking {
        var requests=0
        val client=HttpClient(MockEngine { request ->
            requests++
            val state=if(requests==1) {
                assertEquals("GET",request.method.value)
                assertEquals("/v1/finance/organizations/$org/periods/$source/close",request.url.encodedPath)
                "NOT_REQUESTED"
            } else {
                assertEquals(2,requests)
                assertEquals("POST",request.method.value)
                assertEquals("/v1/finance/organizations/$org/periods/$source/request-close-review",request.url.encodedPath)
                assertEquals(operation,request.headers["Idempotency-Key"])
                "PENDING"
            }
            val approval=if(requests==1) "null" else "\"$proposal\""
            respond("""{"organizationId":"$org","periodId":"$source",
                "ledgerSnapshotSha256":"${"a".repeat(64)}","journalCount":2,"sourceVersion":1,
                "state":"$state","approvalRequestId":$approval}""",
                HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        val receipt=financeRequestIndependentReview(apis(client),org,install,
            FinanceReviewKind.PERIOD_CLOSE,source,operation)
        assertEquals("PENDING",receipt.state)
        assertEquals(proposal,receipt.approvalRequestId)
        assertEquals(2,requests)
    }

    @Test
    fun signedCountRechecksApprovalAndRecordsOnlyWithEvidence() = runBlocking {
        val approval="55555555-6666-4777-8888-999999999999"
        var requests=0
        val client=HttpClient(MockEngine { request ->
            requests++
            val terminal=requests==3
            assertEquals(if(terminal) "POST" else "GET",request.method.value)
            assertEquals("/v1/finance/organizations/$org/cash-counts/$proposal"+(if(terminal) "/record" else "/operator-status"),request.url.encodedPath)
            if(terminal) assertEquals(operation,request.headers["Idempotency-Key"])
            val recorded=if(terminal) "\"$source\"" else "null"
            val state=if(terminal) "RECORDED" else "APPROVED"
            respond("""{"proposalId":"$proposal","organizationId":"$org","cashboxId":"$source",
                "currency":"EGP","countedAmount":"9","ledgerAmount":"10","varianceAmount":"-1",
                "voucherCount":1,"state":"$state","approvalRequestId":"$approval","countRecordId":$recorded}""",
                HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        val sourceReceipt=financeReadSignedSource(apis(client),org,install,FinanceReviewKind.CASH_COUNT,proposal)
        assertTrue(financeSignedReady(sourceReceipt,org))
        assertFailsWith<IllegalArgumentException> {
            financeRecordSignedSource(apis(client),org,install,sourceReceipt,"bad",operation)
        }
        assertEquals(1,requests)
        val recorded=financeRecordSignedSource(apis(client),org,install,sourceReceipt,install,operation)
        assertEquals("RECORDED",recorded.state)
        assertEquals(3,requests)
        assertFalse(financeSignedReady(recorded,org))
    }

    @Test
    fun voucherRequiresCashboxAndPositiveAmount() {
        val valid=FinanceReviewDraft(sourceId=source,cashboxId=install,sourceKind="AR",
            amount="10.00",reference="CUSTODY-REASON",voucherReference="REC-001")
        assertTrue(financeReviewDraftIsValid(FinanceReviewKind.CASH_VOUCHER,valid))
        assertFalse(financeReviewDraftIsValid(FinanceReviewKind.CASH_VOUCHER,valid.copy(cashboxId="")))
        assertFalse(financeReviewDraftIsValid(FinanceReviewKind.CASH_VOUCHER,valid.copy(amount="0")))
    }

    @Test
    fun invoiceCandidateValidatesRealDatesAndSourceBeforeCreation() {
        val draft=FinanceInvoiceCandidateDraft(opportunityId=source,evidenceId=install,
            amount="120.50",legalReference="LEGAL-REVIEW",contractReference="CONTRACT-MILESTONE",
            issueDate="2028-02-29",dueDate="2028-03-01")
        assertTrue(financeInvoiceCandidateValid(draft))
        assertFalse(financeInvoiceCandidateValid(draft.copy(issueDate="2027-02-29")))
        assertFalse(financeInvoiceCandidateValid(draft.copy(dueDate="2028-02-28")))
        assertFalse(financeInvoiceCandidateValid(draft.copy(amount="0")))
        assertFalse(financeInvoiceCandidateValid(draft.copy(evidenceId="missing")))
    }

    @Test
    fun invoiceCandidateCreationReturnsUnissuedDraftFromExactAward() = runBlocking {
        var calls=0
        val client=HttpClient(MockEngine { request ->
            calls++
            assertEquals("POST",request.method.value)
            assertEquals("/v1/finance/organizations/$org/client-award-sources/$source/invoice-candidates",request.url.encodedPath)
            assertEquals(operation,request.headers["Idempotency-Key"])
            respond("""{"candidateId":"$proposal","organizationId":"$org",
                "opportunityId":"$source","customerOrganizationId":"$install",
                "commercialSourceSha256":"${"a".repeat(64)}","requestedAmount":"120.500000",
                "currency":"EGP","state":"DRAFT_UNISSUED"}""",HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType,"application/json"))
        })
        val draft=FinanceInvoiceCandidateDraft(source,install,"120.50","LEGAL-REVIEW","CONTRACT-MILESTONE",
            "2026-11-24","2026-12-24")
        val result=financeCreateInvoiceCandidate(apis(client),org,install,draft,operation)
        assertEquals(proposal,result.candidateId)
        assertFalse(result.invoiceIssued)
        assertEquals(1,calls)
    }

    @Test
    fun signedQueueReturnsOnlyScopedApprovedOperations() = runBlocking {
        val approval="55555555-6666-4777-8888-999999999999"
        for (actualOrg in listOf(org,wrongOrg)) {
            val client=HttpClient(MockEngine { request ->
                assertEquals("GET",request.method.value)
                assertEquals("/v1/finance/organizations/$org/cash-counts/operator-queue",request.url.encodedPath)
                respond("""[{"proposalId":"$proposal","organizationId":"$actualOrg","cashboxId":"$source",
                    "currency":"EGP","countedAmount":"9","ledgerAmount":"10","varianceAmount":"-1",
                    "voucherCount":1,"state":"APPROVED","approvalRequestId":"$approval"}]""",
                    HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
            })
            if(actualOrg==org) {
                val items=financeSignedQueue(apis(client),org,install,FinanceReviewKind.CASH_COUNT)
                assertEquals(proposal,items.single().id)
            } else assertFailsWith<IllegalStateException> {
                financeSignedQueue(apis(client),org,install,FinanceReviewKind.CASH_COUNT)
            }
        }
    }

    @Test
    fun allocationAndCorrectionCompleteOnlyThroughApprovedOperatorSource() = runBlocking {
        for(kind in listOf(FinanceCompletionKind.CASH_ALLOCATION,FinanceCompletionKind.CASH_CORRECTION)) {
            val correction=kind==FinanceCompletionKind.CASH_CORRECTION
            val route=if(correction)"cash-allocation-corrections" else "cash-allocation-proposals"
            val approval="55555555-6666-4777-8888-999999999999"
            var calls=0
            val client=HttpClient(MockEngine {request ->
                calls++
                val terminal=calls==3
                val path="/v1/finance/organizations/$org/$route"
                assertEquals(path+when(calls){1->"/operator-queue";2->"/$proposal";else->"/$proposal/record"},request.url.encodedPath)
                assertEquals(if(terminal)"POST" else "GET",request.method.value)
                if(terminal) assertEquals(operation,request.headers["Idempotency-Key"])
                val state=if(terminal)kind.terminal else "APPROVED"
                val record=if(terminal)"\"$source\"" else "null"
                val extra=if(correction) """"originalAllocationId":"$install","correctionRecordId":$record,
                    "originalMatchStillEffective":${!terminal}""" else """"recordedAllocationId":$record,
                    "cashAllocatedToAccountingSource":$terminal"""
                val receipt="""{"proposalId":"$proposal","organizationId":"$org","sourceKind":"AR",
                    "sourceJournalId":"$source","bankStatementLineId":"$install","amount":"120","currency":"EGP",
                    "state":"$state","approvalRequestId":"$approval",$extra}"""
                respond(if(calls==1)"[$receipt]" else receipt,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
            })
            val source=financeCompletionQueue(apis(client),org,install,kind).single()
            val completed=financeCompleteApprovedSource(apis(client),org,install,source,operation)
            assertEquals(kind.terminal,completed.state)
            assertEquals(3,calls)
            assertFalse(financeCompletionReady(completed,org))
        }
    }

}
