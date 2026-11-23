package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class CreateExpenseClaimDraftRequest(
    val operationId: UUID, val amount: String, val currency: String,
    val purpose: String, val merchant: String, val spentOn: LocalDate,
    val projectId: UUID? = null,
)
data class CreateEmployeeAdvanceDraftRequest(
    val operationId: UUID, val requestedAmount: String, val currency: String,
    val purpose: String, val projectId: UUID? = null,
    val proposedSettlementNote: String? = null,
)
data class CreateFinancialImprestDraftRequest(
    val operationId: UUID, val requestedAmount: String, val currency: String,
    val purpose: String, val projectId: UUID? = null,
    val proposedSettlementOn: LocalDate? = null,
)
data class FinanceEmployeeDraftView(
    val id: UUID, val organizationId: UUID, val employeeId: UUID,
    val kind: String, val amount: String, val currency: String,
    val purpose: String, val state: String, val version: Long,
    val projectId: UUID?, val createdAt: String,
    val merchant: String? = null, val spentOn: LocalDate? = null,
    val proposedSettlementNote: String? = null,
    val proposedSettlementOn: LocalDate? = null,
    val financialActionAllowed: Boolean = false,
    val replayed: Boolean = false,
)

private enum class FinanceDraftKind(
    val table: String, val kindCode: String, val amountColumn: String,
    val employeeColumn: String, val stateCode: String,
) {
    EXPENSE("finance_expense_claim_draft", "EXPENSE_REIMBURSEMENT",
        "amount", "employee_id", "DRAFT_RECEIPT_PENDING"),
    ADVANCE("finance_employee_advance_draft", "EMPLOYEE_ADVANCE",
        "requested_amount", "employee_id", "DRAFT_POLICY_PENDING"),
    IMPREST("finance_financial_imprest_draft", "FINANCIAL_IMPREST",
        "requested_amount", "custodian_employee_id", "DRAFT_CUSTODY_POLICY_PENDING"),
}

private data class CurrentEmployee(val id: UUID)

/**
 * Strict self-service drafts only; no direct finance reviewer, owner or
 * payroll privilege is granted by an employee's ability to draft.
 *
 * Expense / Advance / Imprest use three independent source tables and
 * distinct future accounting workflows. There is NO payout, advance issue,
 * employee borrowing balance, custody funding or payroll deduction here.
 */
