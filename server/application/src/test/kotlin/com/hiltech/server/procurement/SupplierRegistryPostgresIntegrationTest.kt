package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.platform.command.JdbcIdempotentCommandExecutor
import com.hiltech.server.telemetry.HiltechTelemetryRuntime
import io.opentelemetry.api.OpenTelemetry
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Actual transaction, source authority, replay, directory and immutable-history acceptance. */
class SupplierRegistryPostgresIntegrationTest {
    @Test fun registrarCreatesCorrectsSuspendsAndReactivatesWithoutRewritingHistory() {
        val f = fixture()
        val operation = UUID.randomUUID()
        val profile = SupplierProfileData("Synthetic Supplier Legal", "مورد تجريبي", email = "supplier@example.test")
        val request = CreateSupplierRequest(operation, profile)
        val created = f.service.createSupplier(f.ctx(operation), f.org, request)
        val replay = f.service.createSupplier(f.ctx(operation), f.org, request)
        assertFalse(created.replayed)
        assertTrue(replay.replayed)
        assertEquals(created.supplierOrganizationId, replay.supplierOrganizationId)
        val supplier = created.supplierOrganizationId
        assertEquals(1, f.count("procurement_supplier_profile", supplier))
        assertEquals(1, f.count("procurement_supplier_link_event", supplier))
        assertEquals(1, f.count("procurement_supplier_profile_event", supplier))
        assertEquals("مورد تجريبي", f.sourcing.searchEligibleSuppliers(f.org, "تجريبي").single().displayName)
        assertEquals("SUP-000001", f.service.supplierDetail(f.actor, f.org, supplier).organizationCode)
        assertEquals("SUP-000001", f.sourcing.searchEligibleSuppliers(f.org, "sup-000001").single().organizationCode)

        val updateOperation = UUID.randomUUID()
        val update = UpdateSupplierProfileRequest(updateOperation, 1, profile.copy(displayName = "Supplier %_ corrected", phone = "+20 100 000 0000"))
        val saved = f.service.updateSupplierProfile(f.ctx(updateOperation), f.org, supplier, update)
        assertEquals(2L, saved.version)
        assertEquals(supplier, f.sourcing.searchEligibleSuppliers(f.org, "%_").single().supplierOrganizationId)
        assertTrue(f.sourcing.searchEligibleSuppliers(f.org, "تجريبي").isEmpty())
        val nextOperation = UUID.randomUUID()
        f.service.updateSupplierProfile(f.ctx(nextOperation), f.org, supplier,
            UpdateSupplierProfileRequest(nextOperation, 2, update.profile.copy(contactName = "Synthetic contact")))
        val historicalReplay = f.service.updateSupplierProfile(f.ctx(updateOperation), f.org, supplier, update)
        assertTrue(historicalReplay.replayed)
        assertEquals(2L, historicalReplay.version)
        assertEquals(3L, f.service.supplierDetail(f.actor, f.org, supplier).version)
        assertEquals("SUP-000001", f.service.supplierDetail(f.actor, f.org, supplier).organizationCode)
        val stale = UUID.randomUUID()
        assertEquals("SUPPLIER_PROFILE_VERSION_CONFLICT", assertThrows<ProductApiException> {
            f.service.updateSupplierProfile(f.ctx(stale), f.org, supplier, update.copy(operationId = stale))
        }.code)

        val suspendOperation = UUID.randomUUID()
        val suspend = ChangeSupplierLinkRequest(suspendOperation, supplier, 2, "INACTIVE", "Contract under review")
        f.service.changeSupplierLink(f.ctx(suspendOperation), f.org, suspend)
        assertTrue(f.service.changeSupplierLink(f.ctx(suspendOperation), f.org, suspend).replayed)
        assertTrue(f.sourcing.eligibleSuppliers(f.org).isEmpty())
        assertEquals("INACTIVE", f.service.supplierRegistry(f.actor, f.org, "%_").single().relationshipState)
        val activate = UUID.randomUUID()
        f.service.changeSupplierLink(f.ctx(activate), f.org, ChangeSupplierLinkRequest(activate, supplier, 3, "ACTIVE", "Contract review completed"))
        assertEquals(3, f.sourcing.eligibleSuppliers(f.org).single().relationshipRevision)
        assertEquals(6, f.service.supplierHistory(f.actor, f.org, supplier).size)
        assertThrows<DataIntegrityViolationException> {
            f.jdbc.update("UPDATE procurement_supplier_link_event SET source_reference='Changed history' WHERE organization_id=? AND supplier_organization_id=?", f.org, supplier)
        }
        assertThrows<DataIntegrityViolationException> {
            f.jdbc.update("DELETE FROM procurement_supplier_profile_event WHERE organization_id=? AND supplier_organization_id=?", f.org, supplier)
        }
        assertThrows<DataIntegrityViolationException> {
            f.jdbc.update("UPDATE procurement_supplier_profile SET supplier_code='SUP-CHANGED' WHERE organization_id=? AND supplier_organization_id=?", f.org, supplier)
        }
    }

