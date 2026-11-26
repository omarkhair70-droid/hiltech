package com.hiltech.server.identity

import com.hiltech.server.people.EmployeeIdentityAccountState
import com.hiltech.server.people.JdbcEmployeeIdentityInvitationPersistence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Uses real source tables and a synthetic identity provider; never contacts Keycloak or sends invitations. */
class EmployeeAccountProvisioningPostgresContractTest {
    private val at = Instant.parse("2026-11-25T12:00:00Z")
    private val issuer = "https://identity.example.test/realms/synthetic"

    @Test
    fun activeEmployeeAccountCreationPreservesEmployeeAndOnboardingState() = database { f ->
        val result = f.provision() as EmployeeIdentityProvisioningResult.Ready
        assertEquals("ACTIVE", f.jdbc.queryForObject("SELECT state FROM employee WHERE id=?", String::class.java, f.employee))
        assertEquals(7L, f.jdbc.queryForObject("SELECT version FROM employee WHERE id=?", Long::class.java, f.employee))
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM onboarding_case WHERE employee_id=?", Int::class.java, f.employee))
        assertEquals("ACTIVE", f.jdbc.queryForObject("SELECT status FROM user_identity WHERE id=?", String::class.java, result.userIdentityId))
        assertEquals("ACTIVE", f.jdbc.queryForObject("SELECT state FROM organization_membership WHERE id=?", String::class.java, f.membership))
        assertEquals(EmployeeIdentityAccountState.LINKED, f.accounts.accountState(f.employee, at))
        assertEquals(1, f.provider.deliveries)
    }

    @Test
    fun recoverableDeliveryFailureResumesOnlyTheSameInvitationOwnedAccountAndMembership() = database { f ->
        f.provider.delivery = ProviderDeliveryResult(IdentityProvisioningDeliveryState.FAILED_RETRYABLE, null)
        assertTrue(f.provision() is EmployeeIdentityProvisioningResult.RetryableFailure)
        assertEquals(EmployeeIdentityAccountState.PENDING, f.accounts.accountState(f.employee, at))
        f.provider.delivery = ProviderDeliveryResult(IdentityProvisioningDeliveryState.SENT, null)
        val ready = f.provision() as EmployeeIdentityProvisioningResult.Ready
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM user_identity WHERE person_id=?", Int::class.java, f.person))
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM organization_membership WHERE user_identity_id=?", Int::class.java, ready.userIdentityId))
        assertEquals(listOf(f.invitation, f.invitation), f.provider.markers)
    }

    @Test
    fun lockedIdentityIsRejectedBeforeDelivery() = database { f ->
        f.existingIdentity("LOCKED")
        f.existingMembership("ACTIVE")
        assertFailure(f, "IDENTITY_ACCOUNT_RESTRICTED")
        assertEquals("LOCKED", f.jdbc.queryForObject("SELECT status FROM user_identity WHERE id=?", String::class.java, f.identity))
        assertEquals(EmployeeIdentityAccountState.RESTRICTED, f.accounts.accountState(f.employee, at))
    }

    @Test
    fun revokedIdentityCannotBeReactivatedOrReplaced() = database { f ->
        f.existingIdentity("REVOKED")
        f.existingMembership("ENDED")
        assertFailure(f, "IDENTITY_ACCOUNT_RESTRICTED")
        assertEquals("REVOKED", f.jdbc.queryForObject("SELECT status FROM user_identity WHERE id=?", String::class.java, f.identity))
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM user_identity WHERE person_id=?", Int::class.java, f.person))
        assertEquals(EmployeeIdentityAccountState.RESTRICTED, f.accounts.accountState(f.employee, at))
    }

    @Test
    fun suspendedInvitationMembershipCannotBeReactivatedOrDuplicated() = database { f ->
        f.existingIdentity("ACTIVE")
        f.existingMembership("SUSPENDED")
        assertFailure(f, "IDENTITY_MEMBERSHIP_CONFLICT")
        assertEquals("SUSPENDED", f.jdbc.queryForObject("SELECT state FROM organization_membership WHERE id=?", String::class.java, f.membership))
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM organization_membership WHERE user_identity_id=?", Int::class.java, f.identity))
        assertEquals(EmployeeIdentityAccountState.RESTRICTED, f.accounts.accountState(f.employee, at))
    }

