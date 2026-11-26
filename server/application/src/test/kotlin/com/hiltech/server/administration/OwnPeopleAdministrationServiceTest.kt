package com.hiltech.server.administration

import com.hiltech.server.audit.AuditEventWriter
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class OwnPeopleAdministrationServiceTest {
    private val at = Instant.parse("2026-11-25T12:00:00Z")
    private val actor = UUID.randomUUID()
    private val organization = UUID.randomUUID()
    private val operation = UUID.randomUUID()
    private val authority = Mockito.mock(ResponsibilityAdministrationAuthorityPort::class.java)
    private val source = Mockito.mock(RoleTeamSourceAuthorityPort::class.java)
    private val people = Mockito.mock(PeopleAuthorizationPort::class.java)
    private val projection = Mockito.mock(CompanyResponsibilityProjectionPort::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val bindings = Bindings()
    private val executor = Receipts()
    private val service = OwnPeopleAdministrationService(authority, source, people, bindings, projection, executor, audit, Clock.fixed(at, ZoneOffset.UTC))
    init {
        Mockito.`when`(authority.grantableKeys(actor, organization, at)).thenReturn(setOf(CompanyResponsibilityKey.PEOPLE_ADMIN))
        Mockito.`when`(source.isOrganizationMemberCurrent(actor, organization, at)).thenReturn(true)
    }

    @Test
    fun approvedDelegateCanAssignOnlyOwnOrdinaryPeopleRoleWithoutEmployeeRecord() {
        assertEquals("AVAILABLE", service.availability(actor, organization))
        val receipt = service.activate(command())
        assertEquals(actor.toString(), receipt.identityId)
        assertEquals("PEOPLE_ADMIN", receipt.authorityKey)
        assertTrue(receipt.current)
        assertFalse(receipt.accessReady)
        val binding = bindings.rows.values.single()
        assertEquals(actor, binding.principalId)
        assertEquals("USER", binding.principalType)
        assertEquals(CompanyResponsibilityKey.PEOPLE_ADMIN, binding.authorityKey)
        assertEquals(1, Mockito.mockingDetails(audit).invocations.size)
        assertEquals(1, Mockito.mockingDetails(projection).invocations.size)
        assertTrue(Mockito.mockingDetails(authority).invocations.all { it.method.name == "grantableKeys" })
        assertEquals("ASSIGNED", service.availability(actor, organization))
    }

    @Test
    fun ordinaryPeopleRoleDoesNotConferDelegationCeiling() {
        Mockito.`when`(people.canManagePeople(actor, organization)).thenReturn(true)
        Mockito.`when`(authority.grantableKeys(actor, organization, at)).thenReturn(emptySet())
        assertEquals("CURRENT", service.availability(actor, organization))
        assertEquals("RESPONSIBILITY_ADMINISTRATION_DENIED", assertThrows<ProductApiException> { service.activate(command()) }.code)
        assertTrue(bindings.rows.isEmpty())
    }

    @Test
    fun differentCatalogCeilingCannotActivatePeople() {
        Mockito.`when`(authority.grantableKeys(actor, organization, at)).thenReturn(setOf(CompanyResponsibilityKey.PAYROLL_PREPARE))
        assertEquals("NOT_DELEGATED", service.availability(actor, organization))
        assertThrows<ProductApiException> { service.activate(command()) }
        assertTrue(bindings.rows.isEmpty())
    }

    @Test
    fun membershipMustBeCurrentEvenWhenSourceCeilingExists() {
        Mockito.`when`(source.isOrganizationMemberCurrent(actor, organization, at)).thenReturn(false)
        assertEquals("NOT_DELEGATED", service.availability(actor, organization))
        assertThrows<ProductApiException> { service.activate(command()) }
        Mockito.verifyNoInteractions(audit, projection)
    }

    @Test
    fun sourceAuthorityIsRecheckedUnderOrganizationLock() {
        bindings.onLock = { Mockito.`when`(authority.grantableKeys(actor, organization, at)).thenReturn(emptySet()) }
        assertThrows<ProductApiException> { service.activate(command()) }
        assertTrue(bindings.rows.isEmpty())
        Mockito.verifyNoInteractions(audit, projection)
    }

    @Test
    fun replayUsesSameAssignmentWithoutRepeatedSourceWritesOrAudit() {
        val first = service.activate(command())
        val replay = service.activate(command())
        assertTrue(replay.replayed)
        assertEquals(first.bindingId, replay.bindingId)
        assertEquals(1, bindings.rows.size)
        assertEquals(1, Mockito.mockingDetails(audit).invocations.size)
        assertEquals(1, Mockito.mockingDetails(projection).invocations.size)
    }

    @Test
    fun revokedDelegationCannotReadOldCommandReceipt() {
        service.activate(command())
        val before = executor.calls
        Mockito.`when`(authority.grantableKeys(actor, organization, at)).thenReturn(emptySet())
        assertThrows<ProductApiException> { service.activate(command()) }
        assertEquals(before, executor.calls)
    }

    @Test
    fun existingDirectAssignmentIsConfirmedWithoutReplacingItsHistory() {
        val existing = CompanyResponsibilityBinding(UUID.randomUUID(), organization, CompanyResponsibilityKey.PEOPLE_ADMIN, "USER", actor, true, 9)
        bindings.rows[existing.bindingId] = existing
        assertEquals(existing.bindingId.toString(), service.activate(command()).bindingId)
        assertEquals(listOf(existing), bindings.rows.values.toList())
    }

    @Test
    fun currentAccessIsReportedOnlyWhenOwningPeopleAuthorizationAllowsIt() {
        service.activate(command())
        assertEquals("ASSIGNED", service.availability(actor, organization))
        Mockito.`when`(people.canManagePeople(actor, organization)).thenReturn(true)
        assertEquals("CURRENT", service.availability(actor, organization))
        assertTrue(service.activate(command()).accessReady)
    }

    @Test
    fun historicalReceiptDoesNotRegrantLaterEndedAuthority() {
        val first = service.activate(command())
        val id = UUID.fromString(first.bindingId)
        bindings.rows[id] = requireNotNull(bindings.rows[id]).copy(current = false, version = 2)
        val replay = service.activate(command())
        assertTrue(replay.replayed)
        assertFalse(replay.current)
        assertEquals(1, bindings.rows.size)
    }

    @Test
    fun foreignOrganizationCannotReuseApprovedCeiling() {
        assertThrows<ProductApiException> { service.activate(command().copy(organizationId = UUID.randomUUID())) }
        assertTrue(bindings.rows.isEmpty())
    }

    private fun command() = ActivateOwnPeopleAdministrationCommand(operation, organization, actor, "synthetic-own-people")
    private class Bindings : CompanyResponsibilityPersistencePort {
        val rows = mutableMapOf<UUID, CompanyResponsibilityBinding>()
        var onLock: () -> Unit = {}
        override fun lockOrganization(organizationId: UUID) { onLock() }
        override fun lockEmployee(employeeId: UUID) = error("Self-activation never requires an employee record")
        override fun bindings(organizationId: UUID, at: Instant) = rows.values.filter { it.organizationId == organizationId }
        override fun binding(key: CompanyResponsibilityKey, bindingId: UUID, at: Instant) = rows[bindingId]?.takeIf { it.authorityKey == key }
        override fun grant(key: CompanyResponsibilityKey, bindingId: UUID, organizationId: UUID, identityId: UUID, actor: UUID, operationId: UUID, at: Instant) {
            rows[bindingId] = CompanyResponsibilityBinding(bindingId, organizationId, key, "USER", identityId, true, 1)
        }
        override fun end(binding: CompanyResponsibilityBinding, at: Instant) = error("Self-activation never ends another assignment")
    }
    private class Receipts : IdempotentCommandExecutor {
        var calls = 0
        val rows = mutableMapOf<UUID, Pair<IdempotentCommandSpec, IdempotentCommandOutcome>>()
        override fun execute(spec: IdempotentCommandSpec, action: () -> IdempotentCommandOutcome): IdempotentCommandExecution {
            calls += 1
            rows[spec.operationId]?.let { require(it.first == spec); return IdempotentCommandExecution(it.second, true) }
            return IdempotentCommandExecution(action().also { rows[spec.operationId] = spec to it }, false)
        }
    }
}
