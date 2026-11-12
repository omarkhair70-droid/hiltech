package com.hiltech.server.warehouse

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.security.RoleTeamSourceAuthorityPort
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class StockCommandContext(val operationId: UUID, val actor: UUID, val correlationId: String)
data class StockCatalogView(val units: List<StockUnitView>, val categories: List<StockCategoryView>)
data class StockMovementReceipt(val movement: StockMovementView, val replayed: Boolean)

@Service
class StockLedgerService(
    private val ledger: StockLedgerPersistence,
    private val warehouse: WarehousePersistencePort,
    private val authorization: WarehouseAuthorizationPort,
    private val sourceAuthority: RoleTeamSourceAuthorityPort,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    fun catalog(actor: UUID, org: UUID): StockCatalogView {
        requireCatalogAccess(actor, org)
        return StockCatalogView(ledger.units(org), ledger.categories(org, clock.instant()))
    }

    fun items(actor: UUID, org: UUID, afterCode: String?): List<StockItemView> {
        requireCatalogAccess(actor, org)
        return ledger.items(org, afterCode)
    }

    fun item(actor: UUID, id: UUID): StockItemView {
        val item = ledger.item(id) ?: throw stockNotFound()
        requireCatalogAccess(actor, item.organizationId)
        return item
    }

    fun balances(actor: UUID, id: UUID): List<StockBalanceView> {
        val visible = visibleLocations(actor, item(actor, id).organizationId).toSet()
        return ledger.balances(id).filter { it.storageLocationId in visible }
    }

    fun movements(actor: UUID, id: UUID): List<StockMovementView> =
        ledger.movements(id, visibleLocations(actor, item(actor, id).organizationId))

    fun registerUnit(context: StockCommandContext, warehouseId: UUID, rawCode: String, rawName: String): StockUnitView {
        val org = managedWarehouse(context.actor, warehouseId).organizationId
        val code = stockCode(rawCode, 32)
        val name = stockText(rawName, 160, "Unit name")
        execute(context, "STOCK_UNIT_REGISTERED", "WAREHOUSE", warehouseId, warehouseId, code, name) {
            managedWarehouse(context.actor, warehouseId)
            if (ledger.registerUnit(org, code, name) != 1) {
                throw stockConflict("STOCK_UNIT_EXISTS", "This organization already has an active unit with that code.")
            }
        }
        return StockUnitView(code, name)
    }

    fun createItem(context: StockCommandContext, request: CreateStockItemRequest): StockItemView {
        val org = managedWarehouse(context.actor, request.warehouseId).organizationId
        val id = UUID.nameUUIDFromBytes("hiltech:stock-item:${context.operationId}".toByteArray(Charsets.UTF_8))
        val code = stockCode(request.itemCode, 64)
        val name = stockText(request.name, 160, "Stock item name")
        val unit = stockCode(request.baseUnitCode, 32)
        val brand = request.brand?.let { stockText(it, 160, "Brand") }
        val part = request.partNumber?.let { stockText(it, 160, "Part number") }
        val description = request.description?.let { stockText(it, 4000, "Description") }
        val lotTracked = request.lotTracked == true
        execute(context, "STOCK_ITEM_CREATED", "STOCK_ITEM", id,
            request.warehouseId, code, name, description, brand, part,
            request.categoryDefinitionId, unit, request.classification, lotTracked) {
            managedWarehouse(context.actor, request.warehouseId)
            insertItem(id, org, code, name, description, brand, part, request.categoryDefinitionId,
                unit, request.classification, lotTracked, null)
        }
        return requireNotNull(ledger.item(id))
    }

    fun approveImport(context: StockCommandContext, batchId: UUID, rowId: UUID, request: ApproveStockItemRequest): StockItemView {
        val batch = warehouse.importBatch(batchId) ?: throw stockNotFound()
        val verification = requireVerification(context.actor, batch.organizationId, rowId)
        val itemId = UUID.nameUUIDFromBytes("hiltech:stock-import:$rowId".toByteArray(Charsets.UTF_8))
        val code = stockCode(request.itemCode, 64)
        val name = stockText(request.name, 160, "Stock item name")
        val lotTracked = request.lotTracked == true
        execute(context, "STOCK_IMPORT_APPROVED", "STOCK_ITEM", itemId,
            batchId, rowId, request.baseVersion, code, name, lotTracked) {
            val lockedBatch = warehouse.lockImportBatch(batchId) ?: throw stockNotFound()
            val row = warehouse.importRow(rowId)?.takeIf { it.batchId == batchId && it.organizationId == batch.organizationId }
                ?: throw stockNotFound()
            val proof = requireVerification(context.actor, lockedBatch.organizationId, rowId, lock = true)
            if (lockedBatch.state == InventoryImportBatchState.CANCELLED ||
                row.reviewState != InventoryImportReviewState.NEEDS_REVIEW ||
                row.version != request.baseVersion || row.version != proof.rowVersion ||
                lockedBatch.sourceSha256 != proof.sourceSha256 || proof != verification ||
                row.issueCodes.map { it.name }.toSet() != proof.resolvedIssues) {
                throw stockConflict("STOCK_SOURCE_REVIEW_REQUIRED", "The row must match independently verified source and mapping evidence.")
            }
            val classification = when (row.candidateClass) {
                InventoryCandidateClass.STOCK_QUANTITY -> StockClassification.STOCK_QUANTITY
                InventoryCandidateClass.STOCK_SERIALIZED -> StockClassification.STOCK_SERIALIZED
                else -> throw stockConflict("STOCK_SOURCE_REVIEW_REQUIRED", "A reviewed Stock classification is required.")
            }
            val description = row.normalizedDescription
                ?: throw stockConflict("STOCK_SOURCE_REVIEW_REQUIRED", "A reviewed description is required.")
            val unit = row.candidateUom
                ?: throw stockConflict("STOCK_SOURCE_REVIEW_REQUIRED", "An explicit canonical unit mapping is required.")
            insertItem(itemId, row.organizationId, code, name, description, row.normalizedBrand,
                row.normalizedPartNumber, proof.categoryId, unit, classification, lotTracked, rowId)
            if (ledger.approveImport(rowId, itemId, context.actor, clock.instant(), row.version) != 1) {
                throw stockConflict("STOCK_SOURCE_REVIEW_REQUIRED", "The import row has changed.")
            }
        }
        return requireNotNull(ledger.item(itemId))
    }

    fun opening(context: StockCommandContext, id: UUID, request: InitializeStockOpeningRequest): StockMovementReceipt {
        val item = ledger.item(id) ?: throw stockNotFound()
        val proof = requireVerification(context.actor, item.organizationId, request.sourceImportRowId)
        if (proof.locationId != request.storageLocationId || item.sourceImportRowId != request.sourceImportRowId) throw stockNotFound()
        val quantity = stockQuantity(request.quantity)
        val cutover = stockInstant(request.cutoverAt)
        val replayed = execute(context, "STOCK_OPENING_INITIALIZED", "STOCK_ITEM", id,
            request.baseVersion, request.storageLocationId, request.sourceImportRowId,
            request.unitCode, quantity.toPlainString(), cutover) {
            val locked = lockedItem(id, request.baseVersion, request.unitCode, quantity)
            val current = requireVerification(context.actor, locked.organizationId, request.sourceImportRowId, lock = true)
            if (current != proof || cutover != proof.cutoverAt || cutover > clock.instant()) {
                throw stockConflict("STOCK_CUTOVER_NOT_READY", "Opening requires matching verified evidence and an effective cutover time.")
            }
            ledger.append(locked, context.operationId, context.actor, StockMovementType.INITIALIZE_OPENING, quantity,
                null, request.storageLocationId, request.sourceImportRowId, cutover, clock.instant(), context.correlationId)
        }
        return StockMovementReceipt(requireNotNull(ledger.movement(context.operationId)), replayed)
    }

    fun transfer(context: StockCommandContext, id: UUID, request: TransferStockRequest): StockMovementReceipt {
        val item = ledger.item(id) ?: throw stockNotFound()
        val locations = listOf(request.fromStorageLocationId, request.toStorageLocationId)
        if (locations[0] == locations[1]) throw stockInvalid("Transfer locations must be distinct.")
        locations.forEach { transferLocation(context.actor, item.organizationId, it) }
        val quantity = stockQuantity(request.quantity)
        val replayed = execute(context, "STOCK_TRANSFERRED", "STOCK_ITEM", id,
            request.baseVersion, locations[0], locations[1], request.unitCode, quantity.toPlainString()) {
            val locked = lockedItem(id, request.baseVersion, request.unitCode, quantity)
            locations.forEach { transferLocation(context.actor, locked.organizationId, it) }
            val now = clock.instant()
            ledger.append(locked, context.operationId, context.actor, StockMovementType.TRANSFER, quantity,
                locations[0], locations[1], null, now, now, context.correlationId)
        }
        return StockMovementReceipt(requireNotNull(ledger.movement(context.operationId)), replayed)
    }

    private fun insertItem(
        id: UUID, org: UUID, code: String, name: String, description: String?, brand: String?, part: String?,
        categoryId: UUID, unit: String, classification: StockClassification, lotTracked: Boolean, sourceRow: UUID?,
    ) {
        if (ledger.units(org).none { it.code == unit }) throw stockInvalid("Select an active canonical unit.")
        val category = ledger.categories(org, clock.instant()).firstOrNull { it.id == categoryId }
            ?: throw stockInvalid("Select an active Stock category revision.")
        ledger.insertItem(StockItemView(id, org, code, name, description, brand, part,
            category.id, category.revision, unit, classification, classification == StockClassification.STOCK_SERIALIZED,
            lotTracked, true, sourceRow, 1))
    }

    private fun requireVerification(actor: UUID, org: UUID, rowId: UUID, lock: Boolean = false): StockSourceVerification {
        if (!authorization.canManageInventoryOrganization(actor, org)) throw stockNotFound()
        val verification = ledger.verification(rowId, lock)
            ?: throw stockConflict("STOCK_SOURCE_REVIEW_REQUIRED", "Independent source verification is required.")
        val location = activeLocation(org, verification.locationId)
        val facility = location.warehouseId?.let(warehouse::warehouse)
        val authorizedLocation = if (facility == null) authorization.canTransferStock(actor, location)
            else facility.organizationId == org && authorization.canManageWarehouse(actor, facility)
        val now = clock.instant()
        if (verification.approverId != actor || !authorizedLocation || verification.verifiedAt > now ||
            !sourceAuthority.isOrganizationMemberCurrent(verification.verifiedBy, org, now)) throw stockNotFound()
        return verification
    }

    private fun managedWarehouse(actor: UUID, id: UUID): WarehouseSnapshot =
        warehouse.warehouse(id)?.takeIf { authorization.canManageMaster(actor, it) } ?: throw stockNotFound()

    private fun requireCatalogAccess(actor: UUID, org: UUID) {
        if (warehouse.warehouses(org).none { authorization.canViewWarehouse(actor, it) } &&
            visibleLocations(actor, org).isEmpty()) throw stockNotFound()
    }

    private fun visibleLocations(actor: UUID, org: UUID): List<UUID> =
        warehouse.storageLocations(org, null).filter { authorization.canViewStorage(actor, it) }.map { it.storageLocationId }

    private fun activeLocation(org: UUID, id: UUID): StorageLocationSnapshot {
        val now = clock.instant()
        return warehouse.storageLocation(id)?.takeIf {
            it.organizationId == org && it.lifecycleState == StorageLocationLifecycle.ACTIVE &&
                (it.warehouseId == null || warehouse.warehouse(it.warehouseId)?.active == true) &&
                (it.activeFrom == null || it.activeFrom <= now) && (it.activeUntil == null || it.activeUntil > now)
        } ?: throw stockNotFound()
    }

    private fun transferLocation(actor: UUID, org: UUID, id: UUID) {
        if (!authorization.canTransferStock(actor, activeLocation(org, id))) throw stockNotFound()
    }

    private fun lockedItem(id: UUID, version: Long, unit: String, quantity: BigDecimal): StockItemView {
        val item = ledger.item(id, lock = true)?.takeIf { it.active } ?: throw stockNotFound()
        if (item.version != version) throw stockConflict("STOCK_ITEM_VERSION_CONFLICT", "Reload the Stock item.")
        if (item.baseUnitCode != unit) throw stockInvalid("Quantity must use the item's canonical unit.")
        if (item.serialized && quantity.stripTrailingZeros().scale() > 0) {
            throw stockInvalid("Serialized Stock requires a whole quantity.")
        }
        return item
    }

    private fun execute(
        context: StockCommandContext, action: String, targetType: String, target: UUID,
        vararg fields: Any?, write: () -> Unit,
    ): Boolean = try {
        commands.execute(
            IdempotentCommandSpec(context.operationId, context.actor, action, targetType, target,
                warehouseCommandFingerprint(*fields), context.correlationId),
        ) {
            write()
            audit.append(AuditEventRecord(actorUserId = context.actor, action = action, targetType = targetType, targetId = target,
                occurredAt = clock.instant(), correlationId = context.correlationId))
            IdempotentCommandOutcome(action, """{"targetId":"$target"}""")
        }.replayed
    } catch (_: DataIntegrityViolationException) {
        throw stockConflict("STOCK_CONSTRAINT_CONFLICT", "The Stock command conflicts with current quantity, source or identity constraints.")
    }
}

