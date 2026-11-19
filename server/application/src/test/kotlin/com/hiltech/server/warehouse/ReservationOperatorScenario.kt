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
import org.springframework.dao.DataIntegrityViolationException
import java.math.BigDecimal
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
    private val source = WarehouseWorkResourceSource(records, AssetServicePersistence(jdbc), stock, warehouse, assets, StockFulfillmentPersistence(jdbc))

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
        val replacementHold = svc.reserveAsset(context(replacement.operationId), asset, replacement)
        assertEquals("ACTIVE", replacementHold.state)
        val assetEvent = events.last { it.workOrderId == second.workOrderId }
        readiness.resourceChanged(assetEvent)
        val afterAssetEvent = work.workOrder(second.workOrderId)!!.version
        readiness.resourceChanged(assetEvent)
        assertEquals(afterAssetEvent, work.workOrder(second.workOrderId)!!.version)
        assertEquals("ASSET_RESERVED", readiness.readiness(pm, second.workOrderId).requirements
            .single { it.requirementId == secondAsset.requirementId }.evaluationReasonCode)

        val version = records.balances(item, now, location).single().version
        fun stockRequest(order: WorkOrderSnapshot, demand: WorkResourceDemand, quantity: String) = ReserveStockRequest(
            UUID.randomUUID(), order.workOrderId, demand.requirementId, work.workOrder(order.workOrderId)!!.version,
            records.demands(order.workOrderId).single { it.requirementId == demand.requirementId }.requirementVersion,
            version, location, quantity, "SYNTH-RSV", expires.toString())
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
        assertEquals("EXPIRED", records.reservation(ResourceKind.ASSET, replacementHold.id, expires)?.state)
        assertEquals(2L, records.reservation(ResourceKind.ASSET, replacementHold.id, expires)?.version)
        events.takeLast(2).forEach(readiness::resourceChanged)
        assertEquals("STOCK_AVAILABLE", readiness.readiness(pm, winner.workOrderId).requirements
            .single { it.requirementId == winner.requirementId }.evaluationReasonCode)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE stock_item_id = ?", Int::class.java, item))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM asset_movement WHERE asset_id = ?", Int::class.java, asset))
        assertEquals("0.000000", jdbc.queryForObject(
            "SELECT reserved_qty FROM stock_balance WHERE stock_item_id = ?", java.math.BigDecimal::class.java, item)?.toPlainString())
        exerciseStockFulfilment(winner, expires.plusSeconds(1))
        exerciseAssetFulfilment(second, secondAsset, expires.plusSeconds(2))
        assertTrue(warehouse.endWarehouseAuthorityBinding(binding, facility, 1, now))
        val revoked = assetRequest(first, firstAsset)
        denied("RESOURCE_UNAVAILABLE") { svc.reserveAsset(context(revoked.operationId), asset, revoked) }
        assertFalse(svc.forWork(pm, winner.workOrderId).isEmpty())
    }

    private fun exerciseAssetFulfilment(order: WorkOrderSnapshot, demand: WorkResourceDemand, instant: Instant) {
        val org = order.organizationId
        val current = requireNotNull(work.workOrder(order.workOrderId))
        val timestamp = instant.atOffset(ZoneOffset.UTC)
        val person = UUID.randomUUID()
        val employee = UUID.randomUUID()
        jdbc.update("INSERT INTO person (id, display_name, created_at, updated_at) VALUES (?, ?, ?, ?)",
            person, "Synthetic physical asset custodian", timestamp, timestamp)
        jdbc.update("""INSERT INTO employee (id, organization_id, person_id, employee_code, state, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)""", employee, org, person, "SYNTH-ASSET-$employee", timestamp, timestamp)
        jdbc.update("UPDATE asset_type_definition SET inspection_required_on_return = true WHERE config_revision_id = ?", type)
        val hold = UUID.randomUUID()
        records.reserve(ResourceKind.ASSET, hold, asset, current, demand.requirementId, operator,
            instant, instant.plusSeconds(600))
        val initial = requireNotNull(assets.custody(asset))
        val checkout = UUID.randomUUID()
        fun checkoutTo(recipient: UUID, id: UUID = checkout) {
            jdbc.update("""INSERT INTO asset_movement (id, asset_id, organization_id, movement_type,
                from_storage_location_id, to_custodian_target_type, to_custodian_target_id,
                project_id, site_id, work_order_id, occurred_at, recorded_at, recorded_by, source_operation_id,
                condition_at_transfer, expected_return_at, correlation_id, fulfilled_reservation_id, prior_custody_movement_id)
                VALUES (?, ?, ?, 'CHECKOUT', ?, 'EMPLOYEE', ?, ?, ?, ?, ?, ?, ?, ?, 'GOOD', ?, 'synthetic-asset', ?, ?)""",
                id, asset, org, location, recipient, order.projectId, order.siteId, order.workOrderId,
                timestamp, timestamp, operator, UUID.randomUUID(), timestamp.plusHours(1), hold, initial.sourceMovementId)
        }
        assertThrows<DataIntegrityViolationException> { checkoutTo(UUID.randomUUID()) }
        checkoutTo(employee)
        assertEquals("FULFILLED", records.reservation(ResourceKind.ASSET, hold, instant)?.state)
        assertEquals(employee, assets.custody(asset)?.employeeId)
        assertEquals(timestamp.plusHours(1), jdbc.queryForObject(
            "SELECT expected_return_at FROM asset_custody_projection WHERE asset_id = ?", java.time.OffsetDateTime::class.java, asset))
        val due = AssetFulfillmentPersistence(jdbc)
        val pending = due.dueReturns(org, facility, instant).items.single { it.assetId == asset }
        assertEquals(checkout, pending.sourceMovementId)
        assertEquals(order.workOrderId, pending.workOrderId)
        assertFalse(pending.overdue)
        val afterPending = due.dueReturns(org, facility, instant,
            Instant.parse(pending.expectedReturnAt), pending.assetCode, pending.assetId)
        assertTrue(afterPending.items.none { it.assetId == asset })
        val beforePending = due.dueReturns(org, facility, instant,
            Instant.parse(pending.expectedReturnAt).minusNanos(1), "", UUID(0, 0))
        assertEquals(asset, beforePending.items.single { it.assetId == asset }.assetId)
        assertTrue(due.dueReturns(org, facility, instant.plusSeconds(3600)).items.single { it.assetId == asset }.overdue)
        assertTrue(due.dueReturns(org, UUID.randomUUID(), instant).items.isEmpty())
        assertTrue(due.dueReturns(UUID.randomUUID(), facility, instant).items.isEmpty())
        assertThrows<DataIntegrityViolationException> { checkoutTo(employee, UUID.randomUUID()) }
        val returned = UUID.randomUUID()
        jdbc.update("""INSERT INTO asset_movement (id, asset_id, organization_id, movement_type,
            from_custodian_target_type, from_custodian_target_id, to_storage_location_id,
            occurred_at, recorded_at, recorded_by, source_operation_id, condition_at_transfer, correlation_id,
            prior_custody_movement_id)
            VALUES (?, ?, ?, 'RETURN', 'EMPLOYEE', ?, ?, ?, ?, ?, ?, 'GOOD', 'synthetic-asset', ?)""",
            returned, asset, org, employee, location, timestamp.plusSeconds(1), timestamp.plusSeconds(1),
            operator, UUID.randomUUID(), checkout)
        assertEquals(location, assets.custody(asset)?.storageLocationId)
        assertEquals(null, assets.custody(asset)?.employeeId)
        assertTrue(due.dueReturns(org, facility, instant.plusSeconds(2)).items.none { it.assetId == asset })
        val serviceRecords = AssetServicePersistence(jdbc)
        assertEquals("BLOCKED_INSPECTION", serviceRecords.availability(asset, instant.plusSeconds(1))?.state)
        val inspection = UUID.randomUUID()
        jdbc.update("""INSERT INTO asset_return_inspection (id, asset_id, return_movement_id, inspected_by, inspected_at, condition_observed)
            VALUES (?, ?, ?, ?, ?, 'GOOD')""", inspection, asset, returned, operator, timestamp.plusSeconds(2))
        assertEquals("AVAILABLE", serviceRecords.availability(asset, instant.plusSeconds(2))?.state)
        assertThrows<DataIntegrityViolationException> {
            jdbc.update("DELETE FROM asset_return_inspection WHERE id = ?", inspection)
        }
    }

    // Exercise database invariants AND the authorized, idempotent service path.
    private fun exerciseStockFulfilment(previous: ReservationView, instant: Instant) {
        val org = first.organizationId
        val timestamp = instant.atOffset(ZoneOffset.UTC)
        val person = UUID.randomUUID()
        val employee = UUID.randomUUID()
        jdbc.update("INSERT INTO person (id, display_name, created_at, updated_at) VALUES (?, ?, ?, ?)",
            person, "Synthetic no-login custodian", timestamp, timestamp)
        jdbc.update("""INSERT INTO employee (id, organization_id, person_id, employee_code, state, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)""", employee, org, person, "SYNTH-$employee", timestamp, timestamp)
        val order = requireNotNull(work.workOrder(previous.workOrderId))
        val hold = UUID.randomUUID()
        records.reserve(ResourceKind.STOCK, hold, item, order, previous.requirementId, operator,
            instant, instant.plusSeconds(600), location, BigDecimal("8"), "SYNTH-RSV")
        fun post(kind: String, qty: String, recipient: UUID = employee, reservation: UUID? = null) {
            jdbc.update("""INSERT INTO stock_movement (id, stock_item_id, organization_id, quantity, unit_code,
                movement_type, from_storage_location_id, to_storage_location_id, project_id, site_id, work_order_id,
                recipient_target_type, recipient_target_id, work_requirement_instance_id, fulfilled_reservation_id,
                operation_id, reason_code, occurred_at, recorded_at, recorded_by, correlation_id)
                VALUES (?, ?, ?, ?, 'SYNTH-RSV', ?, ?, ?, ?, ?, ?, 'EMPLOYEE', ?, ?, ?, ?, ?, ?, ?, ?, 'synthetic-fulfilment')""",
                UUID.randomUUID(), item, org, BigDecimal(qty), kind,
                location.takeIf { kind == "ISSUE" }, location.takeIf { kind == "RETURN" },
                order.projectId, order.siteId, order.workOrderId, recipient, previous.requirementId, reservation,
                UUID.randomUUID(), kind, timestamp, timestamp, operator)
        }
        fun balance(table: String, column: String): BigDecimal = requireNotNull(jdbc.queryForObject(
            "SELECT $column FROM $table WHERE stock_item_id = ?", BigDecimal::class.java, item))
        assertThrows<DataIntegrityViolationException> { post("ISSUE", "7", reservation = hold) }
        assertEquals("ACTIVE", records.reservation(ResourceKind.STOCK, hold, instant)?.state)
        assertThrows<DataIntegrityViolationException> {
            jdbc.update("""UPDATE stock_reservation SET state = 'FULFILLED', version = version + 1,
                ended_at = ?, ended_by = ?, end_reason = 'No movement' WHERE id = ?""", timestamp, operator, hold)
        }
        val fulfillment = StockFulfillmentPersistence(jdbc)
        val commandClock = Clock.fixed(instant, ZoneOffset.UTC)
        val service = StockFulfillmentService(fulfillment, stock, records, warehouse, authority, work,
            JdbcIdempotentCommandExecutor(jdbc, manager, commandClock, HiltechTelemetryRuntime(OpenTelemetry.noop()) {}),
            JdbcAuditEventWriter(jdbc), { events.add(it as WorkResourceChanged) }, commandClock)
        val demand = records.demands(order.workOrderId).single { it.requirementId == previous.requirementId }
        val issue = IssueReservedStockRequest(UUID.randomUUID(), order.workOrderId, previous.requirementId, employee,
            hold, location, order.version, demand.requirementVersion, 1,
            records.balances(item, instant, location).single().version, "8", "SYNTH-RSV")
        fun stockContext(id: UUID, actor: UUID = operator) = StockCommandContext(id, actor, "synthetic-fulfillment")
        denied("STOCK_FULFILLMENT_UNAVAILABLE") { service.issue(stockContext(issue.operationId, pm), item, issue) }
        denied("STOCK_FULFILLMENT_VERSION_CONFLICT") {
            val stale = issue.copy(operationId = UUID.randomUUID(), balanceVersion = issue.balanceVersion + 1)
            service.issue(stockContext(stale.operationId), item, stale)
        }
        val receipt = service.issue(stockContext(issue.operationId), item, issue)
        assertFalse(receipt.replayed)
        assertEquals(receipt.copy(replayed = true), service.issue(stockContext(issue.operationId), item, issue))
        assertEquals("STOCK_FULFILLED", source.fact(order, demand, instant).reason)
        assertTrue(source.fact(order, demand, instant).satisfied)
        denied("STOCK_FULFILLMENT_UNAVAILABLE") { service.issued(pm, item, order.workOrderId) }
        assertEquals("8.000000", service.issued(operator, item, order.workOrderId).single().outstandingQuantity)
        denied("STOCK_FULFILLMENT_UNAVAILABLE") { service.directory(pm, item, facility, "") }
        val directory = service.directory(operator, item, facility, "Synthetic no-login")
        assertFalse(directory.limited)
        val visible = directory.items.single()
        assertEquals(employee, visible.context.employeeId)
        assertEquals(order.workOrderId, visible.context.workOrderId)
        assertEquals(order.version, visible.workVersion)
        assertEquals("SYNTH-RSV", visible.unitCode)
        assertTrue(service.directory(operator, item, facility, "not-a-matching-employee").items.isEmpty())
        assertEquals("FULFILLED", records.reservation(ResourceKind.STOCK, hold, instant)?.state)
        assertEquals(0, BigDecimal("2").compareTo(balance("stock_balance", "on_hand_qty")))
        assertEquals(0, BigDecimal("8").compareTo(balance("stock_issued_balance", "outstanding_qty")))
        assertThrows<DataIntegrityViolationException> { post("ISSUE", "8", reservation = hold) }
        assertThrows<DataIntegrityViolationException> { post("CONSUME", "9") }
        assertThrows<DataIntegrityViolationException> { post("RETURN", "1", UUID.randomUUID()) }
        assertThrows<DataIntegrityViolationException> {
            records.reserve(ResourceKind.STOCK, UUID.randomUUID(), item, order, previous.requirementId, operator,
                instant, instant.plusSeconds(600), location, BigDecimal.ONE, "SYNTH-RSV")
        }
        val consume = AdjustIssuedStockRequest(UUID.randomUUID(), order.workOrderId, previous.requirementId,
            employee, order.version, service.issued(operator, item, order.workOrderId).single().version, "6", "SYNTH-RSV")
        denied("STOCK_FULFILLMENT_UNAVAILABLE") { service.consume(stockContext(consume.operationId, pm), item, consume) }
        val consumed = service.consume(stockContext(consume.operationId), item, consume)
        assertEquals(consumed.copy(replayed = true), service.consume(stockContext(consume.operationId), item, consume))
        assertEquals(0, BigDecimal("2").compareTo(balance("stock_balance", "on_hand_qty")))
        val returned = consume.copy(operationId = UUID.randomUUID(), quantity = "2", storageLocationId = location,
            issuedVersion = service.issued(operator, item, order.workOrderId).single().version)
        service.returnUnused(stockContext(returned.operationId), item, returned)
        assertTrue(service.directory(operator, item, facility, "").items.isEmpty())
        val history = service.history(operator, item, facility, "Synthetic no-login")
        assertFalse(history.limited)
        assertEquals(3, history.items.size)
        assertEquals(setOf("ISSUE", "RETURN", "CONSUME"), history.items.map { it.type }.toSet())
        assertTrue(history.items.all { it.workOrderId == order.workOrderId && it.requirementId == previous.requirementId })
        assertTrue(history.items.any { it.movementId == receipt.movementId })
        assertTrue(history.items.any { it.movementId == consumed.movementId })
        assertTrue(service.history(operator, item, facility, "no-such-employee").items.isEmpty())
        denied("STOCK_FULFILLMENT_UNAVAILABLE") { service.history(pm, item, facility, "") }
        assertTrue(fulfillment.history(item, org, facility, "", emptyList()).items.isEmpty())
        assertFalse(source.fact(order, demand, instant).satisfied)
        assertEquals(0, BigDecimal("4").compareTo(balance("stock_balance", "on_hand_qty")))
        assertEquals(0, BigDecimal.ZERO.compareTo(balance("stock_issued_balance", "outstanding_qty")))
        assertEquals(balance("stock_issued_balance", "outstanding_qty"), balance("stock_issued_ledger", "outstanding_qty"))
        assertEquals(balance("stock_balance", "on_hand_qty"), balance("stock_ledger_on_hand", "on_hand_qty"))
        assertThrows<DataIntegrityViolationException> {
            jdbc.update("UPDATE stock_issued_balance SET outstanding_qty = 100 WHERE stock_item_id = ?", item)
        }
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
