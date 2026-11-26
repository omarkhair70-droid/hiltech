package com.hiltech.server.people

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Actual V0120 source contracts in rollback-only synthetic fixtures; no identity provider is contacted. */
class EmployeeAccountAccessPostgresContractTest {
    private val at = Instant.parse("2026-11-25T12:00:00Z")

    @Test
    fun currentSourceRequiresTheExactEmployeePersonAccountAndCompanyMembership() = database { f ->
        val account = requireNotNull(f.source.currentLinkedAccount(f.person, f.organization, at))
        assertEquals(f.identity, account.identityId)
        assertEquals(3L, account.identityVersion)
        assertEquals(f.membership, account.membershipId)
        assertEquals(2L, account.membershipVersion)
        assertNull(f.source.currentAccount(f.identity, UUID.randomUUID(), at))
        assertNull(f.source.currentLinkedAccount(UUID.randomUUID(), f.organization, at))
    }

    @Test
    fun restrictedIdentityAndNoncurrentMembershipNeverQualifyForCredentialReplacement() = database { f ->
        for (state in listOf("LOCKED", "REVOKED", "PENDING")) {
            f.jdbc.update("UPDATE user_identity SET status=? WHERE id=?", state, f.identity)
            assertNull(f.source.currentAccount(f.identity, f.organization, at))
            assertTrue(f.source.personHasOtherAccount(f.person, null))
        }
        f.jdbc.update("UPDATE user_identity SET status='ACTIVE' WHERE id=?", f.identity)
        for (state in listOf("SUSPENDED", "ENDED", "PENDING")) {
            f.jdbc.update("UPDATE organization_membership SET state=? WHERE id=?", state, f.membership)
            assertNull(f.source.currentAccount(f.identity, f.organization, at))
        }
        f.jdbc.update("UPDATE organization_membership SET state='ACTIVE',valid_until=? WHERE id=?", at.offset(), f.membership)
        assertNull(f.source.currentAccount(f.identity, f.organization, at))
        f.jdbc.update("UPDATE organization_membership SET valid_until=NULL,valid_from=? WHERE id=?", at.plusSeconds(1).offset(), f.membership)
        assertNull(f.source.currentAccount(f.identity, f.organization, at))
    }

    @Test
    fun inactiveCompanyCannotProvideAccountAccess() = database { f ->
        f.jdbc.update("UPDATE organization SET status='INACTIVE' WHERE id=?", f.organization)
        assertNull(f.source.currentAccount(f.identity, f.organization, at))
        assertNull(f.source.currentLinkedAccount(f.person, f.organization, at))
    }

    @Test
    fun namedSelfLinkAttachesUnownedIdentityAndPreservesEmploymentAndMembership() = database(linked = false) { f ->
        val own = requireNotNull(f.source.currentAccount(f.actor, f.organization, at))
        assertNull(own.personId)
        assertFalse(f.source.personHasOtherAccount(f.person, null))
        f.source.transaction {
            f.source.lockEmployeeAndAccount(f.employee, f.actor)
            assertTrue(f.source.linkOwnAccount(f.context(), own, at))
        }
        val linked = requireNotNull(f.source.currentAccount(f.actor, f.organization, at))
        assertEquals(f.person, linked.personId)
        assertEquals(2L, linked.identityVersion)
        assertEquals(own.membershipVersion, linked.membershipVersion)
        assertEquals("ACTIVE", f.jdbc.queryForObject("SELECT state FROM employee WHERE id=?", String::class.java, f.employee))
        assertEquals(8L, f.jdbc.queryForObject("SELECT version FROM employee WHERE id=?", Long::class.java, f.employee))
        assertEquals(f.organization, f.jdbc.queryForObject("SELECT primary_organization_id FROM user_identity WHERE id=?", UUID::class.java, f.actor))
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM onboarding_case WHERE employee_id=?", Int::class.java, f.employee))
        assertFalse(f.source.personHasOtherAccount(f.person, f.actor))
    }

    @Test
    fun selfLinkCannotMoveOwnedIdentityOrIgnoreItsVersion() = database(linked = false) { f ->
        val own = requireNotNull(f.source.currentAccount(f.actor, f.organization, at))
        assertFalse(f.source.linkOwnAccount(f.context(), own.copy(identityVersion = own.identityVersion + 1), at))
        assertNull(f.source.currentAccount(f.actor, f.organization, at)?.personId)
        f.jdbc.update("UPDATE user_identity SET person_id=? WHERE id=?", f.person, f.actor)
        assertFalse(f.source.linkOwnAccount(f.context(), own, at))
        assertEquals(7L, f.jdbc.queryForObject("SELECT version FROM employee WHERE id=?", Long::class.java, f.employee))
    }

