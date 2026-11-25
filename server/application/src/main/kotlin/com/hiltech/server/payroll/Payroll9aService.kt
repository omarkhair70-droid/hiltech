package com.hiltech.server.payroll

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.ProductApiException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

data class Payroll9aPeriodCommand(
    val operationId: UUID,
    val organizationId: UUID,
    val periodCode: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val cutoffDate: LocalDate,
    val targetPaymentDate: LocalDate?,
)

private data class PeriodRow(
    val id: UUID,
    val operationId: UUID,
    val fingerprint: String,
    val organizationId: UUID,
    val createdByUserId: UUID,
    val code: String,
    val start: LocalDate,
    val end: LocalDate,
    val cutoff: LocalDate,
    val paymentTarget: LocalDate?,
    val state: String,
    val version: Long,
    val snapshotAt: String?,
)

private data class InputSourceRow(
    val employeeId: UUID,
    val employeeCode: String,
    val displayName: String,
    val state: String,
    val employeeVersion: Long,
    val employmentId: UUID,
    val employmentVersion: Long,
    val employmentStart: LocalDate,
    val employmentEnd: LocalDate?,
    val employmentType: String?,
    val assignmentId: UUID?,
    val assignmentVersion: Long?,
)

object Payroll9aPolicy {
    fun validatePeriod(command: Payroll9aPeriodCommand) {
        if (!Regex("^[A-Z][A-Z0-9_-]{0,63}$").matches(command.periodCode)) {
            fail("INVALID_PAYROLL_PERIOD_CODE", HttpStatus.BAD_REQUEST)
        }
        if (command.endDate.isBefore(command.startDate) ||
            command.cutoffDate.isBefore(command.startDate) ||
            command.cutoffDate.isAfter(command.endDate) ||
            (command.targetPaymentDate != null &&
                command.targetPaymentDate.isBefore(command.startDate))
        ) fail("INVALID_PAYROLL_PERIOD_DATES", HttpStatus.BAD_REQUEST)
    }

    fun sourceChanged(
        employeeVersion: Long,
        employmentVersion: Long,
        assignmentId: UUID?,
        assignmentVersion: Long?,
        currentEmployeeVersion: Long?,
        currentEmploymentVersion: Long?,
        currentAssignmentId: UUID?,
        currentAssignmentVersion: Long?,
    ): Boolean =
        currentEmployeeVersion != employeeVersion ||
            currentEmploymentVersion != employmentVersion ||
            currentAssignmentId != assignmentId ||
            currentAssignmentVersion != assignmentVersion

    fun fail(code: String, status: HttpStatus): Nothing =
        throw ProductApiException(
            code = code,
            message = code,
            status = status,
        )
}

/**
 * 9A only. This service never calculates money, approves payroll, prepares bank
 * files, creates Finance payment instructions, or treats missing source as zero.
 */
