package com.hiltech.server.warehouse

import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.platform.command.JdbcIdempotentCommandExecutor
import com.hiltech.server.telemetry.HiltechTelemetryRuntime
import io.opentelemetry.api.OpenTelemetry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

internal class ReceivingOperatorScenario(
    private val jdbc: JdbcTemplate,
    private val manager: PlatformTransactionManager,
    private val warehouse: JdbcWarehousePersistence,
    private val authority: WarehouseAuthorizationPort,
    private val bridge: WarehouseAuthorizationProjectionBridge,
    private val clock: Clock,
    private val organization: UUID,
    private val operator: UUID,
    private val unauthorized: UUID,
) {
    private val stock = StockLedgerPersistence(jdbc)
    private val assets = AssetPassportPersistence(jdbc)
    private val receiving = ReceivingPersistence(jdbc)
    private val facility = UUID.randomUUID()
    private lateinit var location: StorageLocationSnapshot
    private val item = UUID.randomUUID()
    private val serialized = UUID.randomUUID()

    fun exercise(project: () -> Unit) {
        WarehouseManagerProvisioner(
            jdbc, org.springframework.transaction.support.TransactionTemplate(manager), warehouse,
            com.hiltech.server.security.JdbcRoleTeamSourceAuthority(jdbc), bridge,
            JdbcAuditEventWriter(jdbc), clock,
        ).provision(organization, facility, operator, operator, "SYNTH-RECEIVE", "Synthetic receiving warehouse")
        project()

        val createStorage = WarehouseStorageLocationCreateService(
            warehouse, authority, executor(), bridge, JdbcAuditEventWriter(jdbc), clock,
        )
        location = createStorage.create(CreateStorageLocationCommand(
            operationId = UUID.randomUUID(), warehouseId = facility, organizationId = organization,
            code = "SYNTH-RECEIVE-BIN", name = "Synthetic receiving bin", kind = StorageLocationKind.WAREHOUSE,
            parentStorageLocationId = null, projectId = null, siteId = null, temporary = false, activeFrom = null,
            restrictedAccess = false, addressOrLocationRef = null, notes = null, actorUserId = operator,
            clientOccurredAt = clock.instant(), correlationId = "synthetic-receiving",
        )).storageLocation
        project()
        assertTrue(authority.canReceiveAt(operator, location))
        assertFalse(authority.canReceiveAt(unauthorized, location))

        seedStock()
        val service = service()

        assertEquals("RECEIVING_UNAVAILABLE", assertThrows<ProductApiException> {
            service.create(context(UUID.randomUUID(), unauthorized), CreateReceivingRequest(
                organization, location.storageLocationId, ReceivingSourceType.OTHER))
        }.code)

        val createOperation = UUID.randomUUID()
        val createRequest = CreateReceivingRequest(
            organization, location.storageLocationId, ReceivingSourceType.OTHER,
            sourceRef = "SYNTH-DELIVERY", deliveryDocumentRef = "SYNTH-DOC",
        )
        val record = service.create(context(createOperation), createRequest)
        assertEquals(record.record.id, service.create(context(createOperation), createRequest)
            .also { assertTrue(it.replayed) }.record.id)

        val line = service.addStockLine(context(UUID.randomUUID()), record.record.id, AddReceivingStockLineRequest(
            record.record.version, item, stock.item(item)!!.version, ReceivingCandidateClass.STOCK_QUANTITY,
            "5", "EA", "LINE-1",
        )).line
        val beforeAssetCount = jdbc.queryForObject("SELECT count(*) FROM asset", Long::class.java) ?: 0L
        val receiveRecordVersion = receiving.record(record.record.id)!!.version
        val receiveOperation = UUID.randomUUID()
        val receiveRequest = ReceiveStockLineRequest(
            recordVersion = receiveRecordVersion, lineVersion = line.version,
            stockItemVersion = stock.item(item)!!.version, quantity = "5", unitCode = "EA",
            condition = AssetCondition.GOOD,
        )
        val received = service.receiveStockLine(context(receiveOperation), record.record.id, line.id, receiveRequest)
        assertEquals("RECEIVE", received.movement.type)
        assertEquals("5.000000", stock.balances(item).single().onHandQuantity)
        val replay = service.receiveStockLine(context(receiveOperation), record.record.id, line.id, receiveRequest)
        assertTrue(replay.replayed)
        assertEquals("5.000000", stock.balances(item).single().onHandQuantity)
        assertEquals(beforeAssetCount, jdbc.queryForObject("SELECT count(*) FROM asset", Long::class.java))

        val current = receiving.record(record.record.id)!!
        assertEquals(ReceivingState.RECEIVED, service.complete(context(UUID.randomUUID()), current.id,
            CompleteReceivingRequest(current.version)).record.state)

        val serializedRecord = service.create(context(UUID.randomUUID()), CreateReceivingRequest(
            organization, location.storageLocationId, ReceivingSourceType.OTHER, sourceRef = "SERIALIZED-STOCK",
        )).record
        val serializedLine = service.addStockLine(context(UUID.randomUUID()), serializedRecord.id,
            AddReceivingStockLineRequest(serializedRecord.version, serialized, stock.item(serialized)!!.version,
                ReceivingCandidateClass.STOCK_SERIALIZED, "2", "EA", "SERIAL-LINE")).line
        service.receiveStockLine(context(UUID.randomUUID()), serializedRecord.id, serializedLine.id,
            ReceiveStockLineRequest(receiving.record(serializedRecord.id)!!.version, serializedLine.version,
                stock.item(serialized)!!.version, "2", "EA", AssetCondition.GOOD, listOf("SER-001","SER-002")))
        assertEquals("2.000000", stock.balances(serialized).single().onHandQuantity)
        assertEquals(beforeAssetCount, jdbc.queryForObject("SELECT count(*) FROM asset", Long::class.java))

        val wrongUnitRecord = service.create(context(UUID.randomUUID()), CreateReceivingRequest(
            organization, location.storageLocationId, ReceivingSourceType.OTHER)).record
        val wrongUnitLine = service.addStockLine(context(UUID.randomUUID()), wrongUnitRecord.id,
            AddReceivingStockLineRequest(wrongUnitRecord.version, item, stock.item(item)!!.version,
                ReceivingCandidateClass.STOCK_QUANTITY, "1", "EA")).line
        assertEquals("INVALID_RECEIVING", assertThrows<ProductApiException> {
            service.receiveStockLine(context(UUID.randomUUID()), wrongUnitRecord.id, wrongUnitLine.id,
                ReceiveStockLineRequest(receiving.record(wrongUnitRecord.id)!!.version, wrongUnitLine.version,
                    stock.item(item)!!.version, "1", "BOX", AssetCondition.GOOD))
        }.code)

        val shortRecord = service.create(context(UUID.randomUUID()), CreateReceivingRequest(
            organization, location.storageLocationId, ReceivingSourceType.OTHER, sourceRef = "SHORT-DELIVERY",
        )).record
        val shortLine = service.addStockLine(context(UUID.randomUUID()), shortRecord.id,
            AddReceivingStockLineRequest(shortRecord.version, item, stock.item(item)!!.version,
                ReceivingCandidateClass.STOCK_QUANTITY, "5", "EA", "SHORT-LINE")).line
        service.receiveStockLine(context(UUID.randomUUID()), shortRecord.id, shortLine.id,
            ReceiveStockLineRequest(receiving.record(shortRecord.id)!!.version, shortLine.version,
                stock.item(item)!!.version, "4", "EA", AssetCondition.GOOD))
        val beforeExpected = receiving.line(shortLine.id)!!.expectedQuantity
        val discrepancy = service.addDiscrepancy(context(UUID.randomUUID()), shortRecord.id,
            AddReceivingDiscrepancyRequest(receiving.record(shortRecord.id)!!.version, shortLine.id,
                ReceivingDiscrepancyType.SHORT_QTY, "One unit missing at physical receipt"))
        assertEquals("5.000000", beforeExpected)
        assertEquals(beforeExpected, receiving.line(shortLine.id)!!.expectedQuantity)
        assertEquals(ReceivingDiscrepancyType.SHORT_QTY, discrepancy.discrepancy.type)
        assertEquals(1, service.detail(operator, shortRecord.id).discrepancies.size)
        assertTrue(service.directory(operator, facility).items.any { it.id == shortRecord.id })
        val shortCurrent = receiving.record(shortRecord.id)!!
        assertEquals(ReceivingState.RECEIVED_WITH_DISCREPANCY,
            service.complete(context(UUID.randomUUID()), shortRecord.id,
                CompleteReceivingRequest(shortCurrent.version)).record.state)

        val assetType = seedAssetType()
        val assetRecord = service.create(context(UUID.randomUUID()), CreateReceivingRequest(
            organization, location.storageLocationId, ReceivingSourceType.OTHER, sourceRef = "EXPLICIT-ASSET",
        )).record
        val assetLine = service.addAssetLine(context(UUID.randomUUID()), assetRecord.id,
            AddReceivingAssetLineRequest(assetRecord.version, assetType, "ASSET-LINE")).line
        val assetRecordVersion = receiving.record(assetRecord.id)!!.version
        val assetOp = UUID.randomUUID()
        val assetRequest = ReceiveAssetLineRequest(assetRecordVersion, assetLine.version,
            "SYNTH-RX-ASSET", "Synthetic received tool", serialNumber = "RX-SER-001",
            ownershipType = "COMPANY", condition = AssetCondition.GOOD)
        val assetReceipt = service.receiveAssetLine(context(assetOp), assetRecord.id, assetLine.id, assetRequest)
        assertEquals(assetLine.id, receiving.line(assetLine.id)!!.id)
        assertEquals(assetReceipt.asset.id, receiving.line(assetLine.id)!!.receivedAssetId)
        assertEquals(location.storageLocationId, assets.custody(assetReceipt.asset.id)!!.storageLocationId)
        assertEquals("REGISTER_INITIAL_LOCATION",
            jdbc.queryForObject("SELECT movement_type FROM asset_movement WHERE id = ?", String::class.java, assetReceipt.movementId))
        assertEquals(assetLine.id,
            jdbc.queryForObject("SELECT receiving_line_id FROM asset_movement WHERE id = ?", UUID::class.java, assetReceipt.movementId))
        val assetCount = jdbc.queryForObject("SELECT count(*) FROM asset WHERE id = ?", Long::class.java, assetReceipt.asset.id)
        val replayAsset = service.receiveAssetLine(context(assetOp), assetRecord.id, assetLine.id, assetRequest)
        assertTrue(replayAsset.replayed)
        assertEquals(assetCount, jdbc.queryForObject("SELECT count(*) FROM asset WHERE id = ?", Long::class.java, assetReceipt.asset.id))

        val serializedStillStock = receiving.line(serializedLine.id)!!
        assertEquals(ReceivingCandidateClass.STOCK_SERIALIZED, serializedStillStock.candidateClass)
        assertEquals(null, serializedStillStock.receivedAssetId)

        val poOnly = service.create(context(UUID.randomUUID()), CreateReceivingRequest(
            organization, location.storageLocationId, ReceivingSourceType.PO,
            sourceRef = "PO-SYNTH-ONLY", deliveryDocumentRef = "INVOICE-TOTAL-IS-NOT-A-LINE",
        )).record
        assertEquals("RECEIVING_INCOMPLETE", assertThrows<ProductApiException> {
            service.complete(context(UUID.randomUUID()), poOnly.id, CompleteReceivingRequest(poOnly.version))
        }.code)

        jdbc.update("UPDATE storage_location SET lifecycle_state = 'RETIRED', active_until = ? WHERE id = ?",
            clock.instant().atOffset(ZoneOffset.UTC), location.storageLocationId)
        assertEquals("RECEIVING_UNAVAILABLE", assertThrows<ProductApiException> {
            service.create(context(UUID.randomUUID()), CreateReceivingRequest(
                organization, location.storageLocationId, ReceivingSourceType.OTHER))
        }.code)
    }

    private fun seedAssetType(): UUID {
        val at = clock.instant().atOffset(ZoneOffset.UTC)
        val id = UUID.randomUUID()
        jdbc.update("""INSERT INTO config_revision (id, scope_type, scope_organization_id, family, code, name,
            lifecycle_state, revision_number, created_at, created_by, activated_at, activated_by)
            VALUES (?, 'ORGANIZATION', ?, 'asset-types', 'SYNTH-RECEIVE-ASSET', 'Synthetic received Asset type',
            'ACTIVE', 1, ?, ?, ?, ?)""", id, organization, at, operator, at, operator)
        jdbc.update("""INSERT INTO asset_type_definition
            (config_revision_id, calibration_required, high_value_or_restricted, serial_required,
             allowed_tag_types, operator_may_tag, inspection_required_on_return)
            VALUES (?, false, false, true, '{}', false, false)""", id)
        return id
    }

    private fun seedStock() {
        val at = clock.instant().atOffset(ZoneOffset.UTC)
        val category = UUID.randomUUID()
        jdbc.update("""INSERT INTO config_revision (id, scope_type, scope_organization_id, family, code, name,
            lifecycle_state, revision_number, created_at, created_by, activated_at, activated_by)
            VALUES (?, 'ORGANIZATION', ?, 'stock-categories', 'SYNTH-RECEIVE', 'Synthetic receiving category',
            'ACTIVE', 1, ?, ?, ?, ?)""", category, organization, at, operator, at, operator)
        jdbc.update("INSERT INTO stock_item_category_definition VALUES (?, 'EA')", category)
        stock.registerUnit(organization, "EA", "Each")
        stock.insertItem(StockItemView(item, organization, "SYNTH-RECEIVE", "Synthetic receive stock", null, null, null,
            category, 1, "EA", StockClassification.STOCK_QUANTITY, false, false, true, null, 1))
        stock.insertItem(StockItemView(serialized, organization, "SYNTH-RECEIVE-SER", "Synthetic serialized stock", null, null, null,
            category, 1, "EA", StockClassification.STOCK_SERIALIZED, true, false, true, null, 1))
    }

    private fun service(): ReceivingService {
        val events = ApplicationEventPublisher { }
        val writer = ReceivingAssetWriter(
            assets, warehouse, authority,
            com.hiltech.server.security.AuthorizationProjectionIntentWriter { }, events,
        )
        return ReceivingService(
            receiving, stock, writer, warehouse, authority, executor(), JdbcAuditEventWriter(jdbc),
            events, clock,
        )
    }

    private fun executor() = JdbcIdempotentCommandExecutor(
        jdbc, manager, clock, HiltechTelemetryRuntime(OpenTelemetry.noop()) {},
    )

    private fun context(id: UUID, actor: UUID = operator) =
        ReceivingCommandContext(id, actor, "synthetic-receiving")
}
