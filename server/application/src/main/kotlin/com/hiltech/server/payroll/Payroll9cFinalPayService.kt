package com.hiltech.server.payroll

import com.hiltech.server.people.OffboardingCaseState
import com.hiltech.server.people.OffboardingClearanceState
import com.hiltech.server.people.OffboardingClearanceType
import com.hiltech.server.people.OffboardingPersistencePort
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Final pay is linked to the existing offboarding case, not a second People
 * lifecycle. HR/accountant/Finance must attest its inputs and legal treatment
 * without inventing severance, leave, indemnity or tax formulas.
 */
data class PayrollVerifiedFinalPayEvidence(
    val organizationId: UUID,
    val offboardingCaseId: UUID,
    val offboardingCaseVersion: Long,
    val employeeId: UUID,
    val lastWorkingDate: LocalDate,
    val payrollRunVersionId: UUID,
    val payrollRunSourceFingerprint: String,
    val approval: PayrollInputApproval,
    val compensationClearanceRef: String,
    val compensationSourceVersion: Long,
    val financeDeductionsClearanceRef: String,
    val financeDeductionsSourceVersion: Long,
    val statutoryClearanceRef: String,
    val statutoryClearanceVersion: Long,
)

interface PayrollVerifiedFinalPayEvidencePort {
    fun verifiedFor(
        organizationId: UUID,
        offboardingCaseId: UUID,
    ): PayrollVerifiedFinalPayEvidence?
}

data class PayrollFinalPayReadinessDto(
    val offboardingCaseId: String,
    val employeeId: String,
    val employeeState: String,
    val offboardingState: String,
    val lastWorkingDate: String,
    val payrollClearanceState: String?,
    val financeClearanceState: String?,
    val hrClearanceState: String?,
    val status: String,
    val blockers: List<String>,
    val releasedRunVersionId: String?,
    val currency: String?,
    val frozenNet: String?,
    val paymentStatus: String?,
)

/** Isolated policy so incomplete/off-date evidence can be tested without a DB. */
object PayrollFinalPayReadinessPolicy {
    fun isValid(
        organizationId: UUID,
        caseId: UUID,
        caseVersion: Long,
        employeeId: UUID,
        lastWorkingDate: LocalDate,
        evidence: PayrollVerifiedFinalPayEvidence,
    ): Boolean =
        evidence.organizationId == organizationId &&
        evidence.offboardingCaseId == caseId &&
        evidence.offboardingCaseVersion == caseVersion &&
        evidence.employeeId == employeeId &&
        evidence.lastWorkingDate == lastWorkingDate &&
        evidence.approval == PayrollInputApproval.APPROVED &&
        evidence.compensationClearanceRef.isNotBlank() &&
        evidence.compensationSourceVersion >= 1 &&
        evidence.financeDeductionsClearanceRef.isNotBlank() &&
        evidence.financeDeductionsSourceVersion >= 1 &&
        evidence.statutoryClearanceRef.isNotBlank() &&
        evidence.statutoryClearanceVersion >= 1
}

private data class FinalRunLine(
    val runId: UUID,
    val fingerprint: String,
    val currency: String,
    val net: BigDecimal,
)

/**
 * Restricted operational read. It does not change an offboarding clearance,
 * infer a legal final salary or create a Finance payment instruction.
 */
