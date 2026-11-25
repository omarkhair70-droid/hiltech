package com.hiltech.server.approval

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.payroll.Payroll9aAuthorityPort
import com.hiltech.server.payroll.PayrollReviewDecisionConstraint
import com.hiltech.server.payroll.PayrollRunPolicyConfiguration
import com.hiltech.server.platform.IdempotentCommandExecution
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.procurement.ProcurementCandidateReviewEligibilityPort
import com.hiltech.server.procurement.ProcurementReviewPolicyConfiguration
import com.hiltech.server.procurement.PurchaseOrderCandidatePersistence
import com.hiltech.server.procurement.PurchaseOrderCandidateRecord
import com.hiltech.server.procurement.PurchaseOrderReviewDecisionConstraint
import com.hiltech.server.procurement.PurchaseOrderReviewPersistence
import com.hiltech.server.security.AuthorizationProjectionIntentWriter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Deliberately grants generic assignment/FGA access, so only source-owned invariants can stop bypasses. */
class ApprovalSourceDecisionConstraintTest {
    @Test fun genericPurchaseApproveNeedsTheExactDisplayedProofEvenWhenFgaAllowsIt() {
        val fixture = Fixture("PROCUREMENT_PO_CANDIDATE", "PROCUREMENT_PO_REVIEW", "PROCUREMENT_PO_REVIEWER")
        val owner = fixture.purchaseConstraint()
        fixture.rejects(owner)
        fixture.opened = true
        assertEquals(ApprovalRequestState.APPROVED, fixture.decide(owner).state)
        assertEquals(1, fixture.decisions())
    }

    @Test fun genericPurchaseRejectionRequiresIndependenceButNotAnUnreadableDocument() {
        val fixture = Fixture("PROCUREMENT_PO_CANDIDATE", "PROCUREMENT_PO_REVIEW", "PROCUREMENT_PO_REVIEWER")
        assertEquals(ApprovalRequestState.REJECTED, fixture.decide(fixture.purchaseConstraint(), ApprovalDecisionType.REJECT).state)
        assertFalse(fixture.opened)
        val self = Fixture("PROCUREMENT_PO_CANDIDATE", "PROCUREMENT_PO_REVIEW", "PROCUREMENT_PO_REVIEWER")
        self.maker = self.actor
        self.rejects(self.purchaseConstraint(), ApprovalDecisionType.REJECT)
    }

    @Test fun revokedPurchaseReviewerOrDifferentCurrentPolicyCannotUseGenericDecision() {
        val revoked = Fixture("PROCUREMENT_PO_CANDIDATE", "PROCUREMENT_PO_REVIEW", "PROCUREMENT_PO_REVIEWER")
        revoked.opened = true; revoked.eligible = false
        revoked.rejects(revoked.purchaseConstraint())
        val changed = Fixture("PROCUREMENT_PO_CANDIDATE", "PROCUREMENT_PO_REVIEW", "PROCUREMENT_PO_REVIEWER")
        changed.opened = true; changed.policy = changed.policy.copy(versionNumber = 2)
        changed.rejects(changed.purchaseConstraint())
    }

    @Test fun sourceStalenessSupersedesWithoutTurningMissingProofIntoAnApproval() {
        val fixture = Fixture("PROCUREMENT_PO_CANDIDATE", "PROCUREMENT_PO_REVIEW", "PROCUREMENT_PO_REVIEWER")
        fixture.current = false
        assertEquals("APPROVAL_SUPERSEDED", assertThrows<ProductApiException> {
            fixture.decide(fixture.purchaseConstraint())
        }.code)
        assertEquals(0, fixture.decisions())
        Mockito.verify(fixture.persistence).supersede(fixture.context, fixture.now)
    }

