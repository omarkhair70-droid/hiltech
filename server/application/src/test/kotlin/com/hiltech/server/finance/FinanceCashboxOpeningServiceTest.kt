package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalAuthorityBindingSnapshot
import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.ApprovalPolicySnapshot
import com.hiltech.server.approval.ApprovalPrincipalType
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.approval.ApprovalSubjectStatus
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.operationalaccess.OperationalAuthorityOrigin
import com.hiltech.server.operationalaccess.OperationalAuthorityPort
import com.hiltech.server.operationalaccess.OperationalAuthorityProof
import com.hiltech.server.operationalaccess.OperationalResponsibility
import com.hiltech.server.platform.IdempotencyKeyContract
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
import org.springframework.mock.env.MockEnvironment
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class FinanceCashboxOpeningServiceTest {
    private val at = Instant.parse("2026-11-25T12:00:00Z")
    private val org = UUID.randomUUID()
    private val maker = UUID.randomUUID()
    private val custodian = UUID.randomUUID()
    private val reviewer = UUID.randomUUID()
    private val source = MemoryCashboxes()
    private val approvals = Mockito.mock(ApprovalPersistencePort::class.java)
    private val operational = Mockito.mock(OperationalAuthorityPort::class.java)
    private val engine = Mockito.mock(ApprovalEngineService::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val commands = Commands()
    private val policyKey = "FINANCE_CASHBOX_OPENING_REVIEW"
    private val binding = ApprovalAuthorityBindingSnapshot(UUID.randomUUID(), org, "CASHBOX_REVIEWER", ApprovalPrincipalType.USER,
        reviewer, at.minusSeconds(60), null, 1)
    private val policy = ApprovalPolicySnapshot(org, policyKey, 1, true, ApprovalPolicyMode.SINGLE, binding.authorityKey, true)
    private val reviewVersion = cashboxSourceVersion("CASHBOX_REVIEW_V1", org, policyKey, 1, binding.id, 1L)
    private var currentSource = true
    private var currentGrant = true
    private val subject = Mockito.mock(FinanceCashboxOpeningApprovalSubject::class.java) { call -> when (call.method.name) {
        "enabled" -> true
        "policyKey" -> policyKey
        "current" -> source.rows[call.arguments[1] as UUID]?.takeIf {
            currentSource && it.organizationId == call.arguments[0] && !source.active.containsKey(it.id)
        }?.let { FinanceCashboxOpeningVerified(it.id, it.organizationId, it.code, it.currency, it.custodian, it.companyReference,
            it.subjectVersion, it.policyKey, 1, it.preparedBy) }
        else -> null
    } }
    private val service = FinanceCashboxOpeningService(source, subject, approvals, engine, commands, audit,
        MockEnvironment(), Clock.fixed(at, ZoneOffset.UTC), operational)
    init {
        listOf(maker, custodian, reviewer).forEach { Mockito.`when`(approvals.isActiveOrganizationMember(org, it, at)).thenReturn(true) }
        Mockito.`when`(approvals.loadActivePolicy(org, policyKey, at)).thenReturn(policy)
        Mockito.`when`(approvals.resolveCurrentAuthority(org, binding.authorityKey, at)).thenReturn(binding)
        Mockito.`when`(approvals.actorMatchesAuthority(reviewer, binding, at)).thenReturn(true)
        Mockito.`when`(operational.current(maker, org, OperationalResponsibility.FINANCE_CASH_PREPARE, at)).thenAnswer {
            if (currentGrant) OperationalAuthorityProof(UUID.randomUUID(), org, "FINANCE_CASH_PREPARE", 1, OperationalAuthorityOrigin.OPERATIONAL) else null
        }
        source.choices += FinanceCashboxCustodianChoice(custodian, UUID.randomUUID(), "EMP-CUSTODY", "Synthetic custodian", 5)
    }

    @Test fun directoryIncludesAuthorizedPreparerAndExcludesReviewer() {
        source.choices += FinanceCashboxCustodianChoice(maker, UUID.randomUUID(), "EMP-PREP", "Synthetic preparer", 2)
        source.choices += FinanceCashboxCustodianChoice(reviewer, UUID.randomUUID(), "EMP-REVIEW", "Synthetic reviewer", 3)
        val choices = service.custodians(maker, org, "Synthetic")
        assertEquals(listOf(custodian, maker), choices.items.map { it.identityId })
        assertEquals(reviewVersion, choices.reviewSourceVersion)
        assertEquals("Synthetic", source.lastDirectoryQuery)
    }

    @Test fun unauthorizedAccountCannotSearchEmployeesOrReadCashHistory() {
        val stranger = UUID.randomUUID()
        assertEquals("FINANCE_CASHBOX_NOT_AUTHORIZED", assertThrows<ProductApiException> { service.custodians(stranger, org) }.code)
        assertEquals("FINANCE_CASHBOX_NOT_AUTHORIZED", assertThrows<ProductApiException> { service.workbench(stranger, org) }.code)
        assertEquals(0, source.reads)
    }

    @Test fun missingReviewSetupIsVisibleWithoutHidingTheAuthorizedWorkbench() {
        Mockito.`when`(approvals.loadActivePolicy(org, policyKey, at)).thenReturn(null)
        val current = service.workbench(maker, org)
        assertTrue(current.canPrepare)
        assertFalse(current.reviewReady)
        assertEquals("FINANCE_CASHBOX_REVIEW_SETUP_REQUIRED", current.preparationIssueCode)
        assertEquals(0, source.allocations)
    }

    @Test fun automaticCodeIsAllocatedOnceInsideTheRecordedCommandAndReturnedOnReplay() {
        val input = input()
        val first = service.prepare(maker, org, input, "synthetic-cashbox")
        val replay = service.prepare(maker, org, input, "synthetic-cashbox")
        assertEquals("CBX-000001", first.cashboxCode)
        assertEquals(first.cashboxCode, replay.cashboxCode)
        assertEquals("Synthetic custodian", first.custodianDisplayName)
        assertEquals("0.000000", first.openingBalance)
        assertTrue(replay.replayed)
        assertEquals(input.operationId, replay.confirmedOperationId)
        assertEquals(1, source.allocations)
        assertEquals(1, source.rows.size)
        assertEquals(1, Mockito.mockingDetails(audit).invocations.size)
    }

    @Test fun directoryVersionAndReviewVersionAreRecheckedBeforeAllocatingCode() {
        assertEquals("FINANCE_CASHBOX_CUSTODIAN_CHANGED", assertThrows<ProductApiException> {
            service.prepare(maker, org, input().copy(expectedCustodianVersion = 4), "synthetic-cashbox")
        }.code)
        assertEquals("FINANCE_CASHBOX_REVIEW_CHANGED", assertThrows<ProductApiException> {
            service.prepare(maker, org, input().copy(expectedReviewVersion = reviewVersion + 1), "synthetic-cashbox")
        }.code)
        assertEquals(0, source.allocations)
    }

    @Test fun currentAuthorityIsRequiredAgainInsideTheIdempotentTransaction() {
        commands.beforeAction = { currentGrant = false }
        assertEquals("FINANCE_CASHBOX_NOT_AUTHORIZED", assertThrows<ProductApiException> {
            service.prepare(maker, org, input(), "synthetic-cashbox")
        }.code)
        assertEquals(1, source.locks)
        assertEquals(0, source.allocations)
        assertTrue(source.rows.isEmpty())
    }

    @Test fun legacyManualCodeAndFingerprintSurviveReviewerRotation() {
        val request = input().copy(cashboxCode = "HQ-CASH-2026", expectedCustodianVersion = null, expectedReviewVersion = null)
        val first = service.prepare(maker, org, request, "synthetic-cashbox")
        val saved = source.rows.getValue(first.proposalId)
        assertEquals(IdempotencyKeyContract.fingerprint(listOf(org, saved.code, saved.currency, saved.custodian, saved.companyReference,
            saved.subjectVersion).joinToString("|")), commands.specs.single().requestFingerprint)
        currentSource = false
        Mockito.`when`(approvals.loadActivePolicy(org, policyKey, at)).thenReturn(policy.copy(versionNumber = 2))
        val replay = service.prepare(maker, org, request, "synthetic-cashbox")
        assertTrue(replay.replayed)
        assertEquals("HQ-CASH-2026", replay.cashboxCode)
        assertEquals("REVIEW_REQUIRED", replay.state)
        assertEquals(0, source.allocations)
    }

    @Test fun aRevokedPreparationGrantCannotReplayAnEarlierOpeningRequest() {
        val request = input()
        service.prepare(maker, org, request, "synthetic-cashbox")
        currentGrant = false
        assertEquals("FINANCE_CASHBOX_NOT_AUTHORIZED", assertThrows<ProductApiException> {
            service.prepare(maker, org, request, "synthetic-cashbox")
        }.code)
        assertEquals(1, commands.specs.size)
    }

    @Test fun changedPayloadCannotReuseAnAutomaticOpeningOperation() {
        val request = input()
        service.prepare(maker, org, request, "synthetic-cashbox")
        assertThrows<IllegalArgumentException> { service.prepare(maker, org, request.copy(currency = "USD"), "synthetic-cashbox") }
        assertEquals(1, source.allocations)
        assertEquals("EGP", source.rows.values.single().currency)
    }

    @Test fun authorizedCustodianCanPrepareOwnZeroOpeningWithIndependentReviewer() {
        source.choices += FinanceCashboxCustodianChoice(maker, UUID.randomUUID(), "EMP-PREP", "Synthetic preparer", 2)
        val request = input().copy(custodianUserId = maker, expectedCustodianVersion = 2)
        val receipt = service.prepare(maker, org, request, "synthetic-self-custody")
        val replay = service.prepare(maker, org, request, "synthetic-self-custody-retry")
        assertEquals(maker, receipt.custodianUserId)
        assertEquals(maker, source.rows.getValue(receipt.proposalId).preparedBy)
        assertEquals("0.000000", receipt.openingBalance)
        assertTrue(replay.replayed)
        assertEquals(1, source.allocations)
        assertEquals(0, source.activations)
    }

    @Test fun reviewerRemainsIndependentFromBothCustodianAndPreparer() {
        assertEquals("FINANCE_CASHBOX_INDEPENDENCE_REQUIRED", assertThrows<ProductApiException> {
            service.prepare(maker, org, input().copy(custodianUserId = reviewer), "synthetic-cashbox")
        }.code)
        Mockito.`when`(approvals.actorMatchesAuthority(maker, binding, at)).thenReturn(true)
        assertEquals("FINANCE_CASHBOX_INDEPENDENCE_REQUIRED", assertThrows<ProductApiException> {
            service.prepare(maker, org, input(), "synthetic-cashbox")
        }.code)
        assertEquals(0, source.allocations)
    }

    @Test fun staleProposalsStayInThePreparersHistoryAndDoNotOfferActivation() {
        val first = service.prepare(maker, org, input(), "synthetic-cashbox")
        currentSource = false
        val history = service.workbench(maker, org).items
        assertEquals(listOf(first.proposalId), history.map { it.proposalId })
        assertEquals("REVIEW_REQUIRED", history.single().state)
        assertFalse(history.single().canRequestReview)
        assertFalse(history.single().canActivate)
        assertEquals(maker, source.lastHistoryPreparer)
    }

    @Test fun exactApprovedOpeningActivatesOnceAndItsLostResponseCanBeRecovered() {
        val first = service.prepare(maker, org, input(), "synthetic-cashbox")
        val saved = source.rows.getValue(first.proposalId)
        val approval = UUID.randomUUID()
        val ref = ApprovalSubjectRef(org, FINANCE_CASHBOX_OPENING_SUBJECT, saved.id, saved.subjectVersion)
        Mockito.`when`(engine.subjectStatus(ref, saved.policyKey)).thenReturn(ApprovalSubjectStatus(approval, ApprovalRequestState.APPROVED,
            saved.subjectVersion, saved.policyKey))
        source.reviews[saved.id] = FinanceCashboxRecordedReview(approval, "APPROVED")
        val operation = UUID.randomUUID()
        val active = service.activate(maker, org, saved.id, operation, "synthetic-cashbox")
        assertEquals("ACTIVE", active.state)
        assertEquals(saved.id, active.cashboxId)
        assertEquals("0.000000", active.openingBalance)
        assertTrue(service.activate(maker, org, saved.id, operation, "synthetic-cashbox").replayed)
        assertEquals(1, source.activations)
        assertEquals("FINANCE_CASHBOX_ALREADY_ACTIVE", assertThrows<ProductApiException> {
            service.activate(maker, org, saved.id, UUID.randomUUID(), "synthetic-cashbox")
        }.code)
    }

    private fun input() = FinanceCashboxOpeningPrepare(UUID.randomUUID(), currency = "EGP", custodianUserId = custodian,
        companyCustodyReference = "Synthetic company cash custody", expectedCustodianVersion = 5, expectedReviewVersion = reviewVersion)

    private class Commands : IdempotentCommandExecutor {
        val specs = mutableListOf<IdempotentCommandSpec>()
        var beforeAction: () -> Unit = {}
        private val outcomes = mutableMapOf<UUID, Pair<IdempotentCommandSpec, IdempotentCommandOutcome>>()
        override fun execute(spec: IdempotentCommandSpec, action: () -> IdempotentCommandOutcome): IdempotentCommandExecution {
            specs += spec
            outcomes[spec.operationId]?.let {
                // A transport retry has a fresh correlation ID; operation, actor, target,
                // command and exact payload fingerprint remain the idempotency identity.
                require(it.first.copy(correlationId = spec.correlationId) == spec)
                return IdempotentCommandExecution(it.second, true)
            }
            beforeAction()
            return action().let { outcomes[spec.operationId] = spec to it; IdempotentCommandExecution(it, false) }
        }
    }
    private class MemoryCashboxes : FinanceCashboxOpeningPersistencePort {
        val choices = mutableListOf<FinanceCashboxCustodianChoice>()
        val rows = linkedMapOf<UUID, FinanceCashboxOpeningStored>()
        val active = mutableMapOf<UUID, FinanceCashboxActivationStored>()
        val reviews = mutableMapOf<UUID, FinanceCashboxRecordedReview>()
        var locks = 0; var allocations = 0; var activations = 0; var reads = 0
        var lastDirectoryQuery = ""; var lastHistoryPreparer: UUID? = null
        override fun lockOrganization(organizationId: UUID) { locks++ }
        override fun custodians(organizationId: UUID, query: String, at: Instant, limit: Int): List<FinanceCashboxCustodianChoice> {
            reads++; lastDirectoryQuery = query; return choices.take(limit)
        }
        override fun custodian(organizationId: UUID, identityId: UUID, at: Instant, lock: Boolean) = choices.singleOrNull { it.identityId == identityId }
        override fun proposal(organizationId: UUID, id: UUID) = rows[id]?.takeIf { it.organizationId == organizationId }
        override fun operation(operationId: UUID) = rows.values.singleOrNull { it.operationId == operationId }
        override fun history(organizationId: UUID, preparer: UUID?, query: String, limit: Int): List<FinanceCashboxOpeningStored> {
            reads++; lastHistoryPreparer = preparer
            return rows.values.filter { it.organizationId == organizationId && (preparer == null || it.preparedBy == preparer) }.take(limit)
        }
        override fun nextNumber(organizationId: UUID): Long { check(locks > 0); allocations++; return allocations.toLong() }
        override fun append(proposal: FinanceCashboxOpeningStored) { rows[proposal.id] = proposal }
        override fun activation(organizationId: UUID, proposalId: UUID) = active[proposalId]
        override fun recordedReview(proposal: FinanceCashboxOpeningStored) = reviews[proposal.id]
        override fun activate(proposal: FinanceCashboxOpeningStored, operationId: UUID, actor: UUID, approvalRequestId: UUID, at: Instant) {
            activations++; active[proposal.id] = FinanceCashboxActivationStored(operationId, actor, approvalRequestId)
        }
        override fun balance(organizationId: UUID, cashboxId: UUID): FinanceCashboxBalanceReceipt? = null
    }
}
