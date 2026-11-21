package com.hiltech.server.work

import com.hiltech.server.people.EmployeeState
import com.hiltech.server.people.PeoplePersistencePort
import com.hiltech.server.platform.ProductApiException
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * S04A read-only projection. Same current WorkAssignment + can_view gate as
 * Field JobBundle, plus Phase3 own Person/Employee. This endpoint never
 * borrows Warehouse issued-context permissions and never writes stock.
 */
data class FieldPersonalIssuedStockSnapshot(
    val workOrderId: UUID,
    val workOrderVersion: Long,
    val status: String,
    val asOf: Instant,
    val items: List<FieldPersonalIssuedStockLine>,
)

data class FieldPersonalIssuedStockLine(
    val requirementId: UUID,
    val stockItemId: UUID,
    val stockItemCode: String,
    val stockItemName: String,
    val unitCode: String,
    val outstandingQuantity: String,
    val sourceVersion: Long,
    val sourceUpdatedAt: Instant,
)

@Service
class FieldPersonalIssuedStockService(
    private val assignments: WorkAssignmentSourceAuthorityPort,
    private val workAuthorization: WorkAssignmentAuthorizationPort,
    private val work: WorkPersistencePort,
    private val people: PeoplePersistencePort,
    private val stock: FieldPersonalIssuedStockReadPort,
    private val clock: Clock,
    @Value("\${hiltech.field.personal-issued-stock.enabled:false}")
    private val rolloutEnabled: Boolean = false,
) {
    @Transactional(readOnly = true)
    fun ownIssued(actorUserId: UUID, workOrderId: UUID): FieldPersonalIssuedStockSnapshot {
        // Explicit release gate: read authority is not yet approved for production.
        // A successful WorkOrder can_view alone must not enable personal custody data.
        if (!rolloutEnabled) {
            throw ProductApiException(
                "FIELD_ISSUED_STOCK_UNAVAILABLE",
                "Personal issued-stock read is not enabled for this environment.",
                HttpStatus.NOT_FOUND,
            )
        }
        val at = clock.instant()
        if (!assignments.isCurrentAssignedUser(actorUserId, workOrderId, at) ||
            !workAuthorization.canUseAssignedWork(actorUserId, workOrderId, "can_view")) {
            hidden()
        }
        val order = work.workOrder(workOrderId) ?: hidden()
        // The People core links only this identity's Person to a unique
        // organization-scoped Employee, never by a guessed code or crew list.
        val employee = people.loadOwnEmployee(actorUserId, order.organizationId, at)
        if (employee == null || employee.organizationId != order.organizationId ||
            employee.employeeState != EmployeeState.ACTIVE) {
            return FieldPersonalIssuedStockSnapshot(
                workOrderId, order.version, "EMPLOYEE_CONTEXT_UNAVAILABLE", at, emptyList(),
            )
        }

        val rows = stock.ownForWorkOrder(
            order.organizationId, order.projectId, order.siteId, order.workOrderId,
            employee.employeeId,
        )
        if (rows.size > 100) {
            throw ProductApiException(
                "FIELD_ISSUED_STOCK_TOO_MANY_ROWS",
                "Issued stock read is too large. Narrow the approved projection before use.",
                HttpStatus.CONFLICT,
            )
        }
        // A repository or SQL join regression cannot silently leak another
        // employee's/WorkOrder's stock even after this query's scope changes.
        if (rows.any {
                it.employeeId != employee.employeeId ||
                    it.organizationId != order.organizationId ||
                    it.projectId != order.projectId ||
                    it.siteId != order.siteId ||
                    it.workOrderId != order.workOrderId ||
                    (it.outstandingQuantity.toBigDecimalOrNull()?.signum()?.let { sign -> sign < 0 } ?: true) ||
                    it.sourceVersion < 1 ||
                    it.unitCode.isBlank()
            }) {
            throw ProductApiException(
                "FIELD_ISSUED_STOCK_SOURCE_MISMATCH",
                "The issued material source is inconsistent. Nothing was disclosed.",
                HttpStatus.CONFLICT,
            )
        }
        return FieldPersonalIssuedStockSnapshot(
            workOrderId = workOrderId,
            workOrderVersion = order.version,
            status = if (rows.isEmpty()) "NO_ISSUED_CONTEXT" else "SOURCE_CURRENT",
            asOf = at,
            items = rows.map {
                FieldPersonalIssuedStockLine(
                    requirementId = it.requirementId,
                    stockItemId = it.stockItemId,
                    stockItemCode = it.stockItemCode,
                    stockItemName = it.stockItemName,
                    unitCode = it.unitCode,
                    outstandingQuantity = it.outstandingQuantity,
                    sourceVersion = it.sourceVersion,
                    sourceUpdatedAt = it.updatedAt,
                )
            },
        )
    }

    private fun hidden(): Nothing =
        throw ProductApiException(
            "OBJECT_NOT_VISIBLE",
            "The assigned Work context is unavailable.",
            HttpStatus.NOT_FOUND,
        )
}
