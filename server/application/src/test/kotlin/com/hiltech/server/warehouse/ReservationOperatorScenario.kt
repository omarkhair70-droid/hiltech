package com.hiltech.server.warehouse

import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.platform.command.JdbcIdempotentCommandExecutor
import com.hiltech.server.projects.ProjectAuthorizationPort
import com.hiltech.server.projects.ProjectsPersistencePort
import com.hiltech.server.telemetry.HiltechTelemetryRuntime
import com.hiltech.server.work.ReadinessAssignmentService
import com.hiltech.server.work.WorkOrderSnapshot
import com.hiltech.server.work.WorkPersistencePort
import com.hiltech.server.work.WorkResourceChanged
import io.opentelemetry.api.OpenTelemetry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class ReservationOperatorScenario(
    private val jdbc: JdbcTemplate, private val manager: PlatformTransactionManager,
    private val warehouse: JdbcWarehousePersistence, private val authority: WarehouseAuthorizationPort,
    private val bridge: WarehouseAuthorizationProjectionBridge, private val projects: ProjectsPersistencePort,
    private val projectAuth: ProjectAuthorizationPort, private val work: WorkPersistencePort,
    private val readiness: ReadinessAssignmentService, private val clock: Clock,
    private val pm: UUID, private val operator: UUID, private val first: WorkOrderSnapshot, private val second: WorkOrderSnapshot,
) {
    private val records = ReservationPersistence(jdbc)
    private val assets = AssetPassportPersistence(jdbc)
    private val stock = StockLedgerPersistence(jdbc)
    private val events = CopyOnWriteArrayList<WorkResourceChanged>()
    private val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
    private val at = now.atOffset(ZoneOffset.UTC)
    private val facility = UUID.randomUUID()
    private val location = UUID.randomUUID()
    private val asset = UUID.randomUUID()
    private val type = UUID.randomUUID()
    private val item = UUID.randomUUID()
    private val source = WarehouseWorkResourceSource(records, AssetServicePersistence(jdbc), stock, warehouse, assets)

    fun exercise(project: () -> Unit) {
        val binding = WarehouseManagerProvisioner(jdbc, org.springframework.transaction.support.TransactionTemplate(manager),
            warehouse, com.hiltech.server.security.JdbcRoleTeamSourceAuthority(jdbc), bridge, JdbcAuditEventWriter(jdbc), clock)
            .provision(first.organizationId, facility, operator, operator, "SYNTH-RESERVE", "Synthetic reserve warehouse")
        project()
        seed()
        val svc = service(Clock.fixed(now, ZoneOffset.UTC))
        assertTrue(svc.candidates(pm, first.workOrderId).canRequest)
        val firstStock = request(svc, first, "MATERIAL")
        val secondStock = request(svc, second, "MATERIAL")
        val firstAsset = request(svc, first, "ASSET")
        val secondAsset = request(svc, second, "ASSET")
        assertTrue(svc.queue(operator, facility).requests.any { it.workOrderId == first.workOrderId })
        assertEquals("STOCK_AVAILABLE", svc.candidates(pm, first.workOrderId).requirements.single { it.demand == firstStock }.reason)

        val expires = now.plusSeconds(600)
        fun assetRequest(order: WorkOrderSnapshot, demand: WorkResourceDemand) = ReserveAssetRequest(
            UUID.randomUUID(), order.workOrderId, demand.requirementId, work.workOrder(order.workOrderId)!!.version,
            demand.requirementVersion, assets.asset(asset)!!.version, expires.toString())
        val pmAttempt = assetRequest(first, firstAsset)
        denied("RESOURCE_UNAVAILABLE") { svc.reserveAsset(context(pmAttempt.operationId, pm), asset, pmAttempt) }
        denied("RESERVATION_VERSION_CONFLICT") {
            svc.reserveAsset(context(pmAttempt.operationId), asset, pmAttempt.copy(workVersion = pmAttempt.workVersion + 1))
        }
        val held = svc.reserveAsset(context(pmAttempt.operationId), asset, pmAttempt)
        assertEquals(held, svc.reserveAsset(context(pmAttempt.operationId), asset, pmAttempt))
        val collision = assetRequest(second, secondAsset)
        denied("RESERVATION_CAPACITY_CONFLICT") { svc.reserveAsset(context(collision.operationId), asset, collision) }
        val release = ReleaseReservationRequest(UUID.randomUUID(), held.version, "Synthetic scheduling change")
        assertEquals("RELEASED", svc.release(context(release.operationId), ResourceKind.ASSET, held.id, release).state)
        val replacement = assetRequest(second, secondAsset)
        assertEquals("ACTIVE", svc.reserveAsset(context(replacement.operationId), asset, replacement).state)

        val version = records.balances(item, now, location).single().version
        fun stockRequest(order: WorkOrderSnapshot, demand: WorkResourceDemand, quantity: String) = ReserveStockRequest(
            UUID.randomUUID(), order.workOrderId, demand.requirementId, work.workOrder(order.workOrderId)!!.version,
            demand.requirementVersion, version, location, quantity, "SYNTH-RSV", expires.toString())
        val shortage = stockRequest(first, firstStock, "11")
        denied("STOCK_SHORTAGE") { svc.reserveStock(context(shortage.operationId), item, shortage) }
        val attempts = listOf(stockRequest(first, firstStock, "8"), stockRequest(second, secondStock, "8"))
        val barrier = CyclicBarrier(2)
        val reservations = Executors.newFixedThreadPool(2).use { pool ->
            attempts.map { attempt ->
                pool.submit(Callable {
                    barrier.await(10, TimeUnit.SECONDS)
                    runCatching { svc.reserveStock(context(attempt.operationId), item, attempt) }
                })
            }.map { it.get(30, TimeUnit.SECONDS) }
        }
        val winner = reservations.single { it.isSuccess }.getOrThrow()
        assertEquals("RESERVATION_VERSION_CONFLICT", (reservations.single { it.isFailure }.exceptionOrNull() as ProductApiException).code)
        assertEquals("8.000000", records.balances(item, now, location).single().reserved.toPlainString())
        assertEquals("8.000000", jdbc.queryForObject(
            "SELECT reserved_qty FROM stock_balance WHERE stock_item_id = ? AND storage_location_id = ?", java.math.BigDecimal::class.java,
            item, location)?.toPlainString())
        val stockEvent = events.last { it.workOrderId == winner.workOrderId }
        assertEquals(attempts.single { it.workOrderId == winner.workOrderId }.workVersion, work.workOrder(winner.workOrderId)!!.version)
        readiness.resourceChanged(stockEvent)
        assertEquals("STOCK_RESERVED", readiness.readiness(pm, winner.workOrderId).requirements
            .single { it.requirementId == winner.requirementId }.evaluationReasonCode)

        assertEquals("0", records.balances(item, expires, location).single().reserved.toPlainString())
        service(Clock.fixed(expires, ZoneOffset.UTC)).expireDue()
        assertEquals("EXPIRED", records.reservation(ResourceKind.STOCK, winner.id, expires)?.state)
        assertEquals(2L, records.reservation(ResourceKind.STOCK, winner.id, expires)?.version)
        readiness.resourceChanged(events.last { it.workOrderId == winner.workOrderId })
        assertEquals("STOCK_AVAILABLE", readiness.readiness(pm, winner.workOrderId).requirements
            .single { it.requirementId == winner.requirementId }.evaluationReasonCode)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE stock_item_id = ?", Int::class.java, item))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM asset_movement WHERE asset_id = ?", Int::class.java, asset))
        assertEquals("0.000000", jdbc.queryForObject(
            "SELECT reserved_qty FROM stock_balance WHERE stock_item_id = ?", java.math.BigDecimal::class.java, item)?.toPlainString())
        assertTrue(warehouse.endWarehouseAuthorityBinding(binding, facility, 1, now))
        val revoked = assetRequest(first, firstAsset)
        denied("RESOURCE_UNAVAILABLE") { svc.reserveAsset(context(revoked.operationId), asset, revoked) }
        assertFalse(svc.forWork(pm, winner.workOrderId).isEmpty())
    }

    private fun request(svc: ReservationService, order: WorkOrderSnapshot, family: String): WorkResourceDemand {
        val current = svc.candidates(pm, order.workOrderId)
        val demand = current.requirements.first { it.demand.family == family }.demand
        val req = RequestWorkResource(UUID.randomUUID(), current.workVersion, demand.requirementVersion,
            type.takeIf { family == "ASSET" }, item.takeIf { family == "MATERIAL" }, "8".takeIf { family == "MATERIAL" },
            "SYNTH-RSV".takeIf { family == "MATERIAL" })
        return svc.request(context(req.operationId, pm), order.workOrderId, demand.requirementId, req)
    }

    private fun seed() {
        val org = first.organizationId
        jdbc.update("""INSERT INTO storage_location (id, organization_id, warehouse_id, code, name, kind, lifecycle_state)
            VALUES (?, ?, ?, 'SYNTH-RSV-BIN', 'Synthetic reserve bin', 'WAREHOUSE', 'ACTIVE')""", location, org, facility)
        val category = UUID.randomUUID()
        for ((id, family) in listOf(type to "asset-types", category to "stock-categories")) {
            jdbc.update("""INSERT INTO config_revision (id, scope_type, scope_organization_id, family, code, name,
                lifecycle_state, revision_number, created_at, created_by, activated_at, activated_by)
                VALUES (?, 'ORGANIZATION', ?, ?, 'SYNTH-RSV', 'Synthetic reservation configuration', 'ACTIVE', 1, ?, ?, ?, ?)""",
                id, org, family, at, operator, at, operator)
        }
        jdbc.update("INSERT INTO asset_type_definition (config_revision_id) VALUES (?)", type)
        jdbc.update("INSERT INTO stock_item_category_definition VALUES (?, 'SYNTH-RSV')", category)
        stock.registerUnit(org, "SYNTH-RSV", "Synthetic unit")
        val batch = UUID.randomUUID()
        val row = UUID.randomUUID()
        val digest = "c".repeat(64)
        jdbc.update("""INSERT INTO inventory_import_batch (id, organization_id, source_file_name, source_sha256,
            source_type, state, created_by_user_id, created_at, updated_at)
            VALUES (?, ?, 'synthetic-reservation-stock.xlsx', ?, 'LEGACY_EXCEL', 'IN_REVIEW', ?, ?, ?)""",
            batch, org, digest, operator, at, at)
        jdbc.update("""INSERT INTO inventory_import_row (id, batch_id, organization_id, source_sheet, source_row_number,
            raw_payload, candidate_class, normalized_description, candidate_uom, candidate_quantity, issue_codes,
            review_state, reviewed_by_user_id, reviewed_at, created_at, updated_at, version)
            VALUES (?, ?, ?, 'SYNTH', 2, '{}', 'STOCK_QUANTITY', 'Synthetic stock', 'SYNTH-RSV', 10, '{}',
            'NEEDS_REVIEW', ?, ?, ?, ?, 2)""", row, batch, org, operator, at, at, at)
        jdbc.update("""INSERT INTO stock_import_verification (source_row_id, organization_id, source_row_version, source_sha256,
            storage_location_id, category_definition_id, verified_by, cutover_approver_id, evidence_ref, verified_at, cutover_at)
            VALUES (?, ?, 2, ?, ?, ?, ?, ?, 'synthetic-reservations', ?, ?)""", row, org, digest, location, category, pm, operator, at, at)
        jdbc.update("""INSERT INTO stock_item (id, organization_id, item_code, name, category_definition_id,
            category_revision, unit_of_measure_code, classification, source_import_row_id)
            VALUES (?, ?, 'SYNTH-RSV', 'Synthetic stock', ?, 1, 'SYNTH-RSV', 'STOCK_QUANTITY', ?)""",
            item, org, category, row)
        assertEquals(1, stock.approveImport(row, item, operator, now, 2))
        stock.append(stock.item(item)!!, UUID.randomUUID(), operator, StockMovementType.INITIALIZE_OPENING, java.math.BigDecimal.TEN,
            null, location, row, now, now, "synthetic-reservations")
        jdbc.update("""INSERT INTO asset (id, organization_id, warehouse_id, asset_code, asset_type_definition_id, asset_type_revision,
            ownership_type_code, lifecycle_state, condition_state, created_at, created_by)
            VALUES (?, ?, ?, 'SYNTH-RSV-ASSET', ?, 1, 'COMPANY', 'ACTIVE', 'GOOD', ?, ?)""", asset, org, facility, type, at, operator)
        jdbc.update("""INSERT INTO asset_movement (id, asset_id, organization_id, movement_type, to_storage_location_id,
            occurred_at, recorded_at, recorded_by, source_operation_id, condition_at_transfer, correlation_id)
            VALUES (?, ?, ?, 'REGISTER_INITIAL_LOCATION', ?, ?, ?, ?, ?, 'GOOD', 'synthetic-reservations')""",
            UUID.randomUUID(), asset, org, location, at, at, operator, UUID.randomUUID())
    }

    private fun service(time: Clock) = ReservationService(records, source, work, projects, projectAuth, warehouse, authority,
        assets, stock, JdbcIdempotentCommandExecutor(jdbc, manager, time, HiltechTelemetryRuntime(OpenTelemetry.noop()) {}),
        JdbcAuditEventWriter(jdbc), { events.add(it as WorkResourceChanged) }, time, manager)

    private fun context(id: UUID, actor: UUID = operator) = AssetCommandContext(id, actor, "synthetic-reservations")
    private fun denied(code: String, body: () -> Unit) = assertEquals(code, assertThrows<ProductApiException> { body() }.code)
}
