package com.hiltech.server.finance

import com.hiltech.server.operationalaccess.OperationalAuthorityPort
import com.hiltech.server.operationalaccess.currentOperationalAuthority

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.documents.FinanceExpenseReceiptEvidenceTarget
import com.hiltech.server.documents.FinanceExpenseReceiptEvidenceTargetPort
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/** Existing Finance draft, People and Approval sources; no shadow ACL table. */
@Component
class JdbcFinanceExpenseReceiptEvidenceTarget @org.springframework.beans.factory.annotation.Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val approvals: ApprovalPersistencePort,
    private val clock: Clock,
    private val environment: Environment,
    private val operationalAuthority: OperationalAuthorityPort? = null,
) : FinanceExpenseReceiptEvidenceTargetPort {
    override fun loadTarget(expenseDraftId: UUID): FinanceExpenseReceiptEvidenceTarget? =
        jdbc.query(
            """
            SELECT d.id,d.organization_id,d.employee_id,d.created_by_user_id
            FROM finance_expense_claim_draft d
            WHERE d.id=?
            """.trimIndent(),
            { rs, _ -> FinanceExpenseReceiptEvidenceTarget(
                rs.getObject("id", UUID::class.java),
                rs.getObject("organization_id", UUID::class.java),
                rs.getObject("created_by_user_id", UUID::class.java),
                rs.getObject("employee_id", UUID::class.java),
            ) },
            expenseDraftId,
        ).singleOrNull()

    override fun canReserve(actor: UUID, target: FinanceExpenseReceiptEvidenceTarget): Boolean {
        if (!enabled() || target.createdByUserId != actor) return false
        if (!currentMember(actor, target.organizationId)) return false
        return currentEmployeeOwner(actor, target)
    }

    override fun canAccess(actor: UUID, target: FinanceExpenseReceiptEvidenceTarget): Boolean {
        if (!enabled() || !currentMember(actor, target.organizationId)) return false
        if (actor == target.createdByUserId && currentEmployeeOwner(actor, target)) return true
        if (!environment.getProperty(
                "hiltech.finance.employee-review.enabled", Boolean::class.java, false,
            )) return false
        val property = "hiltech.finance.employee-review.authority-key"
        return currentOperationalAuthority(operationalAuthority, approvals, actor, target.organizationId, property,
            environment.getProperty(property), clock.instant()) != null
    }

    private fun currentMember(actor: UUID, org: UUID): Boolean =
        approvals.isActiveOrganizationMember(org, actor, clock.instant())

    private fun currentEmployeeOwner(
        actor: UUID, target: FinanceExpenseReceiptEvidenceTarget,
    ): Boolean = jdbc.queryForObject(
        """
        SELECT EXISTS(
          SELECT 1 FROM finance_expense_claim_draft d
          JOIN employee e ON e.id=d.employee_id
            AND e.organization_id=d.organization_id
          JOIN employment em ON em.employee_id=e.id AND em.state='ACTIVE'
          JOIN user_identity u ON u.id=d.created_by_user_id
            AND u.person_id=e.person_id
          JOIN organization o ON o.id=d.organization_id
          WHERE d.id=? AND d.organization_id=? AND d.employee_id=?
            AND d.created_by_user_id=? AND u.id=? AND u.status='ACTIVE'
            AND e.state='ACTIVE' AND o.status='ACTIVE'
            AND em.start_date<=? AND em.end_date IS NULL
        )
        """.trimIndent(),
        Boolean::class.java,
        target.expenseDraftId, target.organizationId, target.employeeId,
        target.createdByUserId, actor,
        clock.instant().atOffset(ZoneOffset.UTC).toLocalDate(),
    ) == true

    private fun enabled(): Boolean = environment.getProperty(
        "hiltech.finance.expense-receipts.canonical-upload-ready",
        Boolean::class.java, false,
    )
}
