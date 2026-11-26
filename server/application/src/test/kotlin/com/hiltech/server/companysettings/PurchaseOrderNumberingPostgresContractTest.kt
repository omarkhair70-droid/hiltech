package com.hiltech.server.companysettings

import com.hiltech.server.administration.CompanyResponsibilityKey
import com.hiltech.server.administration.JdbcCompanyResponsibilityPersistence
import com.hiltech.server.administration.JdbcOrganizationAdministrationAuthority
import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecution
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Rollback-only synthetic source contract; no company data or external services. */
class PurchaseOrderNumberingPostgresContractTest {
    private val at = Instant.parse("2026-11-25T12:00:00Z")
    private val clock = Clock.fixed(at, ZoneOffset.UTC)

    @Test fun approvalAdministrationAndOperatingIssuerDoNotConferSettingsAuthority() = database { jdbc, org, actor ->
        val bindings = JdbcCompanyResponsibilityPersistence(jdbc)
        val authority = JdbcOrganizationAdministrationAuthority(jdbc, clock)
        for (role in listOf(CompanyResponsibilityKey.APPROVAL_POLICY_ADMIN, CompanyResponsibilityKey.PROCUREMENT_ORDER_ISSUE))
            bindings.grant(role, UUID.randomUUID(), org, actor, actor, UUID.randomUUID(), at)
        assertTrue(authority.canManageApprovalPolicies(actor, org))
        assertFalse(authority.canManageCompanySettings(actor, org))
        bindings.grant(CompanyResponsibilityKey.COMPANY_SETTINGS_ADMIN, UUID.randomUUID(), org, actor, actor, UUID.randomUUID(), at)
        assertTrue(authority.canManageCompanySettings(actor, org))
        jdbc.update("UPDATE organization_membership SET state='SUSPENDED' WHERE organization_id=? AND user_identity_id=?", org, actor)
        assertFalse(authority.canManageCompanySettings(actor, org))
    }

    @Test fun adoptingAndChangingThePrefixPreservesHistoryAndTheCompanySerialCounter() = database { jdbc, org, actor ->
        val source = JdbcPurchaseOrderNumberingPersistence(jdbc, "OLD-PO")
        JdbcCompanyResponsibilityPersistence(jdbc).grant(CompanyResponsibilityKey.COMPANY_SETTINGS_ADMIN,
            UUID.randomUUID(), org, actor, actor, UUID.randomUUID(), at)
        jdbc.update("INSERT INTO procurement_po_number_counter (organization_id,last_number) VALUES (?,26)", org)
        val service = PurchaseOrderNumberingService(JdbcOrganizationAdministrationAuthority(jdbc, clock), source,
            DirectCommands(), JdbcAuditEventWriter(jdbc), clock)
        val inherited = service.current(actor, org)
        assertEquals("DEPLOYMENT", inherited.source)
        assertEquals("OLD-PO", inherited.prefix)
        val first = service.configure(ConfigurePurchaseOrderNumberingCommand(UUID.randomUUID(), org, 0,
            PurchaseOrderNumberingSource.DEPLOYMENT, "OLD-PO", "OLD-PO", "Adopt synthetic existing numbering", actor, "synthetic-numbering"))
        val second = service.configure(ConfigurePurchaseOrderNumberingCommand(UUID.randomUUID(), org, 1,
            PurchaseOrderNumberingSource.COMPANY, "OLD-PO", "HT-PUR", "Change synthetic company prefix", actor, "synthetic-numbering"))
        assertEquals(1L, first.version)
        assertEquals(2L, second.version)
        assertEquals(listOf("HT-PUR", "OLD-PO"), source.history(org, 50).map { it.prefix })
        assertEquals("HT-PUR", JdbcPurchaseOrderNumberingPersistence(jdbc, "REPLACED-ENV").current(org).prefix)
        assertEquals(26L, jdbc.queryForObject("SELECT last_number FROM procurement_po_number_counter WHERE organization_id=?", Long::class.java, org))
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE actor_user_id=? AND action='PURCHASE_ORDER_NUMBERING_CONFIGURED'", Int::class.java, actor))
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM approval_authority_binding WHERE organization_id=?", Int::class.java, org))
    }

