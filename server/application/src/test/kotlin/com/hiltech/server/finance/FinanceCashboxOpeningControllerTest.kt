package com.hiltech.server.finance

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.HiltechRequestHeaders
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletRequest
import java.util.UUID

class FinanceCashboxOpeningControllerTest {
    private val actor = UUID.randomUUID()
    private val org = UUID.randomUUID()
    private val operation = UUID.randomUUID()
    private val custodian = UUID.randomUUID()
    private val service = Mockito.mock(FinanceCashboxOpeningService::class.java)
    private val controller = FinanceCashboxOpeningController(service)
    private fun request() = MockHttpServletRequest().also {
        it.addHeader(HiltechRequestHeaders.CORRELATION_ID, "synthetic-cashbox-controller")
        it.addHeader(HiltechRequestHeaders.IDEMPOTENCY_KEY, operation.toString())
        HiltechRequestContext.bindAuthenticatedIdentity(it, actor, UUID.randomUUID())
    }

    @Test fun employeeChoicesUseTheAuthenticatedActorAndExactOrganizationWithoutCaching() {
        val source = FinanceCashboxCustodianDirectory(org, emptyList(), false, 7, null)
        Mockito.`when`(service.custodians(actor, org, "Named employee")).thenReturn(source)
        val response = controller.custodians(request(), org, "Named employee")
        assertEquals(source, response.body)
        assertTrue(response.headers.cacheControl.orEmpty().contains("no-store"))
        Mockito.verify(service).custodians(actor, org, "Named employee")
    }

    @Test fun prepareRetainsPinnedSourceVersionsAndGetsItsActorOnlyFromVerifiedContext() {
        val input = FinanceCashboxOpeningPrepare(operation, currency = "EGP", custodianUserId = custodian,
            companyCustodyReference = "Synthetic company custody", expectedCustodianVersion = 2, expectedReviewVersion = 3)
        val receipt = FinanceCashboxReceipt(UUID.randomUUID(), org, "CBX-000001", "EGP", custodian, "PROPOSED", confirmedOperationId = operation)
        Mockito.`when`(service.prepare(actor, org, input, "synthetic-cashbox-controller")).thenReturn(receipt)
        assertEquals(receipt, controller.prepare(request(), org, input))
        Mockito.verify(service).prepare(actor, org, input, "synthetic-cashbox-controller")
    }

    @Test fun missingIdentityCannotQueryNamedEmployees() {
        assertEquals("UNAUTHENTICATED", assertThrows<ProductApiException> {
            controller.custodians(MockHttpServletRequest(), org, "")
        }.code)
        Mockito.verifyNoInteractions(service)
    }

    @Test fun mismatchedOperationCannotReachTheCashboxSource() {
        val input = FinanceCashboxOpeningPrepare(UUID.randomUUID(), currency = "EGP", custodianUserId = custodian,
            companyCustodyReference = "Synthetic company custody", expectedCustodianVersion = 2, expectedReviewVersion = 3)
        assertEquals("IDEMPOTENCY_KEY_MISMATCH", assertThrows<ProductApiException> { controller.prepare(request(), org, input) }.code)
        Mockito.verifyNoInteractions(service)
    }
}
