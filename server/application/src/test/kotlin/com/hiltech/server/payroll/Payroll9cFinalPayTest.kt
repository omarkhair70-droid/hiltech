package com.hiltech.server.payroll

import com.hiltech.server.people.OffboardingCaseRecord
import com.hiltech.server.people.OffboardingCaseState
import com.hiltech.server.people.OffboardingClearanceRecord
import com.hiltech.server.people.OffboardingClearanceState
import com.hiltech.server.people.OffboardingClearanceType
import com.hiltech.server.people.OffboardingClearanceSource
import com.hiltech.server.people.OffboardingPersistencePort
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class Payroll9cFinalPayTest {
    private val org = UUID.randomUUID()
    private val employee = UUID.randomUUID()
    private val caseId = UUID.randomUUID()
    private val date = LocalDate.parse("2026-11-15")
    private val actor = UUID.randomUUID()
    private val verified = PayrollVerifiedFinalPayEvidence(
        organizationId = org, offboardingCaseId = caseId,
        offboardingCaseVersion = 3, employeeId = employee,
        lastWorkingDate = date, payrollRunVersionId = UUID.randomUUID(),
        payrollRunSourceFingerprint = "a".repeat(64),
        approval = PayrollInputApproval.APPROVED,
        compensationClearanceRef = "synthetic-hr-clearance",
        compensationSourceVersion = 1,
        financeDeductionsClearanceRef = "synthetic-finance-advance-clearance",
        financeDeductionsSourceVersion = 2,
        statutoryClearanceRef = "synthetic-accountant-final-pay",
        statutoryClearanceVersion = 1,
    )
    @Test
    fun caseSourceVersionsAndLegalInputsMustBeAttested() {
        fun valid(e: PayrollVerifiedFinalPayEvidence) =
            PayrollFinalPayReadinessPolicy.isValid(org, caseId, 3, employee, date, e)
        assertTrue(valid(verified))
        assertFalse(valid(verified.copy(offboardingCaseVersion = 2)))
        assertFalse(valid(verified.copy(lastWorkingDate = date.minusDays(1))))
        assertFalse(valid(verified.copy(employeeId = UUID.randomUUID())))
        assertFalse(valid(verified.copy(organizationId = UUID.randomUUID())))
        assertFalse(valid(verified.copy(approval = PayrollInputApproval.PENDING)))
        assertFalse(valid(verified.copy(financeDeductionsClearanceRef = "")))
        assertFalse(valid(verified.copy(financeDeductionsSourceVersion = 0)))
        assertFalse(valid(verified.copy(statutoryClearanceRef = "")))
        assertFalse(valid(verified.copy(compensationSourceVersion = 0)))
    }

    @Test
    fun offboardingWithoutFinanceClearanceNeverInventsFinalWages() {
        val casePort = Mockito.mock(OffboardingPersistencePort::class.java)
        val record = OffboardingCaseRecord(
            caseId = caseId, operationId = UUID.randomUUID(),
            organizationId = org, employeeId = employee,
            state = OffboardingCaseState.OPEN, lastWorkingDate = date,
            reasonCategoryCode = "SYNTHETIC_LEAVING", note = null,
            startedAt = Instant.parse("2026-11-13T10:00:00Z"),
            startedByUserId = actor,
            completedAt = null, completedByUserId = null, version = 3,
        )
        Mockito.`when`(casePort.case(caseId, false)).thenReturn(record)
        Mockito.`when`(casePort.clearances(caseId)).thenReturn(
            listOf(
                OffboardingClearanceRecord(
                    UUID.randomUUID(), caseId, OffboardingClearanceType.HR,
                    OffboardingClearanceState.CLEAR, null,
                    Instant.parse("2026-11-13T10:00:00Z"), actor,
                    OffboardingClearanceSource.PEOPLE, 1,
                ),
                OffboardingClearanceRecord(
                    UUID.randomUUID(), caseId, OffboardingClearanceType.FINANCE,
                    OffboardingClearanceState.PENDING, null, null, null,
                    OffboardingClearanceSource.PEOPLE, 1,
                ),
            ),
        )
        val authority = object : Payroll9aAuthorityPort {
            override fun canRead(actorId: UUID, organizationId: UUID) =
                actorId == actor && organizationId == org
            override fun canPrepare(actorId: UUID, organizationId: UUID) =
                canRead(actorId, organizationId)
            override fun canReview(actorId: UUID, organizationId: UUID) =
                canRead(actorId, organizationId)
        }
        val none = StaticListableBeanFactory()
        val service = Payroll9cFinalPayService(
            casePort, authority,
            none.getBeanProvider(PayrollVerifiedFinalPayEvidencePort::class.java),
            none.getBeanProvider(PayrollRunPublicationPort::class.java),
            none.getBeanProvider(PayrollFinancePaymentResultsPort::class.java),
            JdbcTemplate(),
        )
        assertThrows<ProductApiException> {
            service.readiness(UUID.randomUUID(), caseId)
        }
        val blocked = service.readiness(actor, caseId)
        assertEquals("REQUIRES_REVIEW", blocked.status)
        assertTrue("FINANCE_CLEARANCE_NOT_CONFIRMED" in blocked.blockers)
        assertTrue("VERIFIED_FINAL_PAY_SOURCE_MISSING_OR_STALE" in blocked.blockers)
        assertEquals(null, blocked.frozenNet)
        assertEquals(null, blocked.paymentStatus)
        assertEquals(null, blocked.releasedRunVersionId)
    }
}
