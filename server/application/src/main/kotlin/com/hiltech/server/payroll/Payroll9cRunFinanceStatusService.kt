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
        val ordinaryLines=run.lines.map{PayrollFrozenPaymentLine(UUID.fromString(it.employeeId),BigDecimal(it.net),it.currency)}
        val differential=financePort.differentialPaymentBasis(org,runVersionId)
        val originalByEmployee=ordinaryLines.associateBy{it.employeeId}
        if(differential!=null&&(!differential.available||differential.lines.size!=ordinaryLines.size||
            differential.lines.map{it.employeeId}.toSet()!=originalByEmployee.keys||
            differential.lines.any{line->val original=originalByEmployee[line.employeeId]
                original==null||line.currency!=original.currency||line.net.signum()<0||line.net>original.net})) {
            return pending(run,"REQUIRES_REVIEW",true,true).copy(unresolvedCount=run.lines.size,
                employeeOutcomes=run.lines.map{PayrollEmployeeFinanceStatusDto(it.employeeId,"PAYMENT_STATUS_UNAVAILABLE")}.sortedBy{it.employeeId})
        }
        val payableLines=differential?.lines?:ordinaryLines
        val recoveryEmployees=differential?.recoveryEmployees.orEmpty()
        if(recoveryEmployees.any{id->payableLines.none{it.employeeId==id&&it.net.signum()==0}})
            return pending(run,"REQUIRES_REVIEW",true,true).copy(unresolvedCount=run.lines.size,
                employeeOutcomes=run.lines.map{PayrollEmployeeFinanceStatusDto(it.employeeId,"PAYMENT_STATUS_UNAVAILABLE")}.sortedBy{it.employeeId})
        val zeroDifference=differential?.lines.orEmpty().filter{it.net.signum()==0}.map{it.employeeId}.toSet()-recoveryEmployees
        if (expected.isEmpty() && events.isNotEmpty()) {
            Payroll9aPolicy.fail("PAYROLL_FINANCE_UNEXPECTED_EVENT", HttpStatus.CONFLICT)
        }
        if (expected.isEmpty() && payableLines.any { it.net.signum() > 0 }) {
            val pending=pending(run, "PAYMENT_NOT_RECORDED", true, true)
            return if(differential==null)pending else pending.copy(
                status=if(recoveryEmployees.isEmpty())pending.status else "REQUIRES_REVIEW",
                settledCount=zeroDifference.size,unresolvedCount=payableLines.count{it.net.signum()>0}+recoveryEmployees.size,
                employeeOutcomes=payableLines.map{PayrollEmployeeFinanceStatusDto(it.employeeId.toString(),when{
                    it.employeeId in recoveryEmployees->"PAYMENT_STATUS_UNAVAILABLE"
                    it.employeeId in zeroDifference->"SUCCESS"
                    else->"PAYMENT_NOT_RECORDED"})}.sortedBy{it.employeeId})
        }
        Payroll9cExpectedBatchGuard.validate(org,runVersionId,payableLines,expected)
        val reconciled = if (expected.isEmpty()) null else
            Payroll9cReconciler.reconcile(org, runVersionId, expected, events)
        val noTransferEmployees = payableLines.filter { it.net.signum()==0 }
        return Payroll9cRunFinanceStatusDto(
            runVersionId = run.runVersionId,
            status = if(recoveryEmployees.isNotEmpty())"REQUIRES_REVIEW"
                else if (expected.isNotEmpty() && events.isEmpty()) "PAYMENT_NOT_RECORDED"
                else reconciled?.status?.name ?: if(differential!=null)"PAID" else "NO_PAYMENT_REQUIRED",
            financeConnected = true,
            publicationApproved = true,
            employeeCount = run.lines.size,
            settledCount = (reconciled?.settledCount ?: 0)+zeroDifference.size,
            unresolvedCount = (reconciled?.unresolvedCount ?: 0)+recoveryEmployees.size,
            employeeOutcomes = (
                (reconciled?.employees ?: emptyList()).map {
                    PayrollEmployeeFinanceStatusDto(
                        employeeId = it.employeeId.toString(),
                        outcome = if (events.none { event -> event.employeeId == it.employeeId }) "PAYMENT_NOT_RECORDED" else it.result.name,
                    )
                } + noTransferEmployees.map {
                    PayrollEmployeeFinanceStatusDto(
                        employeeId = it.employeeId.toString(),
                        outcome = when{
                            it.employeeId in recoveryEmployees->"PAYMENT_STATUS_UNAVAILABLE"
                            it.employeeId in zeroDifference->"SUCCESS"
                            else->"NOT_APPLICABLE"
                        },
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
