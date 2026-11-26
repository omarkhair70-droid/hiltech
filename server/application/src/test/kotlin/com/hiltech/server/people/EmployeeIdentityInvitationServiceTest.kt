package com.hiltech.server.people

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.identity.EmployeeIdentityProvisioningPort
import com.hiltech.server.identity.EmployeeIdentityProvisioningRequest
import com.hiltech.server.identity.EmployeeIdentityProvisioningResult
import com.hiltech.server.identity.IdentityProvisioningDeliveryMode
import com.hiltech.server.identity.IdentityProvisioningDeliveryState
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class EmployeeIdentityInvitationServiceTest {
    private val at = Instant.parse("2026-11-25T12:00:00Z")
    private val actor = UUID.randomUUID()
    private val org = UUID.randomUUID()
    private val employeeId = UUID.randomUUID()
    private val operation = UUID.randomUUID()
    private val identity = UUID.randomUUID()
    private val onboarding = Mockito.mock(OnboardingPersistencePort::class.java)
    private val authority = Mockito.mock(PeopleAuthorizationPort::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private var employee = OnboardingEmployeeContext(employeeId, org, UUID.randomUUID(), "EMP-SYNTHETIC",
        EmployeeState.ACTIVE, 7, "Synthetic employee", null, "synthetic@example.test", 2)
    private val store = Invitations()
    private val events = mutableListOf<Any>()
    private val requests = mutableListOf<EmployeeIdentityProvisioningRequest>()
    private var providerResult: EmployeeIdentityProvisioningResult = EmployeeIdentityProvisioningResult.Ready(
        "https://identity.example.test", "synthetic-subject", identity, IdentityProvisioningDeliveryState.SENT, null)
    private var deliveryModes = IdentityProvisioningDeliveryMode.entries.toSet()
    private val provider = object : EmployeeIdentityProvisioningPort {
        override fun availableDeliveryModes() = deliveryModes
        override fun provision(request: EmployeeIdentityProvisioningRequest): EmployeeIdentityProvisioningResult {
            requests += request
            return providerResult
        }
    }
    private val service = EmployeeIdentityInvitationService(onboarding, store, authority, provider, audit,
        ApplicationEventPublisher { events += it }, Clock.fixed(at, ZoneOffset.UTC))

    init {
        Mockito.`when`(onboarding.employee(employeeId)).thenAnswer { employee }
        Mockito.`when`(authority.canManagePeople(actor, org)).thenReturn(true)
    }

    @Test
    fun activeAndPreboardingStaffWithoutAccountsCanUseAvailableDeliveryMethods() {
        for (state in listOf(EmployeeState.ACTIVE, EmployeeState.PREBOARDING)) {
            employee = employee.copy(employeeState = state)
            val availability = service.availability(employeeId, actor)
            assertEquals("READY_TO_INVITE", availability.status)
            assertEquals(deliveryModes.map { it.name }, availability.deliveryModes)
            assertEquals(null, availability.pending)
        }
    }

    @Test
    fun activeEmployeeReceivesAccountWithoutChangingEmploymentOrOnboarding() {
        val before = employee
        val result = service.provision(command().copy(requestedLogin = " Synthetic@Example.Test "))
        assertEquals(EmployeeIdentityInvitationState.READY, result.invitation.state)
        assertFalse(result.replayed)
        assertEquals(before, employee)
        assertEquals("synthetic@example.test", requests.single().requestedLogin)
        assertEquals(before.personId, requests.single().personId)
        assertEquals(org, requests.single().organizationId)
        assertEquals(1, events.size)
        assertEquals(1, Mockito.mockingDetails(audit).invocations.size)
        assertTrue(Mockito.mockingDetails(onboarding).invocations.all { it.method.name == "employee" })
        assertEquals("LINKED", service.availability(employeeId, actor).status)
    }

    @Test
    fun completedEmailReceiptReplaysAfterEmployeeVersionChangesWithoutSendingAgain() {
        val first = service.provision(command())
        employee = employee.copy(employeeVersion = 8)
        deliveryModes = emptySet()
        val replay = service.provision(command())
        assertTrue(replay.replayed)
        assertEquals(first.invitation, replay.invitation)
        assertEquals(1, requests.size)
        assertEquals(1, events.size)
    }

    @Test
    fun temporaryCredentialCannotBeReissuedByReplayingSuccessfulCreation() {
        providerResult = EmployeeIdentityProvisioningResult.Ready("https://identity.example.test", "synthetic-subject", identity,
            IdentityProvisioningDeliveryState.READY, "synthetic-one-time-secret")
        val command = command().copy(deliveryMode = IdentityProvisioningDeliveryMode.TEMPORARY_PASSWORD_HANDOFF)
        assertEquals("synthetic-one-time-secret", service.provision(command).temporaryCredential)
        assertEquals("TEMPORARY_CREDENTIAL_NOT_REPLAYABLE", assertThrows<ProductApiException> { service.provision(command) }.code)
        assertEquals(1, requests.size)
        assertFalse(store.records.values.single().toString().contains("synthetic-one-time-secret"))
    }

    @Test
    fun completedInvitationDoesNotPretendARestrictedAccountIsStillCurrent() {
        service.provision(command())
        store.account = EmployeeIdentityAccountState.RESTRICTED
        val availability = service.availability(employeeId, actor)
        assertEquals("ACCOUNT_RESTRICTED", availability.status)
        assertTrue(availability.deliveryModes.isEmpty())
        assertEquals("EMPLOYEE_ACCOUNT_RESTRICTED", assertThrows<ProductApiException> { service.provision(command()) }.code)
        assertEquals(1, requests.size)
    }

    @Test
    fun restrictedAccountCannotBeReplacedByANewInvitation() {
        store.account = EmployeeIdentityAccountState.RESTRICTED
        assertEquals("EMPLOYEE_ACCOUNT_RESTRICTED", assertThrows<ProductApiException> { service.provision(command()) }.code)
        assertTrue(store.records.isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun pendingAccountWithoutOwnedInvitationRequiresReviewInsteadOfCreatingDuplicate() {
        store.account = EmployeeIdentityAccountState.PENDING
        assertEquals("ACCOUNT_ATTENTION", service.availability(employeeId, actor).status)
        assertEquals("EMPLOYEE_IDENTITY_ALREADY_LINKED", assertThrows<ProductApiException> { service.provision(command()) }.code)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun offboardingAndFormerEmployeesCannotReceiveNewAccounts() {
        for (state in listOf(EmployeeState.OFFBOARDING, EmployeeState.FORMER)) {
            employee = employee.copy(employeeState = state)
            assertEquals("NOT_ELIGIBLE", service.availability(employeeId, actor).status)
            assertEquals("EMPLOYEE_ACCOUNT_NOT_ELIGIBLE", assertThrows<ProductApiException> { service.provision(command()) }.code)
        }
        assertTrue(requests.isEmpty())
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun retryableProviderFailurePreservesTheExactInvitationAndLogin() {
        providerResult = EmployeeIdentityProvisioningResult.RetryableFailure("IDENTITY_DELIVERY_INCOMPLETE", "synthetic-subject")
        assertTrue(assertThrows<ProductApiException> { service.provision(command()) }.retryable)
        val pending = service.availability(employeeId, actor)
        assertEquals("CONTINUE_INVITATION", pending.status)
        assertEquals(operation.toString(), pending.pending?.operationId)
        assertEquals("synthetic@example.test", pending.pending?.requestedLogin)
        assertEquals("IDEMPOTENCY_KEY_REUSED", assertThrows<ProductApiException> {
            service.provision(command().copy(requestedLogin = "another@example.test"))
        }.code)
        assertEquals("EMPLOYEE_IDENTITY_INVITATION_EXISTS", assertThrows<ProductApiException> {
            service.provision(command().copy(operationId = UUID.randomUUID()))
        }.code)
        providerResult = EmployeeIdentityProvisioningResult.Ready("https://identity.example.test", "synthetic-subject", identity,
            IdentityProvisioningDeliveryState.SENT, null)
        service.provision(command())
        assertEquals(2, requests.size)
        assertEquals(requests[0].invitationId, requests[1].invitationId)
        assertEquals(1, store.records.size)
    }

    @Test
    fun revokedPeopleAuthorityCannotReadOrReplayInvitation() {
        service.provision(command())
        Mockito.`when`(authority.canManagePeople(actor, org)).thenReturn(false)
        assertEquals("PEOPLE_ACCESS_DENIED", assertThrows<ProductApiException> { service.availability(employeeId, actor) }.code)
        assertEquals("PEOPLE_ACCESS_DENIED", assertThrows<ProductApiException> { service.provision(command()) }.code)
        assertEquals(1, requests.size)
    }

    @Test
    fun authorityIsRecheckedAfterAcquiringProvisioningSerialization() {
        store.afterLock = { Mockito.`when`(authority.canManagePeople(actor, org)).thenReturn(false) }
        assertEquals("PEOPLE_ACCESS_DENIED", assertThrows<ProductApiException> { service.provision(command()) }.code)
        assertTrue(store.records.isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun changedEmployeeVersionStopsProviderCall() {
        store.afterLock = { employee = employee.copy(employeeVersion = 8) }
        assertEquals("EMPLOYEE_VERSION_CONFLICT", assertThrows<ProductApiException> { service.provision(command()) }.code)
        assertTrue(store.records.isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun sourceConflictFromProviderRecoveryIsNotPresentedAsRetryableOutage() {
        providerResult = EmployeeIdentityProvisioningResult.RetryableFailure("IDENTITY_MEMBERSHIP_CONFLICT", "synthetic-subject")
        val failure = assertThrows<ProductApiException> { service.provision(command()) }
        assertEquals(HttpStatus.CONFLICT, failure.status)
        assertFalse(failure.retryable)
        assertEquals("IDENTITY_MEMBERSHIP_CONFLICT", store.records.values.single().safeFailureCode)
        assertTrue(events.isEmpty())
        Mockito.verifyNoInteractions(audit)
    }

    @Test
    fun unavailableDeliveryDoesNotCreateAnAttemptOrExposeUnrelatedModes() {
        deliveryModes = emptySet()
        assertEquals("DELIVERY_UNAVAILABLE", service.availability(employeeId, actor).status)
        assertEquals("IDENTITY_DELIVERY_NOT_CONFIGURED", assertThrows<ProductApiException> { service.provision(command()) }.code)
        assertTrue(store.records.isEmpty())
    }

    private fun command() = ProvisionEmployeeIdentityCommand(operation, employeeId, 7, "synthetic@example.test",
        IdentityProvisioningDeliveryMode.EMAIL_ACTION_LINK, actor, "synthetic-account-setup")

    private inner class Invitations : EmployeeIdentityInvitationPersistencePort {
        var account = EmployeeIdentityAccountState.NONE
        var afterLock: () -> Unit = {}
        val records = mutableMapOf<UUID, EmployeeIdentityInvitationSnapshot>()
        override fun <T> serializeProvisioning(employeeId: UUID, provision: () -> T): T { afterLock(); return provision() }
        override fun byOperationId(operationId: UUID) = records[operationId]
        override fun currentForEmployee(employeeId: UUID) = records.values.singleOrNull { it.employeeId == employeeId }
        override fun accountState(employeeId: UUID, at: Instant) = account
        override fun insertPending(invitationId: UUID, operationId: UUID, organizationId: UUID, employeeId: UUID,
            requestedLogin: String, authProvider: String, deliveryMode: IdentityProvisioningDeliveryMode, actorUserId: UUID, at: Instant) {
            records[operationId] = EmployeeIdentityInvitationSnapshot(invitationId, operationId, organizationId, employeeId,
                requestedLogin, authProvider, null, null, EmployeeIdentityInvitationState.PENDING_PROVIDER, deliveryMode,
                IdentityProvisioningDeliveryState.NOT_CONFIGURED, null, at, actorUserId, at, 1)
        }
        override fun markReady(invitationId: UUID, providerSubject: String, userIdentityId: UUID,
            deliveryState: IdentityProvisioningDeliveryState, at: Instant): Boolean {
            val record = records.values.single { it.invitationId == invitationId }
            records[record.operationId] = record.copy(state = EmployeeIdentityInvitationState.READY, providerSubject = providerSubject,
                userIdentityId = userIdentityId, deliveryState = deliveryState, updatedAt = at, version = record.version + 1)
            account = EmployeeIdentityAccountState.LINKED
            return true
        }
        override fun markRetryableFailure(invitationId: UUID, providerSubject: String?, deliveryState: IdentityProvisioningDeliveryState,
            safeFailureCode: String, at: Instant): Boolean {
            val record = records.values.single { it.invitationId == invitationId }
            records[record.operationId] = record.copy(state = EmployeeIdentityInvitationState.FAILED_RETRYABLE, providerSubject = providerSubject,
                deliveryState = deliveryState, safeFailureCode = safeFailureCode, updatedAt = at, version = record.version + 1)
            return true
        }
    }
}
