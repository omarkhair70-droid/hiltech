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
            ?: return pending(run, "FINANCE_NOT_CONNECTED", false, true)

        val expected = financePort.expectedPayments(org, runVersionId)
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
        val reconciled = Payroll9cReconciler.reconcile(
            org, runVersionId, expected,
            financePort.paymentEvents(org, runVersionId),
        )
        return Payroll9cRunFinanceStatusDto(
            runVersionId = run.runVersionId,
            status = reconciled.status.name,
            financeConnected = true,
            publicationApproved = true,
            employeeCount = run.lines.size,
            settledCount = reconciled.settledCount,
            unresolvedCount = reconciled.unresolvedCount,
            employeeOutcomes = reconciled.employees.map {
                PayrollEmployeeFinanceStatusDto(
                    employeeId = it.employeeId.toString(),
                    outcome = it.result.name,
                )
            },
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
        unresolvedCount = run.lines.size,
        employeeOutcomes = emptyList(),
    )
}