@Component
class Payroll9aService(
    private val jdbc: JdbcTemplate,
    private val authority: Payroll9aAuthorityPort,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    private val coverage: ObjectProvider<PayrollSourceCoverageReadPort>? = null,
) {
    @Transactional
    fun create(
        actorId: UUID,
        command: Payroll9aPeriodCommand,
        correlationId: String,
    ): Pair<PayrollPeriodDetailDto, Boolean> {
        requirePrepare(actorId, command.organizationId)
        val normalized = command.copy(periodCode = command.periodCode.trim().uppercase(Locale.ROOT))
        Payroll9aPolicy.validatePeriod(normalized)
        val fingerprint = sha256(
            listOf(
                normalized.organizationId,
                normalized.periodCode,
                normalized.startDate,
                normalized.endDate,
                normalized.cutoffDate,
                normalized.targetPaymentDate,
            ).joinToString("|"),
        )
        val id = UUID.nameUUIDFromBytes(
            ("hiltech:payroll-period:" + normalized.operationId).toByteArray(StandardCharsets.UTF_8),
        )
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        val inserted = try {
            jdbc.update(
                """
                INSERT INTO payroll_period (
                    id, operation_id, request_fingerprint, organization_id,
                    period_code, start_date, end_date, cutoff_date,
                    target_payment_date, state, created_by_user_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?)
                ON CONFLICT (operation_id) DO NOTHING
                """.trimIndent(),
                id,
                normalized.operationId,
                fingerprint,
                normalized.organizationId,
                normalized.periodCode,
                normalized.startDate,
                normalized.endDate,
                normalized.cutoffDate,
                normalized.targetPaymentDate,
                actorId,
                now,
            ) == 1
        } catch (_: DataIntegrityViolationException) {
            Payroll9aPolicy.fail("PAYROLL_PERIOD_CODE_CONFLICT", HttpStatus.CONFLICT)
        }
        val stored = periodByOperation(normalized.operationId)
            ?: Payroll9aPolicy.fail("PAYROLL_PERIOD_NOT_FOUND", HttpStatus.NOT_FOUND)
        if (stored.organizationId != normalized.organizationId ||
            stored.createdByUserId != actorId || stored.fingerprint != fingerprint
        ) {
            Payroll9aPolicy.fail("PAYROLL_IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT)
        }
        if (inserted) {
            audit.append(
                AuditEventRecord(
                    actorUserId = actorId,
                    action = "PAYROLL_PERIOD_CREATED",
                    targetType = "PAYROLL_PERIOD",
                    targetId = stored.id,
                    occurredAt = clock.instant(),
                    correlationId = correlationId,
                ),
            )
        }
        return detailUnsafe(stored) to !inserted
    }

    @Transactional
    fun captureInputs(
        actorId: UUID,
        periodId: UUID,
        correlationId: String,
    ): Pair<PayrollPeriodDetailDto, Boolean> {
        val initial = periodById(periodId)
            ?: Payroll9aPolicy.fail("PAYROLL_PERIOD_NOT_FOUND", HttpStatus.NOT_FOUND)
        requirePrepare(actorId, initial.organizationId)
        // Lock before reading mutable People sources so a second caller cannot double-snapshot.
        val locked = periodById(periodId, lock = true)
            ?: Payroll9aPolicy.fail("PAYROLL_PERIOD_NOT_FOUND", HttpStatus.NOT_FOUND)
        if (locked.state == "INPUTS_SNAPSHOTTED") {
            return detailUnsafe(locked) to true
        }
        if (locked.state != "OPEN") {
            Payroll9aPolicy.fail("PAYROLL_PERIOD_NOT_OPEN", HttpStatus.CONFLICT)
        }

        val rows = currentEligibleFacts(locked)
        val at = clock.instant().atOffset(ZoneOffset.UTC)
        for (source in rows) {
            val snapshotId = UUID.randomUUID()
            jdbc.update(
                """
                INSERT INTO payroll_employee_input_snapshot (
                    id, payroll_period_id, organization_id, employee_id,
                    employee_code, display_name, employee_state, employee_version,
                    employment_id, employment_version, employment_start_date,
                    employment_end_date, employment_type_code,
                    workforce_assignment_id, workforce_assignment_version, captured_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                snapshotId, locked.id, locked.organizationId, source.employeeId,
                source.employeeCode, source.displayName, source.state, source.employeeVersion,
                source.employmentId, source.employmentVersion, source.employmentStart,
                source.employmentEnd, source.employmentType, source.assignmentId,
                source.assignmentVersion, at,
            )
            // Missing integrations are explicit blockers; NEVER represent them as zero.
            exception(locked.id, snapshotId, "COMPENSATION_POLICY_NOT_CONNECTED", "PAYROLL", at)
            exception(locked.id, snapshotId, "APPROVED_TIME_SOURCE_NOT_CONNECTED", "WORKFORCE", at)
            exception(locked.id, snapshotId, "FINANCE_DEDUCTION_SOURCE_NOT_CONNECTED", "FINANCE", at)
            if (source.state != "ACTIVE") {
                exception(locked.id, snapshotId, "OFFBOARDING_REQUIRES_REVIEW", "PEOPLE", at)
            }
            if (source.assignmentId == null) {
                exception(locked.id, snapshotId, "WORKFORCE_ASSIGNMENT_MISSING", "WORKFORCE", at)
            }
        }
        jdbc.update(
            """
            UPDATE payroll_period
            SET state = 'INPUTS_SNAPSHOTTED', snapshot_at = ?,
                snapshot_by_user_id = ?, version = version + 1
            WHERE id = ? AND state = 'OPEN'
            """.trimIndent(),
            at, actorId, locked.id,
        )
        audit.append(
            AuditEventRecord(
                actorUserId = actorId,
                action = "PAYROLL_INPUT_SNAPSHOT_CAPTURED",
                targetType = "PAYROLL_PERIOD",
                targetId = locked.id,
                previousStateRef = "OPEN",
                newStateRef = "INPUTS_SNAPSHOTTED",
                occurredAt = clock.instant(),
                correlationId = correlationId,
            ),
        )
        return detailUnsafe(
            periodById(locked.id) ?: Payroll9aPolicy.fail("PAYROLL_PERIOD_NOT_FOUND", HttpStatus.NOT_FOUND),
        ) to false
    }

    fun list(actorId: UUID, organizationId: UUID): List<PayrollPeriodSummaryDto> {
        requireRead(actorId, organizationId)
        return jdbc.query(
            "SELECT id FROM payroll_period WHERE organization_id = ? ORDER BY start_date DESC, id DESC LIMIT 100",
            { rs, _ ->
                val id = rs.getObject("id", UUID::class.java)
                val item = periodById(id)
                    ?: Payroll9aPolicy.fail("PAYROLL_PERIOD_NOT_FOUND", HttpStatus.NOT_FOUND)
                summary(item)
            },
            organizationId,
        )
    }

    fun detail(actorId: UUID, periodId: UUID): PayrollPeriodDetailDto {
        val period = periodById(periodId)
            ?: Payroll9aPolicy.fail("PAYROLL_PERIOD_NOT_FOUND", HttpStatus.NOT_FOUND)
        requireRead(actorId, period.organizationId)
        return detailUnsafe(period)
    }

    @Transactional
    fun review(
        actorId: UUID,
        periodId: UUID,
        exceptionId: UUID,
        operationId: UUID,
        note: String,
        correlationId: String,
    ): Pair<PayrollExceptionReviewDto, Boolean> {
        val period = periodById(periodId)
            ?: Payroll9aPolicy.fail("PAYROLL_PERIOD_NOT_FOUND", HttpStatus.NOT_FOUND)
        requireReview(actorId, period.organizationId)
        if (period.state != "INPUTS_SNAPSHOTTED") {
            Payroll9aPolicy.fail("PAYROLL_INPUTS_NOT_CAPTURED", HttpStatus.CONFLICT)
        }
        val normalized = note.trim()
        if (normalized.isEmpty() || normalized.length > 500) {
            Payroll9aPolicy.fail("INVALID_PAYROLL_REVIEW_NOTE", HttpStatus.BAD_REQUEST)
        }
        val exists = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM payroll_input_exception WHERE id = ? AND payroll_period_id = ?)",
            Boolean::class.java, exceptionId, periodId,
        ) == true
        if (!exists) Payroll9aPolicy.fail("PAYROLL_EXCEPTION_NOT_FOUND", HttpStatus.NOT_FOUND)
        val reviewId = UUID.nameUUIDFromBytes(
            ("hiltech:payroll-review:" + operationId).toByteArray(StandardCharsets.UTF_8),
        )
        val at = clock.instant().atOffset(ZoneOffset.UTC)
        val inserted = jdbc.update(
            """
            INSERT INTO payroll_exception_review (
                id, operation_id, exception_id, reviewed_by_user_id, review_note, reviewed_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (operation_id) DO NOTHING
            """.trimIndent(),
            reviewId, operationId, exceptionId, actorId, normalized, at,
        ) == 1
        val review = jdbc.query(
            """
            SELECT id, exception_id, reviewed_by_user_id, review_note, reviewed_at
            FROM payroll_exception_review WHERE operation_id = ?
            """.trimIndent(),
            { rs, _ ->
                Triple(
                    rs.getObject("exception_id", UUID::class.java),
                    rs.getObject("reviewed_by_user_id", UUID::class.java),
                    rs.getString("review_note"),
                ) to PayrollExceptionReviewDto(
                    reviewId = rs.getObject("id", UUID::class.java).toString(),
                    reviewedAt = rs.getObject("reviewed_at", OffsetDateTime::class.java).toInstant().toString(),
                    note = rs.getString("review_note"),
                )
            },
            operationId,
        ).singleOrNull() ?: Payroll9aPolicy.fail("PAYROLL_REVIEW_NOT_FOUND", HttpStatus.NOT_FOUND)
        if (review.first.first != exceptionId ||
            review.first.second != actorId ||
            review.first.third != normalized
        ) Payroll9aPolicy.fail("PAYROLL_IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT)
        if (inserted) {
            audit.append(
                AuditEventRecord(
                    actorUserId = actorId,
                    action = "PAYROLL_EXCEPTION_REVIEWED",
                    targetType = "PAYROLL_INPUT_EXCEPTION",
                    targetId = exceptionId,
                    occurredAt = clock.instant(),
                    correlationId = correlationId,
                    // Exclude free-text notes from the shared audit stream.
                ),
            )
        }
        return review.second to !inserted
    }

    private fun requireRead(actor: UUID, organization: UUID) {
        if (!authority.canRead(actor, organization)) {
            Payroll9aPolicy.fail("PAYROLL_FORBIDDEN", HttpStatus.FORBIDDEN)
        }
    }

    private fun requirePrepare(actor: UUID, organization: UUID) {
        if (!authority.canPrepare(actor, organization)) {
            Payroll9aPolicy.fail("PAYROLL_FORBIDDEN", HttpStatus.FORBIDDEN)
        }
    }

    private fun requireReview(actor: UUID, organization: UUID) {
        if (!authority.canReview(actor, organization)) {
            Payroll9aPolicy.fail("PAYROLL_FORBIDDEN", HttpStatus.FORBIDDEN)
        }
    }

    private fun exception(
        periodId: UUID,
        employeeSnapshotId: UUID,
        code: String,
        source: String,
        at: OffsetDateTime,
    ) {
        jdbc.update(
            """
            INSERT INTO payroll_input_exception (
                id, payroll_period_id, employee_snapshot_id,
                exception_code, source_domain, captured_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(), periodId, employeeSnapshotId, code, source, at,
        )
    }

    private fun currentEligibleFacts(period: PeriodRow): List<InputSourceRow> {
        val periodStartTimestamp = period.start.atStartOfDay().atOffset(ZoneOffset.UTC)
        val periodEndExclusive = period.cutoff.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)
        return jdbc.query(
            """
            SELECT e.id AS employee_id, e.employee_code, p.display_name,
                   e.state AS employee_state, e.version AS employee_version,
                   em.id AS employment_id, em.version AS employment_version,
                   em.start_date AS employment_start_date,
                   em.end_date AS employment_end_date,
                   em.employment_type_code,
                   wa.id AS assignment_id, wa.version AS assignment_version
            FROM employee e
            JOIN person p ON p.id = e.person_id
            JOIN LATERAL (
                SELECT h.*
                FROM employment h
                WHERE h.employee_id = e.id
                  AND h.start_date <= ?
                  AND (h.end_date IS NULL OR h.end_date >= ?)
                ORDER BY h.start_date DESC, h.id DESC
                LIMIT 1
            ) em ON true
            LEFT JOIN LATERAL (
                SELECT a.id, a.version
                FROM workforce_assignment a
                WHERE a.employee_id = e.id
                  AND a.organization_id = e.organization_id
                  AND a.effective_from < ?
                  AND (a.effective_to IS NULL OR a.effective_to >= ?)
                ORDER BY a.effective_from DESC, a.id DESC
                LIMIT 1
            ) wa ON true
            WHERE e.organization_id = ?
              AND e.state IN ('ACTIVE', 'OFFBOARDING', 'FORMER')
              AND (e.hire_date IS NULL OR e.hire_date <= ?)
              AND (e.end_date IS NULL OR e.end_date >= ?)
            ORDER BY e.employee_code, e.id
            """.trimIndent(),
            { rs, _ ->
                InputSourceRow(
                    employeeId = rs.getObject("employee_id", UUID::class.java),
                    employeeCode = rs.getString("employee_code"),
                    displayName = rs.getString("display_name"),
                    state = rs.getString("employee_state"),
                    employeeVersion = rs.getLong("employee_version"),
                    employmentId = rs.getObject("employment_id", UUID::class.java),
                    employmentVersion = rs.getLong("employment_version"),
                    employmentStart = rs.getObject("employment_start_date", LocalDate::class.java),
                    employmentEnd = rs.getObject("employment_end_date", LocalDate::class.java),
                    employmentType = rs.getString("employment_type_code"),
                    assignmentId = rs.getObject("assignment_id", UUID::class.java),
                    assignmentVersion = rs.getObject("assignment_version", java.lang.Long::class.java)?.toLong(),
                )
            },
            period.end, period.start, periodEndExclusive, periodStartTimestamp,
            period.organizationId, period.end, period.start,
        )
    }

    private fun detailUnsafe(period: PeriodRow): PayrollPeriodDetailDto {
        val employees = jdbc.query(
            """
            SELECT s.*, e.version AS current_employee_version,
                   em.version AS current_employment_version,
                   current_wa.version AS current_assignment_version,
                   current_wa.id AS current_assignment_id
            FROM payroll_employee_input_snapshot s
            JOIN employee e ON e.id = s.employee_id AND e.organization_id = s.organization_id
            LEFT JOIN employment em ON em.id = s.employment_id
            LEFT JOIN LATERAL (
                SELECT a.id, a.version
                FROM workforce_assignment a
                JOIN payroll_period pp ON pp.id = s.payroll_period_id
                WHERE a.employee_id = s.employee_id
                  AND a.organization_id = s.organization_id
                  AND a.effective_from < ((pp.cutoff_date + 1)::timestamp AT TIME ZONE 'UTC')
                  AND (a.effective_to IS NULL OR
                       a.effective_to >= (pp.start_date::timestamp AT TIME ZONE 'UTC'))
                ORDER BY a.effective_from DESC, a.id DESC
                LIMIT 1
            ) current_wa ON true
            WHERE s.payroll_period_id = ?
            ORDER BY s.employee_code, s.employee_id
            """.trimIndent(),
            { rs, _ ->
                val snapshotId = rs.getObject("id", UUID::class.java)
                val assignmentId = rs.getObject("workforce_assignment_id", UUID::class.java)
                val assignmentVersion = rs.getObject("workforce_assignment_version", java.lang.Long::class.java)?.toLong()
                PayrollEmployeeInputDto(
                    snapshotId = snapshotId.toString(),
                    employeeId = rs.getObject("employee_id", UUID::class.java).toString(),
                    employeeCode = rs.getString("employee_code"),
                    displayName = rs.getString("display_name"),
                    employeeState = rs.getString("employee_state"),
                    employeeVersion = rs.getLong("employee_version"),
                    employmentId = rs.getObject("employment_id", UUID::class.java).toString(),
                    employmentVersion = rs.getLong("employment_version"),
                    workforceAssignmentId = assignmentId?.toString(),
                    workforceAssignmentVersion = assignmentVersion,
                    sourceChanged = Payroll9aPolicy.sourceChanged(
                        employeeVersion = rs.getLong("employee_version"),
                        employmentVersion = rs.getLong("employment_version"),
                        assignmentId = assignmentId,
                        assignmentVersion = assignmentVersion,
                        currentEmployeeVersion = rs.getLong("current_employee_version"),
                        currentEmploymentVersion = rs.getObject("current_employment_version", java.lang.Long::class.java)?.toLong(),
                        currentAssignmentId = rs.getObject("current_assignment_id", UUID::class.java),
                        currentAssignmentVersion = rs.getObject("current_assignment_version", java.lang.Long::class.java)?.toLong(),
                    ),
                    exceptions = exceptions(snapshotId),
                )
            },
            period.id,
        )
        val coverageSource = coverage?.ifAvailable
        val withCurrentSources = if (coverageSource == null) employees else employees.map { employee ->
            val approved = coverageSource.approvedDomains(period.organizationId, period.id, UUID.fromString(employee.employeeId))
            employee.copy(exceptions = employee.exceptions.map { exception ->
                val covered = when (exception.code) {
                    "COMPENSATION_POLICY_NOT_CONNECTED" -> "COMPENSATION" in approved
                    "APPROVED_TIME_SOURCE_NOT_CONNECTED" -> "APPROVED_TIME" in approved
                    "FINANCE_DEDUCTION_SOURCE_NOT_CONNECTED" -> "FINANCE" in approved
                    else -> false
                }
                if (covered) exception.copy(blocksCalculation = false) else exception
            })
        }
        return PayrollPeriodDetailDto(
            period = summary(period),
            employees = withCurrentSources,
        )
    }

    private fun exceptions(snapshotId: UUID): List<PayrollInputExceptionDto> =
        jdbc.query(
            """
            SELECT id, exception_code, source_domain
            FROM payroll_input_exception
            WHERE employee_snapshot_id = ?
            ORDER BY exception_code, id
            """.trimIndent(),
            { rs, _ ->
                val id = rs.getObject("id", UUID::class.java)
                PayrollInputExceptionDto(
                    exceptionId = id.toString(),
                    code = rs.getString("exception_code"),
                    sourceDomain = rs.getString("source_domain"),
                    reviews = jdbc.query(
                        """
                        SELECT id, reviewed_at, review_note
                        FROM payroll_exception_review WHERE exception_id = ?
                        ORDER BY reviewed_at, id
                        """.trimIndent(),
                        { item, _ ->
                            PayrollExceptionReviewDto(
                                reviewId = item.getObject("id", UUID::class.java).toString(),
                                reviewedAt = item.getObject("reviewed_at", OffsetDateTime::class.java).toInstant().toString(),
                                note = item.getString("review_note"),
                            )
                        },
                        id,
                    ),
                )
            },
            snapshotId,
        )

    private fun summary(row: PeriodRow): PayrollPeriodSummaryDto =
        PayrollPeriodSummaryDto(
            periodId = row.id.toString(),
            organizationId = row.organizationId.toString(),
            periodCode = row.code,
            startDate = row.start.toString(),
            endDate = row.end.toString(),
            cutoffDate = row.cutoff.toString(),
            targetPaymentDate = row.paymentTarget?.toString(),
            state = row.state,
            version = row.version,
            snapshotAt = row.snapshotAt,
            employeeCount = jdbc.queryForObject(
                "SELECT count(*) FROM payroll_employee_input_snapshot WHERE payroll_period_id = ?",
                Int::class.java, row.id,
            ) ?: 0,
            exceptionCount = jdbc.queryForObject(
                "SELECT count(*) FROM payroll_input_exception WHERE payroll_period_id = ?",
                Int::class.java, row.id,
            ) ?: 0,
        )

    private fun periodByOperation(operationId: UUID): PeriodRow? =
        loadPeriod("operation_id = ?", operationId, false)

    private fun periodById(periodId: UUID, lock: Boolean = false): PeriodRow? =
        loadPeriod("id = ?", periodId, lock)

    private fun loadPeriod(column: String, id: UUID, lock: Boolean): PeriodRow? =
        jdbc.query(
            """
            SELECT id, operation_id, request_fingerprint, organization_id,
                   period_code, start_date, end_date, cutoff_date,
                   target_payment_date, state, version, snapshot_at,
                   created_by_user_id
            FROM payroll_period WHERE
            """.trimIndent() + " " + column + (if (lock) " FOR UPDATE" else ""),
            { rs: ResultSet, _ ->
                PeriodRow(
                    id = rs.getObject("id", UUID::class.java),
                    operationId = rs.getObject("operation_id", UUID::class.java),
                    fingerprint = rs.getString("request_fingerprint"),
                    organizationId = rs.getObject("organization_id", UUID::class.java),
                    createdByUserId = rs.getObject("created_by_user_id", UUID::class.java),
                    code = rs.getString("period_code"),
                    start = rs.getObject("start_date", LocalDate::class.java),
                    end = rs.getObject("end_date", LocalDate::class.java),
                    cutoff = rs.getObject("cutoff_date", LocalDate::class.java),
                    paymentTarget = rs.getObject("target_payment_date", LocalDate::class.java),
                    state = rs.getString("state"),
                    version = rs.getLong("version"),
                    snapshotAt = rs.getObject("snapshot_at", OffsetDateTime::class.java)?.toInstant()?.toString(),
                )
            },
            id,
        ).singleOrNull()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