@Service
class FinanceEmployeeDraftService(
    private val jdbc: JdbcTemplate,
    private val approvals: ApprovalPersistencePort,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    private val environment: Environment,
) {
    fun expenses(actor: UUID, org: UUID) = list(actor, org, FinanceDraftKind.EXPENSE)
    fun expense(actor: UUID, org: UUID, id: UUID): FinanceEmployeeDraftView =
        detail(actor, org, FinanceDraftKind.EXPENSE, id)
    fun advances(actor: UUID, org: UUID) = list(actor, org, FinanceDraftKind.ADVANCE)
    fun imprests(actor: UUID, org: UUID) = list(actor, org, FinanceDraftKind.IMPREST)

    fun createExpense(
        actor: UUID, org: UUID, correlation: String, idempotencyKey: String?,
        req: CreateExpenseClaimDraftRequest,
    ): FinanceEmployeeDraftView {
        configured()
        val amount = parseAmount(req.amount)
        val currency = currency(req.currency)
        val purpose = purpose(req.purpose)
        val merchant = req.merchant.trim()
        if (merchant.length !in 2..160 || merchant.any { it.isISOControl() }) invalid()
        val employee = currentEmployee(actor, org)
        return create(actor, org, correlation, idempotencyKey, req.operationId,
            FinanceDraftKind.EXPENSE, employee, amount, currency, purpose,
            req.projectId, merchant, req.spentOn, null, null)
    }

    fun createAdvance(
        actor: UUID, org: UUID, correlation: String, idempotencyKey: String?,
        req: CreateEmployeeAdvanceDraftRequest,
    ): FinanceEmployeeDraftView {
        configured()
        val amount = parseAmount(req.requestedAmount)
        val currency = currency(req.currency)
        val purpose = purpose(req.purpose)
        val note = req.proposedSettlementNote?.trim()?.takeIf { it.isNotEmpty() }
        if (note != null && (note.length > 500 || note.any { it.isISOControl() })) invalid()
        val employee = currentEmployee(actor, org)
        return create(actor, org, correlation, idempotencyKey, req.operationId,
            FinanceDraftKind.ADVANCE, employee, amount, currency, purpose,
            req.projectId, null, null, note, null)
    }

    fun createImprest(
        actor: UUID, org: UUID, correlation: String, idempotencyKey: String?,
        req: CreateFinancialImprestDraftRequest,
    ): FinanceEmployeeDraftView {
        configured()
        val amount = parseAmount(req.requestedAmount)
        val currency = currency(req.currency)
        val purpose = purpose(req.purpose)
        val employee = currentEmployee(actor, org)
        return create(actor, org, correlation, idempotencyKey, req.operationId,
            FinanceDraftKind.IMPREST, employee, amount, currency, purpose,
            req.projectId, null, null, null, req.proposedSettlementOn)
    }

    private fun create(
        actor: UUID, org: UUID, correlation: String, idempotencyKey: String?,
        op: UUID, kind: FinanceDraftKind, expected: CurrentEmployee,
        amount: BigDecimal, ccy: String, purpose: String, projectId: UUID?,
        merchant: String?, spentOn: LocalDate?, settlementNote: String?,
        settlementOn: LocalDate?,
    ): FinanceEmployeeDraftView {
        IdempotencyKeyContract.requireMatches(idempotencyKey, op)
        val id = UUID.nameUUIDFromBytes(
            "hiltech:finance-${kind.kindCode}:$op".toByteArray(Charsets.UTF_8),
        )
        val fingerprint = sha(
            kind.kindCode, org, expected.id, amount.stripTrailingZeros(),
            ccy, purpose, projectId, merchant, spentOn, settlementNote, settlementOn,
        )
        val result = commands.execute(IdempotentCommandSpec(
            op, actor, "FINANCE_${kind.kindCode}_DRAFT_RECORDED",
            "FINANCE_${kind.kindCode}_DRAFT", id, fingerprint, correlation,
        )) {
            // All conditions are re-read inside the idempotent transaction.
            val employee = currentEmployee(actor, org, lock = true)
            if (employee != expected) unavailable()
            checkProject(org, projectId)
            val at = clock.instant()
            when (kind) {
                FinanceDraftKind.EXPENSE -> jdbc.update(
                    """
                    INSERT INTO finance_expense_claim_draft(
                      id,operation_id,organization_id,employee_id,project_id,
                      amount,currency,purpose,merchant,spent_on,
                      created_by_user_id,created_at,correlation_id
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """.trimIndent(), id,op,org,employee.id,projectId,amount,ccy,purpose,
                    merchant,spentOn,actor,at.atOffset(ZoneOffset.UTC),correlation,
                )
                FinanceDraftKind.ADVANCE -> jdbc.update(
                    """
                    INSERT INTO finance_employee_advance_draft(
                      id,operation_id,organization_id,employee_id,project_id,
                      requested_amount,currency,purpose,proposed_settlement_note,
                      created_by_user_id,created_at,correlation_id
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """.trimIndent(), id,op,org,employee.id,projectId,amount,ccy,purpose,
                    settlementNote,actor,at.atOffset(ZoneOffset.UTC),correlation,
                )
                FinanceDraftKind.IMPREST -> jdbc.update(
                    """
                    INSERT INTO finance_financial_imprest_draft(
                      id,operation_id,organization_id,custodian_employee_id,project_id,
                      requested_amount,currency,purpose,proposed_settlement_on,
                      created_by_user_id,created_at,correlation_id
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """.trimIndent(), id,op,org,employee.id,projectId,amount,ccy,purpose,
                    settlementOn,actor,at.atOffset(ZoneOffset.UTC),correlation,
                )
            }.let { check(it == 1) }
            audit.append(AuditEventRecord(
                actorUserId = actor,
                action = "FINANCE_${kind.kindCode}_DRAFT_RECORDED",
                targetType = "FINANCE_${kind.kindCode}_DRAFT", targetId = id,
                occurredAt = at, correlationId = correlation,
                reason = "SELF_SERVICE_DRAFT_NO_APPROVAL_FUNDING_OR_PAYROLL",
            ))
            IdempotentCommandOutcome("FINANCE_DRAFT_RECORDED", """{"id":"$id"}""")
        }
        return detail(actor, org, kind, id).copy(replayed = result.replayed)
    }

    private fun currentEmployee(actor: UUID, org: UUID, lock: Boolean = false): CurrentEmployee {
        configured()
        val now = clock.instant()
        if (!approvals.isActiveOrganizationMember(org, actor, now)) unavailable()
        val sql = """
            SELECT e.id FROM employee e
            JOIN user_identity u ON u.person_id=e.person_id
            JOIN employment em ON em.employee_id=e.id AND em.state='ACTIVE'
            JOIN organization o ON o.id=e.organization_id
            WHERE u.id=? AND u.status='ACTIVE' AND e.organization_id=?
              AND e.state='ACTIVE' AND o.status='ACTIVE'
              AND em.start_date<=? AND em.end_date IS NULL
            ${if (lock) "FOR UPDATE OF e" else ""}
        """.trimIndent()
        return jdbc.query(sql,
            { rs, _ -> CurrentEmployee(rs.getObject("id", UUID::class.java)) },
            actor, org, now.atOffset(ZoneOffset.UTC).toLocalDate(),
        ).singleOrNull() ?: unavailable()
    }

    private fun checkProject(org: UUID, projectId: UUID?) {
        if (projectId == null) return
        val found = jdbc.query(
            "SELECT id FROM project WHERE id=? AND organization_id=? FOR KEY SHARE",
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            projectId, org,
        ).singleOrNull()
        if (found == null) unavailable()
    }

    private fun list(actor: UUID, org: UUID, kind: FinanceDraftKind): List<FinanceEmployeeDraftView> {
        val owner = currentEmployee(actor, org)
        return jdbc.query(
            select(kind) + " WHERE organization_id=? AND ${kind.employeeColumn}=? " +
                "ORDER BY created_at DESC LIMIT 100",
            { rs, _ -> map(kind, rs) },
            org, owner.id,
        )
    }

    private fun detail(
        actor: UUID, org: UUID, kind: FinanceDraftKind, id: UUID,
    ): FinanceEmployeeDraftView {
        val owner = currentEmployee(actor, org)
        return jdbc.query(
            select(kind) + " WHERE organization_id=? AND ${kind.employeeColumn}=? AND id=?",
            { rs, _ -> map(kind, rs) }, org, owner.id, id,
        ).singleOrNull() ?: unavailable()
    }

    // Whitelisted SQL fragments only; never expose a table name to the client.
    private fun select(kind: FinanceDraftKind): String {
        val details = when (kind) {
            FinanceDraftKind.EXPENSE ->
                "merchant,spent_on,NULL::varchar AS proposed_settlement_note," +
                    "NULL::date AS proposed_settlement_on"
            FinanceDraftKind.ADVANCE ->
                "NULL::varchar AS merchant,NULL::date AS spent_on," +
                    "proposed_settlement_note,NULL::date AS proposed_settlement_on"
            FinanceDraftKind.IMPREST ->
                "NULL::varchar AS merchant,NULL::date AS spent_on," +
                    "NULL::varchar AS proposed_settlement_note,proposed_settlement_on"
        }
        return """
            SELECT id,organization_id,${kind.employeeColumn} AS employee_id,
              ${kind.amountColumn} AS amount,currency,purpose,project_id,
              state,version,created_at,$details FROM ${kind.table}
        """.trimIndent()
    }

    private fun map(kind: FinanceDraftKind, rs: ResultSet): FinanceEmployeeDraftView =
        FinanceEmployeeDraftView(
            id = rs.getObject("id", UUID::class.java),
            organizationId = rs.getObject("organization_id", UUID::class.java),
            employeeId = rs.getObject("employee_id", UUID::class.java),
            kind = kind.kindCode,
            amount = rs.getBigDecimal("amount").toPlainString(),
            currency = rs.getString("currency"), purpose = rs.getString("purpose"),
            state = rs.getString("state"), version = rs.getLong("version"),
            projectId = rs.getObject("project_id", UUID::class.java),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant().toString(),
            merchant = rs.getString("merchant"),
            spentOn = rs.getDate("spent_on")?.toLocalDate(),
            proposedSettlementNote = rs.getString("proposed_settlement_note"),
            proposedSettlementOn = rs.getDate("proposed_settlement_on")?.toLocalDate(),
        )

    private fun parseAmount(raw: String): BigDecimal {
        val decimal = runCatching { BigDecimal(raw.trim()) }.getOrElse { invalid() }
        if (decimal.signum() <= 0 || decimal.precision() > 18 || decimal.scale() > 6 ||
            decimal.scale() < -6) invalid()
        return decimal
    }

    private fun currency(raw: String): String {
        if (!Regex("^[A-Z]{3}$").matches(raw)) invalid()
        return raw
    }

    private fun purpose(raw: String): String {
        val clean = raw.trim()
        if (clean.length !in 5..600 || clean.any { it.isISOControl() }) invalid()
        return clean
    }

    private fun configured() {
        if (!environment.getProperty("hiltech.finance.employee-requests.enabled",
                Boolean::class.java, false)) unavailable()
    }

    private fun sha(vararg values: Any?): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("\u001f") { it?.toString() ?: "<null>" }
            .toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun unavailable(): Nothing = throw ProductApiException(
        "FINANCE_EMPLOYEE_DRAFT_UNAVAILABLE",
        "Draft unavailable or current employee access not authorized.", HttpStatus.NOT_FOUND,
    )
    private fun invalid(): Nothing = throw ProductApiException(
        "FINANCE_EMPLOYEE_DRAFT_INVALID",
        "Financial draft input is invalid.", HttpStatus.BAD_REQUEST,
    )
}