    @Test
    fun endedMembershipFromAnotherInvitationCannotBeBypassed() = database { f ->
        f.existingIdentity("ACTIVE")
        f.existingMembership("ENDED", UUID.randomUUID())
        assertFailure(f, "IDENTITY_MEMBERSHIP_CONFLICT")
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM organization_membership WHERE id=?", Int::class.java, f.membership))
    }

    @Test
    fun expiredMembershipCannotBeRecoveredAsCurrent() = database { f ->
        f.existingIdentity("ACTIVE")
        f.existingMembership("ACTIVE")
        f.jdbc.update("UPDATE organization_membership SET valid_until=? WHERE id=?", at.atOffset(ZoneOffset.UTC), f.membership)
        assertFailure(f, "IDENTITY_MEMBERSHIP_CONFLICT")
        assertEquals(EmployeeIdentityAccountState.RESTRICTED, f.accounts.accountState(f.employee, at))
    }

    @Test
    fun differentHistoricalAccountForSamePersonBlocksNewProviderSubject() = database { f ->
        f.existingIdentity("REVOKED", "another-synthetic-subject")
        assertFailure(f, "IDENTITY_PERSON_ALREADY_LINKED")
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM user_identity WHERE person_id=?", Int::class.java, f.person))
    }

    @Test
    fun providerSubjectAlreadyLinkedToAnotherPersonCannotBeClaimed() = database { f ->
        val other = UUID.randomUUID()
        f.jdbc.update("INSERT INTO person (id,display_name,created_at,updated_at) VALUES (?,'Synthetic other person',?,?)",
            other, at.atOffset(ZoneOffset.UTC), at.atOffset(ZoneOffset.UTC))
        f.existingIdentity("PENDING", personId = other)
        assertFailure(f, "IDENTITY_PROVIDER_SUBJECT_CONFLICT")
        assertEquals(other, f.jdbc.queryForObject("SELECT person_id FROM user_identity WHERE id=?", UUID::class.java, f.identity))
    }

    @Test
    fun offboardingDuringProviderDeliveryCannotActivateLocalAccount() = database { f ->
        f.provider.beforeDelivery = { f.jdbc.update("UPDATE employee SET state='OFFBOARDING' WHERE id=?", f.employee) }
        val failure = f.provision() as EmployeeIdentityProvisioningResult.RetryableFailure
        assertEquals("IDENTITY_LOCAL_ACTIVATION_FAILED", failure.safeCode)
        assertEquals("PENDING", f.jdbc.queryForObject("SELECT status FROM user_identity WHERE id=?", String::class.java, f.identity))
        assertEquals("PENDING", f.jdbc.queryForObject("SELECT state FROM organization_membership WHERE id=?", String::class.java, f.membership))
    }

    @Test
    fun activationTouchesOnlyTheMembershipOwnedByThisInvitation() = database { f ->
        val separateMembership = UUID.randomUUID()
        f.provider.beforeDelivery = { f.existingMembership("PENDING", separateMembership) }
        assertTrue(f.provision() is EmployeeIdentityProvisioningResult.Ready)
        assertEquals("ACTIVE", f.jdbc.queryForObject("SELECT state FROM organization_membership WHERE id=?", String::class.java, f.membership))
        assertEquals("PENDING", f.jdbc.queryForObject("SELECT state FROM organization_membership WHERE id=?", String::class.java, separateMembership))
    }

    private fun assertFailure(f: Fixture, expected: String) {
        val result = f.provision() as EmployeeIdentityProvisioningResult.RetryableFailure
        assertEquals(expected, result.safeCode)
        assertEquals(0, f.provider.deliveries)
    }

    private fun database(action: (Fixture) -> Unit) {
        assumeTrue(System.getenv("HILTECH_RESPONSIBILITY_CONTRACT_TEST") == "1")
        DriverManager.getConnection(requireNotNull(System.getenv("HILTECH_DB_URL")), requireNotNull(System.getenv("HILTECH_DB_USER")),
            requireNotNull(System.getenv("HILTECH_DB_PASSWORD"))).use { connection ->
            val source = SingleConnectionDataSource(connection, true)
            val transactionManager = DataSourceTransactionManager(source)
            TransactionTemplate(transactionManager).executeWithoutResult { transaction ->
                transaction.setRollbackOnly()
                action(Fixture(JdbcTemplate(source), transactionManager))
            }
        }
    }

