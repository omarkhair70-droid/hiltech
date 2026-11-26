package com.hiltech.server.administration

import com.hiltech.server.people.JdbcPeopleAuthorizationProjectionBridge
import com.hiltech.server.people.PeopleAuthorizationPort
import com.hiltech.server.platform.IdempotentCommandExecution
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.payroll.PayrollRunPolicyConfiguration
import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.projects.JdbcProjectAuthoritySource
import com.hiltech.server.projects.ProjectAuthorizationProjectionBridge
import com.hiltech.server.security.HiltechOpenFgaProperties
import com.hiltech.server.security.JdbcAuthorizationProjectionIntentWriter
import com.hiltech.server.security.JdbcRoleTeamSourceAuthority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Run against an explicitly selected disposable database after all migrations. */
class CompanyResponsibilityPostgresContractTest {
    private val at = Instant.parse("2026-11-25T12:00:00Z")
    private val clock = Clock.fixed(at, ZoneOffset.UTC)

    @Test
    fun everyCatalogAdapterWritesAndEndsItsActualDomainSource() = database { jdbc, ids ->
        val source = JdbcCompanyResponsibilityPersistence(jdbc)
        val bindings = CompanyResponsibilityKey.entries.map { key ->
            val id = UUID.randomUUID()
            source.grant(key, id, ids.organization, ids.recipient, ids.actor, UUID.randomUUID(), at)
            requireNotNull(source.binding(key, id, at)).also {
                assertEquals(key, it.authorityKey)
                assertEquals(ids.recipient, it.principalId)
                assertEquals("USER", it.principalType)
                assertTrue(it.current)
            }
        }
        assertEquals(CompanyResponsibilityKey.entries.toSet(), source.bindings(ids.organization, at).map { it.authorityKey }.toSet())
        for (binding in bindings) {
            assertTrue(source.end(binding, at.plusSeconds(1)))
            assertFalse(source.end(binding, at.plusSeconds(1)))
            val ended = requireNotNull(source.binding(binding.authorityKey, binding.bindingId, at.plusSeconds(1)))
            assertFalse(ended.current)
            assertEquals(binding.version + 1, ended.version)
        }
        assertTrue(source.bindings(ids.organization, at.plusSeconds(1)).isEmpty())
    }

    @Test
    fun delegationAndPolicySetupRequireTheirOwnCurrentSourceBindings() = database { jdbc, ids ->
        val delegation = JdbcResponsibilityAdministrationAuthority(jdbc)
        val policy = JdbcOrganizationAdministrationAuthority(jdbc, clock)
        val source = JdbcCompanyResponsibilityPersistence(jdbc)
        source.grant(CompanyResponsibilityKey.PEOPLE_ADMIN, UUID.randomUUID(), ids.organization, ids.actor, ids.actor, UUID.randomUUID(), at)
        assertTrue(delegation.grantableKeys(ids.actor, ids.organization, at).isEmpty())
        assertFalse(policy.canManageApprovalPolicies(ids.actor, ids.organization))
        val id = UUID.randomUUID()
        jdbc.update("""INSERT INTO responsibility_administration_binding
            (id,organization_id,administrator_identity_id,authority_key,effective_from,source_approval_ref,created_by_user_id,created_at)
            VALUES (?,?,?,'PAYROLL_PREPARE',?,'synthetic-approved-delegation',?,?)""".trimIndent(),
            id, ids.organization, ids.actor, at.atOffset(ZoneOffset.UTC), ids.actor, at.atOffset(ZoneOffset.UTC))
        assertEquals(setOf(CompanyResponsibilityKey.PAYROLL_PREPARE), delegation.grantableKeys(ids.actor, ids.organization, at))
        assertTrue(delegation.grantableKeys(ids.recipient, ids.organization, at).isEmpty())
        assertTrue(delegation.grantableKeys(ids.actor, UUID.randomUUID(), at).isEmpty())
        source.grant(CompanyResponsibilityKey.APPROVAL_POLICY_ADMIN, UUID.randomUUID(), ids.organization, ids.actor, ids.actor, UUID.randomUUID(), at)
        assertTrue(policy.canManageApprovalPolicies(ids.actor, ids.organization))
        jdbc.update("UPDATE organization_membership SET state = 'SUSPENDED' WHERE organization_id = ? AND user_identity_id = ?", ids.organization, ids.actor)
        assertTrue(delegation.grantableKeys(ids.actor, ids.organization, at).isEmpty())
        assertFalse(policy.canManageApprovalPolicies(ids.actor, ids.organization))
    }

