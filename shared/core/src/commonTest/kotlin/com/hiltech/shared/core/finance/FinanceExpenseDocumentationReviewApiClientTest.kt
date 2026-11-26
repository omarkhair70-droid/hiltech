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

class FinanceExpenseDocumentationReviewApiClientTest {
    private val org = "11111111-1111-1111-1111-111111111111"
    private val draft = "22222222-2222-2222-2222-222222222222"
    private val operationId = "33333333-3333-3333-3333-333333333333"
    private val approvalId = "44444444-4444-4444-4444-444444444444"
    private val receiptId = "55555555-5555-5555-5555-555555555555"
    private val installation = "66666666-6666-6666-6666-666666666666"

    @Test fun requestIsAProtectedCompanyDocumentationReviewNotPayment() = runBlocking {
        var requests = 0
        val http = HttpClient(MockEngine { call ->
            assertEquals("POST", call.method.value)
            assertEquals(
                "/v1/finance/me/organizations/$org/expenses/$draft/request-documentation-review",
                call.url.encodedPath,
            )
            assertEquals("Bearer employee-token", call.headers[HttpHeaders.Authorization])
            requests++
            respond(
                """
                {"expenseDraftId":"$draft","sourceVersion":928372,
                 "verifiedReceiptEvidenceIds":["$receiptId"],
                 "documentationReviewRequestId":"$approvalId",
                 "documentationReviewState":"PENDING",
                 "policyKey":"EMPLOYEE_EXPENSE_DOCUMENTATION",
                 "policyVersion":2,"replayed":false,
                 "expenseApproved":false,"payableRecognized":false,
                 "reimbursementAuthorized":false,
                 "payrollDeductionAuthorized":false,
                 "cashMovementRecorded":false}
                """.trimIndent(),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val api = HiltechApiClient(
            http, "https://finance.hiltech.test",
            accessTokenProvider = { "employee-token" },
            correlationIdProvider = { "documentation-contract" },
        )
        val result = FinanceExpenseDocumentationReviewApiClient(api)
            .request(org, draft, operationId, installation)
        assertEquals(1, requests)
        assertEquals(approvalId, result.documentationReviewRequestId)
        assertEquals("PENDING", result.documentationReviewState)
        assertEquals(listOf(receiptId), result.verifiedReceiptEvidenceIds)
        assertFalse(result.expenseApproved)
        assertFalse(result.reimbursementAuthorized)
        assertFalse(result.cashMovementRecorded)
    }

    @Test fun malformedExpenseIdentityNeverReachesTheApi() = runBlocking {
        var called = 0
        val http = HttpClient(MockEngine {
            called++
            error("No remote requests for malformed expense ID")
        })
        val api = HiltechApiClient(
            http, "https://finance.hiltech.test",
            accessTokenProvider = { "employee-token" },
            correlationIdProvider = { "documentation-contract" },
        )
        assertFailsWith<IllegalArgumentException> {
            FinanceExpenseDocumentationReviewApiClient(api)
                .request(org, "not-an-expense-id", operationId, installation)
        }
        assertEquals(0, called)
    }
    @Test fun currentEmployeeReadsRealDecisionStateWithoutPaymentRights() = runBlocking {
        var requests = 0
        val http = HttpClient(MockEngine { call ->
            assertEquals("GET", call.method.value)
            assertEquals(
                "/v1/finance/me/organizations/$org/expenses/$draft/documentation-review",
                call.url.encodedPath,
            )
            assertEquals("Bearer employee-token", call.headers[HttpHeaders.Authorization])
            requests++
            respond(
                """
                {"expenseDraftId":"$draft","sourceVersion":928372,
                 "verifiedReceiptCount":1,"documentationReviewRequestId":"$approvalId",
                 "documentationReviewState":"APPROVED","policyKey":"EMPLOYEE_EXPENSE_DOCUMENTATION",
                 "currentSource":true,"expenseApproved":false,
                 "payableRecognized":false,"reimbursementAuthorized":false,
                 "cashMovementRecorded":false}
                """.trimIndent(),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val api = HiltechApiClient(
            http, "https://finance.hiltech.test",
            accessTokenProvider = { "employee-token" },
            correlationIdProvider = { "documentation-contract" },
        )
        val outcome = FinanceExpenseDocumentationReviewApiClient(api)
            .status(org, draft, installation)
        assertEquals(1, requests)
        assertEquals("APPROVED", outcome.documentationReviewState)
        assertEquals(1, outcome.verifiedReceiptCount)
        assertFalse(outcome.expenseApproved)
        assertFalse(outcome.payableRecognized)
        assertFalse(outcome.reimbursementAuthorized)
        assertFalse(outcome.cashMovementRecorded)
    }

}