    @Test
    fun pendingInvitationRemainsAnAccountLinkConflict() = database(linked = false) { f ->
        assertFalse(f.source.hasInvitation(f.employee))
        JdbcEmployeeIdentityInvitationPersistence(f.jdbc).insertPending(UUID.randomUUID(), UUID.randomUUID(), f.organization,
            f.employee, "synthetic@example.test", "https://identity.example.test/realms/synthetic",
            com.hiltech.server.identity.IdentityProvisioningDeliveryMode.TEMPORARY_PASSWORD_HANDOFF, f.actor, at)
        assertTrue(f.source.hasInvitation(f.employee))
    }

    @Test
    fun dispatchOutcomeCanBeRecordedOnceWithoutCredentialMaterial() = database { f ->
        val command = f.command()
        f.source.insertDispatch(command, f.organization, "synthetic-fingerprint", at)
        val pending = requireNotNull(f.source.reissue(command.operationId))
        assertEquals("DISPATCHING", pending.state)
        assertNull(pending.completedAt)
        assertEquals(1L, pending.version)
        assertTrue(f.source.finishDispatch(command.operationId, "ISSUED", null, at.plusSeconds(1)))
        assertFalse(f.source.finishDispatch(command.operationId, "ISSUED", null, at.plusSeconds(2)))
        val completed = requireNotNull(f.source.reissue(command.operationId))
        assertEquals("ISSUED", completed.state)
        assertEquals(2L, completed.version)
        assertEquals(at.plusSeconds(1), completed.completedAt)
        val columns = f.jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='employee_credential_reissue'", String::class.java)
        assertFalse(columns.map { requireNotNull(it) }.any { it.contains("password") || it.contains("secret") || it.contains("token") || it == "credential" })
    }

    @Test
    fun failedAndUnconfirmedProviderOutcomesRemainDistinct() = database { f ->
        val first = f.command()
        f.source.insertDispatch(first, f.organization, "synthetic-rejected", at)
        assertTrue(f.source.finishDispatch(first.operationId, "REJECTED", "ACCOUNT_CREDENTIAL_PROVIDER_ACCOUNT_RESTRICTED", at))
        val second = f.command().copy(previousOperationId = first.operationId, previousOperationVersion = 2)
        f.source.insertDispatch(second, f.organization, "synthetic-unconfirmed", at)
        assertTrue(f.source.finishDispatch(second.operationId, "UNCONFIRMED", "ACCOUNT_CREDENTIAL_PROVIDER_OUTCOME_UNCONFIRMED", at))
        assertEquals("REJECTED", f.source.reissue(first.operationId)?.state)
        assertEquals("UNCONFIRMED", f.source.reissue(second.operationId)?.state)
        assertEquals(second.operationId, f.source.latestReissue(f.identity)?.operationId)
    }