    @Test fun immutablePrefixHistoryRejectsRewrite() = database { jdbc, org, actor ->
        val source = JdbcPurchaseOrderNumberingPersistence(jdbc)
        JdbcCompanyResponsibilityPersistence(jdbc).grant(CompanyResponsibilityKey.COMPANY_SETTINGS_ADMIN,
            UUID.randomUUID(), org, actor, actor, UUID.randomUUID(), at)
        val row = PurchaseOrderNumberingRevision(UUID.randomUUID(), UUID.randomUUID(), org, 1, "HT-PO",
            PurchaseOrderNumberingSource.NOT_CONFIGURED, null, "Synthetic first prefix", actor, at)
        source.append(row)
        assertThrows<DataIntegrityViolationException> { jdbc.update("UPDATE purchase_order_numbering_revision SET prefix='OTHER' WHERE id=?", row.id) }
    }

    @Test fun databaseSourceGuardDoesNotAcceptARevisionFromAnUnassignedIdentity() = database { jdbc, org, actor ->
        assertThrows<DataIntegrityViolationException> {
            JdbcPurchaseOrderNumberingPersistence(jdbc).append(PurchaseOrderNumberingRevision(UUID.randomUUID(), UUID.randomUUID(), org,
                1, "HT-PO", PurchaseOrderNumberingSource.NOT_CONFIGURED, null, "Synthetic unauthorized source", actor, at))
        }
    }

    @Test fun databaseSourceGuardRejectsSkippingOrRewritingTheCurrentVersion() = database { jdbc, org, actor ->
        JdbcCompanyResponsibilityPersistence(jdbc).grant(CompanyResponsibilityKey.COMPANY_SETTINGS_ADMIN,
            UUID.randomUUID(), org, actor, actor, UUID.randomUUID(), at)
        assertThrows<DataIntegrityViolationException> {
            JdbcPurchaseOrderNumberingPersistence(jdbc).append(PurchaseOrderNumberingRevision(UUID.randomUUID(), UUID.randomUUID(), org,
                2, "HT-PO", PurchaseOrderNumberingSource.NOT_CONFIGURED, null, "Synthetic invalid version", actor, at))
        }
    }

    private class DirectCommands : IdempotentCommandExecutor {
        override fun execute(spec: IdempotentCommandSpec, action: () -> IdempotentCommandOutcome) = IdempotentCommandExecution(action(), false)
    }
    private fun database(action: (JdbcTemplate, UUID, UUID) -> Unit) {
        assumeTrue(System.getenv("HILTECH_RESPONSIBILITY_CONTRACT_TEST") == "1")
        DriverManager.getConnection(requireNotNull(System.getenv("HILTECH_DB_URL")), requireNotNull(System.getenv("HILTECH_DB_USER")),
            requireNotNull(System.getenv("HILTECH_DB_PASSWORD"))).use { connection ->
            connection.autoCommit = false
            try {
                val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
                val org = UUID.randomUUID(); val actor = UUID.randomUUID(); val before = at.minusSeconds(60).atOffset(ZoneOffset.UTC)
                jdbc.update("""INSERT INTO organization (id,organization_code,legal_name,display_name,organization_type,status,created_at)
                    VALUES (?,?,'Synthetic settings company','Synthetic settings company','HILTECH','ACTIVE',?)""", org, "SYNTH_${org}", before)
                jdbc.update("INSERT INTO user_identity (id,auth_provider,auth_subject,status,created_at) VALUES (?,'synthetic-settings',?,'ACTIVE',?)", actor, actor.toString(), before)
                jdbc.update("INSERT INTO organization_membership (id,organization_id,user_identity_id,membership_type,state,valid_from) VALUES (?,?,?,'EMPLOYEE','ACTIVE',?)",
                    UUID.randomUUID(), org, actor, before)
                action(jdbc, org, actor)
            } finally { connection.rollback() }
        }
    }
}
