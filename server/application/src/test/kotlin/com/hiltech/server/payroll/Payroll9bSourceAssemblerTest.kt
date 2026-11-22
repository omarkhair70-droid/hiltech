package com.hiltech.server.payroll

import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.support.StaticListableBeanFactory
import java.math.RoundingMode
import java.util.UUID

class Payroll9bSourceAssemblerTest {
    private val org = UUID.randomUUID()
    private val period = UUID.randomUUID()
    private val employee = UUID.randomUUID()
    private val policy = PayrollValidatedPolicy(
        organizationId = org,
        policyVersionId = UUID.randomUUID(),
        policyVersion = 1,
        currency = "EGP",
        fractionalDigits = 2,
        roundingMode = RoundingMode.HALF_EVEN,
        companyAuthorizationReference = "synthetic-accountant-authorization",
        statutoryPolicyReference = "synthetic-statutory-authorization",
        statutoryVerified = true,
        deductionLimitPolicyReference = null,
        deductionLimitsVerified = false,
    )
    private fun receipt(domain: PayrollInputCoverageDomain) = PayrollInputCoverageReceipt(
        organizationId = org, payrollPeriodId = period, employeeId = employee,
        domain = domain, sourceId = UUID.randomUUID(), sourceVersion = 1,
        approval = PayrollInputApproval.APPROVED,
        sourceApprovalReference = "synthetic-approved-" + domain.name,
        complete = true,
    )
    private val earning = PayrollApprovedComponent(
        organizationId = org, employeeId = employee,
        componentCode = "SYNTHETIC_COMPENSATION",
        kind = PayrollComponentKind.EARNING,
        sourceDomain = "COMPENSATION", sourceId = UUID.randomUUID(),
        sourceVersion = 1, sourceApprovalReference = "synthetic-hr-signature",
        approval = PayrollInputApproval.APPROVED,
        currency = "EGP", amount = "100.00",
    )

    private fun fixture(
        hr: PayrollAttestedSourceBundle?,
        time: PayrollAttestedSourceBundle?,
        money: PayrollAttestedSourceBundle?,
    ): PayrollVerifiedInputAssembler {
        val policyPort = object : PayrollValidatedStatutoryPolicySourcePort {
            override fun approvedPolicy(
                organizationId: UUID,
                payrollPeriodId: UUID,
            ) = policy
        }
        val hrPort = object : PayrollApprovedCompensationSourcePort {
            override fun approvedCompensation(
                organizationId: UUID, payrollPeriodId: UUID, employeeId: UUID,
            ) = hr
        }
        val timePort = object : PayrollApprovedAttendanceSourcePort {
            override fun approvedTimeAndAllowances(
                organizationId: UUID, payrollPeriodId: UUID, employeeId: UUID,
            ) = time
        }
        val financePort = object : PayrollApprovedFinanceInputSourcePort {
            override fun approvedAdvancesDeductionsAndReimbursements(
                organizationId: UUID, payrollPeriodId: UUID, employeeId: UUID,
            ) = money
        }
        return PayrollVerifiedInputAssembler(
            StaticListableBeanFactory(mapOf("policy" to policyPort))
                .getBeanProvider(PayrollValidatedStatutoryPolicySourcePort::class.java),
            StaticListableBeanFactory(mapOf("hr" to hrPort))
                .getBeanProvider(PayrollApprovedCompensationSourcePort::class.java),
            StaticListableBeanFactory(mapOf("time" to timePort))
                .getBeanProvider(PayrollApprovedAttendanceSourcePort::class.java),
            StaticListableBeanFactory(mapOf("finance" to financePort))
                .getBeanProvider(PayrollApprovedFinanceInputSourcePort::class.java),
            StaticListableBeanFactory()
                .getBeanProvider(PayrollVerifiedSeparationEligibilitySourcePort::class.java),
        )
    }

    @Test
    fun explicitlyApprovedZeroTimeAndFinanceStillRequireCoverageReceipts() {
        val hr = PayrollAttestedSourceBundle(
            receipt(PayrollInputCoverageDomain.COMPENSATION), listOf(earning),
        )
        val time = PayrollAttestedSourceBundle(
            receipt(PayrollInputCoverageDomain.APPROVED_TIME), emptyList(),
        )
        val money = PayrollAttestedSourceBundle(
            receipt(PayrollInputCoverageDomain.FINANCE), emptyList(),
        )
        val valid = fixture(hr, time, money)
        assertEquals(policy, valid.policyFor(org, period))
        val inputs = valid.verifiedFor(org, period, employee)
        assertEquals(3, inputs.coverageReceipts.size)
        assertEquals(1, inputs.components.size)
        assertEquals(
            "100.00",
            Payroll9bCalculator.calculate(
                org, period, employee, policy,
                inputs.components, inputs.coverageReceipts,
            ).net.toPlainString(),
        )
        assertThrows<ProductApiException> {
            fixture(hr, time, null).verifiedFor(org, period, employee)
        }
        assertThrows<ProductApiException> {
            fixture(hr, null, money).verifiedFor(org, period, employee)
        }
        assertThrows<ProductApiException> {
            fixture(null, time, money).verifiedFor(org, period, employee)
        }
        assertThrows<ProductApiException> {
            fixture(hr, time.copy(receipt = money.receipt), money)
                .verifiedFor(org, period, employee)
        }
        assertThrows<ProductApiException> {
            fixture(hr, time, money.copy(
                receipt = money.receipt.copy(approval = PayrollInputApproval.PENDING),
            )).verifiedFor(org, period, employee)
        }
        assertThrows<ProductApiException> {
            fixture(hr, time, money.copy(components = listOf(earning)))
                .verifiedFor(org, period, employee)
        }
        assertThrows<ProductApiException> {
            fixture(hr, time.copy(components = listOf(
                earning.copy(kind = PayrollComponentKind.DEDUCTION),
            )), money).verifiedFor(org, period, employee)
        }
    }

    @Test
    fun noPolicyOrDisconnectedDomainNeverInventsAValidPayrun() {
        val noProviders = StaticListableBeanFactory()
        val assembler = PayrollVerifiedInputAssembler(
            noProviders.getBeanProvider(PayrollValidatedStatutoryPolicySourcePort::class.java),
            noProviders.getBeanProvider(PayrollApprovedCompensationSourcePort::class.java),
            noProviders.getBeanProvider(PayrollApprovedAttendanceSourcePort::class.java),
            noProviders.getBeanProvider(PayrollApprovedFinanceInputSourcePort::class.java),
            noProviders.getBeanProvider(PayrollVerifiedSeparationEligibilitySourcePort::class.java),
        )
        assertNull(assembler.policyFor(org, period))
        assertThrows<ProductApiException> {
            assembler.verifiedFor(org, period, employee)
        }
    }
}
