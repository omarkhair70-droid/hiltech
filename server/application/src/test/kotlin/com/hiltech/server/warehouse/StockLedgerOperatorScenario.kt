package com.hiltech.server.warehouse

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class StockLedgerOperatorScenario(
    private val jdbc: JdbcTemplate,
    private val service: StockLedgerService,
    private val clock: Clock,
    private val org: UUID,
    private val warehouse: UUID,
    private val actor: UUID,
    private val verifier: UUID,
) {
    private val source = UUID.randomUUID()
    private val destination = UUID.randomUUID()
    private val alternative = UUID.randomUUID()
    private val retired = UUID.randomUUID()

    fun exercise(projectLocations: (List<UUID>) -> Unit): UUID {
        val category = seedLocationsAndCategory()
        projectLocations(listOf(source, destination, alternative, retired))
        val unit = service.registerUnit(context(), warehouse, "SYNTH-UOM", "Synthetic unit")
        val nativeOperation = UUID.randomUUID()
        val native = service.createItem(context(nativeOperation), CreateStockItemRequest(
            nativeOperation, warehouse, "SYNTH-NATIVE", "Synthetic native master", partNumber = "SYNTH-DUP",
            categoryDefinitionId = category, baseUnitCode = unit.code, classification = StockClassification.STOCK_QUANTITY,
        ))
        val batch = UUID.randomUUID()
        val row = UUID.randomUUID()
        seedImport(batch, row, unit.code)
        val approve = ApproveStockItemRequest(UUID.randomUUID(), 2, "SYNTH-IMPORT", "Synthetic imported master")
        assertEquals("STOCK_SOURCE_REVIEW_REQUIRED", assertThrows<ProductApiException> {
            service.approveImport(context(approve.operationId), batch, row, approve)
        }.code)
        attestImport(row, category)
        assertEquals("STOCK_SOURCE_REVIEW_REQUIRED", assertThrows<ProductApiException> {
            val stale = approve.copy(operationId = UUID.randomUUID(), baseVersion = 1)
            service.approveImport(context(stale.operationId), batch, row, stale)
        }.code)
        val item = service.approveImport(context(approve.operationId), batch, row, approve)
        assertEquals(item.id, service.approveImport(context(approve.operationId), batch, row, approve).id)
        assertEquals(native.partNumber, item.partNumber)
        assertEquals(row, item.sourceImportRowId)
        assertTrue(service.balances(actor, item.id).isEmpty())
        val opening = InitializeStockOpeningRequest(
            UUID.randomUUID(), item.version, row, source, "10.123456", unit.code, clock.instant().toString(),
        )
        assertFalse(service.opening(context(opening.operationId), item.id, opening).replayed)
        assertTrue(service.opening(context(opening.operationId), item.id, opening).replayed)
        assertTrue(service.item(actor, item.id).openingInitialized)
        assertEquals("10.123456", service.balances(actor, item.id).single().onHandQuantity)
        val duplicate = opening.copy(operationId = UUID.randomUUID())
        assertEquals("STOCK_CONSTRAINT_CONFLICT", assertThrows<ProductApiException> {
            service.opening(context(duplicate.operationId), item.id, duplicate)
        }.code)

        val transfer = TransferStockRequest(UUID.randomUUID(), item.version, source, destination, "1.000001", unit.code)
        val first = service.transfer(context(transfer.operationId), item.id, transfer)
        val replay = service.transfer(context(transfer.operationId), item.id, transfer)
        assertTrue(replay.replayed)
        assertEquals(first.movement, replay.movement)
        assertEquals("INVALID_STOCK_COMMAND", assertThrows<ProductApiException> {
            val wrongUnit = transfer.copy(operationId = UUID.randomUUID(), unitCode = "WRONG")
            service.transfer(context(wrongUnit.operationId), item.id, wrongUnit)
        }.code)
        for (invalid in listOf(retired, foreignLocation())) {
            val request = transfer.copy(operationId = UUID.randomUUID(), toStorageLocationId = invalid)
            assertEquals("STOCK_UNAVAILABLE", assertThrows<ProductApiException> {
                service.transfer(context(request.operationId), item.id, request)
            }.code)
        }

        val barrier = CyclicBarrier(2)
        Executors.newFixedThreadPool(2).use { pool ->
            val attempts = listOf(destination, alternative).map { target ->
                pool.submit(Callable {
                    barrier.await(10, TimeUnit.SECONDS)
                    val request = transfer.copy(operationId = UUID.randomUUID(), toStorageLocationId = target, quantity = "8.000001")
                    try {
                        service.transfer(context(request.operationId), item.id, request)
                        "SUCCESS"
                    } catch (failure: ProductApiException) {
                        failure.code
                    }
                })
            }
            assertEquals(listOf("STOCK_CONSTRAINT_CONFLICT", "SUCCESS"), attempts.map { it.get(30, TimeUnit.SECONDS) }.sorted())
        }

        val balances = service.balances(actor, item.id)
        assertEquals(BigDecimal("10.123456"), balances.sumOf { BigDecimal(it.onHandQuantity) })
        assertEquals("1.123454", balances.single { it.storageLocationId == source }.onHandQuantity)
        val rebuilt = jdbc.query(
            "SELECT storage_location_id, on_hand_qty FROM stock_ledger_on_hand WHERE stock_item_id = ?",
            { rs, _ -> rs.getObject("storage_location_id", UUID::class.java) to rs.getBigDecimal("on_hand_qty") }, item.id,
        ).toMap()
        assertEquals(balances.associate { it.storageLocationId to BigDecimal(it.onHandQuantity) }, rebuilt)
        assertEquals(3, service.movements(actor, item.id).size)
        assertThrows<DataIntegrityViolationException> {
            jdbc.update("UPDATE stock_balance SET on_hand_qty = 0 WHERE stock_item_id = ?", item.id)
        }
        assertThrows<DataIntegrityViolationException> {
            jdbc.update("DELETE FROM stock_movement WHERE operation_id = ?", transfer.operationId)
        }
        val session = UUID.randomUUID()
        val response = MockMvcBuilders.standaloneSetup(StockLedgerController(service)).build()
            .perform(get("/v1/stock-items/${item.id}/balances").with {
                HiltechRequestContext.bindAuthenticatedIdentity(it, actor, session)
                it
            })
            .andExpect(status().isOk())
            .andReturn().response.contentAsString
        val wireBalance = Json.parseToJsonElement(response).jsonArray.map { it.jsonObject }
            .single { it.getValue("locationCode").jsonPrimitive.content == "SYNTH-SOURCE" }
        assertEquals("1.123454", wireBalance.getValue("onHandQuantity").jsonPrimitive.content)
        assertTrue(wireBalance.getValue("onHandQuantity").jsonPrimitive.isString)
        return item.id
    }

    fun assertTransferDenied(itemId: UUID, revokedActor: UUID) {
        val request = TransferStockRequest(UUID.randomUUID(), 1, source, destination, "1", "SYNTH-UOM")
        assertEquals("STOCK_UNAVAILABLE", assertThrows<ProductApiException> {
            service.transfer(StockCommandContext(request.operationId, revokedActor, "synthetic-revocation"), itemId, request)
        }.code)
    }

    private fun seedLocationsAndCategory(): UUID {
        val timestamp = clock.instant().atOffset(ZoneOffset.UTC)
        for ((id, code) in listOf(source to "SYNTH-SOURCE", destination to "SYNTH-DEST",
            alternative to "SYNTH-ALT", retired to "SYNTH-RETIRED")) {
            jdbc.update(
                """
                INSERT INTO storage_location (id, organization_id, warehouse_id, code, name, kind, lifecycle_state, active_until)
                VALUES (?, ?, ?, ?, ?, 'WAREHOUSE', ?, ?)
                """.trimIndent(), id, org, warehouse, code, code,
                if (id == retired) "RETIRED" else "ACTIVE", if (id == retired) timestamp else null,
            )
        }
        val category = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO config_revision (id, scope_type, scope_organization_id, family, code, name,
                lifecycle_state, revision_number, created_at, created_by, activated_at, activated_by)
            VALUES (?, 'ORGANIZATION', ?, 'stock-categories', 'SYNTH', 'Synthetic category', 'ACTIVE', 1, ?, ?, ?, ?)
            """.trimIndent(), category, org, timestamp, actor, timestamp, actor,
        )
        jdbc.update("INSERT INTO stock_item_category_definition VALUES (?, 'SYNTH-UOM')", category)
        return category
    }

    private fun seedImport(batch: UUID, row: UUID, unit: String) {
        val timestamp = clock.instant().atOffset(ZoneOffset.UTC)
        jdbc.update(
            """
            INSERT INTO inventory_import_batch (id, organization_id, source_file_name, source_sha256,
                source_type, state, created_by_user_id, created_at, updated_at)
            VALUES (?, ?, 'synthetic-fiber.xlsx', ?, 'LEGACY_EXCEL', 'IN_REVIEW', ?, ?, ?)
            """.trimIndent(), batch, org, "e".repeat(64), actor, timestamp, timestamp,
        )
        jdbc.update(
            """
            INSERT INTO inventory_import_row (id, batch_id, organization_id, source_sheet, source_row_number,
                raw_payload, candidate_class, normalized_part_number, normalized_description,
                candidate_uom, candidate_quantity, issue_codes, review_state, reviewed_by_user_id,
                reviewed_at, created_at, updated_at, version)
            VALUES (?, ?, ?, 'FIBER', 7, '{"UOM":"PC","DESC":"Synthetic cable"}', 'STOCK_QUANTITY',
                'SYNTH-DUP', 'Synthetic mapped cable', ?, 10.123456, ARRAY['UNKNOWN_UOM'], 'NEEDS_REVIEW', ?, ?, ?, ?, 2)
            """.trimIndent(), row, batch, org, unit, actor, timestamp, timestamp, timestamp,
        )
    }

    private fun attestImport(row: UUID, category: UUID) {
        jdbc.update(
            """
            INSERT INTO stock_import_verification (source_row_id, organization_id, source_row_version, source_sha256,
                storage_location_id, category_definition_id, verified_by, cutover_approver_id, evidence_ref,
                resolved_issue_codes, verified_at, cutover_at)
            VALUES (?, ?, 2, ?, ?, ?, ?, ?, 'synthetic-only', ARRAY['UNKNOWN_UOM'], ?, ?)
            """.trimIndent(), row, org, "e".repeat(64), source, category, verifier, actor,
            clock.instant().atOffset(ZoneOffset.UTC),
            clock.instant().atOffset(ZoneOffset.UTC),
        )
    }

    private fun foreignLocation(): UUID {
        val other = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO organization (id, organization_code, legal_name, display_name, organization_type, status, created_at)
            VALUES (?, ?, 'Synthetic other org', 'Synthetic other org', 'HILTECH', 'ACTIVE', ?)
            """.trimIndent(), other, "SYNTH-$other", clock.instant().atOffset(ZoneOffset.UTC),
        )
        val location = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO storage_location (id, organization_id, code, name, kind) VALUES (?, ?, 'OTHER', 'Other', 'OTHER_TYPED')",
            location, other,
        )
        return location
    }

    private fun context(operationId: UUID = UUID.randomUUID()) = StockCommandContext(operationId, actor, "synthetic-stock")
}