    @Test
    fun endedPeopleAndProjectTuplesCanBeRegrantedWithANewerProjectionGeneration() = database { jdbc, ids ->
        val source = JdbcCompanyResponsibilityPersistence(jdbc)
        val writer = JdbcAuthorizationProjectionIntentWriter(jdbc, HiltechOpenFgaProperties(
            enabled = true, apiUrl = "http://127.0.0.1:8080", storeId = "synthetic-store", authorizationModelId = "synthetic-model",
        ))
        val projection = DomainCompanyResponsibilityProjection(
            JdbcPeopleAuthorizationProjectionBridge(jdbc, writer),
            ProjectAuthorizationProjectionBridge(JdbcProjectAuthoritySource(jdbc), writer, clock),
        )
        for (key in listOf(CompanyResponsibilityKey.PEOPLE_ADMIN, CompanyResponsibilityKey.PROJECT_ADMIN)) {
            val firstId = UUID.randomUUID()
            source.grant(key, firstId, ids.organization, ids.recipient, ids.actor, UUID.randomUUID(), at)
            val first = requireNotNull(source.binding(key, firstId, at))
            projection.changed(first, at)
            assertTrue(source.end(first, at))
            val ended = requireNotNull(source.binding(key, firstId, at))
            projection.changed(ended, at)
            val secondId = UUID.randomUUID()
            source.grant(key, secondId, ids.organization, ids.recipient, ids.actor, UUID.randomUUID(), at)
            val second = requireNotNull(source.binding(key, secondId, at))
            assertTrue(second.version > ended.version)
            projection.changed(second, at)
            val relation = if (key == CompanyResponsibilityKey.PEOPLE_ADMIN) "people_admin" else "project_admin"
            val row = jdbc.queryForMap("""SELECT desired_state, source_id, source_version
                FROM authorization_relation_projection WHERE subject_type = 'user' AND subject_id = ?
                  AND relation = ? AND object_type = 'organization' AND object_id = ?""".trimIndent(),
                ids.recipient.toString(), relation, ids.organization.toString())
            assertEquals("PRESENT", row["desired_state"])
            assertEquals(secondId.toString(), row["source_id"])
            assertEquals(second.version, (row["source_version"] as Number).toLong())
        }
    }

    @Test
    fun reviewSetupUsesNamedDomainReviewersAndPreservesRetiredConfigurationHistory() = database { jdbc, ids ->
        val person = UUID.randomUUID()
        val employee = UUID.randomUUID()
        val now = at.minusSeconds(60).atOffset(ZoneOffset.UTC)
        jdbc.update("INSERT INTO person (id,display_name,created_at,updated_at) VALUES (?,'Synthetic reviewer',?,?)", person, now, now)
        jdbc.update("UPDATE user_identity SET person_id=? WHERE id=?", person, ids.recipient)
        jdbc.update("""INSERT INTO employee (id,organization_id,person_id,employee_code,state,created_at,updated_at)
            VALUES (?,?,?,'SYNTH-REVIEWER','ACTIVE',?,?)""".trimIndent(), employee, ids.organization, person, now, now)
        val bindings = JdbcCompanyResponsibilityPersistence(jdbc)
        val setup = JdbcPayrollReviewPolicyPersistence(jdbc)
        val workflow = CompanyReviewWorkflow.PAYROLL_SOURCE
        assertTrue(setup.candidates(ids.organization, workflow, "Synthetic", at, 50).isEmpty())
        bindings.grant(CompanyResponsibilityKey.PAYROLL_REVIEW, UUID.randomUUID(), ids.organization, ids.recipient, ids.actor, UUID.randomUUID(), at)
        val candidate = setup.candidates(ids.organization, workflow, "Synthetic", at, 50).single()
        assertEquals(employee, candidate.employeeId)
        assertEquals(ids.recipient, candidate.identityId)
        assertTrue(setup.candidates(ids.organization, CompanyReviewWorkflow.FINANCE_PAYROLL_RESULT, "Synthetic", at, 50).isEmpty())
        assertTrue(setup.candidates(ids.organization, workflow, "%", at, 50).isEmpty())
        setup.lockOrganization(ids.organization)
        setup.lockEmployee(employee)
        val before = setup.current(ids.organization, workflow, at)
        val first = setup.replace(ids.organization, workflow, before, candidate, ids.actor, UUID.randomUUID(), at)
        val current = setup.current(ids.organization, workflow, at)
        assertTrue(requireNotNull(current.policy).contractMatches)
        assertEquals("USER", current.reviewer?.principalType)
        assertEquals("Synthetic reviewer", current.reviewer?.displayName)
        assertEquals(listOf(ids.recipient), setup.reviewerIdentities(ids.organization, workflow, requireNotNull(current.reviewer), at))
        val next = setup.replace(ids.organization, workflow, current, candidate, ids.actor, UUID.randomUUID(), at.plusSeconds(1))
        assertEquals(2, next.policyRevision)
        val history = jdbc.queryForMap("SELECT lifecycle_state,approval_required,step_mode,authority_key,reason_required FROM approval_policy_version WHERE id=?", first.policyId)
        assertEquals("RETIRED", history["lifecycle_state"])
        assertEquals(true, history["approval_required"])
        assertEquals("SINGLE", history["step_mode"])
        assertEquals("PAYROLL_SOURCE_REVIEWER", history["authority_key"])
        assertEquals(true, history["reason_required"])
        val oldReviewer = jdbc.queryForMap("SELECT active,principal_user_id FROM approval_authority_binding WHERE id=?", first.bindingId)
        assertEquals(false, oldReviewer["active"])
        assertEquals(ids.recipient, oldReviewer["principal_user_id"])
        assertEquals(null, setup.current(ids.organization, CompanyReviewWorkflow.FINANCE_PAYROLL_RESULT, at.plusSeconds(1)).policy)
        jdbc.update("UPDATE organization_membership SET state='SUSPENDED' WHERE user_identity_id=? AND organization_id=?", ids.recipient, ids.organization)
        assertTrue(setup.candidates(ids.organization, workflow, "Synthetic", at, 50).isEmpty())
    }

