package com.hiltech.server.warehouse

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.work.WorkPersistencePort
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

enum class FieldMaterialUsageDecision { CONSUME, REJECT }

data class FieldMaterialUsageReconciliationRequest(
    val operationId: UUID,
    val decision: String,
    val reportWorkVersion: Long,
    val reportIssuedVersion: Long,
    val reason: String? = null,
)

data class FieldMaterialUsageReconciliationReceipt(
    val reportOperationId: UUID,
    val decision: String,
    val stockMoved: Boolean,
    val movementId: UUID?,
    val replayed: Boolean,
)

data class FieldMaterialUsageReviewSource(
    val operationId: UUID,
    val organizationId: UUID,
    val projectId: UUID,
    val siteId: UUID,
    val workOrderId: UUID,
    val employeeId: UUID,
    val requirementId: UUID,
    val stockItemId: UUID,
    val workVersion: Long,
    val issuedVersion: Long,
    val quantity: BigDecimal,
    val unitCode: String,
)

data class FieldMaterialUsageReconciliationRecord(
    val operationId: UUID,
    val reportOperationId: UUID,
    val organizationId: UUID,
    val warehouseId: UUID,
    val reviewerUserId: UUID,
    val decision: FieldMaterialUsageDecision,
    val stockOperationId: UUID?,
    val stockMovementId: UUID?,
    val reason: String?,
    val createdAt: Instant,
    val correlationId: String,
)

interface FieldMaterialUsageReconciliationPersistencePort {
    fun source(reportOperationId: UUID, lock: Boolean = false): FieldMaterialUsageReviewSource?
    fun reconciliation(reportOperationId: UUID): FieldMaterialUsageReconciliationRecord?
    fun insert(record: FieldMaterialUsageReconciliationRecord)
}