    @Test fun guessedForeignSupplierAndCurrentAuthorityFailuresCannotWrite() {
        val f = fixture()
        val foreign = f.organization("Foreign supplier", "SUPPLIER")
        assertTrue(f.service.supplierRegistry(f.actor, f.org, "Foreign").isEmpty())
        val link = UUID.randomUUID()
        assertEquals("SUPPLIER_MASTER_DATA_UNAVAILABLE", assertThrows<ProductApiException> {
            f.service.changeSupplierLink(f.ctx(link), f.org, ChangeSupplierLinkRequest(link, foreign, 1, "ACTIVE", "Guessed foreign identity"))
        }.code)
        val profile = UUID.randomUUID()
        assertEquals("SUPPLIER_MASTER_DATA_UNAVAILABLE", assertThrows<ProductApiException> {
            f.service.updateSupplierProfile(f.ctx(profile), f.org, foreign,
                UpdateSupplierProfileRequest(profile, 0, SupplierProfileData("Forbidden", "Forbidden")))
        }.code)
        assertEquals(0, f.count("procurement_supplier_profile", foreign))
        assertEquals(0, f.count("procurement_supplier_link_event", foreign))

        val buyerOnly = f.identity()
        assertFalse(f.authority.canMaintainSupplierLinks(buyerOnly, f.org))
        val otherCompany = f.organization("Other purchasing company", "HILTECH")
        assertFalse(f.authority.canMaintainSupplierLinks(f.actor, otherCompany))
        f.jdbc.update("UPDATE supplier_master_data_authority_binding SET effective_from=? WHERE organization_id=? AND identity_id=?",
            f.at.plusSeconds(60).atOffset(ZoneOffset.UTC), f.org, f.actor)
        assertFalse(f.authority.canMaintainSupplierLinks(f.actor, f.org))
        f.jdbc.update("UPDATE supplier_master_data_authority_binding SET effective_from=? WHERE organization_id=? AND identity_id=?",
            f.at.minusSeconds(60).atOffset(ZoneOffset.UTC), f.org, f.actor)
        f.jdbc.update("UPDATE user_identity SET status='LOCKED' WHERE id=?", f.actor)
        assertFalse(f.authority.canMaintainSupplierLinks(f.actor, f.org))
        f.jdbc.update("UPDATE user_identity SET status='ACTIVE' WHERE id=?", f.actor)
        f.jdbc.update("UPDATE organization_membership SET state='SUSPENDED' WHERE organization_id=? AND user_identity_id=?", f.org, f.actor)
        assertFalse(f.authority.canMaintainSupplierLinks(f.actor, f.org))
        f.jdbc.update("UPDATE organization_membership SET state='ACTIVE' WHERE organization_id=? AND user_identity_id=?", f.org, f.actor)
        f.jdbc.update("UPDATE organization SET status='INACTIVE' WHERE id=?", f.org)
        assertFalse(f.authority.canMaintainSupplierLinks(f.actor, f.org))
        f.jdbc.update("UPDATE organization SET status='ACTIVE' WHERE id=?", f.org)
        f.jdbc.update("UPDATE supplier_master_data_authority_binding SET active=false,version=version+1 WHERE organization_id=? AND identity_id=?", f.org, f.actor)
        assertFalse(f.service.capabilities(f.actor, f.org).canMaintain)
        val command = UUID.randomUUID()
        assertEquals("SUPPLIER_MASTER_DATA_UNAVAILABLE", assertThrows<ProductApiException> {
            f.service.createSupplier(f.ctx(command), f.org, CreateSupplierRequest(command, SupplierProfileData("Blocked", "Blocked")))
        }.code)
    }