    @Test
    fun existingTeamReviewerIsReadableAndNotSilentlyReplaced() = database { jdbc, ids ->
        val team = UUID.randomUUID()
        val binding = UUID.randomUUID()
        jdbc.update("INSERT INTO team (id,organization_id,code,name,active) VALUES (?,?,'SYNTH-TEAM','Synthetic review team',true)", team, ids.organization)
        jdbc.update("""INSERT INTO approval_authority_binding
            (id,organization_id,authority_key,principal_type,principal_team_id,effective_from,created_by_user_id,created_at)
            VALUES (?,?,'PAYROLL_SOURCE_REVIEWER','TEAM',?,?,?,?)""".trimIndent(),
            binding, ids.organization, team, at.atOffset(ZoneOffset.UTC), ids.actor, at.atOffset(ZoneOffset.UTC))
        val setup = JdbcPayrollReviewPolicyPersistence(jdbc).current(ids.organization, CompanyReviewWorkflow.PAYROLL_SOURCE, at)
        assertEquals("TEAM", setup.reviewer?.principalType)
        assertEquals("Synthetic review team", setup.reviewer?.displayName)
        assertEquals(binding, setup.reviewer?.id)
        assertTrue(jdbc.queryForObject("SELECT active FROM approval_authority_binding WHERE id=?", Boolean::class.java, binding) == true)
    }

