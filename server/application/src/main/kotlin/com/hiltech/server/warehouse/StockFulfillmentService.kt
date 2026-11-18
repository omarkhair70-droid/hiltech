package com.hiltech.server.warehouse

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.work.WorkOrderSnapshot
import com.hiltech.server.work.WorkPersistencePort
import com.hiltech.server.work.WorkResourceChanged
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class StockFulfillmentService(
    private val fulfillment: StockFulfillmentPersistence,
    private val stock: StockLedgerPersistence,
    private val reservations: ReservationPersistence,
    private val warehouse: WarehousePersistencePort,
    private val authorization: WarehouseAuthorizationPort,
    private val work: WorkPersistencePort,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    fun issued(actor: UUID, item: UUID, workId: UUID): List<IssuedStockContext> {
        val master = stock.item(item) ?: unavailable()
        val order = work.workOrder(workId) ?: unavailable()
        if (master.organizationId != order.organizationId ||
            warehouse.warehouses(master.organizationId).none { authorization.canReserveResources(actor, it) }) unavailable()
        return fulfillment.issued(item, workId).filter {
            canAccessOrigins(actor, master.organizationId, item, it.requirementId, it.employeeId)
        }
    }

    fun issue(context: StockCommandContext, itemId: UUID, body: IssueReservedStockRequest): StockFulfillmentReceipt {
        val org = stock.item(itemId)?.organizationId ?: unavailable()
        requireLocation(context.actor, org, body.storageLocationId)
        val amount = quantity(body.quantity)
        return execute(context, itemId, StockFulfillmentKind.ISSUE, body.workOrderId, body.requirementId,
            body.employeeId, body.copy(quantity = amount.toPlainString())) { now ->
            val order = lockOrder(body.workOrderId, body.workVersion, org)
            val item = lockedItem(itemId, org, body.unitCode, amount)
            requireLocation(context.actor, org, body.storageLocationId)
            val demand = reservations.demands(order.workOrderId).singleOrNull { it.requirementId == body.requirementId }
                ?: unavailable()
            if (demand.requirementVersion != body.requirementVersion) stale()
            val hold = reservations.reservation(ResourceKind.STOCK, body.reservationId, now) ?: unavailable()
            if (hold.resourceId != itemId || hold.workOrderId != order.workOrderId ||
                hold.requirementId != demand.requirementId || hold.storageLocationId != body.storageLocationId) unavailable()
            if (hold.version != body.reservationVersion || hold.state != "ACTIVE") stale()
            val balance = reservations.balances(itemId, now, body.storageLocationId).singleOrNull() ?: unavailable()
            if (balance.version != body.balanceVersion) stale()
            // All contributors to an issued context must be visible to the actor.
            val origins = fulfillment.origins(itemId, body.requirementId, body.employeeId)
            if (origins.any { !canLocation(context.actor, org, it) }) unavailable()
            fulfillment.post(item, order, body.requirementId, body.employeeId, context, StockFulfillmentKind.ISSUE,
                amount, body.storageLocationId, hold.id, now)
        }
    }

    fun returnUnused(context: StockCommandContext, item: UUID, body: AdjustIssuedStockRequest): StockFulfillmentReceipt =
        adjust(context, item, body, StockFulfillmentKind.RETURN)

    fun consume(context: StockCommandContext, item: UUID, body: AdjustIssuedStockRequest): StockFulfillmentReceipt =
        adjust(context, item, body, StockFulfillmentKind.CONSUME)

    private fun adjust(context: StockCommandContext, itemId: UUID, body: AdjustIssuedStockRequest,
        kind: StockFulfillmentKind): StockFulfillmentReceipt {
        val org = stock.item(itemId)?.organizationId ?: unavailable()
        if (kind == StockFulfillmentKind.RETURN && body.storageLocationId == null ||
            kind == StockFulfillmentKind.CONSUME && body.storageLocationId != null) invalid()
        if (!canAccessOrigins(context.actor, org, itemId, body.requirementId, body.employeeId)) unavailable()
        body.storageLocationId?.let { requireLocation(context.actor, org, it) }
        val amount = quantity(body.quantity)
        return execute(context, itemId, kind, body.workOrderId, body.requirementId, body.employeeId,
            body.copy(quantity = amount.toPlainString())) { now ->
            val order = lockOrder(body.workOrderId, body.workVersion, org)
            val item = lockedItem(itemId, org, body.unitCode, amount)
            if (!canAccessOrigins(context.actor, org, itemId, body.requirementId, body.employeeId)) unavailable()
            body.storageLocationId?.let { requireLocation(context.actor, org, it) }
            val issued = fulfillment.issued(itemId, order.workOrderId).singleOrNull {
                it.requirementId == body.requirementId && it.employeeId == body.employeeId
            } ?: unavailable()
            if (issued.version != body.issuedVersion) stale()
            fulfillment.post(item, order, body.requirementId, body.employeeId, context, kind,
                amount, body.storageLocationId, null, now)
        }
    }

    private fun lockOrder(id: UUID, version: Long, org: UUID): WorkOrderSnapshot {
        if (!work.lockWorkOrder(id)) unavailable()
        val order = work.workOrder(id)?.takeIf { it.organizationId == org } ?: unavailable()
        if (order.version != version) stale()
        return order
    }

    private fun lockedItem(id: UUID, org: UUID, unit: String, amount: BigDecimal): StockItemView {
        val item = stock.item(id, lock = true)?.takeIf { it.organizationId == org && it.active } ?: unavailable()
        if (item.baseUnitCode != unit || (item.serialized && amount.stripTrailingZeros().scale() > 0)) invalid()
        return item
    }

    private fun canAccessOrigins(actor: UUID, org: UUID, item: UUID, requirement: UUID, employee: UUID): Boolean {
        val locations = fulfillment.origins(item, requirement, employee)
        return locations.isNotEmpty() && locations.all { canLocation(actor, org, it) }
    }

    private fun canLocation(actor: UUID, org: UUID, id: UUID): Boolean {
        val location = warehouse.storageLocation(id)?.takeIf { it.organizationId == org } ?: return false
        val facility = location.warehouseId?.let(warehouse::warehouse)?.takeIf { it.organizationId == org } ?: return false
        // Authorization over historical origins remains possible even when a bin
        // is retired. The database separately validates the actual move location.
        return authorization.canReserveResources(actor, facility) && authorization.canTransferStock(actor, location)
    }

    private fun requireLocation(actor: UUID, org: UUID, id: UUID) {
        if (!canLocation(actor, org, id)) unavailable()
    }

    private fun execute(context: StockCommandContext, item: UUID, kind: StockFulfillmentKind, workId: UUID,
        requirement: UUID, employee: UUID, request: Any, apply: (Instant) -> UUID): StockFulfillmentReceipt {
        val action = "STOCK_${kind.name}_POSTED"
        try {
            val replayed = commands.execute(IdempotentCommandSpec(context.operationId, context.actor, action,
                "STOCK_ITEM", item, warehouseCommandFingerprint(kind.name, workId, requirement, employee, request),
                context.correlationId)) {
                val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
                val id = apply(now)
                audit.append(AuditEventRecord(actorUserId = context.actor, action = action, targetType = "STOCK_ITEM",
                    targetId = item, newStateRef = id.toString(), occurredAt = now, correlationId = context.correlationId))
                events.publishEvent(WorkResourceChanged(id, workId, context.actor, context.correlationId))
                IdempotentCommandOutcome(action, """{"movementId":"$id"}""")
            }.replayed
            return requireNotNull(fulfillment.receipt(context.operationId, replayed))
        } catch (_: DataIntegrityViolationException) {
            throw ProductApiException("STOCK_FULFILLMENT_CONFLICT", "Stock, reservation or custody context changed.", HttpStatus.CONFLICT)
        }
    }
}

private fun quantity(value: String): BigDecimal {
    if (!Regex("[0-9]{1,14}(\\.[0-9]{1,6})?").matches(value)) invalid()
    return value.toBigDecimal().stripTrailingZeros().takeIf { it.signum() > 0 } ?: invalid()
}
private fun invalid(): Nothing = throw ProductApiException("INVALID_STOCK_FULFILLMENT", "Invalid quantity, unit or location.", HttpStatus.BAD_REQUEST)
private fun unavailable(): Nothing = throw ProductApiException("STOCK_FULFILLMENT_UNAVAILABLE", "Stock or custody context is unavailable.", HttpStatus.NOT_FOUND)
private fun stale(): Nothing = throw ProductApiException("STOCK_FULFILLMENT_VERSION_CONFLICT", "Reload Work, reservation and issued stock.", HttpStatus.CONFLICT)