@Component
class Payroll9cFinalPayService(
    private val cases: OffboardingPersistencePort,
    private val authority: Payroll9aAuthorityPort,
    private val evidenceSource: ObjectProvider<PayrollVerifiedFinalPayEvidencePort>,
    private val publications: ObjectProvider<PayrollRunPublicationPort>,
    private val finance: ObjectProvider<PayrollFinancePaymentResultsPort>,
    private val jdbc: JdbcTemplate,
) {
    fun readiness(actorId: UUID, caseId: UUID): PayrollFinalPayReadinessDto {
        val case = cases.case(caseId)
            ?: Payroll9aPolicy.fail("PAYROLL_FINAL_CASE_NOT_FOUND", HttpStatus.NOT_FOUND)
        if (!authority.canReview(actorId, case.organizationId)) {
            Payroll9aPolicy.fail("PAYROLL_FINAL_FORBIDDEN", HttpStatus.FORBIDDEN)
        }
        val clearances = cases.clearances(caseId).associateBy { it.type }
        val hr = clearances[OffboardingClearanceType.HR]?.state
        val financial = clearances[OffboardingClearanceType.FINANCE]?.state
        val payroll = clearances[OffboardingClearanceType.PAYROLL]?.state
        val blockers = mutableListOf<String>()
        if (hr != OffboardingClearanceState.CLEAR) {
            blockers += "HR_CLEARANCE_NOT_CONFIRMED"
        }
        if (financial != OffboardingClearanceState.CLEAR) {
            blockers += "FINANCE_CLEARANCE_NOT_CONFIRMED"
        }
        val evidence = evidenceSource.ifAvailable
            ?.verifiedFor(case.organizationId, caseId)
        if (evidence == null || !PayrollFinalPayReadinessPolicy.isValid(
                case.organizationId, caseId, case.version,
                case.employeeId, case.lastWorkingDate, evidence,
            )
        ) {
            blockers += "VERIFIED_FINAL_PAY_SOURCE_MISSING_OR_STALE"
        }

        var run: FinalRunLine? = null
        var paymentStatus: String? = null
        if (evidence != null && PayrollFinalPayReadinessPolicy.isValid(
                case.organizationId, caseId, case.version,
                case.employeeId, case.lastWorkingDate, evidence,
            )
        ) {
            run = jdbc.query(
                """
                SELECT rv.id AS run_id, rv.source_fingerprint, l.currency, l.net
                FROM payroll_run_version rv
                JOIN payroll_run_release released
                  ON released.run_version_id = rv.id
                 AND released.organization_id = rv.organization_id
                JOIN payroll_run_employee_line l
                  ON l.run_version_id = rv.id
                JOIN payroll_period p
                  ON p.id = rv.payroll_period_id
                WHERE rv.id = ? AND rv.organization_id = ?
                  AND l.employee_id = ?
                  AND p.cutoff_date >= ?
                """.trimIndent(),
                { rs, _ ->
                    FinalRunLine(
                        runId = rs.getObject("run_id", UUID::class.java),
                        fingerprint = rs.getString("source_fingerprint"),
                        currency = rs.getString("currency"),
                        net = rs.getBigDecimal("net"),
                    )
                },
                evidence.payrollRunVersionId, case.organizationId,
                case.employeeId, case.lastWorkingDate,
            ).singleOrNull()
            if (run == null ||
                run.fingerprint != evidence.payrollRunSourceFingerprint ||
                publications.ifAvailable?.canPublish(
                    case.organizationId, evidence.payrollRunVersionId,
                    evidence.payrollRunSourceFingerprint,
                ) != true
            ) {
                run = null
                blockers += "FINAL_PAY_RUN_NOT_RELEASED_OR_STALE"
            }
            if (run != null) {
                val financePort = finance.ifAvailable
                if (financePort == null) {
                    blockers += "FINANCE_PAYMENT_RESULT_NOT_CONNECTED"
                } else {
                    // Validate the Finance batch without ever marking anything
                    // paid merely because a payment was prepared.
                    val expected = financePort.expectedPayments(case.organizationId, run.runId)
                    // Incomplete coworker instructions or mismatched frozen
                    // totals also invalidate this employee's final-pay batch.
                    val fullyMatched = try {
                        Payroll9cExpectedBatchGuard.validateAgainstDatabase(
                            jdbc, case.organizationId, run.runId, expected,
                        )
                        true
                    } catch (_: com.hiltech.server.platform.ProductApiException) {
                        false
                    }
                    if (!fullyMatched) {
                        blockers += "FINAL_PAY_FINANCE_INSTRUCTION_NOT_MATCHED"
                    } else {
                        val events = financePort.paymentEvents(
                            case.organizationId, run.runId,
                        )
                        if (expected.isEmpty() && events.isNotEmpty()) {
                            blockers += "FINAL_PAY_FINANCE_UNEXPECTED_EVENT"
                        } else {
                            // The employee has no bank transfer due when
                            // frozen net is zero; never label this PAID.
                            val result = if (expected.isEmpty()) null else
                                Payroll9cReconciler.reconcile(
                                    case.organizationId, run.runId, expected, events,
                                )
                            val differential=financePort.differentialPaymentBasis(case.organizationId,run.runId)
                            val employeeDelta=differential?.takeIf{it.available}?.lines?.singleOrNull{it.employeeId==case.employeeId}
                            paymentStatus = if (run.net.signum() == 0)
                                "NO_PAYMENT_REQUIRED"
                            else if(employeeDelta!=null&&employeeDelta.currency==run.currency&&employeeDelta.net.signum()==0)PayrollFinanceOutcome.SUCCESS.name
                            else result?.employees?.singleOrNull {
                                it.employeeId == case.employeeId
                            }?.result?.name
                            if (run.net.signum() > 0 &&
                                paymentStatus != PayrollFinanceOutcome.SUCCESS.name
                            ) {
                                blockers += "FINAL_PAY_PAYMENT_NOT_CONFIRMED"
                            }
                        }
                    }
                }
            }
        }
        return PayrollFinalPayReadinessDto(
            offboardingCaseId = caseId.toString(),
            employeeId = case.employeeId.toString(),
            employeeState = if (case.state == OffboardingCaseState.COMPLETED)
                "OFFBOARDED" else "OFFBOARDING",
            offboardingState = case.state.name,
            lastWorkingDate = case.lastWorkingDate.toString(),
            payrollClearanceState = payroll?.name,
            financeClearanceState = financial?.name,
            hrClearanceState = hr?.name,
            status = when {
                blockers.isNotEmpty() -> "REQUIRES_REVIEW"
                paymentStatus == "NO_PAYMENT_REQUIRED" ->
                    "NO_PAYMENT_REQUIRED_FOR_CLEARANCE_REVIEW"
                else -> "PAYMENT_CONFIRMED_FOR_CLEARANCE_REVIEW"
            },
            blockers = blockers,
            releasedRunVersionId = run?.runId?.toString(),
            currency = run?.currency,
            frozenNet = run?.net?.toPlainString(),
            paymentStatus = paymentStatus,
        )
    }
}