@Repository
class FieldMaterialUsageReconciliationPersistence(
    private val jdbc: JdbcTemplate,
) : FieldMaterialUsageReconciliationPersistencePort {
    override fun source(reportOperationId: UUID, lock: Boolean): FieldMaterialUsageReviewSource? {
        val suffix = if (lock) " FOR UPDATE" else ""
        return jdbc.query(
            """
            SELECT operation_id, organization_id, project_id, site_id, work_order_id,
                   employee_id, requirement_id, stock_item_id, work_version, issued_version,
                   quantity, unit_code
            FROM field_material_usage_report
            WHERE operation_id = ?
            """.trimIndent() + suffix,
            { row, _ ->
                FieldMaterialUsageReviewSource(
                    row.getObject("operation_id", UUID::class.java),
                    row.getObject("organization_id", UUID::class.java),
                    row.getObject("project_id", UUID::class.java),
                    row.getObject("site_id", UUID::class.java),
                    row.getObject("work_order_id", UUID::class.java),
                    row.getObject("employee_id", UUID::class.java),
                    row.getObject("requirement_id", UUID::class.java),
                    row.getObject("stock_item_id", UUID::class.java),
                    row.getLong("work_version"),
                    row.getLong("issued_version"),
                    row.getBigDecimal("quantity"),
                    row.getString("unit_code"),
                )
            },
            reportOperationId,
        ).singleOrNull()
    }

    override fun reconciliation(reportOperationId: UUID): FieldMaterialUsageReconciliationRecord? =
        jdbc.query(
            """
            SELECT operation_id, report_operation_id, organization_id, warehouse_id,
                   reviewer_user_id, decision, stock_operation_id, stock_movement_id,
                   reason, created_at, correlation_id
            FROM field_material_usage_reconciliation
            WHERE report_operation_id = ?
            """.trimIndent(),
            { row, _ ->
                FieldMaterialUsageReconciliationRecord(
                    row.getObject("operation_id", UUID::class.java),
                    row.getObject("report_operation_id", UUID::class.java),
                    row.getObject("organization_id", UUID::class.java),
                    row.getObject("warehouse_id", UUID::class.java),
                    row.getObject("reviewer_user_id", UUID::class.java),
                    FieldMaterialUsageDecision.valueOf(row.getString("decision")),
                    row.getObject("stock_operation_id", UUID::class.java),
                    row.getObject("stock_movement_id", UUID::class.java),
                    row.getString("reason"),
                    row.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    row.getString("correlation_id"),
                )
            },
            reportOperationId,
        ).singleOrNull()

    override fun insert(record: FieldMaterialUsageReconciliationRecord) {
        val inserted = jdbc.update(
            """
            INSERT INTO field_material_usage_reconciliation (
              operation_id, report_operation_id, organization_id, warehouse_id,
              reviewer_user_id, decision, stock_operation_id, stock_movement_id,
              reason, created_at, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            record.operationId, record.reportOperationId, record.organizationId, record.warehouseId,
            record.reviewerUserId, record.decision.name, record.stockOperationId, record.stockMovementId,
            record.reason, record.createdAt.atOffset(ZoneOffset.UTC), record.correlationId,
        )
        check(inserted == 1) { "A material usage reconciliation must insert exactly one row." }
    }
}

@Service
class FieldMaterialUsageReconciliationService(
    private val persistence: FieldMaterialUsageReconciliationPersistencePort,
    private val warehouse: WarehousePersistencePort,
    private val authorization: WarehouseAuthorizationPort,
    private val fulfillment: StockFulfillmentPersistence,
    private val stockService: StockFulfillmentService,
    private val work: WorkPersistencePort,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    @Value("\${hiltech.field.material-usage-reconciliation.enabled:false}")
    private val enabled: Boolean = false,
) {
    fun reconcile(
        context: StockCommandContext,
        warehouseId: UUID,
        reportOperationId: UUID,
        request: FieldMaterialUsageReconciliationRequest,
    ): FieldMaterialUsageReconciliationReceipt {
        if (!enabled) hidden()
        if (request.reportWorkVersion < 1 || request.reportIssuedVersion < 1) invalid()
        val decision = runCatching { FieldMaterialUsageDecision.valueOf(request.decision.trim().uppercase()) }
            .getOrElse { invalid() }
        val reason = request.reason?.trim()?.takeIf { it.isNotEmpty() }
        if (request.reason != null && (reason == null || reason.length > 500)) invalid()
        if (decision == FieldMaterialUsageDecision.REJECT && reason == null) invalid()

        val initial = persistence.source(reportOperationId) ?: hidden()
        requireSnapshot(initial, request)
        requireReviewAuthority(context.actor, warehouseId, initial)

        val fingerprint = IdempotencyKeyContract.fingerprint(
            listOf(
                warehouseId, reportOperationId, decision.name,
                request.reportWorkVersion, request.reportIssuedVersion, reason.orEmpty(),
            ).joinToString("|"),
        )
        val executed = commands.execute(
            IdempotentCommandSpec(
                operationId = context.operationId,
                actorUserId = context.actor,
                commandType = "FIELD_MATERIAL_USAGE_RECONCILED",
                targetType = "FIELD_MATERIAL_USAGE_REPORT",
                targetId = reportOperationId,
                requestFingerprint = fingerprint,
                correlationId = context.correlationId,
            ),
        ) {
            val source = persistence.source(reportOperationId, lock = true) ?: hidden()
            requireSnapshot(source, request)
            requireReviewAuthority(context.actor, warehouseId, source)
            if (persistence.reconciliation(reportOperationId) != null) {
                conflict("FIELD_MATERIAL_USAGE_ALREADY_RECONCILED")
            }

            val now = clock.instant()
            var stockOperationId: UUID? = null
            var stockMovementId: UUID? = null
            if (decision == FieldMaterialUsageDecision.CONSUME) {
                val order = work.workOrder(source.workOrderId)?.takeIf {
                    it.organizationId == source.organizationId &&
                        it.projectId == source.projectId &&
                        it.siteId == source.siteId
                } ?: hidden()
                if (order.version != request.reportWorkVersion) conflict("FIELD_MATERIAL_USAGE_WORK_CHANGED")
                val issued = fulfillment.issued(source.stockItemId, source.workOrderId).singleOrNull {
                    it.employeeId == source.employeeId && it.requirementId == source.requirementId
                } ?: hidden()
                if (issued.version != request.reportIssuedVersion) {
                    conflict("FIELD_MATERIAL_USAGE_ISSUED_STOCK_CHANGED")
                }
                val outstanding = issued.outstandingQuantity.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }
                    ?: conflict("FIELD_MATERIAL_USAGE_ISSUED_STOCK_CHANGED")
                if (source.quantity > outstanding) conflict("FIELD_MATERIAL_USAGE_EXCEEDS_OUTSTANDING")

                stockOperationId = consumeOperationId(reportOperationId)
                val consumed = stockService.consume(
                    StockCommandContext(stockOperationId, context.actor, context.correlationId),
                    source.stockItemId,
                    AdjustIssuedStockRequest(
                        operationId = stockOperationId,
                        workOrderId = source.workOrderId,
                        requirementId = source.requirementId,
                        employeeId = source.employeeId,
                        workVersion = request.reportWorkVersion,
                        issuedVersion = request.reportIssuedVersion,
                        quantity = source.quantity.stripTrailingZeros().toPlainString(),
                        unitCode = source.unitCode,
                    ),
                )
                stockMovementId = consumed.movementId
            }

            persistence.insert(
                FieldMaterialUsageReconciliationRecord(
                    operationId = context.operationId,
                    reportOperationId = source.operationId,
                    organizationId = source.organizationId,
                    warehouseId = warehouseId,
                    reviewerUserId = context.actor,
                    decision = decision,
                    stockOperationId = stockOperationId,
                    stockMovementId = stockMovementId,
                    reason = reason,
                    createdAt = now,
                    correlationId = context.correlationId,
                ),
            )
            audit.append(
                AuditEventRecord(
                    actorUserId = context.actor,
                    action = "FIELD_MATERIAL_USAGE_${decision.name}",
                    targetType = "FIELD_MATERIAL_USAGE_REPORT",
                    targetId = source.operationId,
                    newStateRef = stockMovementId?.toString() ?: decision.name,
                    safeDiffJson = """{"fields":["materialUsageReview","${decision.name.lowercase()}"]}""",
                    occurredAt = now,
                    correlationId = context.correlationId,
                ),
            )
            IdempotentCommandOutcome(
                decision.name,
                """{"reportOperationId":"${source.operationId}","decision":"${decision.name}","stockMoved":${stockMovementId != null}}""",
            )
        }

        val result = persistence.reconciliation(reportOperationId)
            ?: conflict("FIELD_MATERIAL_USAGE_RECONCILIATION_MISSING")
        return FieldMaterialUsageReconciliationReceipt(
            reportOperationId = reportOperationId,
            decision = result.decision.name,
            stockMoved = result.stockMovementId != null,
            movementId = result.stockMovementId,
            replayed = executed.replayed,
        )
    }

    private fun requireSnapshot(
        source: FieldMaterialUsageReviewSource,
        request: FieldMaterialUsageReconciliationRequest,
    ) {
        if (source.workVersion != request.reportWorkVersion ||
            source.issuedVersion != request.reportIssuedVersion) {
            conflict("FIELD_MATERIAL_USAGE_REVIEW_SNAPSHOT_CHANGED")
        }
    }

    private fun requireReviewAuthority(actor: UUID, warehouseId: UUID, source: FieldMaterialUsageReviewSource) {
        val facility = warehouse.warehouse(warehouseId)?.takeIf {
            it.active && it.organizationId == source.organizationId &&
                authorization.canReserveResources(actor, it)
        } ?: hidden()
        val origins = fulfillment.origins(source.stockItemId, source.requirementId, source.employeeId)
        if (origins.isEmpty()) hidden()
        val allowed = origins.all { locationId ->
            val location = warehouse.storageLocation(locationId)
            location != null &&
                location.organizationId == facility.organizationId &&
                location.warehouseId == warehouseId &&
                authorization.canTransferStock(actor, location)
        }
        if (!allowed) hidden()
    }

    private fun hidden(): Nothing = throw ProductApiException(
        "FIELD_MATERIAL_REVIEW_UNAVAILABLE",
        "The material review context is not available.",
        HttpStatus.NOT_FOUND,
    )

    private fun invalid(): Nothing = throw ProductApiException(
        "FIELD_MATERIAL_REVIEW_INVALID",
        "Invalid material review request.",
        HttpStatus.BAD_REQUEST,
    )

    private fun conflict(code: String): Nothing = throw ProductApiException(
        code,
        "Material review source changed; reload before continuing.",
        HttpStatus.CONFLICT,
    )
}

@RestController
class FieldMaterialUsageReconciliationController(
    private val service: FieldMaterialUsageReconciliationService,
) {
    @PostMapping("/v1/warehouses/{warehouseId}/material-usage-reports/{reportOperationId}/reconcile")
    fun reconcile(
        request: HttpServletRequest,
        @PathVariable warehouseId: UUID,
        @PathVariable reportOperationId: UUID,
        @RequestBody body: FieldMaterialUsageReconciliationRequest,
    ) = service.reconcile(request.stockContext(body.operationId), warehouseId, reportOperationId, body)
}

private fun consumeOperationId(reportOperationId: UUID): UUID =
    UUID.nameUUIDFromBytes(
        "hiltech:field-material-usage:consume:$reportOperationId".toByteArray(Charsets.UTF_8),
    )
