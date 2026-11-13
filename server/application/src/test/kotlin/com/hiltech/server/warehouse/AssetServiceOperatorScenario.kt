package com.hiltech.server.warehouse

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiErrorWriter
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.platform.ProductApiExceptionHandler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class AssetServiceOperatorScenario(
    private val jdbc: JdbcTemplate,
    private val passports: AssetPassportService,
    private val records: AssetServicePersistence,
    private val service: AssetService,
    private val clock: Clock,
    private val warehouse: UUID,
    private val manager: UUID,
    private val custodian: UUID,
) {
    private val mvc = MockMvcBuilders.standaloneSetup(AssetPassportController(passports), AssetServiceController(service))
        .setControllerAdvice(ProductApiExceptionHandler(ProductApiErrorWriter())).build()
    private val session = UUID.randomUUID()
    private val now get() = clock.instant()
    private val at get() = now.atOffset(ZoneOffset.UTC)

    fun exercise(passport: AssetPassportView, project: () -> Unit) {
        val asset = passport.identity.id
        val tag = passport.tags.single { it.active }.code
        val org = passport.identity.organizationId
        val storage = requireNotNull(passport.custody?.storageLocationId)
        val employee = requireNotNull(jdbc.queryForObject(
            "SELECT id FROM employee WHERE organization_id = ? AND employee_code = 'SYNTH-EMPLOYEE'", UUID::class.java, org,
        ))
        fun version() = service.availability(manager, asset).assetVersion
        fun codes() = service.availability(manager, asset).reasons.map { it.code }.toSet()
        assertEquals(setOf("CALIBRATION_MISSING"), codes())
        assertTrue(service.queue(manager, warehouse, null).any { it.id == asset })
        denied("ASSET_UNAVAILABLE") { service.history(custodian, asset) }
        denied("ASSET_UNAVAILABLE") { service.report(context(actor = custodian), asset,
            ReportAssetIncidentRequest(UUID.randomUUID(), version(), AssetIncidentType.DAMAGE, "Synthetic unauthorized report")) }

        val expired = RecordAssetCalibrationRequest(UUID.randomUUID(), version(), "SYNTH-CAL", CalibrationResult.PASSED,
            now.minusSeconds(172800).toString(), now.minusSeconds(1).toString())
        service.calibrate(context(expired.operationId), asset, expired)
        assertEquals(setOf("CALIBRATION_EXPIRED"), codes())
        val certificate = evidence(org, asset)
        val valid = expired.copy(operationId = UUID.randomUUID(), baseVersion = version(),
            calibratedAt = now.minusSeconds(60).toString(), validUntil = now.plusSeconds(3600).toString(),
            certificateEvidenceId = certificate, providerRef = "SYNTH-PRIVATE-PROVIDER", notes = "SYNTH-PRIVATE-NOTES")
        val recorded = service.calibrate(context(valid.operationId), asset, valid)
        assertEquals(recorded, service.calibrate(context(valid.operationId), asset, valid))
        assertEquals("AVAILABLE", service.availability(manager, asset).state)
        assertFalse(service.queue(manager, warehouse, null).any { it.id == asset })
        assertEquals("CALIBRATION_EXPIRED", records.availability(asset, now.plusSeconds(3600))?.reasons?.single()?.code)
        assertThrows<DataIntegrityViolationException> {
            jdbc.update("UPDATE asset_calibration_record SET valid_until = valid_until + interval '1 day' WHERE id = ?", recorded.id)
        }
        denied("ASSET_UNAVAILABLE") {
            val otherAsset = UUID.randomUUID()
            val wrong = valid.copy(operationId = UUID.randomUUID(), baseVersion = version(), calibratedAt = now.minusSeconds(30).toString(),
                certificateEvidenceId = evidence(org, otherAsset))
            service.calibrate(context(wrong.operationId), asset, wrong)
        }

        val failed = valid.copy(operationId = UUID.randomUUID(), baseVersion = version(),
            calibratedAt = now.minusSeconds(30).toString(), validUntil = null, result = CalibrationResult.FAILED)
        service.calibrate(context(failed.operationId), asset, failed)
        assertEquals(setOf("CALIBRATION_FAILED"), codes())

        val damage = UUID.randomUUID()
        val incidentResponse = mvc.perform(post("/v1/assets/$asset/incidents").contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", damage.toString()).with {
                HiltechRequestContext.bindAuthenticatedIdentity(it, manager, session)
                it
            }.content("""{"operationId":"$damage","baseVersion":${version()},"type":"DAMAGE","description":"SYNTH-PRIVATE-INCIDENT"}"""))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("OPEN"))
            .andReturn().response.contentAsString
        val incidentId = UUID.fromString(Json.parseToJsonElement(incidentResponse).jsonObject.getValue("id").toString().trim('"'))
        val review = ChangeAssetIncidentRequest(UUID.randomUUID(), version(), 1, AssetIncidentState.UNDER_REVIEW, investigationOwnerId = employee)
        val underReview = service.transition(context(review.operationId), asset, incidentId, review)
        assertEquals(employee, underReview.investigationOwnerId)
        assertTrue("INCIDENT_DAMAGE" in codes())
        val stale = review.copy(operationId = UUID.randomUUID(), baseVersion = version())
        denied("ASSET_INCIDENT_VERSION_CONFLICT") { service.transition(context(stale.operationId), asset, incidentId, stale) }

        val sendVersion = version()
        race {
            val request = SendAssetMaintenanceRequest(UUID.randomUUID(), sendVersion, AssetServiceType.MAINTENANCE, "Synthetic bench service")
            service.send(context(request.operationId), asset, request)
        }
        val current = service.history(manager, asset).maintenance.single()
        assertEquals(setOf("INCIDENT_DAMAGE", "CALIBRATION_FAILED", "MAINTENANCE_IN_PROGRESS"), codes())
        val duplicate = SendAssetMaintenanceRequest(UUID.randomUUID(), version(), AssetServiceType.CALIBRATION, "Synthetic second service")
        denied("ASSET_SERVICE_CONSTRAINT_CONFLICT") { service.send(context(duplicate.operationId), asset, duplicate) }

        val beforeUnfit = passports.passport(manager, asset)
        val correction = CorrectAssetCustodyRequest(UUID.randomUUID(), version(), AssetCondition.UNFIT,
            requireNotNull(beforeUnfit.custody).sourceMovementId, "Synthetic inspection found unfit", locationId = storage)
        passports.correct(context(correction.operationId), asset, correction)
        assertEquals("BLOCKED_CONDITION", service.availability(manager, asset).state)
        val resolvedRequest = ChangeAssetIncidentRequest(UUID.randomUUID(), version(), underReview.version,
            AssetIncidentState.RESOLVED, "Synthetic investigation completed")
        val resolved = service.transition(context(resolvedRequest.operationId), asset, incidentId, resolvedRequest)
        val close = ChangeAssetIncidentRequest(UUID.randomUUID(), version(), resolved.version, AssetIncidentState.CLOSED)
        assertEquals(AssetIncidentState.CLOSED, service.transition(context(close.operationId), asset, incidentId, close).state)
        assertEquals(setOf("CONDITION_UNFIT", "CALIBRATION_FAILED", "MAINTENANCE_IN_PROGRESS"), codes())
        val finishVersion = version()
        race {
            val request = CompleteAssetMaintenanceRequest(UUID.randomUUID(), finishVersion, current.id, current.version, "Synthetic work completed")
            service.complete(context(request.operationId), asset, request)
        }
        assertEquals(setOf("CONDITION_UNFIT", "CALIBRATION_FAILED"), codes())

        val restored = valid.copy(operationId = UUID.randomUUID(), baseVersion = version(), calibratedAt = now.toString())
        service.calibrate(context(restored.operationId), asset, restored)
        assertEquals(setOf("CONDITION_UNFIT"), codes())
        val calibrationService = SendAssetMaintenanceRequest(UUID.randomUUID(), version(), AssetServiceType.CALIBRATION, "Synthetic calibration dispatch")
        val dispatch = service.send(context(calibrationService.operationId), asset, calibrationService)
        assertEquals(setOf("CONDITION_UNFIT", "CALIBRATION_IN_PROGRESS"), codes())
        val finishCalibration = CompleteAssetMaintenanceRequest(UUID.randomUUID(), version(), dispatch.id, dispatch.version, "Synthetic instrument returned")
        service.complete(context(finishCalibration.operationId), asset, finishCalibration)

        val beforeEmployee = passports.passport(manager, asset)
        val toEmployee = CorrectAssetCustodyRequest(UUID.randomUUID(), version(), AssetCondition.GOOD,
            requireNotNull(beforeEmployee.custody).sourceMovementId, "Synthetic custody review", employeeId = employee)
        passports.correct(context(toEmployee.operationId), asset, toEmployee)
        project()
        assertEquals("BLOCKED_CUSTODY", service.availability(manager, asset).state)
        val missing = ReportAssetIncidentRequest(UUID.randomUUID(), version(), AssetIncidentType.MISSING, "SYNTH-PRIVATE-REPORT")
        service.report(context(missing.operationId, custodian), asset, missing)
        assertEquals(setOf("ASSET_IN_EMPLOYEE_CUSTODY", "INCIDENT_MISSING"), codes())
        val wire = mvc.perform(get("/v1/assets/by-tag/$tag").with {
            HiltechRequestContext.bindAuthenticatedIdentity(it, custodian, session)
            it
        }).andExpect(status().isOk()).andExpect(jsonPath("$.availability.incidentTypes[0]").value("MISSING"))
            .andExpect(jsonPath("$.availability.certificateOnFile").value(true))
            .andReturn().response.contentAsString
        for (secret in listOf(certificate.toString(), "SYNTH-PRIVATE", "acquisitionCost", "sourceId", "evidenceId", "objectKey")) {
            assertFalse(wire.contains(secret), secret)
        }

        val evaluated = records.availability(asset, now)
        assertEquals(evaluated, AssetServicePersistence(jdbc).availability(asset, now))
        assertTrue(evaluated?.reasons?.all { it.sourceId != null && it.sourceVersion != null } == true)
        assertThrows<DataIntegrityViolationException> { jdbc.update("DELETE FROM asset_incident WHERE id = ?", incidentId) }
        assertThrows<DataIntegrityViolationException> { jdbc.update("DELETE FROM asset_maintenance_record WHERE id = ?", current.id) }
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE target_id = ? AND action = 'ASSET_SERVICE_COMPLETED'",
            Int::class.java, asset) == 2)

        val beforeLocation = passports.passport(manager, asset)
        val toLocation = CorrectAssetCustodyRequest(UUID.randomUUID(), version(), AssetCondition.GOOD,
            requireNotNull(beforeLocation.custody).sourceMovementId, "Synthetic custody restoration", locationId = storage)
        passports.correct(context(toLocation.operationId), asset, toLocation)
        denied("ASSET_UNAVAILABLE") { service.report(context(actor = custodian), asset, missing.copy(baseVersion = version())) }
        project()

        val retired = passports.directory(manager, warehouse, "SYNTH-DUPLICATE", null).single()
        jdbc.update("UPDATE asset SET lifecycle_state = 'RETIRED', retired_at = ?, version = version + 1 WHERE id = ?", at, retired.id)
        assertEquals("RETIRED", service.availability(manager, retired.id).state)
        assertTrue("CALIBRATION_MISSING" in service.availability(manager, retired.id).reasons.map { it.code })
        exerciseDuePolicy(org, asset, passport.identity.typeId, current.id)
    }

    fun assertRevoked(id: UUID, actor: UUID) {
        denied("ASSET_UNAVAILABLE") { service.availability(actor, id) }
        val request = SendAssetMaintenanceRequest(UUID.randomUUID(), 1, AssetServiceType.MAINTENANCE, "Synthetic revoked service request")
        denied("ASSET_UNAVAILABLE") { service.send(context(request.operationId, actor), id, request) }
    }

    private fun exerciseDuePolicy(org: UUID, asset: UUID, type: UUID, completed: UUID) {
        val revision = UUID.randomUUID()
        jdbc.update("""
            INSERT INTO config_revision (id, scope_type, scope_organization_id, family, code, name,
                lifecycle_state, revision_number, created_at, created_by, activated_at, activated_by)
            VALUES (?, 'ORGANIZATION', ?, 'asset-types', 'SYNTH-MAINTENANCE', 'Synthetic maintenance policy', 'ACTIVE', 1, ?, ?, ?, ?)
        """.trimIndent(), revision, org, at, manager, at, manager)
        jdbc.update("INSERT INTO asset_maintenance_policy VALUES (?, 2, true)", revision)
        jdbc.update("UPDATE asset_type_definition SET maintenance_policy_id = ?, calibration_due_lead_days = 1 WHERE config_revision_id = ?", revision, type)
        val before = requireNotNull(records.availability(asset, now))
        assertEquals("DUE", before.calibrationStatus)
        assertFalse(before.reasons.any { it.code == "MAINTENANCE_DUE" })
        val due = requireNotNull(records.availability(asset, now.plusSeconds(172800)))
        assertEquals("DUE", due.maintenanceStatus)
        assertEquals(revision, due.reasons.single { it.code == "MAINTENANCE_DUE" }.sourceId)
        assertEquals("COMPLETED", service.history(manager, asset).maintenance.single { it.id == completed }.state)
        jdbc.update("UPDATE asset_maintenance_policy SET blocks_when_due = false WHERE config_revision_id = ?", revision)
        val advisory = requireNotNull(records.availability(asset, now.plusSeconds(172800)))
        assertEquals("DUE", advisory.maintenanceStatus)
        assertFalse(advisory.reasons.any { it.code == "MAINTENANCE_DUE" })
    }

    private fun evidence(org: UUID, asset: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc.update("""
            INSERT INTO evidence (id, organization_id, target_type, target_id, evidence_type_code, content_type,
                size_bytes, sha256, captured_at, captured_by_user_id, storage_state, classification_code,
                client_visibility_mode, created_at, object_key_ref)
            VALUES (?, ?, 'ASSET', ?, 'SYNTH-CERT', 'application/pdf', 512, ?, ?, ?, 'READY', 'INTERNAL',
                'INTERNAL_ONLY', ?, 'SYNTH-PRIVATE-OBJECT')
        """.trimIndent(), id, org, asset, "a".repeat(64), at, manager, at)
        return id
    }

    private fun race(write: () -> Unit) {
        val barrier = CyclicBarrier(2)
        Executors.newFixedThreadPool(2).use { pool ->
            val outcomes = List(2) {
                pool.submit(Callable {
                    barrier.await(10, TimeUnit.SECONDS)
                    try {
                        write()
                        "SUCCESS"
                    } catch (failure: ProductApiException) {
                        failure.code
                    }
                })
            }.map { it.get(30, TimeUnit.SECONDS) }.sorted()
            assertEquals(listOf("ASSET_VERSION_CONFLICT", "SUCCESS"), outcomes)
        }
    }

    private fun context(operation: UUID = UUID.randomUUID(), actor: UUID = manager) = AssetCommandContext(operation, actor, "synthetic-asset-service")
    private fun denied(code: String, body: () -> Unit) = assertEquals(code, assertThrows<ProductApiException> { body() }.code)
}