    @Test fun genericPayrollSourceAndRunCannotBeApprovedByTheirMakerOrRequester() {
        for (type in listOf("PAYROLL_SOURCE_RECORD", "PAYROLL_RUN_VERSION")) {
            val key = if (type == "PAYROLL_SOURCE_RECORD") "PAYROLL_SOURCE_REVIEW" else "PAYROLL_RUN_REVIEW"
            val authority = if (type == "PAYROLL_SOURCE_RECORD") "PAYROLL_SOURCE_REVIEWER" else "PAYROLL_RUN_REVIEWER"
            val maker = Fixture(type, key, authority)
            maker.maker = maker.actor
            maker.rejects(maker.payrollConstraint())
            val requester = Fixture(type, key, authority)
            requester.context = requester.context.copy(requesterUserId = requester.actor)
            requester.rejects(requester.payrollConstraint())
        }
    }

    @Test fun genericPayrollReviewRequiresCurrentDomainReviewAuthorityAndPinnedPolicy() {
        for (type in listOf("PAYROLL_SOURCE_RECORD", "PAYROLL_RUN_VERSION")) {
            val key = if (type == "PAYROLL_SOURCE_RECORD") "PAYROLL_SOURCE_REVIEW" else "PAYROLL_RUN_REVIEW"
            val authority = if (type == "PAYROLL_SOURCE_RECORD") "PAYROLL_SOURCE_REVIEWER" else "PAYROLL_RUN_REVIEWER"
            val fixture = Fixture(type, key, authority)
            fixture.eligible = false
            val constraint = fixture.payrollConstraint()
            fixture.rejects(constraint)
            fixture.eligible = true; fixture.policy = fixture.policy.copy(versionNumber = 2)
            fixture.rejects(constraint)
            fixture.policy = fixture.policy.copy(versionNumber = 1)
            assertEquals(ApprovalRequestState.APPROVED, fixture.decide(constraint).state)
        }
    }

    @Test fun sourceWorkflowCanCommitTheRecordedSupersededOutcomeWithoutApplyingABusinessDecision() {
        val fixture = Fixture("PROCUREMENT_PO_CANDIDATE", "PROCUREMENT_PO_REVIEW", "PROCUREMENT_PO_REVIEWER")
        fixture.current = false
        assertEquals(ApprovalRequestState.SUPERSEDED, fixture.decide(fixture.purchaseConstraint(), sourceOwned = true).state)
        assertEquals(0, fixture.decisions())
        Mockito.verify(fixture.persistence).supersede(fixture.context, fixture.now)
    }

