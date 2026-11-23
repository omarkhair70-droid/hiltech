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

class FinanceSupplierAccountingReviewApiClientTest {
    private val candidate = "11111111-1111-1111-1111-111111111111"
    private val invoice = "22222222-2222-2222-2222-222222222222"
    private val supplier = "33333333-3333-3333-3333-333333333333"
    private val approval = "44444444-4444-4444-4444-444444444444"
    private val operation = "55555555-5555-5555-5555-555555555555"
    private val installation = "66666666-6666-6666-6666-666666666666"

    @Test fun requestOnlyEverPostsToCanonicalSupplierAccountingReview() = runBlocking {
        var hits = 0
        val http = HttpClient(MockEngine { call ->
            hits++
            assertEquals("POST", call.method.value)
            assertEquals(
                "/v1/finance/payable-candidates/$candidate/request-accounting-review",
                call.url.encodedPath,
            )
            assertEquals("Bearer finance-token", call.headers[HttpHeaders.Authorization])
            respond(
                """
                {"candidateId":"$candidate","supplierInvoiceId":"$invoice",
                 "supplierOrganizationId":"$supplier","invoiceNumber":"INV-001",
                 "amount":"1500.000000","currency":"EGP","sourceSha256":"${"a".repeat(64)}",
                 "sourceVersion":82909,"policyKey":"FINANCE_SUPPLIER_ACCOUNTING",
                 "policyVersion":3,"approvalRequestId":"$approval",
                 "reviewState":"PENDING","existingRequest":false,
                 "replayed":false,"bookedPayable":false,"taxCredited":false,
                 "paymentApproved":false,"bankPaymentSent":false,
                 "paymentReconciled":false}
                """.trimIndent(),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val api = HiltechApiClient(
            http, "https://finance.hiltech.test",
            accessTokenProvider = { "finance-token" },
            correlationIdProvider = { "accounting-review" },
        )
        val result = FinanceSupplierAccountingReviewApiClient(api)
            .request(candidate, operation, installation)
        assertEquals(1, hits)
        assertEquals(approval, result.approvalRequestId)
        assertEquals("PENDING", result.reviewState)
        assertFalse(result.bookedPayable)
        assertFalse(result.taxCredited)
        assertFalse(result.paymentApproved)
        assertFalse(result.bankPaymentSent)
        assertFalse(result.paymentReconciled)
    }

    @Test fun completedAccountingEvidenceStillDoesNotPostOrAuthorizePayment() = runBlocking {
        var hits = 0
        val http = HttpClient(MockEngine { call ->
            hits++
            assertEquals("GET", call.method.value)
            assertEquals(
                "/v1/finance/payable-candidates/$candidate/accounting-review",
                call.url.encodedPath,
            )
            respond(
                """
                {"candidateId":"$candidate","supplierInvoiceId":"$invoice",
                 "supplierOrganizationId":"$supplier","invoiceNumber":"INV-001",
                 "amount":"1500.000000","currency":"EGP","sourceSha256":"${"a".repeat(64)}",
                 "sourceVersion":82909,"policyKey":"FINANCE_SUPPLIER_ACCOUNTING",
                 "policyVersion":3,"approvalRequestId":"$approval",
                 "reviewState":"APPROVED","bookedPayable":false,
                 "taxCredited":false,"paymentApproved":false,
                 "bankPaymentSent":false,"paymentReconciled":false}
                """.trimIndent(),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val api = HiltechApiClient(
            http, "https://finance.hiltech.test",
            accessTokenProvider = { "finance-token" },
            correlationIdProvider = { "accounting-review" },
        )
        val result = FinanceSupplierAccountingReviewApiClient(api)
            .status(candidate, installation)
        assertEquals(1, hits)
        assertEquals("APPROVED", result.reviewState)
        assertFalse(result.bookedPayable)
        assertFalse(result.paymentApproved)
        assertFalse(result.bankPaymentSent)
    }

    @Test fun userCannotUseArbitrarySupplierReviewId() = runBlocking {
        var hits = 0
        val http = HttpClient(MockEngine {
            hits++
            error("Invalid ID must not access server")
        })
        val api = HiltechApiClient(
            http, "https://finance.hiltech.test",
            accessTokenProvider = { "finance-token" },
            correlationIdProvider = { "accounting-review" },
        )
        assertFailsWith<IllegalArgumentException> {
            FinanceSupplierAccountingReviewApiClient(api)
                .request("supplier:evil", operation, installation)
        }
        assertEquals(0, hits)
    }
}
