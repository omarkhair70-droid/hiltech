package com.hiltech.server.warehouse

import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.platform.command.JdbcIdempotentCommandExecutor
import com.hiltech.server.telemetry.HiltechTelemetryRuntime
import io.opentelemetry.api.OpenTelemetry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

internal class StocktakeOperatorScenario(
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
    private val stocktakes = StocktakePersistence(jdbc)
    private val facility = UUID.randomUUID()
    private lateinit var location: StorageLocationSnapshot
    private val item = UUID.randomUUID()

    fun exercise(project: () -> Unit) {
        WarehouseManagerProvisioner(
            jdbc, org.springframework.transaction.support.TransactionTemplate(manager), warehouse,
            com.hiltech.server.security.JdbcRoleTeamSourceAuthority(jdbc), bridge,
            JdbcAuditEventWriter(jdbc), clock,
        ).provision(organization, facility, operator, operator, "SYNTH-STOCKTAKE", "Synthetic stocktake warehouse")
        project()

        location = WarehouseStorageLocationCreateService(
            warehouse, authority, executor(), bridge, JdbcAuditEventWriter(jdbc), clock,
        ).create(CreateStorageLocationCommand(
            operationId = UUID.randomUUID(), warehouseId = facility, organizationId = organization,
            code = "SYNTH-COUNT-BIN", name = "Synthetic count bin", kind = StorageLocationKind.WAREHOUSE,
            parentStorageLocationId = null, projectId = null, siteId = null, temporary = false, activeFrom = null,
            restrictedAccess = false, addressOrLocationRef = null, notes = null, actorUserId = operator,
            clientOccurredAt = clock.instant(), correlationId = "synthetic-stocktake",
        )).storageLocation
        project()
        assertTrue(authority.canCountAt(operator, location))
        assertFalse(authority.canCountAt(unauthorized, location))

        seedStock()
        receive("5", "STOCKTAKE-OPENING")
        val service = service()

        assertEquals("STOCKTAKE_UNAVAILABLE", assertThrows<ProductApiException> {
            service.create(context(UUID.randomUUID(), unauthorized), CreateStocktakeRequest(
                organization, facility, location.storageLocationId))
        }.code)

        val createOp = UUID.randomUUID()
        val created = service.create(context(createOp), CreateStocktakeRequest(
            organization, facility, location.storageLocationId, stockItemIds = listOf(item)))
        assertEquals(created.session.id, service.create(context(createOp), CreateStocktakeRequest(
            organization, facility, location.storageLocationId, stockItemIds = listOf(item)))
            .also { assertTrue(it.replayed) }.session.id)

        val started = service.start(context(UUID.randomUUID()), created.session.id, StartStocktakeRequest(created.session.version))
        assertEquals(StocktakeState.ACTIVE, started.session.state)
        val snapshot = started.snapshotLines.single()
        assertEquals("5.000000", snapshot.expectedOnHand)
        assertEquals(1L, snapshot.balanceVersion)

        assertEquals("STOCKTAKE_INCOMPLETE", assertThrows<ProductApiException> {
            service.submit(context(UUID.randomUUID()), started.session.id, SubmitStocktakeRequest(started.session.version))
        }.code)

        receive("1", "STOCKTAKE-AFTER-SNAPSHOT")
        val current = stock.balances(item).single()
        assertEquals("6.000000", current.onHandQuantity)
        assertNotEquals(snapshot.balanceVersion, current.version)
        assertEquals("5.000000", stocktakes.snapshotLine(snapshot.id)!!.expectedOnHand)

        val countOp = UUID.randomUUID()
        val countRequest = RecordStockCountRequest(
            started.session.version, snapshot.id, "5", snapshot.unitCode, "SCAN-SYNTH-001", "First physical count")
        val first = service.recordCount(context(countOp), started.session.id, countRequest)
        assertEquals("5.000000", first.count.countedQuantity)
        val replay = service.recordCount(context(countOp), started.session.id, countRequest)
        assertTrue(replay.replayed)
        assertEquals(1, stocktakes.counts(snapshot.id).size)
        assertEquals("6.000000", stock.balances(item).single().onHandQuantity)

        val recount = service.recordCount(context(UUID.randomUUID()), started.session.id,
            RecordStockCountRequest(started.session.version, snapshot.id, "6", snapshot.unitCode,
                "SCAN-SYNTH-002", "Verified recount"))
        assertEquals(first.count.id, recount.count.supersedesCountRecordId)
        assertEquals(2, stocktakes.counts(snapshot.id).size)
        assertEquals("6.000000", stock.balances(item).single().onHandQuantity)

        val submitOp = UUID.randomUUID()
        val submitted = service.submit(
            context(submitOp), started.session.id, SubmitStocktakeRequest(started.session.version))
        assertEquals(StocktakeState.SUBMITTED, submitted.session.state)
        val discrepancy = submitted.discrepancies.single()
        assertEquals(snapshot.id, discrepancy.snapshotLineId)
        assertEquals(recount.count.id, discrepancy.latestCountRecordId)
        assertEquals("5.000000", discrepancy.expectedQuantity)
        assertEquals("6.000000", discrepancy.countedQuantity)
        assertEquals("1.000000", discrepancy.varianceQuantity)
        assertEquals(snapshot.balanceVersion, discrepancy.snapshotBalanceVersion)
        assertEquals(current.version, discrepancy.currentBalanceVersion)
        assertTrue(discrepancy.sourceChangedSinceSnapshot)
        assertEquals("OPEN", discrepancy.state)
        assertEquals("6.000000", stock.balances(item).single().onHandQuantity)

        val submitReplay = service.submit(
            context(submitOp), started.session.id, SubmitStocktakeRequest(started.session.version))
        assertTrue(submitReplay.replayed)
        assertEquals(discrepancy.id, submitReplay.discrepancies.single().id)
        assertEquals(1, stocktakes.discrepancies(started.session.id).size)
        assertEquals("6.000000", stock.balances(item).single().onHandQuantity)
    }

    private fun seedStock() {
        val at = clock.instant().atOffset(ZoneOffset.UTC)
        val category = UUID.randomUUID()
        jdbc.update("""INSERT INTO config_revision (id, scope_type, scope_organization_id, family, code, name,
            lifecycle_state, revision_number, created_at, created_by, activated_at, activated_by)
            VALUES (?, 'ORGANIZATION', ?, 'stock-categories', 'SYNTH-STOCKTAKE', 'Synthetic stocktake category',
            'ACTIVE', 1, ?, ?, ?, ?)""", category, organization, at, operator, at, operator)
        jdbc.update("INSERT INTO stock_item_category_definition VALUES (?, 'EA')", category)
        stock.registerUnit(organization, "EA", "Each")
        stock.insertItem(StockItemView(
            item, organization, "SYNTH-COUNT-ITEM", "Synthetic count item", null, null, null,
            category, 1, "EA", StockClassification.STOCK_QUANTITY, false, false, true, null, 1,
        ))
    }

    private fun receive(quantity: String, source: String) {
        val events = ApplicationEventPublisher { }
        val receiving = ReceivingPersistence(jdbc)
        val receivingService = ReceivingService(
            receiving, stock,
            ReceivingAssetWriter(
                AssetPassportPersistence(jdbc), warehouse, authority,
                com.hiltech.server.security.AuthorizationProjectionIntentWriter { }, events,
            ),
            warehouse, authority, executor(), JdbcAuditEventWriter(jdbc), events, clock,
        )
        val record = receivingService.create(context(UUID.randomUUID()).receiving(), CreateReceivingRequest(
            organization, location.storageLocationId, ReceivingSourceType.OTHER, sourceRef = source,
        )).record
        val line = receivingService.addStockLine(context(UUID.randomUUID()).receiving(), record.id,
            AddReceivingStockLineRequest(record.version, item, stock.item(item)!!.version,
                ReceivingCandidateClass.STOCK_QUANTITY, quantity, "EA", source)).line
        receivingService.receiveStockLine(context(UUID.randomUUID()).receiving(), record.id, line.id,
            ReceiveStockLineRequest(receiving.record(record.id)!!.version, line.version, stock.item(item)!!.version,
                quantity, "EA", AssetCondition.GOOD))
    }

    private fun service() = StocktakeService(
        stocktakes, stock, warehouse, authority, executor(), JdbcAuditEventWriter(jdbc),
        ApplicationEventPublisher { }, clock,
    )

    private fun executor() = JdbcIdempotentCommandExecutor(
        jdbc, manager, clock, HiltechTelemetryRuntime(OpenTelemetry.noop()) {},
    )

    private fun context(id: UUID, actor: UUID = operator) =
        StocktakeCommandContext(id, actor, "synthetic-stocktake")

    private fun StocktakeCommandContext.receiving() =
        ReceivingCommandContext(operationId, actor, correlationId)
}
