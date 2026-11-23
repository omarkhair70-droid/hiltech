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

class FinanceMoneyPolicyReviewApiClientTest {
    private val org = "11111111-1111-1111-1111-111111111111"
    private val draft = "22222222-2222-2222-2222-222222222222"
    private val operation = "33333333-3333-3333-3333-333333333333"
    private val approval = "44444444-4444-4444-4444-444444444444"
    private val installation = "55555555-5555-5555-5555-555555555555"

    @Test fun employeeAdvanceReviewCannotBeReadAsImprestOrPayrollDeduction() = runBlocking {
        var count = 0
        val http = HttpClient(MockEngine { call ->
            assertEquals("POST", call.method.value)
            assertEquals(
                "/v1/finance/me/organizations/$org/advances/$draft/request-policy-review",
                call.url.encodedPath,
            )
            assertEquals("Bearer self-service-token", call.headers[HttpHeaders.Authorization])
            count++
            respond(
                """
                {"draftId":"$draft","kind":"EMPLOYEE_ADVANCE",
                "sourceVersion":117,"policyKey":"FINANCE_ADVANCE_REVIEW",
                "policyVersion":2,"approvalRequestId":"$approval",
                "requestState":"PENDING","existingRequest":false,
                "replayed":false,"moneyIssued":false,
                "personalLoanRecognized":false,"cashCustodyRecorded":false,
                "payrollDeductionAuthorized":false,"settlementRecorded":false}
                """.trimIndent(),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val api = HiltechApiClient(
            http, "https://finance.hiltech.test",
            accessTokenProvider = { "self-service-token" },
            correlationIdProvider = { "finance-advance" },
        )
        val result = FinanceMoneyPolicyReviewApiClient(api).request(
            org, "advances", draft, operation, installation,
        )
        assertEquals(1, count)
        assertEquals("EMPLOYEE_ADVANCE", result.kind)
        assertEquals(approval, result.approvalRequestId)
        assertEquals("PENDING", result.requestState)
        assertFalse(result.moneyIssued)
        assertFalse(result.personalLoanRecognized)
        assertFalse(result.payrollDeductionAuthorized)
        assertFalse(result.cashCustodyRecorded)
    }

    @Test fun companyImprestUsesItsOwnStatusRouteNeverLoanBalance() = runBlocking {
        var count = 0
        val http = HttpClient(MockEngine { call ->
            assertEquals("GET", call.method.value)
            assertEquals(
                "/v1/finance/me/organizations/$org/imprests/$draft/policy-review",
                call.url.encodedPath,
            )
            count++
            respond(
                """
                {"draftId":"$draft","kind":"FINANCIAL_IMPREST",
                "sourceVersion":118,"policyKey":"FINANCE_IMPREST_REVIEW",
                "policyVersion":7,"approvalRequestId":"$approval",
                "requestState":"APPROVED","existingRequest":true,
                "replayed":false,"moneyIssued":false,
                "personalLoanRecognized":false,"cashCustodyRecorded":false,
                "payrollDeductionAuthorized":false,"settlementRecorded":false}
                """.trimIndent(),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val api = HiltechApiClient(
            http, "https://finance.hiltech.test",
            accessTokenProvider = { "self-service-token" },
            correlationIdProvider = { "finance-imprest" },
        )
        val result = FinanceMoneyPolicyReviewApiClient(api).mine(
            org, "imprests", draft, installation,
        )
        assertEquals(1, count)
        assertEquals("FINANCIAL_IMPREST", result.kind)
        assertEquals("APPROVED", result.requestState)
        assertFalse(result.moneyIssued)
        assertFalse(result.cashCustodyRecorded)
        assertFalse(result.personalLoanRecognized)
        assertFalse(result.payrollDeductionAuthorized)
    }

    @Test fun anyUnrecognizedMoneyKindIsRejectedBeforeNetwork() = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine {
            calls++
            error("No network calls for arbitrary review type.")
        })
        val api = HiltechApiClient(
            http, "https://finance.hiltech.test",
            accessTokenProvider = { "self-service-token" },
            correlationIdProvider = { "invalid" },
        )
        assertFailsWith<IllegalArgumentException> {
            FinanceMoneyPolicyReviewApiClient(api).request(
                org, "payroll-deductions", draft, operation, installation,
            )
        }
        assertEquals(0, calls)
    }
}
