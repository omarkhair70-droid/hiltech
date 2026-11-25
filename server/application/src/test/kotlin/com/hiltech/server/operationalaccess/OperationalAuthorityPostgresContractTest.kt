package com.hiltech.server.operationalaccess

import com.hiltech.server.administration.CompanyResponsibilityKey
import com.hiltech.server.administration.JdbcCompanyResponsibilityPersistence
import com.hiltech.server.approval.JdbcApprovalPersistence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Explicit opt-in, synthetic company identities and rollback-only disposable database. */
class OperationalAuthorityPostgresContractTest {
    private val at = Instant.parse("2026-11-25T12:00:00Z")
    private val stamp = at.atOffset(ZoneOffset.UTC)

    @Test fun multipleNamedPreparersCanUseTheSameRoleWithoutCreatingAnApprovalBinding() = database { jdbc, ids ->
        val managed = JdbcCompanyResponsibilityPersistence(jdbc)
        val source = JdbcOperationalAuthority(jdbc)
        for (user in listOf(ids.actor, ids.recipient)) {
            val id = UUID.randomUUID()
            managed.grant(CompanyResponsibilityKey.FINANCE_PAYABLE_PREPARE, id, ids.organization, user, ids.actor, UUID.randomUUID(), at)
            val proof = requireNotNull(source.current(user, ids.organization, OperationalResponsibility.FINANCE_PAYABLE_PREPARE, at))
            assertEquals(id, proof.bindingId)
            assertEquals(OperationalAuthorityOrigin.OPERATIONAL, proof.origin)
            assertNull(source.current(user, ids.organization, OperationalResponsibility.FINANCE_PAYABLE_POST, at))
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM approval_authority_binding WHERE organization_id=?", Int::class.java, ids.organization))
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM responsibility_administration_binding WHERE organization_id=?", Int::class.java, ids.organization))
    }

    @Test fun actualTeamMembershipIsRequiredAndDoesNotSurviveTeamOrCompanySuspension() = database { jdbc, ids ->
        val team = UUID.randomUUID()
        val binding = UUID.randomUUID()
        jdbc.update("INSERT INTO team (id,organization_id,code,name,active) VALUES (?,?,'SYNTH-OPERATIONS','Synthetic operating team',true)", team, ids.organization)
        jdbc.update("INSERT INTO team_membership (id,team_id,user_identity_id,valid_from) VALUES (?,?,?,?)", UUID.randomUUID(), team, ids.recipient, stamp)
        jdbc.update("""INSERT INTO operational_authority_binding
            (id,organization_id,authority_key,principal_type,principal_team_id,effective_from,created_by_user_id,created_at)
            VALUES (?,?,'PROCUREMENT_BUYER','TEAM',?,?,?,?)""", binding, ids.organization, team, stamp, ids.actor, stamp)
        val source = JdbcOperationalAuthority(jdbc)
        assertEquals(binding, source.current(ids.recipient, ids.organization, OperationalResponsibility.PROCUREMENT_BUYER, at)?.bindingId)
        assertNull(source.current(ids.actor, ids.organization, OperationalResponsibility.PROCUREMENT_BUYER, at))
        jdbc.update("UPDATE team SET active=false WHERE id=?", team)
        assertNull(source.current(ids.recipient, ids.organization, OperationalResponsibility.PROCUREMENT_BUYER, at))
        jdbc.update("UPDATE team SET active=true WHERE id=?", team)
        jdbc.update("UPDATE organization_membership SET state='SUSPENDED' WHERE organization_id=? AND user_identity_id=?", ids.organization, ids.recipient)
        assertNull(source.current(ids.recipient, ids.organization, OperationalResponsibility.PROCUREMENT_BUYER, at))
    }

    @Test fun expiredMembershipIsRejectedAtItsExclusiveBoundary() = database { jdbc, ids ->
        val managed = JdbcCompanyResponsibilityPersistence(jdbc)
        managed.grant(CompanyResponsibilityKey.PROCUREMENT_BUYER, UUID.randomUUID(), ids.organization, ids.recipient, ids.actor, UUID.randomUUID(), at)
        jdbc.update("UPDATE organization_membership SET valid_until=? WHERE organization_id=? AND user_identity_id=?", stamp, ids.organization, ids.recipient)
        assertNull(JdbcOperationalAuthority(jdbc).current(ids.recipient, ids.organization, OperationalResponsibility.PROCUREMENT_BUYER, at))
    }

    @Test fun endingAnExplicitGrantPreservesItsHistoryAndCustomExistingSource() = database { jdbc, ids ->
        val old = UUID.randomUUID()
        val direct = UUID.randomUUID()
        jdbc.update("""INSERT INTO approval_authority_binding
            (id,organization_id,authority_key,principal_type,principal_user_id,effective_from,created_by_user_id,created_at)
            VALUES (?,?,'SYNTH_CUSTOM_BUYER','USER',?,?,?,?)""", old, ids.organization, ids.recipient, stamp, ids.actor, stamp)
        val env = StandardEnvironment().also { it.propertySources.addFirst(MapPropertySource("synthetic-operating-sources",
            mapOf("hiltech.procurement.sourcing.buyer-authority-key" to "SYNTH_CUSTOM_BUYER"))) }
        val legacy = JdbcLegacyOperationalAuthorityDirectory(jdbc, env)
        val managed = JdbcCompanyResponsibilityPersistence(jdbc, legacy)
        managed.grant(CompanyResponsibilityKey.PROCUREMENT_BUYER, direct, ids.organization, ids.recipient, ids.actor, UUID.randomUUID(), at)
        val before = managed.bindings(ids.organization, at)
        assertEquals(2, before.size)
        assertFalse(before.single { it.bindingId == old }.administrationManaged)
        assertFalse(managed.end(before.single { it.bindingId == old }, at))
        assertTrue(managed.end(before.single { it.bindingId == direct }, at))
        val proof = currentOperationalAuthority(JdbcOperationalAuthority(jdbc), JdbcApprovalPersistence(jdbc), ids.recipient,
            ids.organization, "hiltech.procurement.sourcing.buyer-authority-key", "SYNTH_CUSTOM_BUYER", at)
        assertEquals(old, proof?.bindingId)
        assertEquals(OperationalAuthorityOrigin.LEGACY_APPROVAL_BINDING, proof?.origin)
        assertEquals(1, managed.bindings(ids.organization, at).size)
        assertFalse(requireNotNull(managed.binding(CompanyResponsibilityKey.PROCUREMENT_BUYER, direct, at)).current)
        assertEquals(2L, managed.binding(CompanyResponsibilityKey.PROCUREMENT_BUYER, direct, at)?.version)
        assertTrue(jdbc.queryForObject("SELECT active FROM approval_authority_binding WHERE id=?", Boolean::class.java, old) == true)
    }

    @Test fun issuerProofUsesTheRealSourceAndCannotBeSubstitutedWithAnotherRoleOrIdentity() = database { jdbc, ids ->
        val managed = JdbcCompanyResponsibilityPersistence(jdbc)
        val issuer = UUID.randomUUID()
        val buyer = UUID.randomUUID()
        managed.grant(CompanyResponsibilityKey.PROCUREMENT_ORDER_ISSUE, issuer, ids.organization, ids.recipient, ids.actor, UUID.randomUUID(), at)
        managed.grant(CompanyResponsibilityKey.PROCUREMENT_BUYER, buyer, ids.organization, ids.recipient, ids.actor, UUID.randomUUID(), at)
        fun proof(user: UUID, binding: UUID, key: String) = jdbc.queryForObject(
            "SELECT current_purchase_issuer_source(?,?,NULL,?,?,?)", Boolean::class.java,
            ids.organization, user, binding, key, stamp) == true
        assertTrue(proof(ids.recipient, issuer, "PROCUREMENT_ORDER_ISSUE"))
        assertFalse(proof(ids.actor, issuer, "PROCUREMENT_ORDER_ISSUE"))
        assertFalse(proof(ids.recipient, buyer, "PROCUREMENT_BUYER"))
        assertFalse(proof(ids.recipient, issuer, "SYNTH_CUSTOM_KEY"))
        assertTrue(managed.end(requireNotNull(managed.binding(CompanyResponsibilityKey.PROCUREMENT_ORDER_ISSUE, issuer, at)), at))
        assertFalse(proof(ids.recipient, issuer, "PROCUREMENT_ORDER_ISSUE"))
        assertNotNull(managed.binding(CompanyResponsibilityKey.PROCUREMENT_ORDER_ISSUE, issuer, at))
    }

    @Test fun historicalGrantCannotBeRewrittenIntoAReviewerOrDifferentIdentity() = database { jdbc, ids ->
        val id = UUID.randomUUID()
        JdbcCompanyResponsibilityPersistence(jdbc).grant(CompanyResponsibilityKey.PROCUREMENT_BUYER,
            id, ids.organization, ids.recipient, ids.actor, UUID.randomUUID(), at)
        assertThrows<DataIntegrityViolationException> {
            jdbc.update("UPDATE operational_authority_binding SET principal_user_id=? WHERE id=?", ids.actor, id)
        }
    }

    private fun database(action: (JdbcTemplate, Ids) -> Unit) {
        assumeTrue(System.getenv("HILTECH_RESPONSIBILITY_CONTRACT_TEST") == "1")
        DriverManager.getConnection(requireNotNull(System.getenv("HILTECH_DB_URL")),
            requireNotNull(System.getenv("HILTECH_DB_USER")), requireNotNull(System.getenv("HILTECH_DB_PASSWORD"))).use { connection ->
            connection.autoCommit = false
            try {
                val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
                val ids = Ids(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
                val before = at.minusSeconds(60).atOffset(ZoneOffset.UTC)
                jdbc.update("""INSERT INTO organization (id,organization_code,legal_name,display_name,organization_type,status,created_at)
                    VALUES (?,?,'Synthetic operating company','Synthetic operating company','HILTECH','ACTIVE',?)""", ids.organization, "SYNTH_${ids.organization}", before)
                for (id in listOf(ids.actor, ids.recipient)) {
                    jdbc.update("INSERT INTO user_identity (id,auth_provider,auth_subject,status,created_at) VALUES (?,'synthetic-operating',?,'ACTIVE',?)", id, id.toString(), before)
                    jdbc.update("""INSERT INTO organization_membership (id,organization_id,user_identity_id,membership_type,state,valid_from)
                        VALUES (?,?,?,'EMPLOYEE','ACTIVE',?)""", UUID.randomUUID(), ids.organization, id, before)
                }
                action(jdbc, ids)
            } finally { connection.rollback() }
        }
    }
    private data class Ids(val organization: UUID, val actor: UUID, val recipient: UUID)
}
