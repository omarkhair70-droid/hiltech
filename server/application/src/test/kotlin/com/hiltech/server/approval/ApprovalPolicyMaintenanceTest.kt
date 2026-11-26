package com.hiltech.server.approval

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecution
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.security.AuthorizationProjectionIntentWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ApprovalPolicyMaintenanceTest {
    private val actor = UUID.randomUUID()
    private val org = UUID.randomUUID()
    private val originalReviewer = UUID.randomUUID()
    private val requestId = UUID.randomUUID()
    private val operationId = UUID.randomUUID()
    private val now = Instant.parse("2026-11-25T12:00:00Z")
    private val scope = ApprovalPolicyMaintenanceScope(org, UUID.randomUUID(), "PROCUREMENT_PO_REVIEW", 3, 2,
        "PROCUREMENT_PO_CANDIDATE", 1)
    private val record = ApprovalDecisionContext(requestId, org, scope.subjectType, UUID.randomUUID(), 1,
        scope.policyKey, scope.policyRevision, UUID.randomUUID(), ApprovalRequestState.PENDING, 1,
        UUID.randomUUID(), "PROCUREMENT_PO_REVIEWER", UUID.randomUUID(), ApprovalPrincipalType.USER,
        originalReviewer, "ASSIGNED")
    private val persistence = Mockito.mock(ApprovalPersistencePort::class.java)
    private val store = Mockito.mock(ApprovalPolicyMaintenancePersistencePort::class.java)
    private val setupAuthority = Mockito.mock(ApprovalPolicyMaintenanceAuthorityPort::class.java)
    private val decisionAuthority = Mockito.mock(ApprovalAuthorizationPort::class.java)
    private val guard = Mockito.mock(ApprovalSubjectVersionGuard::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val projections = Mockito.mock(AuthorizationProjectionIntentWriter::class.java)
    private val events = Mockito.mock(ApplicationEventPublisher::class.java)
    private val commands = Commands()
    private val engine = ApprovalEngineService(persistence, commands, projections, decisionAuthority, guard, audit, events,
        Clock.fixed(now, ZoneOffset.UTC), store, setupAuthority)

    @Test
    fun policyAdministratorEndsRevokedReviewersPendingAssignmentWithoutApprovingItsSource() {
        allow()
        val first = engine.supersedePolicyRequests(actor, operationId, scope, "The assigned reviewer left the company", "synthetic-policy-maintenance")
        assertEquals(1, first.supersededCount)
        val retry = engine.supersedePolicyRequests(actor, operationId, scope, "The assigned reviewer left the company", "synthetic-policy-maintenance")
        assertTrue(retry.replayed)
        Mockito.verify(persistence, Mockito.times(1)).supersede(record, now)
        Mockito.verifyNoInteractions(decisionAuthority, guard)
        assertEquals(0, Mockito.mockingDetails(persistence).invocations.count { it.method.name == "applyDecision" })
        assertEquals(1, Mockito.mockingDetails(projections).invocations.size)
        assertEquals(1, Mockito.mockingDetails(audit).invocations.size)
        assertEquals(1, Mockito.mockingDetails(events).invocations.size)
    }

    @Test
    fun existingReviewerAuthorityDoesNotConferPolicyMaintenanceAuthority() {
        Mockito.`when`(persistence.isActiveOrganizationMember(org, actor, now)).thenReturn(true)
        assertEquals("APPROVAL_POLICY_ADMINISTRATION_DENIED", assertThrows<ProductApiException> {
            engine.supersedePolicyRequests(actor, operationId, scope, "Change reviewer", "synthetic")
        }.code)
        assertEquals(0, commands.calls)
        Mockito.verifyNoInteractions(store, audit, projections, events)
    }

    @Test
    fun policyVersionAndPendingCountRacesFailBeforeAnyRequestIsEnded() {
        allow()
        Mockito.`when`(store.lockPolicy(scope)).thenReturn(false)
        assertEquals("APPROVAL_POLICY_CHANGED", assertThrows<ProductApiException> {
            engine.supersedePolicyRequests(actor, operationId, scope, "Change reviewer", "synthetic")
        }.code)
        Mockito.`when`(store.lockPolicy(scope)).thenReturn(true)
        Mockito.`when`(store.pendingRequestIds(scope)).thenReturn(emptyList())
        assertEquals("APPROVAL_POLICY_PENDING_CHANGED", assertThrows<ProductApiException> {
            engine.supersedePolicyRequests(actor, operationId, scope, "Change reviewer", "synthetic")
        }.code)
        Mockito.verifyNoInteractions(audit, projections, events)
    }

    @Test
    fun anotherPolicyOrSubjectCannotBeIncludedInTheMaintenanceBatch() {
        allow()
        Mockito.`when`(persistence.loadDecisionContextForUpdate(requestId)).thenReturn(record.copy(subjectType = "OTHER_SUBJECT"))
        assertEquals("APPROVAL_POLICY_PENDING_CHANGED", assertThrows<ProductApiException> {
            engine.supersedePolicyRequests(actor, operationId, scope, "Change reviewer", "synthetic")
        }.code)
        Mockito.verifyNoInteractions(audit, projections, events)
    }

    @Test
    fun maintenanceRechecksAuthorityAfterLockAndBeforeReplaying() {
        allow()
        Mockito.`when`(setupAuthority.canSupersedePending(actor, scope)).thenReturn(true, false)
        assertEquals("APPROVAL_POLICY_ADMINISTRATION_DENIED", assertThrows<ProductApiException> {
            engine.supersedePolicyRequests(actor, operationId, scope, "Change reviewer", "synthetic")
        }.code)
        Mockito.verifyNoInteractions(audit, projections, events)
    }

    @Test
    fun reasonIsRequiredAndChangingTheSavedCommandCannotProduceAnotherTransition() {
        allow()
        assertEquals("APPROVAL_POLICY_MAINTENANCE_INVALID", assertThrows<ProductApiException> {
            engine.supersedePolicyRequests(actor, operationId, scope, " ", "synthetic")
        }.code)
        engine.supersedePolicyRequests(actor, operationId, scope, "Change reviewer", "synthetic")
        assertThrows<IllegalArgumentException> {
            engine.supersedePolicyRequests(actor, operationId, scope, "Another reason", "synthetic")
        }
        Mockito.verify(persistence, Mockito.times(1)).supersede(record, now)
    }

    private fun allow() {
        Mockito.`when`(persistence.isActiveOrganizationMember(org, actor, now)).thenReturn(true)
        Mockito.`when`(setupAuthority.canSupersedePending(actor, scope)).thenReturn(true)
        Mockito.`when`(store.lockPolicy(scope)).thenReturn(true)
        Mockito.`when`(store.pendingRequestIds(scope)).thenReturn(listOf(requestId))
        Mockito.`when`(persistence.loadDecisionContextForUpdate(requestId)).thenReturn(record)
    }

    private class Commands : IdempotentCommandExecutor {
        var calls = 0
        private val receipts = mutableMapOf<UUID, Pair<IdempotentCommandSpec, IdempotentCommandOutcome>>()
        override fun execute(spec: IdempotentCommandSpec, action: () -> IdempotentCommandOutcome): IdempotentCommandExecution {
            calls += 1
            receipts[spec.operationId]?.let { (previous, result) ->
                require(previous == spec)
                return IdempotentCommandExecution(result, true)
            }
            val result = action()
            receipts[spec.operationId] = spec to result
            return IdempotentCommandExecution(result, false)
        }
    }
}
