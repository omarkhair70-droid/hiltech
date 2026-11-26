package com.hiltech.server.administration

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.people.EmployeeState
import com.hiltech.server.people.PeopleAuthorizationPort
import com.hiltech.server.platform.IdempotentCommandExecution
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.security.RoleTeamSourceAuthorityPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class CompanyResponsibilityServiceTest {
    private val actor = UUID.randomUUID()
    private val organization = UUID.randomUUID()
    private val employeeId = UUID.randomUUID()
    private val identity = UUID.randomUUID()
    private val at = Instant.parse("2026-11-25T12:00:00Z")
    private val employees = Mockito.mock(ResponsibilityEmployeeDirectoryPort::class.java)
    private val people = Mockito.mock(PeopleAuthorizationPort::class.java)
    private val authority = Mockito.mock(ResponsibilityAdministrationAuthorityPort::class.java)
    private val source = Mockito.mock(RoleTeamSourceAuthorityPort::class.java)
    private val projection = Mockito.mock(CompanyResponsibilityProjectionPort::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val persistence = MemoryBindings()
    private val commands = RecordingExecutor()
    private val service = CompanyResponsibilityService(employees, people, authority, source, persistence, projection, commands, audit,
        Clock.fixed(at, ZoneOffset.UTC))

    @Test
    fun peopleAdministrationDoesNotConferDelegation() {
        currentAccount()
        val failure = assertThrows<ProductApiException> { service.grant(command()) }
        assertEquals("RESPONSIBILITY_ADMINISTRATION_DENIED", failure.code)
        assertEquals(0, commands.calls)
        assertTrue(persistence.rows.isEmpty())
    }

    @Test
    fun exactDelegationKeyIsRequiredEvenWhenAnotherKeyIsManaged() {
        authorized(CompanyResponsibilityKey.PEOPLE_ADMIN)
        assertEquals("RESPONSIBILITY_ADMINISTRATION_DENIED",
            assertThrows<ProductApiException> { service.grant(command(CompanyResponsibilityKey.PAYROLL_PREPARE)) }.code)
        assertTrue(persistence.rows.isEmpty())
    }

    @Test
    fun subsetDelegateCanManageItsScopeWithoutPrivatePeopleAdministration() {
        authorized(CompanyResponsibilityKey.SALES_VIEW)
        Mockito.`when`(people.canManagePeople(actor, organization)).thenReturn(false)
        val hidden = binding()
        persistence.rows[hidden.bindingId] = hidden
        val result = service.grant(command(CompanyResponsibilityKey.SALES_VIEW))
        assertTrue(result.current)
        val view = service.current(actor, employeeId)
        assertEquals("DELEGATED_KEYS", view.viewScope)
        assertEquals(listOf("SALES_VIEW"), view.bindings.map { it.authorityKey })
        assertEquals(listOf("SALES_VIEW"), view.catalog.filter { it.canGrant }.map { it.authorityKey })
    }

    @Test
    fun foreignOrganizationCannotBeSubstitutedIntoTheCommand() {
        authorized()
        val foreign = UUID.randomUUID()
        Mockito.`when`(authority.grantableKeys(actor, foreign, at)).thenReturn(setOf(CompanyResponsibilityKey.PEOPLE_ADMIN))
        assertEquals("RESPONSIBILITY_ADMINISTRATION_DENIED",
            assertThrows<ProductApiException> { service.grant(command().copy(organizationId = foreign)) }.code)
        assertEquals(0, commands.calls)
    }

    @Test
    fun neitherPeopleAccessNorExplicitDelegationCanReadTheSummary() {
        Mockito.`when`(employees.employee(employeeId)).thenReturn(employee())
        assertEquals("RESPONSIBILITY_DIRECTORY_DENIED", assertThrows<ProductApiException> { service.current(actor, employeeId) }.code)
        Mockito.verifyNoInteractions(source)
    }

    @Test
    fun delegationIsRecheckedAfterTakingEmployeeLock() {
        authorized()
        Mockito.`when`(authority.grantableKeys(actor, organization, at)).thenReturn(setOf(CompanyResponsibilityKey.PEOPLE_ADMIN), emptySet())
        assertEquals("RESPONSIBILITY_ADMINISTRATION_DENIED", assertThrows<ProductApiException> { service.grant(command()) }.code)
        assertEquals(1, persistence.locks)
        assertTrue(persistence.rows.isEmpty())
        Mockito.verifyNoInteractions(audit, projection)
    }

    @Test
    fun changedEmployeeVersionIsRejectedBeforeMutation() {
        authorized()
        assertEquals("RESPONSIBILITY_EMPLOYEE_CHANGED",
            assertThrows<ProductApiException> { service.grant(command().copy(baseEmployeeVersion = 2)) }.code)
        assertTrue(persistence.rows.isEmpty())
    }

    @Test
    fun frozenRequestCannotTargetAnAccountRelinkedAfterPreparation() {
        authorized()
        Mockito.`when`(employees.employee(employeeId)).thenReturn(employee(), employee().copy(linkedIdentityId = UUID.randomUUID()))
        assertEquals("RESPONSIBILITY_EMPLOYEE_CHANGED", assertThrows<ProductApiException> { service.grant(command()) }.code)
        assertTrue(persistence.rows.isEmpty())
    }

    @Test
    fun recipientMembershipMustStillBeCurrent() {
        authorized()
        Mockito.`when`(source.isOrganizationMemberCurrent(identity, organization, at)).thenReturn(false)
        assertEquals("RESPONSIBILITY_ACCOUNT_NOT_CURRENT", assertThrows<ProductApiException> { service.grant(command()) }.code)
        assertTrue(persistence.rows.isEmpty())
    }

    @Test
    fun formerEmployeeCannotReceiveNewAccess() {
        authorized()
        Mockito.`when`(employees.employee(employeeId)).thenReturn(employee().copy(employeeState = EmployeeState.FORMER))
        assertEquals("RESPONSIBILITY_ACCOUNT_NOT_CURRENT", assertThrows<ProductApiException> { service.grant(command()) }.code)
    }

    @Test
    fun explicitlyDelegatedAdministratorCanAssignOwnOperationalResponsibility() {
        authorized()
        Mockito.`when`(employees.employee(employeeId)).thenReturn(employee().copy(linkedIdentityId = actor))
        Mockito.`when`(source.isOrganizationMemberCurrent(actor, organization, at)).thenReturn(true)
        val result = service.grant(command().copy(expectedIdentityId = actor))
        assertEquals(actor.toString(), result.identityId)
        assertTrue(result.current)
    }

    @Test
    fun selfAssignmentWithoutTheExactCeilingCannotEscalateAuthority() {
        authorized(CompanyResponsibilityKey.SALES_VIEW)
        Mockito.`when`(employees.employee(employeeId)).thenReturn(employee().copy(linkedIdentityId = actor))
        assertEquals("RESPONSIBILITY_ADMINISTRATION_DENIED",
            assertThrows<ProductApiException> { service.grant(command().copy(expectedIdentityId = actor)) }.code)
        assertTrue(persistence.rows.isEmpty())
    }

    @Test
    fun duplicateGrantIsRejectedAndSuccessfulRetryDoesNotDuplicateAuditOrProjection() {
        authorized()
        val request = command()
        val first = service.grant(request)
        val retry = service.grant(request)
        assertFalse(first.replayed)
        assertTrue(retry.replayed)
        assertEquals(first.bindingId, retry.bindingId)
        assertEquals(request.operationId.toString(), retry.operationId)
        assertEquals(1, persistence.rows.size)
        assertEquals(1, Mockito.mockingDetails(audit).invocations.size)
        assertEquals(1, Mockito.mockingDetails(projection).invocations.size)
        assertEquals("RESPONSIBILITY_ALREADY_ASSIGNED", assertThrows<ProductApiException> { service.grant(command()) }.code)
    }

    @Test
    fun revokedDelegationPreventsReplayOfAFormerlySuccessfulCommand() {
        authorized()
        val request = command()
        service.grant(request)
        Mockito.`when`(authority.grantableKeys(actor, organization, at)).thenReturn(emptySet())
        assertEquals("RESPONSIBILITY_ADMINISTRATION_DENIED", assertThrows<ProductApiException> { service.grant(request) }.code)
        assertEquals(1, commands.calls)
    }

    @Test
    fun summarySeparatesDirectInheritedForeignAndEndedBindings() {
        authorized()
        val direct = binding()
        val team = UUID.randomUUID()
        val inherited = binding().copy(principalType = "TEAM", principalId = team)
        Mockito.`when`(source.isTeamMemberCurrent(identity, team, at)).thenReturn(true)
        listOf(direct, inherited, binding().copy(current = false), binding().copy(principalId = UUID.randomUUID()),
            binding().copy(organizationId = UUID.randomUUID())).forEach { persistence.rows[it.bindingId] = it }
        val result = service.current(actor, employeeId)
        assertEquals("ORGANIZATION_CATALOG", result.scope)
        assertEquals(setOf(direct.bindingId.toString(), inherited.bindingId.toString()), result.bindings.map { it.bindingId }.toSet())
        assertTrue(result.bindings.single { it.assignmentSource == "DIRECT" }.canEnd)
        assertFalse(result.bindings.single { it.assignmentSource == "TEAM" }.canEnd)
        assertEquals(listOf("PEOPLE_ADMIN"), result.catalog.filter { it.canGrant }.map { it.authorityKey })
    }

    @Test
    fun missingCurrentAccountPreventsShowingAssignmentsAsUsable() {
        authorized()
        Mockito.`when`(source.isOrganizationMemberCurrent(identity, organization, at)).thenReturn(false)
        val result = service.current(actor, employeeId)
        assertEquals("NO_CURRENT_LINKED_ACCOUNT", result.accountStatus)
        assertEquals(null, result.linkedIdentityId)
        assertTrue(result.catalog.none { it.canGrant })
        assertTrue(result.bindings.isEmpty())
    }

    @Test
    fun inheritedAssignmentCannotBeEndedThroughTheEmployeeRecord() {
        authorized()
        val inherited = binding().copy(principalType = "TEAM", principalId = UUID.randomUUID())
        persistence.rows[inherited.bindingId] = inherited
        assertEquals("RESPONSIBILITY_DIRECT_ASSIGNMENT_REQUIRED",
            assertThrows<ProductApiException> { service.end(end(inherited)) }.code)
        assertTrue(persistence.rows.getValue(inherited.bindingId).current)
    }

    @Test
    fun staleBindingVersionCannotEndANewerAssignment() {
        authorized()
        val binding = binding().copy(version = 2)
        persistence.rows[binding.bindingId] = binding
        assertEquals("RESPONSIBILITY_VERSION_CONFLICT",
            assertThrows<ProductApiException> { service.end(end(binding).copy(baseBindingVersion = 1)) }.code)
    }

    @Test
    fun endingOneDirectAssignmentPreservesAnotherAndReplaysReceipt() {
        authorized(CompanyResponsibilityKey.PEOPLE_ADMIN, CompanyResponsibilityKey.SALES_VIEW)
        val first = service.grant(command())
        val other = service.grant(command(CompanyResponsibilityKey.SALES_VIEW))
        val binding = persistence.rows.getValue(UUID.fromString(first.bindingId))
        val request = end(binding)
        val ended = service.end(request)
        assertFalse(ended.current)
        assertEquals(2L, ended.version)
        assertTrue(service.end(request).replayed)
        assertTrue(persistence.rows.getValue(UUID.fromString(other.bindingId)).current)
    }

    @Test
    fun replayedGrantReportsThatTheAssignmentHasSinceEnded() {
        authorized()
        val request = command()
        val first = service.grant(request)
        service.end(end(persistence.rows.getValue(UUID.fromString(first.bindingId))))
        val replay = service.grant(request)
        assertTrue(replay.replayed)
        assertFalse(replay.current)
        assertEquals("COMPANY_RESPONSIBILITY_GRANTED", replay.resultCode)
    }

    @Test
    fun catalogContainsNoFinalApprovalOrDelegationGovernorEntry() {
        assertFalse(CompanyResponsibilityKey.entries.any { it.name in setOf("OWNER_FINAL", "RESPONSIBILITY_ADMINISTRATOR", "PAYROLL_PAY", "FINANCE_APPROVE") })
    }

    @Test
    fun existingConfiguredSourceIsVisibleWithoutOfferingToRevokeItsApprovalBinding() {
        authorized(CompanyResponsibilityKey.PROCUREMENT_BUYER)
        val old = binding().copy(authorityKey = CompanyResponsibilityKey.PROCUREMENT_BUYER, administrationManaged = false)
        persistence.rows[old.bindingId] = old
        val current = service.current(actor, employeeId)
        assertEquals("LEGACY", current.bindings.single().assignmentSource)
        assertFalse(current.bindings.single().canEnd)
        assertEquals("RESPONSIBILITY_DIRECT_ASSIGNMENT_REQUIRED", assertThrows<ProductApiException> { service.end(end(old)) }.code)
        assertTrue(persistence.rows.getValue(old.bindingId).current)
    }

    @Test
    fun fullExplicitRoleCanBeGrantedAlongsidePartialLegacySourceAndEndingItReportsRemainingAccess() {
        authorized(CompanyResponsibilityKey.FINANCE_REPORT_READ)
        val old = binding().copy(authorityKey = CompanyResponsibilityKey.FINANCE_REPORT_READ, administrationManaged = false)
        persistence.rows[old.bindingId] = old
        val granted = service.grant(command(CompanyResponsibilityKey.FINANCE_REPORT_READ))
        assertTrue(granted.current)
        assertEquals(2, service.current(actor, employeeId).bindings.size)
        val direct = persistence.rows.getValue(UUID.fromString(granted.bindingId))
        val result = service.end(end(direct))
        assertFalse(result.current)
        assertTrue(result.effectiveAccessRemaining)
        assertTrue(persistence.rows.getValue(old.bindingId).current)
    }

    @Test
    fun remainingAccessUsesCurrentRealTeamMembershipAndIgnoresUnrelatedResponsibility() {
        authorized(CompanyResponsibilityKey.FINANCE_PAYABLE_PREPARE)
        val team = UUID.randomUUID()
        val inherited = binding().copy(authorityKey = CompanyResponsibilityKey.FINANCE_PAYABLE_PREPARE,
            principalType = "TEAM", principalId = team)
        persistence.rows[inherited.bindingId] = inherited
        Mockito.`when`(source.isTeamMemberCurrent(identity, team, at)).thenReturn(true)
        val first = service.grant(command(CompanyResponsibilityKey.FINANCE_PAYABLE_PREPARE))
        val direct = persistence.rows.getValue(UUID.fromString(first.bindingId))
        val operation = end(direct)
        assertTrue(service.end(operation).effectiveAccessRemaining)
        Mockito.`when`(source.isTeamMemberCurrent(identity, team, at)).thenReturn(false)
        assertFalse(service.end(operation).effectiveAccessRemaining)
        assertTrue(service.current(actor, employeeId).bindings.isEmpty())
    }

    private fun authorized(vararg keys: CompanyResponsibilityKey = arrayOf(CompanyResponsibilityKey.PEOPLE_ADMIN)) {
        currentAccount()
        Mockito.`when`(authority.grantableKeys(actor, organization, at)).thenReturn(keys.toSet())
    }
    private fun currentAccount() {
        Mockito.`when`(people.canManagePeople(actor, organization)).thenReturn(true)
        Mockito.`when`(employees.employee(employeeId)).thenReturn(employee())
        Mockito.`when`(source.isOrganizationMemberCurrent(identity, organization, at)).thenReturn(true)
    }
    private fun employee() = ResponsibilityEmployeeContext(
        employeeId, organization, 3, "SYNTH-EMPLOYEE", "Synthetic employee", EmployeeState.ACTIVE, identity,
    )
    private fun command(key: CompanyResponsibilityKey = CompanyResponsibilityKey.PEOPLE_ADMIN) = CompanyResponsibilityCommand(
        UUID.randomUUID(), employeeId, organization, 3, identity, key, actorUserId = actor, correlationId = "synthetic-responsibility-test",
    )
    private fun binding() = CompanyResponsibilityBinding(UUID.randomUUID(), organization, CompanyResponsibilityKey.PEOPLE_ADMIN, "USER", identity, true, 1)
    private fun end(binding: CompanyResponsibilityBinding) = command(binding.authorityKey).copy(bindingId = binding.bindingId, baseBindingVersion = binding.version)

    private class MemoryBindings : CompanyResponsibilityPersistencePort {
        val rows = linkedMapOf<UUID, CompanyResponsibilityBinding>()
        var locks = 0
        override fun lockOrganization(organizationId: UUID) = Unit
        override fun lockEmployee(employeeId: UUID) { locks += 1 }
        override fun bindings(organizationId: UUID, at: Instant) = rows.values.toList()
        override fun binding(key: CompanyResponsibilityKey, bindingId: UUID, at: Instant) = rows[bindingId]?.takeIf { it.authorityKey == key }
        override fun grant(key: CompanyResponsibilityKey, bindingId: UUID, organizationId: UUID, identityId: UUID, actor: UUID, operationId: UUID, at: Instant) {
            rows[bindingId] = CompanyResponsibilityBinding(bindingId, organizationId, key, "USER", identityId, true, 1)
        }
        override fun end(binding: CompanyResponsibilityBinding, at: Instant): Boolean {
            if (rows[binding.bindingId] != binding) return false
            rows[binding.bindingId] = binding.copy(current = false, version = binding.version + 1)
            return true
        }
    }
    private class RecordingExecutor : IdempotentCommandExecutor {
        var calls = 0
        private val receipts = mutableMapOf<UUID, Pair<IdempotentCommandSpec, IdempotentCommandOutcome>>()
        override fun execute(spec: IdempotentCommandSpec, action: () -> IdempotentCommandOutcome): IdempotentCommandExecution {
            calls += 1
            val prior = receipts[spec.operationId]
            if (prior != null) {
                require(prior.first == spec)
                return IdempotentCommandExecution(prior.second, true)
            }
            val result = action()
            receipts[spec.operationId] = spec to result
            return IdempotentCommandExecution(result, false)
        }
    }
}
