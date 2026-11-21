package com.hiltech.server.work

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

data class FieldMaterialUsageReportRecord(
    val operationId: UUID,
    val organizationId: UUID,
    val projectId: UUID,
    val siteId: UUID,
    val workOrderId: UUID,
    val actorUserId: UUID,
    val employeeId: UUID,
    val assignmentId: UUID,
    val assignmentVersion: Long,
    val workVersion: Long,
    val requirementId: UUID,
    val stockItemId: UUID,
    val issuedVersion: Long,
    val quantity: BigDecimal,
    val unitCode: String,
    val clientOccurredAt: Instant,
    val recordedAt: Instant,
    val correlationId: String,
)

interface FieldMaterialUsageReportPersistencePort {
    fun totalPending(workOrder: UUID, employee: UUID, requirement: UUID, item: UUID): BigDecimal
    fun insertPending(report: FieldMaterialUsageReportRecord)
}

@Repository
class JdbcFieldMaterialUsageReportPersistence(
    private val jdbc: JdbcTemplate,
) : FieldMaterialUsageReportPersistencePort {
    override fun totalPending(workOrder: UUID, employee: UUID, requirement: UUID, item: UUID): BigDecimal =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(r.quantity), 0)
            FROM field_material_usage_report r
            WHERE r.work_order_id = ? AND r.employee_id = ?
              AND r.requirement_id = ? AND r.stock_item_id = ?
              AND r.state = 'PENDING_REVIEW'
              AND NOT EXISTS (
                  SELECT 1 FROM field_material_usage_reconciliation x
                  WHERE x.report_operation_id = r.operation_id
              )
            """.trimIndent(),
            BigDecimal::class.java, workOrder, employee, requirement, item,
        ) ?: BigDecimal.ZERO

    override fun insertPending(report: FieldMaterialUsageReportRecord) {
        val inserted = jdbc.update(
            """
            INSERT INTO field_material_usage_report (
              operation_id, organization_id, project_id, site_id,
              work_order_id, actor_user_id, employee_id,
              assignment_id, assignment_version, work_version,
              requirement_id, stock_item_id, issued_version,
              quantity, unit_code, state, client_occurred_at,
              created_at, correlation_id
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'PENDING_REVIEW', ?, ?, ?)
            """.trimIndent(),
            report.operationId, report.organizationId, report.projectId, report.siteId,
            report.workOrderId, report.actorUserId, report.employeeId, report.assignmentId,
            report.assignmentVersion, report.workVersion,
            report.requirementId, report.stockItemId, report.issuedVersion,
            report.quantity, report.unitCode,
            report.clientOccurredAt.atOffset(ZoneOffset.UTC),
            report.recordedAt.atOffset(ZoneOffset.UTC), report.correlationId,
        )
        check(inserted == 1) { "A pending material usage report insert must affect one row." }
    }
}
