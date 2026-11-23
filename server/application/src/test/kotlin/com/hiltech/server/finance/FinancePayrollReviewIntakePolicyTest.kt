package com.hiltech.server.finance

import com.hiltech.server.payroll.PayrollFinanceReviewLine
import com.hiltech.server.payroll.PayrollFinanceReviewManifest
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class FinancePayrollReviewIntakePolicyTest {
    private fun valid(): PayrollFinanceReviewManifest {
        val org = UUID.randomUUID()
        return PayrollFinanceReviewManifest(
            organizationId = org,
            payrollPeriodId = UUID.randomUUID(),
            runVersionId = UUID.randomUUID(),
            releaseId = UUID.randomUUID(),
            operationId = UUID.randomUUID(),
            requestedByUserId = UUID.randomUUID(),
            runSourceFingerprint = "a".repeat(64),
            policyKey = "PAYROLL_FINANCE_REVIEW",
            policyVersion = 2,
            currency = "EGP",
            payableTotal = BigDecimal("150.000000"),
            positiveTransferCount = 2,
            lines = listOf(
                PayrollFinanceReviewLine(UUID.randomUUID(), BigDecimal("100.000000"), "EGP", true),
                PayrollFinanceReviewLine(UUID.randomUUID(), BigDecimal("50.000000"), "EGP", true),
            ),
            manifestSha256 = "b".repeat(64),
        )
    }

    @Test fun approvedSourceShapePermitsReviewEnvelopeButNeverPayout() {
        assertDoesNotThrow { FinancePayrollReviewIntakePolicy.validate(valid()) }
    }

    @Test fun rejectsChangedRunMoneyOrBeneficiarySetBeforeAnyDatabaseMutation() {
        val m = valid()
        val invalid = listOf(
            m.copy(manifestSha256 = "not-a-digest"),
            m.copy(payableTotal = BigDecimal("150.01")),
            m.copy(positiveTransferCount = 0),
            m.copy(lines = m.lines + m.lines.first()),
            m.copy(lines = m.lines.map { it.copy(currency = "USD") }),
            m.copy(lines = m.lines.map { it.copy(requiresTransfer = false) }),
            m.copy(lines = m.lines.map { it.copy(net = BigDecimal("-1.0")) }),
            m.copy(lines = m.lines.map { it.copy(net = BigDecimal("0.0000001")) }),
            m.copy(currency = "egp"),
            m.copy(policyVersion = 0),
            m.copy(lines = emptyList()),
        )
        invalid.forEach { candidate ->
            assertThrows<ProductApiException> {
                FinancePayrollReviewIntakePolicy.validate(candidate)
            }
        }
    }
}
