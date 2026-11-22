package com.hiltech.server.payroll

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.ProductApiException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class Payroll9bRunLineDto(
    val employeeId: String,
    val snapshotId: String,
    val currency: String,
    val gross: String,
    val deductions: String,
    val reimbursements: String,
    val net: String,
    val sourceFingerprint: String,
)

data class Payroll9bRunDto(
    val runVersionId: String,
    val organizationId: String,
    val payrollPeriodId: String,
    val generation: Int,
    val state: String,
    val currency: String,
    val gross: String,
    val deductions: String,
    val reimbursements: String,
    val net: String,
    val sourceFingerprint: String,
    val lines: List<Payroll9bRunLineDto>,
    val approvalAllowed: Boolean = false,
    val paymentAllowed: Boolean = false,
)

data class Payroll9bDraftCommandDto(
    val run: Payroll9bRunDto,
    val replayed: Boolean,
    val correlationId: String,
)

data class Payroll9bRunSummaryDto(
    val runVersionId: String,
    val generation: Int,
    val state: String,
    val currency: String,
    val net: String,
    val employeeCount: Int,
    val sourceFingerprint: String,
)

data class Payroll9bRunHistoryDto(
    val payrollPeriodId: String,
    val items: List<Payroll9bRunSummaryDto>,
)

private data class DraftRunPersisted(
    val id: UUID,
    val operationId: UUID,
    val organizationId: UUID,
    val periodId: UUID,
    val generation: Int,
    val fingerprint: String,
    val currency: String,
    val gross: BigDecimal,
    val deductions: BigDecimal,
    val reimbursements: BigDecimal,
    val net: BigDecimal,
    val state: String,
)

/**
 * Concrete 9B draft lifecycle, no approval or cash effects.
 * The authoritative source adapter must supply already validated HR, approved
 * time and Finance references; absent adapter/policy => fail closed.
 */