    private class Fixture(type: String, key: String, authorityKey: String) {
        val actor = UUID.randomUUID()
        val org = UUID.randomUUID()
        var maker = UUID.randomUUID()
        val now = Instant.parse("2026-11-25T12:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        var current = true
        var eligible = true
        var opened = false
        var context = ApprovalDecisionContext(UUID.randomUUID(), org, type, UUID.randomUUID(), 1, key, 1,
            UUID.randomUUID(), ApprovalRequestState.PENDING, 1, UUID.randomUUID(), authorityKey, UUID.randomUUID(),
            ApprovalPrincipalType.USER, actor, "ASSIGNED")
        var policy = ApprovalPolicySnapshot(org, key, 1, true, ApprovalPolicyMode.SINGLE, authorityKey, true)
        val persistence = Mockito.mock(ApprovalPersistencePort::class.java)
        val auth = Mockito.mock(ApprovalAuthorizationPort::class.java)
        val binding = ApprovalAuthorityBindingSnapshot(UUID.randomUUID(), org, authorityKey, ApprovalPrincipalType.USER,
            actor, now.minusSeconds(60), null, 1)

        init {
            Mockito.`when`(persistence.loadDecisionContextForUpdate(context.requestId)).thenAnswer { context }
            Mockito.`when`(persistence.actorMatchesAssignment(Mockito.eq(actor) ?: actor,
                Mockito.any(ApprovalDecisionContext::class.java) ?: context, Mockito.eq(now) ?: now)).thenReturn(true)
            Mockito.`when`(persistence.resolveCurrentAuthorityForSubject(ApprovalSubjectRef(org, type, context.subjectId, 1), authorityKey, now)).thenReturn(binding)
            Mockito.`when`(persistence.actorMatchesAuthority(actor, binding, now)).thenReturn(true)
            Mockito.`when`(persistence.loadActivePolicy(org, key, now)).thenAnswer { policy }
            Mockito.`when`(auth.canDecide(actor, context.requestId, ApprovalPrincipalType.USER, actor)).thenReturn(true)
        }

        fun purchaseConstraint(): ApprovalDecisionConstraint {
            val candidates = Mockito.mock(PurchaseOrderCandidatePersistence::class.java)
            val proof = Mockito.mock(PurchaseOrderReviewPersistence::class.java)
            val record = PurchaseOrderCandidateRecord(context.subjectId, UUID.randomUUID(), org, UUID.randomUUID(), UUID.randomUUID(),
                1, UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ONE, "EA", "EGP", BigDecimal.TEN,
                BigDecimal.ZERO, BigDecimal.TEN, "a".repeat(64), "Synthetic quotation selection", maker, now, "synthetic")
            Mockito.`when`(candidates.byId(context.subjectId)).thenAnswer { record.copy(proposedBy = maker) }
            Mockito.`when`(proof.opened(Mockito.eq(actor) ?: actor, Mockito.any(PurchaseOrderCandidateRecord::class.java) ?: record,
                Mockito.eq(context.requestId) ?: context.requestId, Mockito.eq(1L), Mockito.eq(1))).thenAnswer { opened }
            return PurchaseOrderReviewDecisionConstraint(candidates, ProcurementCandidateReviewEligibilityPort { _, _ -> eligible },
                persistence, ProcurementReviewPolicyConfiguration(""), proof, clock)
        }

        fun payrollConstraint(): ApprovalDecisionConstraint {
            val jdbc = Mockito.mock(JdbcTemplate::class.java)
            Mockito.`when`(jdbc.query(Mockito.anyString(), Mockito.any<RowMapper<UUID>>() ?: RowMapper { _, _ -> maker },
                Mockito.eq(context.subjectId) ?: context.subjectId, Mockito.eq(org) ?: org)).thenAnswer { listOf(maker) }
            val authority = object : Payroll9aAuthorityPort {
                override fun canRead(actorId: UUID, organizationId: UUID) = true
                override fun canPrepare(actorId: UUID, organizationId: UUID) = true
                override fun canReview(actorId: UUID, organizationId: UUID) = eligible
            }
            return PayrollReviewDecisionConstraint(jdbc, authority, persistence, PayrollRunPolicyConfiguration(""), clock)
        }

        fun decide(constraint: ApprovalDecisionConstraint, decision: ApprovalDecisionType = ApprovalDecisionType.APPROVE,
                   sourceOwned: Boolean = false): ApprovalDecisionResult {
            val engine = ApprovalEngineService(persistence, object : IdempotentCommandExecutor {
                override fun execute(spec: IdempotentCommandSpec, action: () -> IdempotentCommandOutcome) = IdempotentCommandExecution(action(), false)
            }, AuthorizationProjectionIntentWriter {}, auth, ApprovalSubjectVersionGuard { current }, AuditEventWriter.NOOP,
                ApplicationEventPublisher {}, clock, decisionConstraints = listOf(constraint))
            return if (sourceOwned) engine.decideForSourceWorkflow(actor, UUID.randomUUID(), context.requestId, decision, "Synthetic independent review", "synthetic")
                else engine.decide(actor, UUID.randomUUID(), context.requestId, decision, "Synthetic independent review", "synthetic")
        }

        fun decisions() = Mockito.mockingDetails(persistence).invocations.count { it.method.name == "applyDecision" }
        fun rejects(constraint: ApprovalDecisionConstraint, decision: ApprovalDecisionType = ApprovalDecisionType.APPROVE) {
            assertEquals("APPROVAL_SOURCE_REVIEW_NOT_ALLOWED", assertThrows<ProductApiException> { decide(constraint, decision) }.code)
            assertEquals(0, decisions())
        }
    }
}
