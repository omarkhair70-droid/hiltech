package com.hiltech.server.administration

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.people.EmployeeState
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ResponsibilityDelegationServiceTest {
    private val organization = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val identity = UUID.randomUUID()
    private val employeeId = UUID.randomUUID()
    private val at = Instant.parse("2026-11-25T12:00:00Z")
    private val employees = Mockito.mock(ResponsibilityEmployeeDirectoryPort::class.java)
    private val source = Mockito.mock(RoleTeamSourceAuthorityPort::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val rows = MemoryDelegations()
    private val commands = RecordingCommands()
    private val authority = object : ResponsibilityAdministrationAuthorityPort {
        override fun grantableKeys(actor: UUID, organizationId: UUID, at: Instant) = rows.rows.values
            .filter { it.organizationId == organizationId && it.identityId == actor && it.current }.map { it.authorityKey }.toSet()
        override fun delegableKeys(actor: UUID, organizationId: UUID, at: Instant) = rows.rows.values
            .filter { it.organizationId == organizationId && it.identityId == actor && it.current && it.canDelegateAdministration }.map { it.authorityKey }.toSet()
    }
    private val service = ResponsibilityDelegationService(employees, authority, source, rows, commands, audit, Clock.fixed(at, ZoneOffset.UTC))

    @Test
    fun operationalAdministrationWithoutOnwardAuthorityCannotDelegateIt() {
        currentAccount()
        seed(actor, CompanyResponsibilityKey.SALES_VIEW, false)
        assertEquals("RESPONSIBILITY_ADMINISTRATION_DELEGATION_DENIED", assertThrows<ProductApiException> { service.grant(command()) }.code)
        assertThrows<ProductApiException> { service.current(actor, employeeId) }
        assertEquals(0, commands.calls)
    }

    @Test
    fun grantsOnlyAnExplicitSubsetOfCurrentOnwardCeiling() {
        authorized()
        assertEquals("RESPONSIBILITY_ADMINISTRATION_DELEGATION_DENIED", assertThrows<ProductApiException> {
            service.grant(command().copy(authorityKeys = setOf(CompanyResponsibilityKey.SALES_VIEW, CompanyResponsibilityKey.PAYROLL_PREPARE)))
        }.code)
        assertTrue(rows.rows.values.none { it.identityId == identity })
    }

    @Test
    fun administrationCannotBeSelfGrantedEvenWithinAnOperationalRoleCeiling() {
        authorized()
        Mockito.`when`(employees.employee(employeeId)).thenReturn(employee().copy(linkedIdentityId = actor))
        assertEquals("RESPONSIBILITY_GOVERNANCE_SELF_GRANT_DENIED", assertThrows<ProductApiException> {
            service.grant(command().copy(expectedIdentityId = actor))
        }.code)
        assertEquals(0, commands.calls)
    }

    @Test
    fun onwardBitIsExplicitAndBatchGrantIsAuditedOncePerKey() {
        authorized()
        seed(actor, CompanyResponsibilityKey.PAYROLL_READ, true)
        val request = command().copy(authorityKeys = setOf(CompanyResponsibilityKey.SALES_VIEW, CompanyResponsibilityKey.PAYROLL_READ), canDelegateAdministration = true)
        val first = service.grant(request)
        val retry = service.grant(request)
        assertEquals(2, first.bindings.size)
        assertTrue(first.bindings.all { it.canDelegateAdministration && it.current })
        assertTrue(retry.replayed)
        assertEquals(first.bindings.map { it.bindingId }, retry.bindings.map { it.bindingId })
        assertEquals(2, Mockito.mockingDetails(audit).invocations.size)
        assertEquals(request.authorityKeys, authority.delegableKeys(identity, organization, at))
    }

    @Test
    fun ordinaryDelegationDoesNotEnableOnwardDelegation() {
        authorized()
        service.grant(command())
        assertEquals(setOf(CompanyResponsibilityKey.SALES_VIEW), authority.grantableKeys(identity, organization, at))
        assertTrue(authority.delegableKeys(identity, organization, at).isEmpty())
    }

    @Test
    fun revocationBetweenPreviewAndTransactionPreventsGrant() {
        val own = authorized()
        rows.onLock = { rows.rows[own.id] = own.copy(current = false) }
        assertThrows<ProductApiException> { service.grant(command()) }
        assertTrue(rows.rows.values.none { it.identityId == identity })
        Mockito.verifyNoInteractions(audit)
    }

    @Test
    fun revokedAuthorityCannotReplayARecordedGrant() {
        val own = authorized()
        val request = command()
        service.grant(request)
        rows.rows[own.id] = own.copy(current = false)
        assertThrows<ProductApiException> { service.grant(request) }
        assertEquals(1, commands.calls)
    }

    @Test
    fun changedEmployeeOrRecipientMembershipPreventsGrant() {
        authorized()
        assertEquals("RESPONSIBILITY_EMPLOYEE_CHANGED", assertThrows<ProductApiException> { service.grant(command().copy(baseEmployeeVersion = 2)) }.code)
        Mockito.`when`(source.isOrganizationMemberCurrent(identity, organization, at)).thenReturn(false)
        assertEquals("RESPONSIBILITY_ACCOUNT_NOT_CURRENT", assertThrows<ProductApiException> { service.grant(command()) }.code)
    }

    @Test
    fun endingLastCurrentDelegatorRequiresTransferFirst() {
        val own = authorized()
        Mockito.`when`(employees.employee(employeeId)).thenReturn(employee().copy(linkedIdentityId = actor))
        Mockito.`when`(source.isOrganizationMemberCurrent(actor, organization, at)).thenReturn(true)
        assertFalse(service.current(actor, employeeId).bindings.single().canEnd)
        assertEquals("RESPONSIBILITY_DELEGATION_TRANSFER_REQUIRED", assertThrows<ProductApiException> {
            service.end(command().copy(expectedIdentityId = actor, bindingId = own.id, baseBindingVersion = 1))
        }.code)
        assertTrue(rows.rows.getValue(own.id).current)
    }

    @Test
    fun explicitTransferThenEndPreservesAWorkingDelegatorAndHistory() {
        val own = authorized()
        val replacement = service.grant(command().copy(canDelegateAdministration = true))
        Mockito.`when`(employees.employee(employeeId)).thenReturn(employee().copy(linkedIdentityId = actor))
        Mockito.`when`(source.isOrganizationMemberCurrent(actor, organization, at)).thenReturn(true)
        val result = service.end(command().copy(expectedIdentityId = actor, bindingId = own.id, baseBindingVersion = 1))
        assertFalse(result.bindings.single().current)
        assertEquals(2L, rows.rows.getValue(own.id).version)
        assertTrue(rows.rows.getValue(UUID.fromString(replacement.bindings.single().bindingId)).current)
        assertEquals(setOf(CompanyResponsibilityKey.SALES_VIEW), authority.delegableKeys(identity, organization, at))
    }

    @Test
    fun staleOrForeignDelegationCannotBeEnded() {
        authorized()
        val binding = seed(identity, CompanyResponsibilityKey.SALES_VIEW, false)
        assertEquals("RESPONSIBILITY_VERSION_CONFLICT", assertThrows<ProductApiException> {
            service.end(command().copy(bindingId = binding.id, baseBindingVersion = 2))
        }.code)
        rows.rows[binding.id] = binding.copy(organizationId = UUID.randomUUID())
        assertEquals("RESPONSIBILITY_DELEGATION_UNAVAILABLE", assertThrows<ProductApiException> {
            service.end(command().copy(bindingId = binding.id, baseBindingVersion = 1))
        }.code)
    }

    private fun currentAccount() {
        Mockito.`when`(employees.employee(employeeId)).thenReturn(employee())
        Mockito.`when`(source.isOrganizationMemberCurrent(identity, organization, at)).thenReturn(true)
    }
    private fun authorized(): ResponsibilityDelegationBinding { currentAccount(); return seed(actor, CompanyResponsibilityKey.SALES_VIEW, true) }
    private fun seed(who: UUID, key: CompanyResponsibilityKey, onward: Boolean): ResponsibilityDelegationBinding =
        ResponsibilityDelegationBinding(UUID.randomUUID(), organization, who, key, onward, true, 1).also { rows.rows[it.id] = it }
    private fun employee() = ResponsibilityEmployeeContext(employeeId, organization, 3, "SYNTH", "Synthetic employee", EmployeeState.ACTIVE, identity)
    private fun command() = ResponsibilityDelegationCommand(UUID.randomUUID(), employeeId, organization, identity, 3,
        setOf(CompanyResponsibilityKey.SALES_VIEW), false, actor = actor, correlationId = "synthetic-delegation")

    private class MemoryDelegations : ResponsibilityDelegationPersistencePort {
        val rows = linkedMapOf<UUID, ResponsibilityDelegationBinding>()
        var onLock: () -> Unit = {}
        override fun lockOrganization(organizationId: UUID) = onLock()
        override fun lockEmployee(employeeId: UUID) = Unit
        override fun currentBindings(organizationId: UUID, identityId: UUID, at: Instant) = rows.values.filter { it.organizationId == organizationId && it.identityId == identityId && it.current }
        override fun binding(id: UUID, at: Instant) = rows[id]
        override fun hasOtherCurrentDelegator(organizationId: UUID, key: CompanyResponsibilityKey, exceptId: UUID, at: Instant) =
            rows.values.any { it.organizationId == organizationId && it.authorityKey == key && it.id != exceptId && it.current && it.canDelegateAdministration }
        override fun grant(id: UUID, organizationId: UUID, identityId: UUID, key: CompanyResponsibilityKey, onward: Boolean, actor: UUID, operationId: UUID, at: Instant) {
            rows[id] = ResponsibilityDelegationBinding(id, organizationId, identityId, key, onward, true, 1)
        }
        override fun end(binding: ResponsibilityDelegationBinding, at: Instant): Boolean {
            if (rows[binding.id] != binding) return false
            rows[binding.id] = binding.copy(current = false, version = binding.version + 1)
            return true
        }
    }
    private class RecordingCommands : IdempotentCommandExecutor {
        var calls = 0
        private val receipts = mutableMapOf<UUID, Pair<IdempotentCommandSpec, IdempotentCommandOutcome>>()
        override fun execute(spec: IdempotentCommandSpec, action: () -> IdempotentCommandOutcome): IdempotentCommandExecution {
            calls += 1
            receipts[spec.operationId]?.let { require(it.first == spec); return IdempotentCommandExecution(it.second, true) }
            val receipt = action(); receipts[spec.operationId] = spec to receipt
            return IdempotentCommandExecution(receipt, false)
        }
    }
}