    @Test
    fun initialAdministratorProvisioningIsExplicitAuditedAndCannotAlterExistingGovernance() = database { jdbc, ids ->
        // The enclosing real connection owns this rollback-only test transaction.
        val transaction = object : TransactionTemplate() {
            override fun <T> execute(action: TransactionCallback<T>): T = action.doInTransaction(SimpleTransactionStatus())
        }
        val provisioner = ResponsibilityAdministratorProvisioner(jdbc, transaction, JdbcRoleTeamSourceAuthority(jdbc),
            JdbcAuditEventWriter(jdbc), clock)
        val keys = setOf(CompanyResponsibilityKey.PEOPLE_ADMIN, CompanyResponsibilityKey.PAYROLL_PREPARE)
        val first = provisioner.provision(ids.organization, ids.actor, ids.recipient, keys, "synthetic-approved-governance", true)
        val second = provisioner.provision(ids.organization, ids.actor, ids.recipient, keys, "synthetic-approved-governance", true)
        assertEquals(first.toSet(), second.toSet())
        assertEquals(keys, JdbcResponsibilityAdministrationAuthority(jdbc).grantableKeys(ids.recipient, ids.organization, at))
        assertEquals(keys, JdbcResponsibilityAdministrationAuthority(jdbc).delegableKeys(ids.recipient, ids.organization, at))
        assertTrue(JdbcCompanyResponsibilityPersistence(jdbc).bindings(ids.organization, at).isEmpty())
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE actor_user_id=? AND action='RESPONSIBILITY_INITIAL_ADMINISTRATOR_PROVISIONED'",
            Int::class.java, ids.actor))
        assertThrows<IllegalArgumentException> {
            provisioner.provision(ids.organization, ids.actor, ids.recipient, keys + CompanyResponsibilityKey.PAYROLL_REVIEW, "synthetic-approved-governance", true)
        }
        assertThrows<IllegalArgumentException> {
            provisioner.provision(ids.organization, ids.actor, ids.actor, keys, "synthetic-approved-governance", true)
        }
        jdbc.update("UPDATE organization_membership SET state='SUSPENDED' WHERE organization_id=? AND user_identity_id=?", ids.organization, ids.recipient)
        assertThrows<IllegalArgumentException> {
            provisioner.provision(ids.organization, ids.actor, ids.recipient, keys, "synthetic-approved-governance", true)
        }
    }

    @Test
    fun governanceSourceDistinguishesOnwardRightsAndCurrentLastAdministrator() = database { jdbc, ids ->
        val source = JdbcResponsibilityDelegationPersistence(jdbc)
        val key = CompanyResponsibilityKey.PEOPLE_ADMIN
        val first = UUID.randomUUID()
        val recipient = UUID.randomUUID()
        source.lockOrganization(ids.organization)
        source.grant(first, ids.organization, ids.actor, key, true, ids.actor, UUID.randomUUID(), at)
        source.grant(recipient, ids.organization, ids.recipient, key, false, ids.actor, UUID.randomUUID(), at)
        assertFalse(source.hasOtherCurrentDelegator(ids.organization, key, first, at))
        assertTrue(JdbcResponsibilityAdministrationAuthority(jdbc).delegableKeys(ids.recipient, ids.organization, at).isEmpty())
        assertTrue(source.end(requireNotNull(source.binding(recipient, at)), at))
        val onward = UUID.randomUUID()
        source.grant(onward, ids.organization, ids.recipient, key, true, ids.actor, UUID.randomUUID(), at)
        assertTrue(source.hasOtherCurrentDelegator(ids.organization, key, first, at))
        jdbc.update("UPDATE organization_membership SET state='SUSPENDED' WHERE organization_id=? AND user_identity_id=?", ids.organization, ids.recipient)
        assertFalse(source.hasOtherCurrentDelegator(ids.organization, key, first, at))
        assertFalse(requireNotNull(source.binding(onward, at)).current)
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM responsibility_administration_binding WHERE organization_id=?", Int::class.java, ids.organization))
    }

    @Test
    fun runSetupReadsTheActualConfiguredPolicyKeyAndShowsProtectedLegacyReviewer() = database { jdbc, ids ->
        val key = "SYNTH_CUSTOM_RUN_POLICY"
        val now = at.atOffset(ZoneOffset.UTC)
        jdbc.update("""INSERT INTO approval_policy_version
            (id,organization_id,policy_key,version_number,lifecycle_state,approval_required,step_mode,authority_key,
             reason_required,effective_from,created_by_user_id,created_at,version)
            VALUES (?,?,?,1,'ACTIVE',true,'SINGLE','OWNER_FINAL',true,?,?,?,1)""".trimIndent(),
            UUID.randomUUID(), ids.organization, key, now, ids.actor, now)
        val binding = UUID.randomUUID()
        jdbc.update("""INSERT INTO approval_authority_binding
            (id,organization_id,authority_key,principal_type,principal_user_id,effective_from,created_by_user_id,created_at)
            VALUES (?,?,'OWNER_FINAL','USER',?,?,?,?)""".trimIndent(), binding, ids.organization, ids.recipient, now, ids.actor, now)
        val store = JdbcPayrollReviewPolicyPersistence(jdbc, PayrollRunPolicyConfiguration(key))
        val state = store.current(ids.organization, CompanyReviewWorkflow.PAYROLL_RUN, at)
        assertEquals("OWNER_FINAL", state.policy?.authorityKey)
        assertFalse(requireNotNull(state.policy).contractMatches)
        assertEquals(binding, state.reviewer?.id)
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM approval_policy_version WHERE organization_id=? AND policy_key='PAYROLL_RUN_REVIEW'",
            Int::class.java, ids.organization))
    }

    @Test
    fun approvedFirstMemberCanActivatePeopleBeforeAnyEmployeeExists() = database { jdbc, ids ->
        val source = JdbcCompanyResponsibilityPersistence(jdbc)
        val authority = JdbcResponsibilityAdministrationAuthority(jdbc)
        jdbc.update("""INSERT INTO responsibility_administration_binding
            (id,organization_id,administrator_identity_id,authority_key,can_delegate_administration,effective_from,source_approval_ref,created_by_user_id,created_at)
            VALUES (?,?,?,'PEOPLE_ADMIN',true,?,'synthetic-approved-first-member',?,?)""".trimIndent(),
            UUID.randomUUID(), ids.organization, ids.recipient, at.atOffset(ZoneOffset.UTC), ids.actor, at.atOffset(ZoneOffset.UTC))
        val writer = JdbcAuthorizationProjectionIntentWriter(jdbc, HiltechOpenFgaProperties(enabled = true,
            apiUrl = "http://127.0.0.1:8080", storeId = "synthetic-store", authorizationModelId = "synthetic-model"))
        val projection = DomainCompanyResponsibilityProjection(JdbcPeopleAuthorizationProjectionBridge(jdbc, writer),
            ProjectAuthorizationProjectionBridge(JdbcProjectAuthoritySource(jdbc), writer, clock))
        val executor = object : IdempotentCommandExecutor {
            override fun execute(spec: IdempotentCommandSpec, action: () -> IdempotentCommandOutcome) = IdempotentCommandExecution(action(), false)
        }
        val service = OwnPeopleAdministrationService(authority, JdbcRoleTeamSourceAuthority(jdbc), Mockito.mock(PeopleAuthorizationPort::class.java),
            source, projection, executor, JdbcAuditEventWriter(jdbc), clock)
        assertEquals("AVAILABLE", service.availability(ids.recipient, ids.organization))
        val receipt = service.activate(ActivateOwnPeopleAdministrationCommand(UUID.randomUUID(), ids.organization, ids.recipient, "synthetic-first-member"))
        assertTrue(receipt.current)
        assertFalse(receipt.accessReady)
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE organization_id=?", Int::class.java, ids.organization))
        val binding = source.bindings(ids.organization, at).single()
        assertEquals(CompanyResponsibilityKey.PEOPLE_ADMIN, binding.authorityKey)
        assertEquals(ids.recipient, binding.principalId)
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM responsibility_administration_binding WHERE organization_id=?", Int::class.java, ids.organization))
        assertEquals(setOf(CompanyResponsibilityKey.PEOPLE_ADMIN), authority.delegableKeys(ids.recipient, ids.organization, at))
        assertEquals("PRESENT", jdbc.queryForObject("""SELECT desired_state FROM authorization_relation_projection
            WHERE object_type='organization' AND object_id=? AND relation='people_admin' AND subject_type='user' AND subject_id=?""".trimIndent(),
            String::class.java, ids.organization.toString(), ids.recipient.toString()))
    }

    private fun database(action: (JdbcTemplate, Ids) -> Unit) {
        assumeTrue(System.getenv("HILTECH_RESPONSIBILITY_CONTRACT_TEST") == "1")
        DriverManager.getConnection(
            requireNotNull(System.getenv("HILTECH_DB_URL")), requireNotNull(System.getenv("HILTECH_DB_USER")),
            requireNotNull(System.getenv("HILTECH_DB_PASSWORD")),
        ).use { connection ->
            connection.autoCommit = false
            try {
                val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
                val ids = Ids(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
                val now = at.minusSeconds(60).atOffset(ZoneOffset.UTC)
                jdbc.update("""INSERT INTO organization
                    (id,organization_code,legal_name,display_name,organization_type,status,created_at)
                    VALUES (?,?, 'Synthetic responsibility company','Synthetic responsibility company','HILTECH','ACTIVE',?)""".trimIndent(),
                    ids.organization, "TEST_${ids.organization}", now)
                for (identity in listOf(ids.actor, ids.recipient)) {
                    jdbc.update("INSERT INTO user_identity (id,auth_provider,auth_subject,status,created_at) VALUES (?,'synthetic-responsibility',?,'ACTIVE',?)",
                        identity, identity.toString(), now)
                    jdbc.update("""INSERT INTO organization_membership
                        (id,organization_id,user_identity_id,membership_type,state,valid_from)
                        VALUES (?,?,?,'EMPLOYEE','ACTIVE',?)""".trimIndent(), UUID.randomUUID(), ids.organization, identity, now)
                }
                action(jdbc, ids)
            } finally { connection.rollback() }
        }
    }
    private data class Ids(val organization: UUID, val actor: UUID, val recipient: UUID)
}