@Component
class Payroll9bDraftService(
    private val jdbc: JdbcTemplate,
    private val payroll9a: Payroll9aService,
    private val authority: Payroll9aAuthorityPort,
    private val sources: ObjectProvider<PayrollApprovedInputSourcePort>,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    @Transactional
    fun create(
        periodId: UUID,
        operationId: UUID,
        actorUserId: UUID,
        correlationId: String,
    ): Pair<Payroll9bRunDto, Boolean> {
        val period = payroll9a.detail(actorUserId, periodId)
        val orgId = UUID.fromString(period.period.organizationId)
        if (!authority.canPrepare(actorUserId, orgId)) {
            Payroll9aPolicy.fail("PAYROLL_FORBIDDEN", HttpStatus.FORBIDDEN)
        }
        // Lock the period before deriving sequential versions or source facts.
        jdbc.queryForObject(
            "SELECT id FROM payroll_period WHERE id = ? AND organization_id = ? FOR UPDATE",
            UUID::class.java, periodId, orgId,
        ) ?: Payroll9aPolicy.fail("PAYROLL_PERIOD_NOT_FOUND", HttpStatus.NOT_FOUND)
        val prior = loadByOperation(operationId)
        if (prior != null) {
            if (prior.organizationId != orgId || prior.periodId != periodId) {
                Payroll9aPolicy.fail("PAYROLL_IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT)
            }
            return present(prior) to true
        }
        val fresh = payroll9a.detail(actorUserId, periodId)
        if (fresh.period.state != "INPUTS_SNAPSHOTTED" || fresh.employees.isEmpty()) {
            Payroll9aPolicy.fail("PAYROLL_INPUT_SNAPSHOT_REQUIRED", HttpStatus.CONFLICT)
        }
        if (fresh.employees.any { it.sourceChanged }) {
            Payroll9aPolicy.fail("PAYROLL_SOURCE_CHANGED_AFTER_SNAPSHOT", HttpStatus.CONFLICT)
        }
        val provider = sources.ifAvailable
            ?: Payroll9aPolicy.fail("PAYROLL_APPROVED_SOURCE_NOT_CONNECTED", HttpStatus.CONFLICT)
        val policy = provider.policyFor(orgId, periodId)
            ?: Payroll9aPolicy.fail("PAYROLL_POLICY_NOT_VERIFIED", HttpStatus.CONFLICT)

        val calculations = fresh.employees.map { employee ->
            val id = UUID.fromString(employee.employeeId)
            val components = provider.approvedFor(orgId, periodId, id)
            val coverage = provider.verifiedCoverageFor(orgId, periodId, id)
            employee to (
                Payroll9bCalculator.calculate(orgId, periodId, id, policy, components, coverage)
                    to components
            )
        }
        val fingerprint = PayrollRunSourceFingerprint.of(
            orgId, periodId, policy.policyVersionId,
            calculations.map { it.second.first.sourceFingerprint },
        )
        val currency = policy.currency
        val gross = calculations.fold(BigDecimal.ZERO) { acc, item -> acc + item.second.first.gross }
        val deductions = calculations.fold(BigDecimal.ZERO) { acc, item -> acc + item.second.first.deductions }
        val reimbursements = calculations.fold(BigDecimal.ZERO) {
            acc, item -> acc + item.second.first.reimbursements
        }
        val net = gross + reimbursements - deductions
        val next = (jdbc.queryForObject(
            "SELECT COALESCE(MAX(generation), 0) + 1 FROM payroll_run_version WHERE payroll_period_id = ?",
            Int::class.java, periodId,
        ) ?: 1)
        val runId = UUID.nameUUIDFromBytes(
            ("hiltech:payroll-run:" + operationId).toByteArray(StandardCharsets.UTF_8),
        )
        val at = clock.instant().atOffset(ZoneOffset.UTC)
        try {
            jdbc.update(
                """
                INSERT INTO payroll_run_version (
                    id, operation_id, request_fingerprint, organization_id,
                    payroll_period_id, generation, policy_version_id, policy_version,
                    currency, source_fingerprint, gross, deductions, reimbursements,
                    net, state, created_by_user_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?)
                """.trimIndent(),
                runId, operationId, fingerprint, orgId, periodId, next,
                policy.policyVersionId, policy.policyVersion, currency, fingerprint,
                gross, deductions, reimbursements, net, actorUserId, at,
            )
            calculations.forEach { (employee, pair) ->
                val (calculation, components) = pair
                val lineId = UUID.randomUUID()
                jdbc.update(
                    """
                    INSERT INTO payroll_run_employee_line (
                        id, run_version_id, payroll_period_id, employee_snapshot_id,
                        employee_id, source_fingerprint, currency, gross, deductions,
                        reimbursements, net
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    lineId, runId, periodId, UUID.fromString(employee.snapshotId),
                    calculation.employeeId, calculation.sourceFingerprint,
                    currency, calculation.gross, calculation.deductions,
                    calculation.reimbursements, calculation.net,
                )
                components.forEach { input ->
                    val amount = BigDecimal(input.amount).setScale(
                        policy.fractionalDigits, policy.roundingMode,
                    )
                    jdbc.update(
                        """
                        INSERT INTO payroll_run_component_ref (
                            id, run_employee_line_id, component_code, component_kind,
                            source_domain, source_id, source_version,
                            source_approval_ref, amount
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        UUID.randomUUID(), lineId, input.componentCode, input.kind.name,
                        input.sourceDomain, input.sourceId, input.sourceVersion,
                        input.sourceApprovalReference, amount,
                    )
                }
            }
        } catch (_: DataIntegrityViolationException) {
            Payroll9aPolicy.fail("PAYROLL_DRAFT_CONFLICT", HttpStatus.CONFLICT)
        }
        audit.append(
            AuditEventRecord(
                actorUserId = actorUserId,
                action = "PAYROLL_DRAFT_VERSION_CREATED",
                targetType = "PAYROLL_RUN_VERSION",
                targetId = runId,
                occurredAt = clock.instant(),
                correlationId = correlationId,
                // Do not leak amounts into generic audit, telemetry or email.
            ),
        )
        return present(loadById(runId) ?: Payroll9aPolicy.fail("PAYROLL_DRAFT_NOT_FOUND", HttpStatus.NOT_FOUND)) to false
    }

    fun history(actorUserId: UUID, periodId: UUID): Payroll9bRunHistoryDto {
        val period = payroll9a.detail(actorUserId, periodId)
        val org = UUID.fromString(period.period.organizationId)
        return Payroll9bRunHistoryDto(
            payrollPeriodId = periodId.toString(),
            items = jdbc.query(
                """
                SELECT r.id, r.generation, r.state, r.currency, r.net,
                       r.source_fingerprint, COUNT(l.id) AS employee_count
                FROM payroll_run_version r
                LEFT JOIN payroll_run_employee_line l ON l.run_version_id = r.id
                WHERE r.payroll_period_id = ? AND r.organization_id = ?
                GROUP BY r.id, r.generation, r.state, r.currency,
                         r.net, r.source_fingerprint
                ORDER BY r.generation DESC LIMIT 100
                """.trimIndent(),
                { rs, _ ->
                    Payroll9bRunSummaryDto(
                        runVersionId = rs.getObject("id", UUID::class.java).toString(),
                        generation = rs.getInt("generation"),
                        state = rs.getString("state"),
                        currency = rs.getString("currency"),
                        net = rs.getBigDecimal("net").toPlainString(),
                        employeeCount = rs.getInt("employee_count"),
                        sourceFingerprint = rs.getString("source_fingerprint"),
                    )
                }, periodId, org,
            ),
        )
    }

    fun read(actorUserId: UUID, runVersionId: UUID): Payroll9bRunDto {
        val row = loadById(runVersionId)
            ?: Payroll9aPolicy.fail("PAYROLL_DRAFT_NOT_FOUND", HttpStatus.NOT_FOUND)
        if (!authority.canRead(actorUserId, row.organizationId)) {
            Payroll9aPolicy.fail("PAYROLL_FORBIDDEN", HttpStatus.FORBIDDEN)
        }
        return present(row)
    }

    private fun present(row: DraftRunPersisted): Payroll9bRunDto =
        Payroll9bRunDto(
            runVersionId = row.id.toString(),
            organizationId = row.organizationId.toString(),
            payrollPeriodId = row.periodId.toString(),
            generation = row.generation,
            state = row.state,
            currency = row.currency,
            gross = row.gross.toPlainString(),
            deductions = row.deductions.toPlainString(),
            reimbursements = row.reimbursements.toPlainString(),
            net = row.net.toPlainString(),
            sourceFingerprint = row.fingerprint,
            lines = jdbc.query(
                """
                SELECT s.employee_id, s.employee_snapshot_id, s.currency,
                       s.gross, s.deductions, s.reimbursements, s.net,
                       s.source_fingerprint
                FROM payroll_run_employee_line s WHERE s.run_version_id = ?
                ORDER BY s.employee_id
                """.trimIndent(),
                { rs, _ ->
                    Payroll9bRunLineDto(
                        employeeId = rs.getObject("employee_id", UUID::class.java).toString(),
                        snapshotId = rs.getObject("employee_snapshot_id", UUID::class.java).toString(),
                        currency = rs.getString("currency"),
                        gross = rs.getBigDecimal("gross").toPlainString(),
                        deductions = rs.getBigDecimal("deductions").toPlainString(),
                        reimbursements = rs.getBigDecimal("reimbursements").toPlainString(),
                        net = rs.getBigDecimal("net").toPlainString(),
                        sourceFingerprint = rs.getString("source_fingerprint"),
                    )
                },
                row.id,
            ),
        )

    private fun loadByOperation(operationId: UUID): DraftRunPersisted? =
        load("operation_id", operationId)

    private fun loadById(runVersionId: UUID): DraftRunPersisted? =
        load("id", runVersionId)

    private fun load(column: String, id: UUID): DraftRunPersisted? =
        jdbc.query(
            """
            SELECT id, operation_id, organization_id, payroll_period_id,
                   generation, source_fingerprint, currency, gross, deductions,
                   reimbursements, net, state
            FROM payroll_run_version WHERE
            """.trimIndent() + " " + column + " = ?",
            { rs, _ ->
                DraftRunPersisted(
                    id = rs.getObject("id", UUID::class.java),
                    operationId = rs.getObject("operation_id", UUID::class.java),
                    organizationId = rs.getObject("organization_id", UUID::class.java),
                    periodId = rs.getObject("payroll_period_id", UUID::class.java),
                    generation = rs.getInt("generation"),
                    fingerprint = rs.getString("source_fingerprint"),
                    currency = rs.getString("currency"),
                    gross = rs.getBigDecimal("gross"),
                    deductions = rs.getBigDecimal("deductions"),
                    reimbursements = rs.getBigDecimal("reimbursements"),
                    net = rs.getBigDecimal("net"),
                    state = rs.getString("state"),
                )
            }, id,
        ).singleOrNull()

    private fun sha256(parts: List<String>): String =
        MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString("|").toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
}
