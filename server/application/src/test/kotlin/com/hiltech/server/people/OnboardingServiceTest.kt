package com.hiltech.server.people

import com.hiltech.server.audit.AuditEventWriter
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
import org.springframework.http.HttpStatus
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class OnboardingServiceTest {
    private val persistence = Mockito.mock(OnboardingPersistencePort::class.java)
    private val authority = Mockito.mock(PeopleAuthorizationPort::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val events = mutableListOf<Any>()
    private val executor = ReplayExecutor()
    private val now = Instant.parse("2026-11-25T12:00:00Z")
    private val actor = UUID.randomUUID()
    private val org = UUID.randomUUID()
    private val employeeId = UUID.randomUUID()
    private val policyId = UUID.randomUUID()
    private val operationId = UUID.randomUUID()
    private val caseId = UUID.nameUUIDFromBytes("onboarding-case:$operationId".toByteArray(StandardCharsets.UTF_8))
    private var employee = OnboardingEmployeeContext(employeeId, org, UUID.randomUUID(), "EMP-SYNTHETIC",
        EmployeeState.PREBOARDING, 1, "Synthetic employee", null, null, 1)
    private var policy = OnboardingPolicyContext(policyId, "ORGANIZATION", org, "ACTIVE", "SYNTHETIC", "Synthetic policy", 1)
    private var record: OnboardingCaseRecord? = null
    private var policies = listOf(policy)
    private var definitions = emptyList<OnboardingRequirementDefinition>()
    private val service = OnboardingService(persistence, authority, executor, audit,
        ApplicationEventPublisher { events += it }, Clock.fixed(now, ZoneOffset.UTC))

    init {
        Mockito.`when`(persistence.employee(employeeId)).thenAnswer { employee }
        Mockito.`when`(authority.canManagePeople(actor, org)).thenReturn(true)
        Mockito.`when`(persistence.policy(policyId)).thenAnswer { policy }
        Mockito.`when`(persistence.activePolicies(org)).thenAnswer { policies }
        Mockito.`when`(persistence.policyRequirements(policyId)).thenAnswer { definitions }
        Mockito.`when`(persistence.latestCaseForEmployee(employeeId)).thenAnswer { record }
        Mockito.`when`(persistence.openCaseForEmployee(employeeId)).thenAnswer { record?.takeIf { it.state == OnboardingCaseState.OPEN } }
        Mockito.`when`(persistence.loadCase(caseId)).thenAnswer { record }
        Mockito.`when`(persistence.ownEmployee(actor, org, now)).thenAnswer { employee }
        Mockito.doAnswer {
            record = caseRecord()
            null
        }.`when`(persistence).insertCase(caseId, org, employeeId, policyId, actor, now)
    }

    @Test
    fun unauthorizedCallerCannotDiscoverEmployeePoliciesOrCase() {
        Mockito.`when`(authority.canManagePeople(actor, org)).thenReturn(false)
        assertEquals("PEOPLE_ACCESS_DENIED", assertThrows<ProductApiException> { service.workspace(actor, employeeId) }.code)
        assertTrue(Mockito.mockingDetails(persistence).invocations.none {
            it.method.name in setOf("activePolicies", "latestCaseForEmployee", "policyRequirements")
        })
    }

    @Test
    fun choicesContainOnlyActivePoliciesApplicableToThisOrganization() {
        val system = policy.copy(configRevisionId = UUID.randomUUID(), scopeType = "SYSTEM", scopeOrganizationId = null)
        policies = listOf(policy, system, policy.copy(configRevisionId = UUID.randomUUID(), lifecycleState = "SUPERSEDED"),
            policy.copy(configRevisionId = UUID.randomUUID(), scopeOrganizationId = UUID.randomUUID()),
            policy.copy(configRevisionId = UUID.randomUUID(), scopeType = "SYSTEM"))
        val view = service.workspace(actor, employeeId)
        assertEquals(listOf(policy, system), view.policies)
        assertEquals("AVAILABLE", view.startAvailability)
        assertEquals(employeeId, view.employeeId)
        assertEquals(org, view.organizationId)
    }

    @Test
    fun noConfiguredPolicyDoesNotProduceAnInventedDefault() {
        policies = emptyList()
        val view = service.workspace(actor, employeeId)
        assertEquals("POLICY_NOT_CONFIGURED", view.startAvailability)
        assertTrue(view.policies.isEmpty())
        assertEquals(null, view.currentCase)
        Mockito.verifyNoInteractions(audit)
    }

    @Test
    fun resolutionChoicesFollowPolicyAndAreNeverExposedAsEmployeeAuthority() {
        record = caseRecord()
        definitions = listOf(definition("MANUAL", OnboardingRequirementType.MANUAL_CONFIRMATION),
            definition("DOCUMENT", OnboardingRequirementType.EMPLOYEE_DOCUMENT, waiver = true),
            definition("CERTIFICATE", OnboardingRequirementType.CERTIFICATION))
        Mockito.`when`(persistence.documentFacts(employeeId, "DOCUMENT")).thenReturn(emptyList())
        Mockito.`when`(persistence.certificationFacts(employeeId, "CERTIFICATE")).thenReturn(emptyList())
        val admin = requireNotNull(service.workspace(actor, employeeId).currentCase)
        assertEquals(listOf(OnboardingManualResolutionType.SATISFIED), admin.requirements[0].allowedResolutions)
        assertEquals(listOf(OnboardingManualResolutionType.WAIVED), admin.requirements[1].allowedResolutions)
        assertTrue(admin.requirements[2].allowedResolutions.isEmpty())
        assertFalse(admin.canActivate)
        val own = service.own(actor, org)
        assertTrue(own.requirements.all { it.allowedResolutions.isEmpty() })
        assertFalse(own.canActivate)
    }

    @Test
    fun completedCaseOffersNeitherRestartNorFurtherResolution() {
        record = caseRecord().copy(state = OnboardingCaseState.ACTIVATED, activatedAt = now, activatedByUserId = actor, version = 2)
        employee = employee.copy(employeeState = EmployeeState.ACTIVE, employeeVersion = 2)
        definitions = listOf(definition("MANUAL", OnboardingRequirementType.MANUAL_CONFIRMATION, waiver = true))
        val view = service.workspace(actor, employeeId)
        assertEquals("CASE_EXISTS", view.startAvailability)
        assertTrue(view.policies.isEmpty())
        assertTrue(requireNotNull(view.currentCase).requirements.single().allowedResolutions.isEmpty())
        assertFalse(requireNotNull(view.currentCase).canActivate)
    }

    @Test
    fun successfulStartCanReplayAfterEmployeeActivationAndPolicySupersession() {
        val command = start()
        assertFalse(service.start(command).replayed)
        employee = employee.copy(employeeState = EmployeeState.ACTIVE, employeeVersion = 4)
        policy = policy.copy(lifecycleState = "SUPERSEDED")
        record = requireNotNull(record).copy(state = OnboardingCaseState.ACTIVATED, version = 3,
            activatedAt = now, activatedByUserId = actor)
        val replay = service.start(command)
        assertTrue(replay.replayed)
        assertEquals(caseId, replay.onboarding.caseId)
        assertEquals(EmployeeState.ACTIVE, replay.onboarding.employeeState)
        assertEquals(1, Mockito.mockingDetails(persistence).invocations.count { it.method.name == "insertCase" })
        assertEquals(1, events.size)
    }

    @Test
    fun revokedCurrentAuthorityCannotReadEvenAnExistingSuccessfulReceipt() {
        service.start(start())
        Mockito.`when`(authority.canManagePeople(actor, org)).thenReturn(false)
        val before = executor.calls
        assertEquals("PEOPLE_ACCESS_DENIED", assertThrows<ProductApiException> { service.start(start()) }.code)
        assertEquals(before, executor.calls)
    }

    @Test
    fun otherOrganizationAdministratorCannotInspectThisEmployeeReceipt() {
        service.start(start())
        val otherActor = UUID.randomUUID()
        Mockito.`when`(authority.canManagePeople(otherActor, UUID.randomUUID())).thenReturn(true)
        assertEquals("PEOPLE_ACCESS_DENIED", assertThrows<ProductApiException> {
            service.start(start().copy(actorUserId = otherActor))
        }.code)
    }

    @Test
    fun reusedOperationWithChangedPayloadIsRejectedBeforeChangedLifecyclePreconditions() {
        service.start(start())
        employee = employee.copy(employeeState = EmployeeState.ACTIVE, employeeVersion = 2)
        assertEquals("IDEMPOTENCY_KEY_REUSED", assertThrows<ProductApiException> {
            service.start(start().copy(baseEmployeeVersion = 2))
        }.code)
        assertEquals(1, events.size)
    }

    @Test
    fun currentAuthorityIsCheckedAgainBeforeTheFirstWrite() {
        Mockito.`when`(authority.canManagePeople(actor, org)).thenReturn(true, false)
        assertEquals("PEOPLE_ACCESS_DENIED", assertThrows<ProductApiException> { service.start(start()) }.code)
        assertTrue(Mockito.mockingDetails(persistence).invocations.none { it.method.name == "insertCase" })
        Mockito.verifyNoInteractions(audit)
    }

    @Test
    fun activationRecomputesSourceBlockersAfterAnEarlierReadyPreview() {
        record = caseRecord()
        definitions = listOf(definition("MANUAL", OnboardingRequirementType.MANUAL_CONFIRMATION))
        var manual: OnboardingManualResolution? = OnboardingManualResolution(UUID.randomUUID(), caseId, "MANUAL",
            OnboardingManualResolutionType.SATISFIED, null, now, actor, 1)
        Mockito.`when`(persistence.manualResolution(caseId, "MANUAL")).thenAnswer { manual }
        assertTrue(requireNotNull(service.workspace(actor, employeeId).currentCase).canActivate)
        manual = null
        assertEquals("ONBOARDING_BLOCKERS_REMAIN", assertThrows<ProductApiException> {
            service.activate(ActivateEmployeeOnboardingCommand(UUID.randomUUID(), caseId, 1, 1, actor, "synthetic"))
        }.code)
        assertTrue(Mockito.mockingDetails(persistence).invocations.none { it.method.name in setOf("activateEmployee", "activateCase") })
    }

    @Test
    fun sourceBackedRequirementCannotBeManuallySatisfiedAndWaiverNeedsItsReason() {
        record = caseRecord()
        definitions = listOf(definition("DOCUMENT", OnboardingRequirementType.EMPLOYEE_DOCUMENT, waiver = true))
        val command = ResolveOnboardingRequirementCommand(UUID.randomUUID(), caseId, 1, "DOCUMENT",
            OnboardingManualResolutionType.SATISFIED, null, actor, "synthetic")
        assertEquals("ONBOARDING_MANUAL_RESOLUTION_NOT_ALLOWED", assertThrows<ProductApiException> { service.resolveRequirement(command) }.code)
        assertEquals("ONBOARDING_WAIVER_REASON_REQUIRED", assertThrows<ProductApiException> {
            service.resolveRequirement(command.copy(resolution = OnboardingManualResolutionType.WAIVED))
        }.code)
        assertEquals(0, executor.calls)
    }

    private fun start() = StartOnboardingCommand(operationId, employeeId, 1, policyId, actor, "synthetic")
    private fun caseRecord() = OnboardingCaseRecord(caseId, org, employeeId, policyId, OnboardingCaseState.OPEN, now, actor, null, null, 1)
    private fun definition(key: String, type: OnboardingRequirementType, waiver: Boolean = false) = OnboardingRequirementDefinition(
        UUID.randomUUID(), policyId, key, type, key, OnboardingResponsibility.HILTECH,
        blocking = true, waiverAllowed = waiver, selfServiceVisible = true, employeeMaySubmit = false,
        evidenceRequired = false, profileFieldCode = null,
        documentTypeCode = key.takeIf { type == OnboardingRequirementType.EMPLOYEE_DOCUMENT },
        certificationTypeCode = key.takeIf { type == OnboardingRequirementType.CERTIFICATION },
        manualConfirmationCode = key.takeIf { type == OnboardingRequirementType.MANUAL_CONFIRMATION }, sortOrder = 1,
    )

    private class ReplayExecutor : IdempotentCommandExecutor {
        var calls = 0
        private val receipts = mutableMapOf<UUID, Pair<IdempotentCommandSpec, IdempotentCommandOutcome>>()
        override fun execute(spec: IdempotentCommandSpec, action: () -> IdempotentCommandOutcome): IdempotentCommandExecution {
            calls++
            receipts[spec.operationId]?.let { (original, outcome) ->
                if (original.actorUserId != spec.actorUserId || original.commandType != spec.commandType ||
                    original.targetId != spec.targetId || original.requestFingerprint != spec.requestFingerprint) {
                    throw ProductApiException("IDEMPOTENCY_KEY_REUSED", "Synthetic key reuse", HttpStatus.CONFLICT)
                }
                return IdempotentCommandExecution(outcome, true)
            }
            val outcome = action()
            receipts[spec.operationId] = spec to outcome
            return IdempotentCommandExecution(outcome, false)
        }
    }
}