    @Test
    fun latestOperationUsesAppendOrderEvenWhenClockAndUuidOrderingDisagree() = database { f ->
        val first = f.command().copy(operationId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"))
        val second = f.command().copy(operationId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            previousOperationId = first.operationId, previousOperationVersion = 1)
        f.source.insertDispatch(first, f.organization, "synthetic-first", at)
        f.source.insertDispatch(second, f.organization, "synthetic-second", at)
        assertEquals(second.operationId, f.source.latestReissue(f.identity)?.operationId)
        assertNotNull(f.source.reissue(first.operationId))
    }

    @Test
    fun immutableIntentAndCompletedHistoryCannotBeRewrittenOrDeleted() = database { f ->
        val command = f.command()
        f.source.insertDispatch(command, f.organization, "synthetic-fingerprint", at)
        rejected(f.jdbc) { f.jdbc.update("UPDATE employee_credential_reissue SET reason='changed' WHERE operation_id=?", command.operationId) }
        assertTrue(f.source.finishDispatch(command.operationId, "ISSUED", null, at))
        rejected(f.jdbc) { f.jdbc.update("UPDATE employee_credential_reissue SET state='REJECTED',version=version+1 WHERE operation_id=?", command.operationId) }
        rejected(f.jdbc) { f.jdbc.update("DELETE FROM employee_credential_reissue WHERE operation_id=?", command.operationId) }
        assertEquals("ISSUED", f.source.reissue(command.operationId)?.state)
    }

    @Test
    fun sameOperationIdCannotCreateASecondDispatch() = database { f ->
        val command = f.command()
        f.source.insertDispatch(command, f.organization, "synthetic-first", at)
        rejected(f.jdbc) { f.source.insertDispatch(command.copy(reason = "Changed synthetic reason"), f.organization, "synthetic-second", at) }
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM employee_credential_reissue WHERE operation_id=?", Int::class.java, command.operationId))
    }

    private fun rejected(jdbc: JdbcTemplate, mutation: () -> Unit) {
        jdbc.execute(ConnectionCallback { connection ->
            val savepoint = connection.setSavepoint()
            try { assertThrows<DataAccessException> { mutation() } }
            finally { connection.rollback(savepoint); connection.releaseSavepoint(savepoint) }
            Unit
        })
    }
    private fun database(linked: Boolean = true, action: (Fixture) -> Unit) {
        assumeTrue(System.getenv("HILTECH_RESPONSIBILITY_CONTRACT_TEST") == "1")
        DriverManager.getConnection(requireNotNull(System.getenv("HILTECH_DB_URL")), requireNotNull(System.getenv("HILTECH_DB_USER")),
            requireNotNull(System.getenv("HILTECH_DB_PASSWORD"))).use { connection ->
            val dataSource = SingleConnectionDataSource(connection, true)
            val manager = DataSourceTransactionManager(dataSource)
            TransactionTemplate(manager).executeWithoutResult { transaction ->
                transaction.setRollbackOnly()
                action(Fixture(JdbcTemplate(dataSource), manager, linked))
            }
        }
    }
    private fun Instant.offset() = atOffset(ZoneOffset.UTC)
    private inner class Fixture(val jdbc: JdbcTemplate, manager: DataSourceTransactionManager, linked: Boolean) {
        val organization = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val actorMembership = UUID.randomUUID()
        val person = UUID.randomUUID()
        val employee = UUID.randomUUID()
        val identity = UUID.randomUUID()
        val membership = UUID.randomUUID()
        val source = JdbcEmployeeAccountAccessPersistence(jdbc, manager)
        init {
            val before = at.minusSeconds(60).offset()
            jdbc.update("""INSERT INTO organization (id,organization_code,legal_name,display_name,organization_type,status,created_at)
                VALUES (?,?,'Synthetic access company','Synthetic access company','HILTECH','ACTIVE',?)""".trimIndent(), organization, "TEST_$organization", before)
            jdbc.update("INSERT INTO person (id,display_name,created_at,updated_at) VALUES (?,'Synthetic account employee',?,?)", person, before, before)
            jdbc.update("""INSERT INTO employee (id,organization_id,person_id,employee_code,state,created_at,updated_at,version)
                VALUES (?,?,?,'EMP-SYNTHETIC','ACTIVE',?,?,7)""".trimIndent(), employee, organization, person, before, before)
            jdbc.update("""INSERT INTO user_identity (id,auth_provider,auth_subject,status,created_at)
                VALUES (?,'https://identity.example.test/realms/synthetic',?,'ACTIVE',?)""".trimIndent(), actor, "synthetic-actor-$actor", before)
            jdbc.update("""INSERT INTO organization_membership (id,organization_id,user_identity_id,membership_type,state,valid_from)
                VALUES (?,?,?,'EMPLOYEE','ACTIVE',?)""".trimIndent(), actorMembership, organization, actor, before)
            if (linked) {
                jdbc.update("""INSERT INTO user_identity (id,auth_provider,auth_subject,status,person_id,primary_organization_id,created_at,version)
                    VALUES (?,'https://identity.example.test/realms/synthetic',?,'ACTIVE',?,?,?,3)""".trimIndent(), identity, "synthetic-target-$identity", person, organization, before)
                jdbc.update("""INSERT INTO organization_membership (id,organization_id,user_identity_id,membership_type,state,valid_from,version)
                    VALUES (?,?,?,'EMPLOYEE','ACTIVE',?,2)""".trimIndent(), membership, organization, identity, before)
            }
        }
        fun context() = OnboardingEmployeeContext(employee, organization, person, "EMP-SYNTHETIC", EmployeeState.ACTIVE, 7,
            "Synthetic account employee", null, "synthetic@example.test", 1)
        fun command() = CredentialReissueCommand(UUID.randomUUID(), employee, 7, identity, 3, membership, 2, null, null,
            "Synthetic lost handoff", actor, "synthetic-access-contract")
    }
}
