package com.hiltech.server.finance

import com.hiltech.server.operationalaccess.OperationalAuthorityPort
import com.hiltech.server.operationalaccess.currentOperationalAuthority

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A very deliberately READ-ONLY queue for explicitly appointed Finance
 * reviewers. Merely seeing employee draft money requests grants no power
 * to approve, transform to payable, fund, allocate, settle or deduct payroll.
 */
data class FinanceEmployeeReviewItem(
    val id: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val employeeCode: String,
    val kind: String,
    val amount: String,
    val currency: String,
    val purpose: String,
    val state: String,
    val projectId: UUID?,
    val createdAt: String,
    val approvable: Boolean = false,
    val fundable: Boolean = false,
)

@Service
class FinanceEmployeeReviewService @org.springframework.beans.factory.annotation.Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val approvals: ApprovalPersistencePort,
    private val clock: Clock,
    private val environment: Environment,
    private val operationalAuthority: OperationalAuthorityPort? = null,
) {
    fun list(actor: UUID, organizationId: UUID): List<FinanceEmployeeReviewItem> {
        authorized(actor, organizationId)
        return jdbc.query(
            """
            SELECT r.id,r.organization_id,r.employee_id,e.employee_code,
                   r.kind,r.amount,r.currency,r.purpose,r.state,
                   r.project_id,r.created_at
            FROM (
                SELECT id,organization_id,employee_id,amount,currency,purpose,
                       state,project_id,created_at,
                       'EXPENSE_REIMBURSEMENT' AS kind
                FROM finance_expense_claim_draft WHERE organization_id=?
                UNION ALL
                SELECT id,organization_id,employee_id,requested_amount AS amount,
                       currency,purpose,state,project_id,created_at,
                       'EMPLOYEE_ADVANCE' AS kind
                FROM finance_employee_advance_draft WHERE organization_id=?
                UNION ALL
                SELECT id,organization_id,custodian_employee_id AS employee_id,
                       requested_amount AS amount,currency,purpose,state,
                       project_id,created_at,'FINANCIAL_IMPREST' AS kind
                FROM finance_financial_imprest_draft WHERE organization_id=?
            ) r
            JOIN employee e ON e.id=r.employee_id
             AND e.organization_id=r.organization_id
            ORDER BY r.created_at DESC, r.id DESC
            LIMIT 100
            """.trimIndent(),
            { rs, _ -> FinanceEmployeeReviewItem(
                rs.getObject("id", UUID::class.java),
                rs.getObject("organization_id", UUID::class.java),
                rs.getObject("employee_id", UUID::class.java),
                rs.getString("employee_code"),
                rs.getString("kind"), rs.getBigDecimal("amount").toPlainString(),
                rs.getString("currency"), rs.getString("purpose"),
                rs.getString("state"), rs.getObject("project_id", UUID::class.java),
                rs.getObject("created_at", OffsetDateTime::class.java).toInstant().toString(),
            ) }, organizationId, organizationId, organizationId,
        )
    }

    private fun authorized(actor: UUID, org: UUID) {
        if (!environment.getProperty("hiltech.finance.employee-review.enabled", Boolean::class.java, false)) denied()
        val property = "hiltech.finance.employee-review.authority-key"
        currentOperationalAuthority(operationalAuthority, approvals, actor, org, property,
            environment.getProperty(property), clock.instant()) ?: denied()
    }

    private fun denied(): Nothing = throw ProductApiException(
        "FINANCE_EMPLOYEE_REVIEW_UNAVAILABLE",
        "Finance employee review unavailable or unauthorized.", HttpStatus.NOT_FOUND,
    )
}

@RestController
class FinanceEmployeeReviewController(private val reviews: FinanceEmployeeReviewService) {
    @GetMapping("/v1/finance/organizations/{orgId}/employee-draft-review")
    fun list(req: HttpServletRequest, @PathVariable orgId: UUID): List<FinanceEmployeeReviewItem> =
        reviews.list(
            HiltechRequestContext.current(req).identityId
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: throw ProductApiException(
                    "UNAUTHENTICATED", "Authentication required.", HttpStatus.UNAUTHORIZED,
                ),
            orgId,
        )
}