@RestController
class FinanceEmployeeDraftController(private val service: FinanceEmployeeDraftService) {
    @GetMapping("/v1/finance/me/organizations/{orgId}/expenses")
    fun expenses(req: HttpServletRequest, @PathVariable orgId: UUID) =
        service.expenses(actor(req), orgId)

    @GetMapping("/v1/finance/me/organizations/{orgId}/advances")
    fun advances(req: HttpServletRequest, @PathVariable orgId: UUID) =
        service.advances(actor(req), orgId)

    @GetMapping("/v1/finance/me/organizations/{orgId}/imprests")
    fun imprests(req: HttpServletRequest, @PathVariable orgId: UUID) =
        service.imprests(actor(req), orgId)

    @PostMapping("/v1/finance/me/organizations/{orgId}/expenses")
    fun expense(req: HttpServletRequest, @PathVariable orgId: UUID,
                @RequestBody data: CreateExpenseClaimDraftRequest): FinanceEmployeeDraftView {
        val ctx = HiltechRequestContext.current(req)
        return service.createExpense(actor(req), orgId, ctx.correlationId, ctx.idempotencyKey, data)
    }

    @PostMapping("/v1/finance/me/organizations/{orgId}/advances")
    fun advance(req: HttpServletRequest, @PathVariable orgId: UUID,
                @RequestBody data: CreateEmployeeAdvanceDraftRequest): FinanceEmployeeDraftView {
        val ctx = HiltechRequestContext.current(req)
        return service.createAdvance(actor(req), orgId, ctx.correlationId, ctx.idempotencyKey, data)
    }

    @PostMapping("/v1/finance/me/organizations/{orgId}/imprests")
    fun imprest(req: HttpServletRequest, @PathVariable orgId: UUID,
                @RequestBody data: CreateFinancialImprestDraftRequest): FinanceEmployeeDraftView {
        val ctx = HiltechRequestContext.current(req)
        return service.createImprest(actor(req), orgId, ctx.correlationId, ctx.idempotencyKey, data)
    }

    private fun actor(req: HttpServletRequest): UUID =
        HiltechRequestContext.current(req).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException(
                "UNAUTHENTICATED", "Authentication required.", HttpStatus.UNAUTHORIZED,
            )
}