private fun stockQuantity(value: String): BigDecimal {
    if (!Regex("[0-9]{1,14}(\\.[0-9]{1,6})?").matches(value)) throw stockInvalid("Enter an exact decimal quantity with up to six decimal places.")
    return BigDecimal(value).stripTrailingZeros().takeIf { it.signum() > 0 } ?: throw stockInvalid("Quantity must be positive.")
}

private fun stockCode(value: String, max: Int): String =
    stockText(value, max, "Code").uppercase(Locale.ROOT).also { if (it.length > max) throw stockInvalid("Code is too long.") }

private fun stockText(value: String, max: Int, field: String): String =
    value.trim().takeIf { it.isNotEmpty() && it.length <= max } ?: throw stockInvalid("$field is invalid.")

private fun stockInstant(value: String): Instant =
    runCatching { Instant.parse(value) }.getOrElse { throw stockInvalid("Enter a valid cutover timestamp with an offset.") }

private fun stockNotFound() = ProductApiException("STOCK_UNAVAILABLE", "Stock or its location is unavailable.", HttpStatus.NOT_FOUND)
private fun stockInvalid(message: String) = ProductApiException("INVALID_STOCK_COMMAND", message, HttpStatus.BAD_REQUEST)
private fun stockConflict(code: String, message: String) = ProductApiException(code, message, HttpStatus.CONFLICT)