    @Test fun failedAuditRollsBackIdentityProfileLinkAndIdempotencyBeforeSafeRetry() {
        val f = fixture()
        val command = UUID.randomUUID()
        val supplier = UUID.nameUUIDFromBytes("hiltech:procurement:supplier:$command".toByteArray(Charsets.UTF_8))
        val request = CreateSupplierRequest(command, SupplierProfileData("Atomic test supplier", "Atomic test supplier"))
        val failing = f.service(AuditEventWriter { error("Synthetic audit failure") })
        assertThrows<IllegalStateException> { failing.createSupplier(f.ctx(command), f.org, request) }
        assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM organization WHERE id=?", Int::class.java, supplier))
        assertEquals(0, f.count("procurement_supplier_profile", supplier))
        assertEquals(0, f.count("procurement_supplier_link_event", supplier))
        assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM idempotent_operation WHERE operation_id=?", Int::class.java, command))
        assertEquals(0, f.jdbc.queryForObject("SELECT count(*) FROM procurement_supplier_number WHERE organization_id=?", Int::class.java, f.org))
        assertFalse(f.service.createSupplier(f.ctx(command), f.org, request).replayed)
        assertEquals(1, f.count("procurement_supplier_profile_event", supplier))
        assertEquals("SUP-000001", f.service.supplierDetail(f.actor, f.org, supplier).organizationCode)
        assertEquals("IDEMPOTENCY_KEY_REUSED", assertThrows<ProductApiException> {
            f.service.createSupplier(f.ctx(command), f.org, request.copy(profile = request.profile.copy(displayName = "Changed request")))
        }.code)
    }

    @Test fun localCorrectionOfLegacySupplierDoesNotRenameAnotherCompanysSupplier() {
        val f = fixture()
        val supplier = f.organization("Shared legacy name", "SUPPLIER")
        val otherCompany = f.organization("Second synthetic purchaser", "HILTECH")
        fun link(company: UUID) = f.jdbc.update(
            """INSERT INTO procurement_supplier_link_event(id,organization_id,supplier_organization_id,revision_number,state,
                   source_reference,recorded_by_user_id,recorded_at,correlation_id) VALUES (?,?,?,1,'ACTIVE',?,?,?,'supplier-registry-test')""",
            UUID.randomUUID(), company, supplier, "Existing source-owned link", f.actor, f.at.atOffset(ZoneOffset.UTC),
        )
        link(f.org); link(otherCompany)
        assertEquals(0L, f.service.supplierDetail(f.actor, f.org, supplier).version)
        val originalCode = f.service.supplierDetail(f.actor, f.org, supplier).organizationCode
        val operation = UUID.randomUUID()
        f.service.updateSupplierProfile(f.ctx(operation), f.org, supplier,
            UpdateSupplierProfileRequest(operation, 0, SupplierProfileData("Company legal name", "Company local name")))
        assertEquals("Company local name", f.sourcing.eligibleSuppliers(f.org).single().displayName)
        assertEquals("Shared legacy name", f.sourcing.eligibleSuppliers(otherCompany).single().displayName)
        assertEquals("Shared legacy name", f.jdbc.queryForObject("SELECT display_name FROM organization WHERE id=?", String::class.java, supplier))
        assertEquals(originalCode, f.service.supplierDetail(f.actor, f.org, supplier).organizationCode)
        assertEquals(originalCode, f.sourcing.eligibleSuppliers(otherCompany).single().organizationCode)
    }

    @Test fun concurrentCreationAndReplayIssueDistinctCompactCompanyCodesWithoutRenumberingLegacySuppliers() {
        val f = fixture()
        val legacy = f.organization("Legacy compact supplier", "SUPPLIER")
        val reservedNumber = (UUID.randomUUID().mostSignificantBits and 0xffffffffffL) + 1_000_000
        val legacyCode = "SUP-$reservedNumber"
        f.jdbc.update("UPDATE organization SET organization_code=? WHERE id=?", legacyCode, legacy)
        f.jdbc.update("INSERT INTO procurement_supplier_number(organization_id,last_issued) VALUES (?,?)", f.org, reservedNumber - 1)
        f.jdbc.update("""INSERT INTO procurement_supplier_link_event(id,organization_id,supplier_organization_id,revision_number,state,
            source_reference,recorded_by_user_id,recorded_at,correlation_id) VALUES (?,?,?,1,'ACTIVE','Synthetic legacy supplier reference',?,?,'supplier-registry-test')""",
            UUID.randomUUID(), f.org, legacy, f.actor, f.at.atOffset(ZoneOffset.UTC))
        val requests = (1..6).map { index -> CreateSupplierRequest(UUID.randomUUID(), SupplierProfileData("Concurrent legal $index", "Concurrent supplier $index")) }
        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        val results = try {
            val futures = (requests + requests.take(2)).map { request ->
                executor.submit<SupplierProfileReceipt> {
                    check(gate.await(20, TimeUnit.SECONDS))
                    f.service.createSupplier(f.ctx(request.operationId), f.org, request)
                }
            }
            gate.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            gate.countDown()
            executor.shutdownNow()
        }
        assertEquals(6, results.map { it.supplierOrganizationId }.toSet().size)
        assertEquals(2, results.count { it.replayed })
        val codes = results.map { f.service.supplierDetail(f.actor, f.org, it.supplierOrganizationId).organizationCode }.toSet()
        assertEquals((1..6).map { "SUP-" + (reservedNumber + it).toString().padStart(6, '0') }.toSet(), codes)
        assertEquals(reservedNumber + 6, f.jdbc.queryForObject("SELECT last_issued FROM procurement_supplier_number WHERE organization_id=?", Long::class.java, f.org))
        codes.forEach { code ->
            assertEquals(code, f.service.supplierRegistry(f.actor, f.org, code).single().organizationCode)
            assertEquals(code, f.sourcing.searchEligibleSuppliers(f.org, code).single().organizationCode)
        }
        assertEquals(legacyCode, f.service.supplierDetail(f.actor, f.org, legacy).organizationCode)
        assertEquals(6, f.jdbc.queryForObject("""SELECT count(*) FROM procurement_supplier_profile_event e
            JOIN procurement_supplier_profile p ON p.organization_id=e.organization_id AND p.supplier_organization_id=e.supplier_organization_id
            WHERE e.organization_id=? AND e.profile_snapshot->>'supplierCode'=p.supplier_code""", Int::class.java, f.org))
        // Another company has an independent human sequence; the shared identity remains globally unique.
        val other = fixture()
        val first = CreateSupplierRequest(UUID.randomUUID(), SupplierProfileData("Other company supplier", "Other company supplier"))
        val created = other.service.createSupplier(other.ctx(first.operationId), other.org, first)
        assertEquals("SUP-000001", other.service.supplierDetail(other.actor, other.org, created.supplierOrganizationId).organizationCode)
    }

