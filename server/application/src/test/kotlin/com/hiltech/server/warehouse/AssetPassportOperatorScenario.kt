package com.hiltech.server.warehouse

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.platform.ProductApiExceptionHandler
import com.hiltech.server.platform.ProductApiErrorWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class AssetPassportOperatorScenario(
    private val jdbc: JdbcTemplate,
    private val service: AssetPassportService,
    private val clock: Clock,
    private val organization: UUID,
    private val warehouse: UUID,
    private val manager: UUID,
    private val verifier: UUID,
    private val custodian: UUID,
) {
    private val type = UUID.randomUUID()
    private val bin = UUID.randomUUID()
    private val employee = UUID.randomUUID()
    private val time get() = clock.instant().atOffset(ZoneOffset.UTC)

    fun exercise(project: () -> Unit): AssetPassportView {
        seedCatalog()
        val request = AssetRegistrationRequest(
            UUID.randomUUID(), warehouse, type, "SYNTH-PASSPORT", "Synthetic calibration instrument",
            "Synthetic manufacturer", "Synthetic model", "Synthetic-serial", "COMPANY", AssetCondition.GOOD,
        )
        denied("INVALID_ASSET_COMMAND") { register(request.copy(serialNumber = null)) }
        val registered = register(request)
        assertEquals(registered.identity.id, register(request).identity.id)
        assertNull(registered.custody)
        assertTrue(registered.canInitialize)
        denied("ASSET_CONSTRAINT_CONFLICT") { register(request.copy(operationId = UUID.randomUUID(), assetCode = "synth-passport")) }
        val second = register(request.copy(operationId = UUID.randomUUID(), assetCode = "SYNTH-DUPLICATE",
            serialNumber = " synthetic-SERIAL ", initialLocationId = bin))
        assertEquals(bin, second.custody?.storageLocationId)
        assertTrue(second.identity.duplicateSerial)
        assertEquals(2, service.directory(manager, warehouse, "synthetic-serial", null).size)
        assertEquals("REGISTER_INITIAL_LOCATION", service.movements(manager, second.identity.id).single().type)

        val id = registered.identity.id
        val tagCommand = context()
        val tag = service.tag(tagCommand, id, registered.identity.version, "QR", null)
        assertEquals(tag, service.tag(tagCommand, id, registered.identity.version, "QR", null))
        denied("INVALID_ASSET_COMMAND") { service.tag(context(), id, 2, "UNSUPPORTED", tag.id) }
        denied("ASSET_PRIMARY_TAG_CHANGED") { service.tag(context(), id, 2, "QR", null) }
        assertThrows<DataIntegrityViolationException> {
            jdbc.update("""
                INSERT INTO asset_tag (id, asset_id, tag_type_code, public_opaque_code, active, is_primary, issued_at)
                VALUES (?, ?, 'QR', 'synthetic-forbidden-second-primary', true, true, ?)
            """.trimIndent(), UUID.randomUUID(), id, time)
        }
        val replacement = service.tag(context(), id, 2, "QR", tag.id)
        assertNotEquals(tag.code, replacement.code)
        assertEquals(tag.id, replacement.replacesTagId)
        denied("ASSET_UNAVAILABLE") { service.lookup(manager, tag.code) }
        assertEquals(id, service.directory(manager, warehouse, replacement.code, null).single().id)
        assertEquals(replacement.id, service.passport(manager, id).tags.single { it.active && it.primary }.id)

        val version = service.passport(manager, id).identity.version
        val opening = OpenAssetCustodyRequest(UUID.randomUUID(), version, AssetCondition.GOOD, employeeId = employee)
        denied("ASSET_UNAVAILABLE") { service.lookup(custodian, replacement.code) }
        denied("ASSET_UNAVAILABLE") {
            service.opening(context(opening.operationId, custodian), id, opening)
        }
        assertRace {
            val attempt = opening.copy(operationId = UUID.randomUUID())
            service.opening(context(attempt.operationId), id, attempt)
        }
        denied("ASSET_UNAVAILABLE") { service.lookup(custodian, replacement.code) }
        project()
        assertEquals("SYNTH-EMPLOYEE", service.lookup(custodian, replacement.code).employeeCode)
        denied("ASSET_UNAVAILABLE") { service.movements(custodian, id) }
        denied("ASSET_UNAVAILABLE") { service.tag(context(actor = custodian), id, version + 1, "QR", replacement.id) }

        jdbc.update("UPDATE asset SET acquisition_cost = 98765.43, currency_code = 'USD' WHERE id = ?", id)
        assertSafeLookup(replacement.code, "SYNTH-PASSPORT")
        val prior = requireNotNull(service.passport(manager, id).custody)
        val correction = CorrectAssetCustodyRequest(UUID.randomUUID(), version + 1, AssetCondition.FAIR,
            prior.sourceMovementId, "Synthetic reconciliation with verified label", locationId = bin)
        denied("ASSET_VERSION_CONFLICT") {
            service.correct(context(correction.operationId), id, correction.copy(baseVersion = version))
        }
        assertRace {
            val attempt = correction.copy(operationId = UUID.randomUUID())
            service.correct(context(attempt.operationId), id, attempt)
        }
        // The current projection denies the former custodian while OpenFGA still has the old grant.
        denied("ASSET_UNAVAILABLE") { service.lookup(custodian, replacement.code) }
        project()
        val passport = service.passport(manager, id)
        assertEquals(AssetCondition.FAIR, passport.identity.condition)
        assertEquals(bin, passport.custody?.storageLocationId)
        val movements = service.movements(manager, id)
        assertEquals(listOf("CONTROLLED_CORRECTION", "INITIALIZE_OPENING_CUSTODY"), movements.map { it.type })
        assertEquals(prior.sourceMovementId, movements.first().correctedMovementId)
        assertEquals(correction.reason, movements.first().reason)
        assertProjection(id)
        assertThrows<DataIntegrityViolationException> {
            jdbc.update("UPDATE asset_custody_projection SET current_storage_location_id = NULL WHERE asset_id = ?", id)
        }
        assertThrows<DataIntegrityViolationException> {
            jdbc.update("DELETE FROM asset_movement WHERE id = ?", prior.sourceMovementId)
        }
        assertEquals(4, jdbc.queryForObject("""
            SELECT count(*) FROM audit_event WHERE target_type = 'ASSET' AND target_id = ?
              AND action IN ('ASSET_TAG_ISSUED', 'ASSET_TAG_REPLACED', 'ASSET_CUSTODY_INITIALIZED', 'ASSET_CUSTODY_CORRECTED')
        """.trimIndent(), Int::class.java, id))
        exerciseImport()
        return passport
    }

    fun assertRevoked(passport: AssetPassportView, actor: UUID) {
        val id = passport.identity.id
        val primary = passport.tags.single { it.active && it.primary }
        denied("ASSET_UNAVAILABLE") { service.passport(actor, id) }
        denied("ASSET_UNAVAILABLE") { service.lookup(actor, primary.code) }
        denied("ASSET_UNAVAILABLE") { service.tag(context(actor = actor), id, passport.identity.version, "QR", primary.id) }
    }

    private fun assertSafeLookup(code: String, expected: String) {
        val session = UUID.randomUUID()
        val mvc = MockMvcBuilders.standaloneSetup(AssetPassportController(service))
            .setControllerAdvice(ProductApiExceptionHandler(ProductApiErrorWriter())).build()
        val response = mvc.perform(get("/v1/assets/by-tag/$code").with {
            HiltechRequestContext.bindAuthenticatedIdentity(it, custodian, session)
            it
        }).andExpect(status().isOk()).andReturn().response.contentAsString
        val json = Json.parseToJsonElement(response).jsonObject
        assertEquals(expected, json.getValue("assetCode").jsonPrimitive.content)
        assertEquals("SYNTH-EMPLOYEE", json.getValue("employeeCode").jsonPrimitive.content)
        assertFalse(response.contains("98765.43"))
        for (field in listOf("acquisitionCost", "currencyCode", "sourceImportRowId", "tags", "movements", "employeeId")) {
            assertFalse(json.containsKey(field), field)
        }
        val detail = mvc.perform(get("/v1/assets").param("warehouseId", warehouse.toString()).with {
            HiltechRequestContext.bindAuthenticatedIdentity(it, custodian, session)
            it
        })
        detail.andExpect(status().isNotFound())
    }

    private fun assertRace(write: () -> Unit) {
        val barrier = CyclicBarrier(2)
        Executors.newFixedThreadPool(2).use { pool ->
            val attempts = List(2) {
                pool.submit(Callable {
                    barrier.await(10, TimeUnit.SECONDS)
                    try {
                        write()
                        "SUCCESS"
                    } catch (failure: ProductApiException) {
                        failure.code
                    }
                })
            }
            assertEquals(listOf("ASSET_VERSION_CONFLICT", "SUCCESS"), attempts.map { it.get(30, TimeUnit.SECONDS) }.sorted())
        }
    }

    private fun assertProjection(id: UUID) {
        assertEquals(1, jdbc.queryForObject("""
            SELECT count(*) FROM asset_custody_projection p JOIN asset_ledger_custody m ON m.asset_id = p.asset_id
            WHERE p.asset_id = ? AND p.source_movement_id = m.source_movement_id AND p.asset_version = m.ledger_version
                AND p.current_storage_location_id IS NOT DISTINCT FROM m.to_storage_location_id
                AND p.current_custodian_target_id IS NOT DISTINCT FROM m.to_custodian_target_id
                AND p.current_custodian_target_type IS NOT DISTINCT FROM m.to_custodian_target_type
                AND p.current_project_id IS NOT DISTINCT FROM m.project_id AND p.current_site_id IS NOT DISTINCT FROM m.site_id
                AND p.current_work_order_id IS NOT DISTINCT FROM m.work_order_id AND p.custody_started_at = m.occurred_at
        """.trimIndent(), Int::class.java, id))
    }

    private fun exerciseImport() {
        val batch = UUID.randomUUID()
        val row = UUID.randomUUID()
        val hash = "9".repeat(64)
        jdbc.update("""
            INSERT INTO inventory_import_batch (id, organization_id, source_file_name, source_sha256, source_type,
                state, created_by_user_id, created_at, updated_at)
            VALUES (?, ?, 'synthetic-asset-records.xlsx', ?, 'LEGACY_EXCEL', 'IN_REVIEW', ?, ?, ?)
        """.trimIndent(), batch, organization, hash, manager, time, time)
        jdbc.update("""
            INSERT INTO inventory_import_row (id, batch_id, organization_id, source_sheet, source_row_number,
                raw_payload, candidate_class, normalized_description, issue_codes, review_state, reviewed_by_user_id,
                reviewed_at, created_at, updated_at, version)
            VALUES (?, ?, ?, 'SYNTH-TOOLS', 3, '{"description":"Synthetic instrument"}', 'COMPANY_ASSET',
                'Synthetic instrument', '{}', 'NEEDS_REVIEW', ?, ?, ?, ?, 2)
        """.trimIndent(), row, batch, organization, manager, time, time, time)
        val approve = ApproveAssetImportRequest(UUID.randomUUID(), 2, "SYNTH-IMPORTED", "Synthetic imported instrument",
            "COMPANY", AssetCondition.GOOD)
        denied("ASSET_SOURCE_REVIEW_REQUIRED") { service.approve(context(approve.operationId), batch, row, approve) }
        jdbc.update("""
            INSERT INTO asset_import_verification (source_row_id, organization_id, source_row_version, source_sha256,
                warehouse_id, asset_type_id, verified_serial, opening_location_id, cutover_at, verified_by, approver_id,
                verified_at, evidence_ref)
            VALUES (?, ?, 2, ?, ?, ?, 'SYNTH-VERIFIED-SERIAL', ?, ?, ?, ?, ?, 'synthetic-only')
        """.trimIndent(), row, organization, hash, warehouse, type, bin, time, verifier, manager, time)
        val stale = approve.copy(operationId = UUID.randomUUID(), baseVersion = 1)
        denied("ASSET_SOURCE_REVIEW_REQUIRED") { service.approve(context(stale.operationId), batch, row, stale) }
        val imported = service.approve(context(approve.operationId), batch, row, approve)
        assertEquals(row, imported.identity.sourceImportRowId)
        assertEquals("SYNTH-VERIFIED-SERIAL", imported.identity.serialNumber)
        assertNull(imported.custody)
        val opening = OpenAssetCustodyRequest(UUID.randomUUID(), imported.identity.version, AssetCondition.GOOD,
            locationId = bin, cutoverAt = clock.instant().toString())
        val wrong = opening.copy(operationId = UUID.randomUUID(), cutoverAt = clock.instant().minusSeconds(1).toString())
        denied("ASSET_SOURCE_REVIEW_REQUIRED") { service.opening(context(wrong.operationId), imported.identity.id, wrong) }
        val actual = service.opening(context(opening.operationId), imported.identity.id, opening)
        assertEquals(bin, actual.custody?.storageLocationId)
        assertEquals(actual, service.opening(context(opening.operationId), imported.identity.id, opening))
        assertEquals(row, service.movements(manager, imported.identity.id).single().sourceImportRowId)
        assertProjection(imported.identity.id)
    }

    private fun seedCatalog() {
        jdbc.update("""
            INSERT INTO config_revision (id, scope_type, scope_organization_id, family, code, name,
                lifecycle_state, revision_number, created_at, created_by, activated_at, activated_by)
            VALUES (?, 'ORGANIZATION', ?, 'asset-types', 'SYNTH-INSTRUMENT', 'Synthetic test instrument', 'ACTIVE', 1, ?, ?, ?, ?)
        """.trimIndent(), type, organization, time, manager, time, manager)
        jdbc.update("""
            INSERT INTO asset_type_definition (config_revision_id, serial_required, calibration_required,
                high_value_or_restricted, allowed_tag_types, inspection_required_on_return)
            VALUES (?, true, true, true, ARRAY['QR'], true)
        """.trimIndent(), type)
        jdbc.update("""
            INSERT INTO storage_location (id, organization_id, warehouse_id, code, name, kind, lifecycle_state)
            VALUES (?, ?, ?, 'SYNTH-ASSET-BIN', 'Synthetic asset bin', 'WAREHOUSE', 'ACTIVE')
        """.trimIndent(), bin, organization, warehouse)
        val person = UUID.randomUUID()
        jdbc.update("INSERT INTO person (id, display_name, created_at, updated_at) VALUES (?, 'Synthetic custodian', ?, ?)", person, time, time)
        jdbc.update("UPDATE user_identity SET person_id = ? WHERE id = ?", person, custodian)
        jdbc.update("""
            INSERT INTO employee (id, organization_id, person_id, employee_code, state, created_at, updated_at)
            VALUES (?, ?, ?, 'SYNTH-EMPLOYEE', 'ACTIVE', ?, ?)
        """.trimIndent(), employee, organization, person, time, time)
    }

    private fun register(request: AssetRegistrationRequest) = service.register(context(request.operationId), request)
    private fun context(operation: UUID = UUID.randomUUID(), actor: UUID = manager) = AssetCommandContext(operation, actor, "synthetic-assets")
    private fun denied(code: String, action: () -> Unit) = assertEquals(code, assertThrows<ProductApiException> { action() }.code)
}