    private inner class Fixture(val jdbc: JdbcTemplate, transactionManager: DataSourceTransactionManager) {
        val organization = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val person = UUID.randomUUID()
        val employee = UUID.randomUUID()
        val invitation = UUID.randomUUID()
        val identity = UUID.nameUUIDFromBytes(("identity:" + invitation).toByteArray(Charsets.UTF_8))
        val membership = UUID.nameUUIDFromBytes(("identity-membership:" + invitation).toByteArray(Charsets.UTF_8))
        val subject = "synthetic-subject-$invitation"
        val provider = Provider(subject)
        val accounts = JdbcEmployeeIdentityInvitationPersistence(jdbc)
        private val beanFactory = DefaultListableBeanFactory().also { it.registerSingleton("syntheticProvider", provider) }
        private val provisioning = JdbcEmployeeIdentityProvisioning(jdbc, transactionManager,
            HiltechOidcProperties(enabled = true, issuerUri = issuer),
            KeycloakProvisioningProperties(enabled = true, emailActionLinkEnabled = true),
            beanFactory.getBeanProvider(KeycloakIdentityAdminPort::class.java), Clock.fixed(at, ZoneOffset.UTC))

        init {
            val before = at.minusSeconds(60).atOffset(ZoneOffset.UTC)
            jdbc.update("""INSERT INTO organization (id,organization_code,legal_name,display_name,organization_type,status,created_at)
                VALUES (?,?,'Synthetic employee company','Synthetic employee company','HILTECH','ACTIVE',?)""".trimIndent(),
                organization, "TEST_$organization", before)
            jdbc.update("INSERT INTO user_identity (id,auth_provider,auth_subject,status,created_at) VALUES (?,'synthetic-account-admin',?,'ACTIVE',?)",
                actor, actor.toString(), before)
            jdbc.update("INSERT INTO person (id,display_name,created_at,updated_at) VALUES (?,'Synthetic active employee',?,?)", person, before, before)
            jdbc.update("""INSERT INTO employee (id,organization_id,person_id,employee_code,state,created_at,updated_at,version)
                VALUES (?,?,?,'EMP-SYNTHETIC','ACTIVE',?,?,7)""".trimIndent(), employee, organization, person, before, before)
        }
        fun existingIdentity(status: String, providerSubject: String = subject, personId: UUID = person) {
            jdbc.update("""INSERT INTO user_identity (id,auth_provider,auth_subject,person_id,status,primary_organization_id,created_at)
                VALUES (?,?,?,?,?,?,?)""".trimIndent(), identity, issuer, providerSubject, personId, status, organization, at.atOffset(ZoneOffset.UTC))
        }
        fun existingMembership(state: String, id: UUID = membership) {
            jdbc.update("""INSERT INTO organization_membership (id,organization_id,user_identity_id,membership_type,state,valid_from,invited_by)
                VALUES (?,?,?,'EMPLOYEE',?,?,?)""".trimIndent(), id, organization, identity, state, at.minusSeconds(60).atOffset(ZoneOffset.UTC), actor)
        }
        fun provision() = provisioning.provision(EmployeeIdentityProvisioningRequest(invitation, person, organization,
            "synthetic@example.test", actor, IdentityProvisioningDeliveryMode.EMAIL_ACTION_LINK))
    }

    private class Provider(private val subject: String) : KeycloakIdentityAdminPort {
        var delivery = ProviderDeliveryResult(IdentityProvisioningDeliveryState.SENT, null)
        var deliveries = 0
        val markers = mutableListOf<UUID>()
        var beforeDelivery: () -> Unit = {}
        override fun createOrRecoverDisabledUser(invitationId: UUID, requestedLogin: String): ProviderIdentityHandle {
            markers += invitationId
            return ProviderIdentityHandle(subject)
        }
        override fun enableAndPrepareDelivery(handle: ProviderIdentityHandle, requestedLogin: String, mode: IdentityProvisioningDeliveryMode): ProviderDeliveryResult {
            deliveries += 1
            beforeDelivery()
            return delivery
        }
    }
}
