package com.hiltech.server.payroll

import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID

/** Restricted, read-only Finance settlement view for payroll reviewers. */
data class PayrollEmployeeFinanceStatusDto(
    val employeeId: String,
    val outcome: String,
)

data class Payroll9cRunFinanceStatusDto(
    val runVersionId: String,
    val status: String,
    val financeConnected: Boolean,
    val publicationApproved: Boolean,
    val employeeCount: Int,
    val settledCount: Int,
    val unresolvedCount: Int,
    val employeeOutcomes: List<PayrollEmployeeFinanceStatusDto>,
)

@Component
class Payroll9cRunFinanceStatusService(
    private val drafts: Payroll9bDraftService,
    private val authority: Payroll9aAuthorityPort,
    private val publication: ObjectProvider<PayrollRunPublicationPort>,
    private val finance: ObjectProvider<PayrollFinancePaymentResultsPort>,
) {
    fun status(
        actorUserId: UUID,
        runVersionId: UUID,
    ): Payroll9cRunFinanceStatusDto {
        // The draft service checks current payroll READ authorization before
        // returning any sensitive run/employee information.
        val run = drafts.read(actorUserId, runVersionId)
        val org = UUID.fromString(run.organizationId)
        if (!authority.canReview(actorUserId, org)) {
            Payroll9aPolicy.fail("PAYROLL_FINANCE_REVIEW_FORBIDDEN", HttpStatus.FORBIDDEN)
        }
        val publisher = publication.ifAvailable
        val approved = publisher?.canPublish(
            org, runVersionId, run.sourceFingerprint,
        ) == true
        if (!approved) return pending(run, "NOT_RELEASED", false, false)
        val financePort = finance.ifAvailable
            ?: return pending(run,
                if (run.lines.all { BigDecimal(it.net).signum() == 0 }) "NO_PAYMENT_REQUIRED" else "FINANCE_NOT_CONNECTED",
                false, true)

        val expected = financePort.expectedPayments(org, runVersionId)
        val events = financePort.paymentEvents(org, runVersionId)
        if (expected.isEmpty() && events.isNotEmpty()) {
            Payroll9aPolicy.fail("PAYROLL_FINANCE_UNEXPECTED_EVENT", HttpStatus.CONFLICT)
        }
        if (expected.isEmpty() && run.lines.any { BigDecimal(it.net).signum() > 0 }) {
            return pending(run, "PAYMENT_NOT_RECORDED", true, true)
        }
        Payroll9cExpectedBatchGuard.validate(
            org, runVersionId,
            run.lines.map {
                PayrollFrozenPaymentLine(
                    employeeId = UUID.fromString(it.employeeId),
                    net = BigDecimal(it.net),
                    currency = it.currency,
                )
            },
            expected,
        )
        val reconciled = if (expected.isEmpty()) null else
            Payroll9cReconciler.reconcile(org, runVersionId, expected, events)
        val noTransferEmployees = run.lines.filter { BigDecimal(it.net).signum() == 0 }
        return Payroll9cRunFinanceStatusDto(
            runVersionId = run.runVersionId,
            status = if (expected.isNotEmpty() && events.isEmpty()) "PAYMENT_NOT_RECORDED"
                else reconciled?.status?.name ?: "NO_PAYMENT_REQUIRED",
            financeConnected = true,
            publicationApproved = true,
            employeeCount = run.lines.size,
            settledCount = reconciled?.settledCount ?: 0,
            unresolvedCount = reconciled?.unresolvedCount ?: 0,
            employeeOutcomes = (
                (reconciled?.employees ?: emptyList()).map {
                    PayrollEmployeeFinanceStatusDto(
                        employeeId = it.employeeId.toString(),
                        outcome = if (events.none { event -> event.employeeId == it.employeeId }) "PAYMENT_NOT_RECORDED" else it.result.name,
                    )
                } + noTransferEmployees.map {
                    PayrollEmployeeFinanceStatusDto(
                        employeeId = it.employeeId,
                        outcome = "NOT_APPLICABLE",
                    )
                }
            ).sortedBy { it.employeeId },
        )
    }

    private fun pending(
        run: Payroll9bRunDto,
        status: String,
        financeConnected: Boolean,
        publicationApproved: Boolean,
    ) = Payroll9cRunFinanceStatusDto(
        runVersionId = run.runVersionId,
        status = status,
        financeConnected = financeConnected,
        publicationApproved = publicationApproved,
        employeeCount = run.lines.size,
        settledCount = 0,
        unresolvedCount = run.lines.count { BigDecimal(it.net).signum() > 0 },
        employeeOutcomes = if (status in setOf("PAYMENT_NOT_RECORDED", "FINANCE_NOT_CONNECTED", "NO_PAYMENT_REQUIRED")) run.lines.map {
            PayrollEmployeeFinanceStatusDto(it.employeeId, when {
                BigDecimal(it.net).signum() == 0 -> "NOT_APPLICABLE"
                status == "FINANCE_NOT_CONNECTED" -> "PAYMENT_STATUS_UNAVAILABLE"
                else -> "PAYMENT_NOT_RECORDED"
            })
        }.sortedBy { it.employeeId } else emptyList(),
    )
}
