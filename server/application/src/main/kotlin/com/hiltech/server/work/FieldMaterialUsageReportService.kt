package com.hiltech.server.work

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.people.EmployeeState
import com.hiltech.server.people.PeoplePersistencePort
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * First S04B vertical only: a reported claim awaiting authorized Warehouse review.
 * It is NOT an inventory consumption, receipt, reservation, or approval.
 * No stock movement can be created through this service.
 */
data class FieldMaterialUsageReportCommand(
    val operationId: UUID,
    val workOrderId: UUID,
    val actorUserId: UUID,
    val assignmentId: UUID,
    val assignmentVersion: Long,
    val workVersion: Long,
    val requirementId: UUID,
    val stockItemId: UUID,
    val issuedVersion: Long,
    val quantity: String,
    val unitCode: String,
    val clientOccurredAt: Instant,
    val correlationId: String,
)

data class FieldMaterialUsageReportReceipt(
    val operationId: UUID,
    val workOrderId: UUID,
    val status: String,
    val stockMoved: Boolean,
    val replayed: Boolean,
)

@Service
class FieldMaterialUsageReportService(
    private val source: WorkAssignmentSourceAuthorityPort,
    private val authorization: WorkAssignmentAuthorizationPort,
    private val work: WorkPersistencePort,
    private val assignments: ReadinessAssignmentPersistencePort,
    private val people: PeoplePersistencePort,
    private val stock: FieldPersonalIssuedStockReadPort,
    private val reports: FieldMaterialUsageReportPersistencePort,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    @Value("\${hiltech.field.material-usage-report.enabled:false}")
    private val rolloutEnabled: Boolean = false,
) {
    fun report(command: FieldMaterialUsageReportCommand): FieldMaterialUsageReportReceipt {
        if (!rolloutEnabled) {
            throw ProductApiException(
                "FIELD_MATERIAL_USAGE_REPORT_UNAVAILABLE",
                "Material reporting is not enabled for this environment.",
                HttpStatus.NOT_FOUND,
            )
        }
        if (command.assignmentVersion < 1 || command.workVersion < 1 || command.issuedVersion < 1 ||
            command.unitCode.isBlank() || command.unitCode.length > 64 ||
            command.correlationId.isBlank() || command.correlationId.length > 128) invalid()
        val rawQuantity = command.quantity
        if (!Regex("[0-9]{1,14}(\\.[0-9]{1,6})?").matches(rawQuantity)) invalid()
        val quantity = rawQuantity.toBigDecimal().stripTrailingZeros().takeIf { it.signum() > 0 } ?: invalid()

        // Run even on a retry; a revoked user cannot retrieve the original operation receipt.
        requireAssignedWrite(command)
        val fingerprint = IdempotencyKeyContract.fingerprint(
            listOf(
                command.workOrderId, command.assignmentId, command.assignmentVersion,
                command.workVersion, command.requirementId, command.stockItemId,
                command.issuedVersion, quantity.toPlainString(), command.unitCode,
                command.clientOccurredAt,
            ).joinToString("|"),
        )
        val executed = commands.execute(
            IdempotentCommandSpec(
                operationId = command.operationId,
                actorUserId = command.actorUserId,
                commandType = "FIELD_MATERIAL_USAGE_REPORTED",
                targetType = "WORK_ORDER",
                targetId = command.workOrderId,
                requestFingerprint = fingerprint,
                correlationId = command.correlationId,
            ),
        ) {
            // This lock matches the established Work->Stock lock order and serializes
            // competing technician proposals for the same WorkOrder. It does NOT
            // lock or mutate a stock balance and does not authorize Warehouse Consume.
            if (!work.lockWorkOrder(command.workOrderId)) hidden()
            val now = clock.instant()
            requireAssignedWrite(command)
            val order = work.workOrder(command.workOrderId) ?: hidden()
            val currentAssignment = assignments.activeAssignment(command.workOrderId) ?: hidden()
            if (currentAssignment.assignmentId != command.assignmentId ||
                currentAssignment.version != command.assignmentVersion) conflict("FIELD_ASSIGNMENT_CHANGED")
            if (order.version != command.workVersion) conflict("FIELD_WORK_VERSION_CONFLICT")
            if (order.lifecycleState !in setOf(WorkOrderLifecycle.IN_PROGRESS, WorkOrderLifecycle.BLOCKED)) {
                conflict("FIELD_WORK_NOT_ACTIVE")
            }
            val employee = people.loadOwnEmployee(command.actorUserId, order.organizationId, now)
                ?.takeIf { it.employeeState == EmployeeState.ACTIVE &&
                    it.organizationId == order.organizationId } ?: hidden()
            val ownRows = stock.ownForWorkOrder(
                order.organizationId, order.projectId, order.siteId, order.workOrderId, employee.employeeId,
            )
            if (ownRows.size > 100) conflict("FIELD_ISSUED_STOCK_SOURCE_LIMIT")
            val issued = ownRows.singleOrNull {
                it.requirementId == command.requirementId && it.stockItemId == command.stockItemId &&
                    it.employeeId == employee.employeeId && it.organizationId == order.organizationId &&
                    it.projectId == order.projectId && it.siteId == order.siteId &&
                    it.workOrderId == order.workOrderId
            } ?: hidden()
            if (issued.sourceVersion != command.issuedVersion) conflict("FIELD_ISSUED_STOCK_VERSION_CONFLICT")
            if (issued.unitCode != command.unitCode) invalid()
            if (issued.serialized && quantity.stripTrailingZeros().scale() > 0) invalid()
            val outstanding = issued.outstandingQuantity.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }
                ?: conflict("FIELD_ISSUED_STOCK_SOURCE_UNAVAILABLE")
            val pending = reports.totalPending(
                order.workOrderId, employee.employeeId, command.requirementId, command.stockItemId,
            )
            if (pending.signum() < 0 || quantity.add(pending) > outstanding) {
                conflict("FIELD_MATERIAL_USAGE_EXCEEDS_OUTSTANDING")
            }
            reports.insertPending(
                FieldMaterialUsageReportRecord(
                    command.operationId, order.organizationId, order.projectId, order.siteId,
                    order.workOrderId, command.actorUserId, employee.employeeId,
                    currentAssignment.assignmentId, currentAssignment.version,
                    order.version, command.requirementId, command.stockItemId,
                    issued.sourceVersion, quantity, issued.unitCode,
                    command.clientOccurredAt, now, command.correlationId,
                ),
            )
            audit.append(
                AuditEventRecord(
                    actorUserId = command.actorUserId,
                    action = "FIELD_MATERIAL_USAGE_REPORTED",
                    targetType = "WORK_ORDER",
                    targetId = order.workOrderId,
                    newStateRef = command.operationId.toString(),
                    safeDiffJson = """{"fields":["fieldMaterialUsageReport","pendingReview"]}""",
                    occurredAt = now,
                    correlationId = command.correlationId,
                ),
            )
            IdempotentCommandOutcome(
                "PENDING_REVIEW",
                """{"reportOperationId":"${command.operationId}","status":"PENDING_REVIEW","stockMoved":false}""",
            )
        }
        return FieldMaterialUsageReportReceipt(
            command.operationId, command.workOrderId, "PENDING_REVIEW",
            stockMoved = false, replayed = executed.replayed,
        )
    }

    private fun requireAssignedWrite(command: FieldMaterialUsageReportCommand) {
        if (!source.isCurrentAssignedUser(command.actorUserId, command.workOrderId, clock.instant()) ||
            !authorization.canUseAssignedWork(command.actorUserId, command.workOrderId, "can_submit_completion")) {
            hidden()
        }
    }

    private fun invalid(): Nothing = throw ProductApiException(
        "FIELD_MATERIAL_USAGE_INVALID", "Invalid material usage report.", HttpStatus.BAD_REQUEST,
    )

    private fun hidden(): Nothing = throw ProductApiException(
        "OBJECT_NOT_VISIBLE", "The assigned Work context is unavailable.", HttpStatus.NOT_FOUND,
    )

    private fun conflict(code: String): Nothing = throw ProductApiException(
        code, "Material source or Work context changed; refresh before reporting.", HttpStatus.CONFLICT,
    )
}