    private fun fixture(): Fixture {
        assumeTrue(System.getenv("HILTECH_DB_CONTRACT_TEST") == "1")
        val ds = DriverManagerDataSource(
            System.getenv("HILTECH_DB_URL") ?: "jdbc:postgresql://localhost:5432/hiltech",
            System.getenv("HILTECH_DB_USER") ?: "hiltech", System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech",
        )
        Flyway.configure().dataSource(ds).locations("filesystem:" + (System.getenv("HILTECH_MIGRATIONS_PATH") ?: "database/migrations")).load().migrate()
        return Fixture(ds)
    }

    private class Fixture(ds: DriverManagerDataSource) {
        val jdbc = JdbcTemplate(ds)
        val at: Instant = Instant.parse("2026-11-25T12:00:00Z")
        val clock: Clock = Clock.fixed(at, ZoneOffset.UTC)
        val org = organization("Synthetic registrar organization", "HILTECH")
        val actor = identity()
        val authority = CurrentSupplierMasterDataAuthorization(jdbc, clock)
        val sourcing = SupplierSourcingPersistence(jdbc)
        val registry = SupplierRegistryPersistence(jdbc)
        val commands = JdbcIdempotentCommandExecutor(jdbc, DataSourceTransactionManager(ds), clock, HiltechTelemetryRuntime(OpenTelemetry.noop()) {})
        val service = service(JdbcAuditEventWriter(jdbc))

        init {
            jdbc.update("""INSERT INTO supplier_master_data_authority_binding(id,organization_id,identity_id,effective_from,created_by_user_id,created_at)
                VALUES (?,?,?,?,?,?)""", UUID.randomUUID(), org, actor, at.minusSeconds(60).atOffset(ZoneOffset.UTC), actor, at.atOffset(ZoneOffset.UTC))
        }

        fun service(audit: AuditEventWriter) = SupplierMasterDataService(sourcing, registry, authority, commands, audit, clock)
        fun ctx(operation: UUID) = ProcurementDraftContext(operation, actor, "supplier-registry-test")
        fun count(table: String, supplier: UUID): Int = jdbc.queryForObject(
            "SELECT count(*) FROM $table WHERE organization_id=? AND supplier_organization_id=?", Int::class.java, org, supplier,
        )!!
        fun organization(name: String, type: String): UUID = UUID.randomUUID().also { id ->
            jdbc.update("""INSERT INTO organization(id,organization_code,legal_name,display_name,organization_type,status,created_at)
                VALUES (?,?,?,?,?,'ACTIVE',?)""", id, "TEST-" + id, name, name, type, at.atOffset(ZoneOffset.UTC))
        }
        fun identity(): UUID = UUID.randomUUID().also { id ->
            jdbc.update("""INSERT INTO user_identity(id,auth_provider,auth_subject,status,primary_organization_id,created_at)
                VALUES (?,'SYNTHETIC',?,'ACTIVE',?,?)""", id, "supplier-registrar-$id", org, at.atOffset(ZoneOffset.UTC))
            jdbc.update("""INSERT INTO organization_membership(id,organization_id,user_identity_id,membership_type,state,valid_from)
                VALUES (?,?,?,'EMPLOYEE','ACTIVE',?)""", UUID.randomUUID(), org, id, at.minusSeconds(60).atOffset(ZoneOffset.UTC))
        }
    }
}
