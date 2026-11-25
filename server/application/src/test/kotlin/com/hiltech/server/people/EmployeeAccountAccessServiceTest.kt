package com.hiltech.server.people

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.identity.EmployeeCredentialReplacementPort
import com.hiltech.server.identity.EmployeeCredentialReplacementResult
import com.hiltech.server.identity.IdentityProvisioningDeliveryMode
import com.hiltech.server.identity.IdentityProvisioningDeliveryState
import com.hiltech.server.platform.IdempotentCommandExecution
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class EmployeeAccountAccessServiceTest {
    private val at = Instant.parse("2026-11-25T12:00:00Z")
    private val org = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val employeeId = UUID.randomUUID()
    private val identityId = UUID.randomUUID()
    private val membershipId = UUID.randomUUID()
    private val actorMembership = UUID.randomUUID()
    private val operation = UUID.randomUUID()
    private var employee = OnboardingEmployeeContext(employeeId, org, UUID.randomUUID(), "EMP-SYNTH", EmployeeState.ACTIVE, 7,
        "Synthetic account employee", null, "synthetic@example.test", 2)
    private val employees = Mockito.mock(OnboardingPersistencePort::class.java)
    private val authority = Mockito.mock(PeopleAuthorizationPort::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private var authorized = true
    private var providerAvailable = true
    private var providerResult: EmployeeCredentialReplacementResult = EmployeeCredentialReplacementResult.Issued("synthetic-new-password")
    private var providerCalls = 0
    private var afterProvider: () -> Unit = {}
    private val source = Source()
    private val events = mutableListOf<Any>()
    private val provider = object : EmployeeCredentialReplacementPort {
        override fun availableFor(issuer: String) = providerAvailable
        override fun replace(issuer: String, subject: String): EmployeeCredentialReplacementResult {
            providerCalls += 1; afterProvider(); return providerResult
        }
    }
    private val service = EmployeeAccountAccessService(employees, authority, source, InvitationLock(), provider, Commands(), audit,
        ApplicationEventPublisher { events += it }, Clock.fixed(at, ZoneOffset.UTC))
    init {
        Mockito.`when`(employees.employee(employeeId)).thenAnswer { employee }
        Mockito.`when`(authority.canManagePeople(actor, org)).thenAnswer { authorized }
        source.accounts[identityId] = CurrentEmployeeAccount(identityId, 3, employee.personId, org, membershipId, 2,
            "https://identity.example.test/realms/synthetic", "synthetic-subject")
        source.accounts[actor] = CurrentEmployeeAccount(actor, 1, null, org, actorMembership, 1,
            "https://identity.example.test/realms/synthetic", "synthetic-admin")
    }

    @Test
    fun currentEmployeeAccountCanReceiveOneExplicitPasswordReplacement() {
        val before = employee
        assertTrue(service.availability(actor, employeeId, "Synthetic administrator").canReissue)
        val result = service.reissue(command())
        assertEquals("ISSUED", result.status)
        assertEquals("synthetic-new-password", result.temporaryCredential)
        assertFalse(result.replayed)
        assertEquals(1, providerCalls)
        assertEquals(before, employee)
        assertEquals(2, Mockito.mockingDetails(audit).invocations.size)
        assertEquals(3L, source.accounts[identityId]?.identityVersion)
        assertFalse(source.records.toString().contains("synthetic-new-password"))
        assertFalse(result.toString().contains("synthetic-new-password"))
        assertFalse(providerResult.toString().contains("synthetic-new-password"))
    }

    @Test
    fun sameOperationNeverDispatchesAgainAndCannotReplayTheSecret() {
        service.reissue(command())
        val replay = service.reissue(command())
        assertTrue(replay.replayed)
        assertEquals("ISSUED", replay.status)
        assertEquals(null, replay.temporaryCredential)
        assertEquals(1, providerCalls)
        assertEquals(2, Mockito.mockingDetails(audit).invocations.size)
    }

    @Test
    fun lostOutcomeAfterDispatchIsRecoveredWithoutAnotherAutomaticReset() {
        source.recordOutcome = false
        assertEquals("ACCOUNT_CREDENTIAL_OUTCOME_UNCONFIRMED", assertThrows<ProductApiException> { service.reissue(command()) }.code)
        assertEquals("DISPATCHING", source.records.getValue(operation).state)
        source.recordOutcome = true
        val recovered = service.reissue(command())
        assertEquals("UNCONFIRMED", recovered.status)
        assertEquals(null, recovered.temporaryCredential)
        assertEquals(1, providerCalls)
    }

    @Test
    fun newExplicitOperationCanReplaceAnUnconfirmedCredentialUsingItsCurrentHistoryVersion() {
        providerResult = EmployeeCredentialReplacementResult.Unconfirmed("ACCOUNT_CREDENTIAL_PROVIDER_OUTCOME_UNCONFIRMED")
        val prior = service.reissue(command())
        providerResult = EmployeeCredentialReplacementResult.Issued("synthetic-replacement-after-loss")
        val next = service.reissue(command().copy(operationId = UUID.randomUUID(), previousOperationId = operation,
            previousOperationVersion = prior.version, reason = "Synthetic explicit replacement after lost result"))
        assertEquals("ISSUED", next.status)
        assertEquals("synthetic-replacement-after-loss", next.temporaryCredential)
        assertEquals(2, providerCalls)
        assertEquals(2, source.records.size)
        assertEquals("UNCONFIRMED", source.records.getValue(operation).state)
    }

    @Test
    fun staleHistoryPreventsASecondPasswordFromInvalidatingANewerIssue() {
        service.reissue(command())
        assertEquals("ACCOUNT_CREDENTIAL_HISTORY_CHANGED", assertThrows<ProductApiException> {
            service.reissue(command().copy(operationId = UUID.randomUUID()))
        }.code)
        assertEquals(1, providerCalls)
    }

    @Test
    fun changingReasonOrIdentityWithSameOperationIsRejected() {
        service.reissue(command())
        assertEquals("IDEMPOTENCY_KEY_REUSED", assertThrows<ProductApiException> { service.reissue(command().copy(reason = "Different reason")) }.code)
        assertEquals(1, providerCalls)
    }

    @Test
    fun reasonAndPositiveVersionsAreRequiredBeforeDispatch() {
        assertEquals("ACCOUNT_CREDENTIAL_REQUEST_INVALID", assertThrows<ProductApiException> { service.reissue(command().copy(reason = "  ")) }.code)
        assertEquals("ACCOUNT_ACCESS_INVALID_VERSION", assertThrows<ProductApiException> { service.reissue(command().copy(baseIdentityVersion = 0)) }.code)
        assertEquals(0, providerCalls)
    }

    @Test
    fun suspendedOrRevokedTargetAccountCannotBeResetOrReactivated() {
        source.restricted += identityId
        assertFalse(service.availability(actor, employeeId, null).canReissue)
        assertEquals("EMPLOYEE_ACCOUNT_RESTRICTED", assertThrows<ProductApiException> { service.reissue(command()) }.code)
        assertEquals(0, providerCalls)
        assertTrue(source.records.isEmpty())
    }

    @Test
    fun accountForAnotherPersonCannotBeResetFromThisEmployee() {
        source.accounts[identityId] = requireNotNull(source.accounts[identityId]).copy(personId = UUID.randomUUID())
        assertThrows<ProductApiException> { service.reissue(command()) }
        assertEquals(0, providerCalls)
    }

    @Test
    fun changedEmployeeIdentityOrMembershipVersionStopsReset() {
        for (changed in listOf(command().copy(baseEmployeeVersion = 6), command().copy(baseIdentityVersion = 2), command().copy(baseMembershipVersion = 1),
            command().copy(expectedMembershipId = UUID.randomUUID()))) {
            assertEquals("ACCOUNT_CREDENTIAL_SOURCE_CHANGED", assertThrows<ProductApiException> { service.reissue(changed) }.code)
        }
        assertEquals(0, providerCalls)
    }

    @Test
    fun formerEmployeeCannotGetNewCredentials() {
        employee = employee.copy(employeeState = EmployeeState.FORMER)
        assertFalse(service.availability(actor, employeeId, null).canReissue)
        assertEquals("EMPLOYEE_ACCOUNT_NOT_ELIGIBLE", assertThrows<ProductApiException> { service.reissue(command()) }.code)
        assertEquals(0, providerCalls)
    }

    @Test
    fun sourceAuthorityIsRecheckedUnderLockAndBeforeReceiptReplay() {
        source.afterLock = { authorized = false }
        assertEquals("PEOPLE_ACCESS_DENIED", assertThrows<ProductApiException> { service.reissue(command()) }.code)
        assertEquals(0, providerCalls)
        assertTrue(source.records.isEmpty())
        source.afterLock = {}; authorized = true
        service.reissue(command())
        authorized = false
        assertEquals("PEOPLE_ACCESS_DENIED", assertThrows<ProductApiException> { service.reissue(command()) }.code)
        assertEquals(1, providerCalls)
    }

    @Test
    fun authorityLostAfterProviderMutationNeverRevealsCredentialOrClaimsNoEffect() {
        afterProvider = { authorized = false }
        val failure = assertThrows<ProductApiException> { service.reissue(command()) }
        assertEquals("ACCOUNT_CREDENTIAL_ACCESS_CHANGED_AFTER_DISPATCH", failure.code)
        assertTrue(failure.retryable)
        assertEquals("ISSUED", source.records.getValue(operation).state)
        assertFalse(failure.toString().contains("synthetic-new-password"))
    }

    @Test
    fun offboardingDuringProviderMutationWithholdsCredentialWithoutClaimingThePasswordWasUnchanged() {
        afterProvider = { employee = employee.copy(employeeState = EmployeeState.OFFBOARDING) }
        val failure = assertThrows<ProductApiException> { service.reissue(command()) }
        assertEquals("ACCOUNT_CREDENTIAL_ACCESS_CHANGED_AFTER_DISPATCH", failure.code)
        assertTrue(failure.retryable)
        assertEquals("ISSUED", source.records.getValue(operation).state)
        assertEquals(1, providerCalls)
    }

    @Test
    fun providerRejectionIsRecordedWithoutInventingSuccessOrSecret() {
        providerResult = EmployeeCredentialReplacementResult.Rejected("ACCOUNT_CREDENTIAL_PROVIDER_ACCOUNT_RESTRICTED")
        val result = service.reissue(command())
        assertEquals("REJECTED", result.status)
        assertEquals(null, result.temporaryCredential)
        assertEquals("ACCOUNT_CREDENTIAL_PROVIDER_ACCOUNT_RESTRICTED", result.safeFailureCode)
        service.reissue(command())
        assertEquals(1, providerCalls)
    }

    @Test
    fun providerUnavailabilityBeforeDispatchLeavesNoCredentialOperation() {
        providerAvailable = false
        assertFalse(service.availability(actor, employeeId, null).canReissue)
        assertEquals("ACCOUNT_CREDENTIAL_PROVIDER_UNAVAILABLE", assertThrows<ProductApiException> { service.reissue(command()) }.code)
        assertTrue(source.records.isEmpty())
        assertEquals(0, providerCalls)
    }

    @Test
    fun unlinkedCurrentAdministratorGetsNamedSelfCandidateWithoutRawIdentityChoice() {
        source.accounts.remove(identityId)
        val setup = service.availability(actor, employeeId, "Synthetic administrator")
        assertEquals(null, setup.account)
        assertEquals("Synthetic administrator", setup.ownAccountCandidate?.displayName)
        assertEquals(actorMembership.toString(), setup.ownAccountCandidate?.membershipId)
        assertFalse(setup.canReissue)
    }

    @Test
    fun ownAccountLinkUsesOnlyAuthenticatedActorAndPreservesEmploymentState() {
        source.accounts.remove(identityId)
        val result = service.linkOwnAccount(linkCommand())
        assertTrue(result.linked)
        assertEquals(8L, result.employeeVersion)
        assertEquals(EmployeeState.ACTIVE, employee.employeeState)
        assertEquals(employee.personId, source.accounts[actor]?.personId)
        assertEquals(1, events.size)
        assertEquals(employeeId, (events.single() as EmployeeIdentityLinked).employeeId)
        assertEquals(1, Mockito.mockingDetails(audit).invocations.size)
        assertEquals(0, providerCalls)
    }

    @Test
    fun ownLinkReceiptCanReplayAfterExpectedSourceVersionsHaveAdvanced() {
        source.accounts.remove(identityId)
        service.linkOwnAccount(linkCommand())
        val replay = service.linkOwnAccount(linkCommand())
        assertTrue(replay.replayed)
        assertTrue(replay.linked)
        assertEquals(8L, employee.employeeVersion)
        assertEquals(1, events.size)
    }

    @Test
    fun ownAccountAlreadyBelongingToAnotherPersonCannotBeMoved() {
        source.accounts.remove(identityId)
        source.accounts[actor] = requireNotNull(source.accounts[actor]).copy(personId = UUID.randomUUID())
        assertEquals(null, service.availability(actor, employeeId, null).ownAccountCandidate)
        assertEquals("ACCOUNT_LINK_CURRENT_IDENTITY_UNAVAILABLE", assertThrows<ProductApiException> { service.linkOwnAccount(linkCommand()) }.code)
        assertTrue(events.isEmpty())
    }

    @Test
    fun existingEmployeeAccountOrInvitationPreventsOwnLinkReplacement() {
        assertEquals(null, service.availability(actor, employeeId, null).ownAccountCandidate)
        assertEquals("ACCOUNT_LINK_CONFLICT", assertThrows<ProductApiException> { service.linkOwnAccount(linkCommand()) }.code)
        source.accounts.remove(identityId); source.invitationExists = true
        assertEquals(null, service.availability(actor, employeeId, null).ownAccountCandidate)
        assertEquals("ACCOUNT_LINK_CONFLICT", assertThrows<ProductApiException> { service.linkOwnAccount(linkCommand()) }.code)
        assertTrue(events.isEmpty())
    }

    @Test
    fun ownLinkRechecksMembershipVersionAndPeopleAuthority() {
        source.accounts.remove(identityId)
        assertEquals("ACCOUNT_LINK_SOURCE_CHANGED", assertThrows<ProductApiException> { service.linkOwnAccount(linkCommand().copy(baseMembershipVersion = 2)) }.code)
        source.afterLock = { authorized = false }
        assertEquals("PEOPLE_ACCESS_DENIED", assertThrows<ProductApiException> { service.linkOwnAccount(linkCommand()) }.code)
        assertEquals(null, source.accounts[actor]?.personId)
        assertTrue(events.isEmpty())
    }

    private fun command() = CredentialReissueCommand(operation, employeeId, 7, identityId, 3, membershipId, 2, null, null,
        "Synthetic employee lost the handoff", actor, "synthetic-credential-reissue")
    private fun linkCommand() = LinkOwnEmployeeAccountCommand(operation, employeeId, 7, 1, actorMembership, 1, actor, "synthetic-self-link")

    private inner class Source : EmployeeAccountAccessPersistencePort {
        val accounts = mutableMapOf<UUID, CurrentEmployeeAccount>()
        val restricted = mutableSetOf<UUID>()
        val records = linkedMapOf<UUID, CredentialReissueRecord>()
        var afterLock: () -> Unit = {}
        var invitationExists = false
        var recordOutcome = true
        override fun <T : Any> transaction(action: () -> T): T = action()
        override fun <T : Any> serializeCredentialReplacement(identityId: UUID, action: () -> T): T = action()
        override fun lockEmployeeAndAccount(employeeId: UUID, identityId: UUID) { afterLock() }
        override fun currentAccount(identityId: UUID, organizationId: UUID, at: Instant) = accounts[identityId]?.takeIf { it.organizationId == organizationId && identityId !in restricted }
        override fun currentLinkedAccount(personId: UUID, organizationId: UUID, at: Instant) = accounts.values.firstOrNull {
            it.personId == personId && it.organizationId == organizationId && it.identityId !in restricted
        }
        override fun personHasOtherAccount(personId: UUID, exceptIdentityId: UUID?) = accounts.values.any { it.personId == personId && it.identityId != exceptIdentityId }
        override fun hasInvitation(employeeId: UUID) = invitationExists
        override fun linkOwnAccount(employee: OnboardingEmployeeContext, account: CurrentEmployeeAccount, at: Instant): Boolean {
            accounts[account.identityId] = account.copy(personId = employee.personId, identityVersion = account.identityVersion + 1)
            this@EmployeeAccountAccessServiceTest.employee = employee.copy(employeeVersion = employee.employeeVersion + 1)
            return true
        }
        override fun reissue(operationId: UUID) = records[operationId]
        override fun latestReissue(identityId: UUID) = records.values.lastOrNull { it.identityId == identityId }
        override fun insertDispatch(command: CredentialReissueCommand, organizationId: UUID, fingerprint: String, at: Instant) {
            records[command.operationId] = CredentialReissueRecord(command.operationId, organizationId, command.employeeId, command.expectedIdentityId,
                command.actorUserId, fingerprint, "DISPATCHING", null, at, null, 1)
        }
        override fun finishDispatch(operationId: UUID, state: String, safeCode: String?, at: Instant): Boolean {
            if (!recordOutcome) return false
            val row = records.getValue(operationId)
            records[operationId] = row.copy(state = state, safeFailureCode = safeCode, completedAt = at, version = 2)
            return true
        }
    }
    private class Commands : IdempotentCommandExecutor {
        private val receipts = mutableMapOf<UUID, Pair<IdempotentCommandSpec, IdempotentCommandOutcome>>()
        override fun execute(spec: IdempotentCommandSpec, action: () -> IdempotentCommandOutcome): IdempotentCommandExecution {
            receipts[spec.operationId]?.let { require(it.first == spec); return IdempotentCommandExecution(it.second, true) }
            return IdempotentCommandExecution(action().also { receipts[spec.operationId] = spec to it }, false)
        }
    }
    private class InvitationLock : EmployeeIdentityInvitationPersistencePort {
        override fun <T> serializeProvisioning(employeeId: UUID, provision: () -> T) = provision()
        override fun byOperationId(operationId: UUID): EmployeeIdentityInvitationSnapshot? = error("Not used")
        override fun currentForEmployee(employeeId: UUID): EmployeeIdentityInvitationSnapshot? = error("Not used")
        override fun accountState(employeeId: UUID, at: Instant) = error("Not used")
        override fun insertPending(invitationId: UUID, operationId: UUID, organizationId: UUID, employeeId: UUID, requestedLogin: String,
            authProvider: String, deliveryMode: IdentityProvisioningDeliveryMode, actorUserId: UUID, at: Instant) = error("Not used")
        override fun markReady(invitationId: UUID, providerSubject: String, userIdentityId: UUID, deliveryState: IdentityProvisioningDeliveryState, at: Instant) = error("Not used")
        override fun markRetryableFailure(invitationId: UUID, providerSubject: String?, deliveryState: IdentityProvisioningDeliveryState, safeFailureCode: String, at: Instant) = error("Not used")
    }
}
