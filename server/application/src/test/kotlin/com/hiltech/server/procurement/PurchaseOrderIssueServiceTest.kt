package com.hiltech.server.procurement

import com.hiltech.server.companysettings.PurchaseOrderNumberingSnapshot
import com.hiltech.server.companysettings.PurchaseOrderNumberingSource
import com.hiltech.server.companysettings.PurchaseOrderNumberingSourcePort
import com.hiltech.server.operationalaccess.OperationalAuthorityOrigin
import com.hiltech.server.operationalaccess.OperationalAuthorityPort
import com.hiltech.server.operationalaccess.OperationalAuthorityProof
import com.hiltech.server.operationalaccess.OperationalResponsibility

import com.hiltech.server.approval.ApprovalAuthorityBindingSnapshot
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.ApprovalPolicySnapshot
import com.hiltech.server.approval.ApprovalPrincipalType
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecution
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.work.WorkOrderSnapshot
import com.hiltech.server.work.WorkPersistencePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class PurchaseOrderIssueServiceTest {
    private val at = Instant.parse("2026-11-22T17:00:00Z")
    private val org = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val candidateId = UUID.randomUUID()
    private val op = UUID.randomUUID()
    private val approvalId = UUID.randomUUID()
    private val bindingId = UUID.randomUUID()
    private val destinationId = UUID.randomUUID()
    private val workId = UUID.randomUUID()
    private val projectId = UUID.randomUUID()
    private val workSiteId = UUID.randomUUID()
    private val candidate = PurchaseOrderCandidateRecord(
        candidateId, UUID.randomUUID(), org, UUID.randomUUID(), UUID.randomUUID(),
        3L, UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(),
        BigDecimal("100"), "M", "EGP", BigDecimal("12.50"),
        BigDecimal("20"), BigDecimal("1270"), "a".repeat(64),
        "Valid buyer comparison selection", actor, at, "test",
    )
    private val requirement = PurchaseRequirementDraftRecord(
        candidate.requirementId, UUID.randomUUID(), org, workId, 2L,
        candidate.stockItemId, 3L, BigDecimal("100"), "M",
        "Project cable", "WAREHOUSE_REVIEW_REQUIRED",
        null, at, actor, at, "test",
    )
    private val authority = ApprovalAuthorityBindingSnapshot(
        bindingId, org, "PROCUREMENT_PO_ISSUER", ApprovalPrincipalType.USER,
        actor, at.minusSeconds(500), null, 1L,
    )
    private val policy = ApprovalPolicySnapshot(
        org, "PROCUREMENT_STANDARD", 5, true,
        ApprovalPolicyMode.SINGLE, "PROCUREMENT_APPROVER", true,
    )
    private val decision = PurchaseOrderApprovalSnapshot(
        approvalId, 5, ApprovalRequestState.APPROVED, 1,
    )
    private val repository = Mockito.mock(PurchaseOrderCandidatePersistence::class.java)
    private val issued = Mockito.mock(PurchaseOrderIssuePersistence::class.java)
    private val sourcing = Mockito.mock(SupplierSourcingPersistence::class.java)
    private val source = Mockito.mock(PurchaseRequirementDraftPersistence::class.java)
    private val work = Mockito.mock(WorkPersistencePort::class.java)
    private val workSnapshot = Mockito.mock(WorkOrderSnapshot::class.java)
    private val approvals = Mockito.mock(ApprovalPersistencePort::class.java)
    private val guard = Mockito.mock(PurchaseOrderCandidateApprovalSubject::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private var actualIssued: PurchaseOrderIssuedRecord? = null
    private val commands = object : IdempotentCommandExecutor {
        override fun execute(spec: IdempotentCommandSpec, action: () -> IdempotentCommandOutcome) =
            IdempotentCommandExecution(action(), false)
    }

    private fun service(
        enabled: Boolean = true,
        policyKey: String = "PROCUREMENT_STANDARD",
        authorityKey: String = "PROCUREMENT_PO_ISSUER",
        prefix: String = "PO",
        operationalSource: OperationalAuthorityPort? = null,
        numberingSource: PurchaseOrderNumberingSourcePort? = null,
        executor: IdempotentCommandExecutor = commands,
    ) = PurchaseOrderIssueService(
        repository, issued, sourcing, source, work, approvals, guard, executor, audit,
        Clock.fixed(at, ZoneOffset.UTC), enabled, policyKey, authorityKey, prefix, operationalAuthority = operationalSource, numberingSource = numberingSource,
    )

    private fun request() = IssuePurchaseOrderRequest(op, 1L, destinationId)
    private fun ctx() = ProcurementDraftContext(op, actor, "s03-issue")

    private fun allow() {
        Mockito.`when`(repository.byId(candidateId)).thenReturn(candidate)
        Mockito.`when`(repository.lockCandidate(candidateId)).thenReturn(true)
        Mockito.`when`(sourcing.lockRfq(candidate.rfqId)).thenReturn(true)
        Mockito.`when`(source.lock(candidate.requirementId)).thenReturn(true)
        Mockito.`when`(issued.lockWorkAndStockSource(
            org, workId, candidate.stockItemId,
        )).thenReturn(true)
        Mockito.`when`(guard.isCurrent(
            com.hiltech.server.approval.ApprovalSubjectRef(
                org, "PROCUREMENT_PO_CANDIDATE", candidateId, 1L,
            ),
        )).thenReturn(true)
        Mockito.`when`(approvals.isActiveOrganizationMember(org, actor, at)).thenReturn(true)
        Mockito.`when`(approvals.resolveCurrentAuthority(
            org, "PROCUREMENT_PO_ISSUER", at,
        )).thenReturn(authority)
        Mockito.`when`(approvals.actorMatchesAuthority(actor, authority, at)).thenReturn(true)
        Mockito.`when`(approvals.loadActivePolicy(
            org, "PROCUREMENT_STANDARD", at,
        )).thenReturn(policy)
        Mockito.`when`(issued.lockApprovedCandidate(
            org, candidateId, "PROCUREMENT_STANDARD",
        )).thenReturn(decision)
        Mockito.`when`(source.byId(candidate.requirementId)).thenReturn(requirement)
        Mockito.`when`(work.workOrder(workId)).thenReturn(workSnapshot)
        Mockito.`when`(workSnapshot.organizationId).thenReturn(org)
        Mockito.`when`(workSnapshot.projectId).thenReturn(projectId)
        Mockito.`when`(workSnapshot.siteId).thenReturn(workSiteId)
        Mockito.`when`(issued.validDeliveryLocation(
            org, destinationId, projectId, workSiteId, at,
        )).thenReturn(true)
        Mockito.`when`(issued.lockCurrentPurchasingOrganization(org)).thenReturn(true)
        Mockito.`when`(issued.allocateSerial(org)).thenReturn(26L)
        Mockito.doAnswer { invocation ->
            actualIssued = invocation.getArgument(0)
            Unit
        }.`when`(issued).insert(
            Mockito.any(PurchaseOrderIssuedRecord::class.java) ?: fakePurchaseOrder(),
            Mockito.any(PurchaseOrderCandidateRecord::class.java) ?: candidate,
        )
        Mockito.`when`(issued.byCandidate(candidateId)).thenAnswer { actualIssued }
    }

    private fun fakePurchaseOrder() = PurchaseOrderIssuedRecord(
        UUID.randomUUID(), op, org, candidateId, approvalId, "PROCUREMENT_STANDARD",
        5, "PROCUREMENT_PO_ISSUER", bindingId, "PO-000026", 26,
        candidate.supplierOrganizationId, destinationId, candidate.stockItemId,
        candidate.quoteId, candidate.sourceSha256, actor, at, "s03-issue",
    )

    @Test fun actualCanonicalApprovedDecisionAndIssuerProduceExactlyOneInternallyIssuedOrder() {
        allow()
        val receipt = service().issue(ctx(), candidateId, request())
        assertEquals("ISSUED", receipt.state)
        assertEquals("NOT_SENT", receipt.supplierDispatchState)
        assertEquals("PO-000026", receipt.code)
        assertEquals(26L, receipt.serial)
        assertEquals(approvalId, receipt.approvalRequestId)
        assertFalse(receipt.stockReceived)
        assertFalse(receipt.supplierNotified)
        assertFalse(receipt.paymentAuthorized)
        val recorded = requireNotNull(actualIssued)
        assertEquals(candidateId, recorded.candidateId)
        assertEquals(destinationId, recorded.deliveryStorageLocationId)
        assertEquals(policy.versionNumber, recorded.approvalPolicyVersion)
        assertEquals(bindingId, recorded.issuerAuthorityBindingId)
        assertEquals(1, Mockito.mockingDetails(audit).invocations
            .count { it.method.name == "append" })
    }

    @Test fun explicitOperationalIssuerPersistsItsOwnProofAndNeverFabricatesAnApprovalBinding() {
        allow()
        val source = Mockito.mock(OperationalAuthorityPort::class.java)
        val proof = OperationalAuthorityProof(UUID.randomUUID(), org, "PROCUREMENT_ORDER_ISSUE", 2, OperationalAuthorityOrigin.OPERATIONAL)
        Mockito.`when`(source.current(actor, org, OperationalResponsibility.PROCUREMENT_ORDER_ISSUE, at)).thenReturn(proof)
        Mockito.`when`(approvals.actorMatchesAuthority(actor, authority, at)).thenReturn(false)
        service(authorityKey = "", operationalSource = source).issue(ctx(), candidateId, request())
        val saved = requireNotNull(actualIssued)
        assertEquals(null, saved.issuerAuthorityBindingId)
        assertEquals(proof.bindingId, saved.issuerOperationalBindingId)
        assertEquals("PROCUREMENT_ORDER_ISSUE", saved.issuerAuthorityKey)
        assertEquals(approvalId, saved.approvalRequestId)
    }

    @Test fun operatingIssuerGrantNeverWaivesTheIndependentApprovedDecision() {
        allow()
        val source = Mockito.mock(OperationalAuthorityPort::class.java)
        Mockito.`when`(source.current(actor, org, OperationalResponsibility.PROCUREMENT_ORDER_ISSUE, at)).thenReturn(
            OperationalAuthorityProof(UUID.randomUUID(), org, "PROCUREMENT_ORDER_ISSUE", 1, OperationalAuthorityOrigin.OPERATIONAL))
        Mockito.`when`(issued.lockApprovedCandidate(org, candidateId, "PROCUREMENT_STANDARD"))
            .thenReturn(decision.copy(approvedDecisionCount = 0))
        assertEquals("PROCUREMENT_PO_ISSUE_UNAVAILABLE", assertThrows<ProductApiException> {
            service(authorityKey = "", operationalSource = source).issue(ctx(), candidateId, request())
        }.code)
        assertEquals(null, actualIssued)
        assertEquals(0, Mockito.mockingDetails(issued).invocations.count { it.method.name == "allocateSerial" })
    }

    @Test fun permissionRevocationOrMissingApprovalDeniesIssueBeforeNumberAllocation() {
        allow()
        Mockito.`when`(approvals.actorMatchesAuthority(actor, authority, at)).thenReturn(false)
        assertEquals("PROCUREMENT_PO_ISSUE_UNAVAILABLE", assertThrows<ProductApiException> {
            service().issue(ctx(), candidateId, request())
        }.code)
        Mockito.`when`(approvals.actorMatchesAuthority(actor, authority, at)).thenReturn(true)
        Mockito.`when`(issued.lockApprovedCandidate(
            org, candidateId, "PROCUREMENT_STANDARD",
        )).thenReturn(decision.copy(state = ApprovalRequestState.PENDING))
        assertEquals("PROCUREMENT_PO_ISSUE_UNAVAILABLE", assertThrows<ProductApiException> {
            service().issue(ctx(), candidateId, request())
        }.code)
        assertEquals(0, Mockito.mockingDetails(issued).invocations.count {
            it.method.name == "allocateSerial"
        })
    }

    @Test fun expiredApprovalPolicyAndStaleQuotePreventIssue() {
        allow()
        Mockito.`when`(approvals.loadActivePolicy(
            org, "PROCUREMENT_STANDARD", at,
        )).thenReturn(policy.copy(versionNumber = 6))
        assertEquals("PROCUREMENT_PO_ISSUE_UNAVAILABLE", assertThrows<ProductApiException> {
            service().issue(ctx(), candidateId, request())
        }.code)
        Mockito.`when`(guard.isCurrent(
            com.hiltech.server.approval.ApprovalSubjectRef(
                org, "PROCUREMENT_PO_CANDIDATE", candidateId, 1L,
            ),
        )).thenReturn(false)
        assertEquals("PROCUREMENT_PO_SOURCE_CHANGED", assertThrows<ProductApiException> {
            service().issue(ctx(), candidateId, request())
        }.code)
    }

    @Test fun currentCompanyNumberingOverridesDeploymentPrefixAndPinsItsRealRevision() {
        allow()
        val numbering = Mockito.mock(PurchaseOrderNumberingSourcePort::class.java)
        val configured = PurchaseOrderNumberingSnapshot(org, PurchaseOrderNumberingSource.COMPANY, "HT-PUR", 3, UUID.randomUUID(), at)
        Mockito.`when`(numbering.current(org)).thenReturn(configured)
        val result = service(prefix = "OLD", numberingSource = numbering).issue(ctx(), candidateId, request())
        assertEquals("HT-PUR-000026", result.code)
        assertEquals(configured.revisionId, actualIssued?.numberingRevisionId)
    }

    @Test fun lostIssueReceiptCanBeReplayedAfterPrefixRotationWithoutRenumberingOrReissuing() {
        allow()
        val numbering = Mockito.mock(PurchaseOrderNumberingSourcePort::class.java)
        val first = PurchaseOrderNumberingSnapshot(org, PurchaseOrderNumberingSource.COMPANY, "OLD-PO", 1, UUID.randomUUID(), at)
        Mockito.`when`(numbering.current(org)).thenReturn(first)
        var original: IdempotentCommandSpec? = null
        var outcome: IdempotentCommandOutcome? = null
        val executor = object : IdempotentCommandExecutor {
            override fun execute(spec: IdempotentCommandSpec, action: () -> IdempotentCommandOutcome): IdempotentCommandExecution {
                val saved = outcome
                if (saved != null) { assertEquals(original, spec); return IdempotentCommandExecution(saved, true) }
                original = spec
                return action().let { outcome = it; IdempotentCommandExecution(it, false) }
            }
        }
        val runtime = service(numberingSource = numbering, executor = executor)
        val issued = runtime.issue(ctx(), candidateId, request())
        Mockito.`when`(numbering.current(org)).thenReturn(first.copy(prefix = "NEW-PO", version = 2, revisionId = UUID.randomUUID()))
        val replay = runtime.issue(ctx(), candidateId, request())
        assertTrue(replay.replayed)
        assertEquals(issued.code, replay.code)
        assertEquals("OLD-PO-000026", replay.code)
        assertEquals(first.revisionId, actualIssued?.numberingRevisionId)
        assertEquals(1, Mockito.mockingDetails(this.issued).invocations.count { it.method.name == "allocateSerial" })
    }

    @Test fun concurrentNumberingChangeIsRejectedBeforeAllocatingASerial() {
        allow()
        val numbering = Mockito.mock(PurchaseOrderNumberingSourcePort::class.java)
        val first = PurchaseOrderNumberingSnapshot(org, PurchaseOrderNumberingSource.COMPANY, "HT-PO", 1, UUID.randomUUID(), at)
        Mockito.`when`(numbering.current(org)).thenReturn(first, first.copy(prefix = "HT-PUR", version = 2, revisionId = UUID.randomUUID()))
        assertEquals("PROCUREMENT_PO_NUMBERING_CHANGED", assertThrows<ProductApiException> {
            service(numberingSource = numbering).issue(ctx(), candidateId, request())
        }.code)
        assertEquals(null, actualIssued)
        assertEquals(0, Mockito.mockingDetails(issued).invocations.count { it.method.name == "allocateSerial" })
    }

    @Test fun issuerCanReadMissingNumberingAsActionableSetupWithoutHidingItsWorkbench() {
        allow()
        assertFalse(service(prefix = "").numbering(actor, org).ready)
        assertEquals("NOT_CONFIGURED", service(prefix = "").numbering(actor, org).source)
    }

    @Test fun locationScopePolicyAndFeatureDefaultsAreDenyByDefault() {
        allow()
        Mockito.`when`(issued.validDeliveryLocation(
            org, destinationId, projectId, workSiteId, at,
        )).thenReturn(false)
        assertEquals("PROCUREMENT_PO_ISSUE_UNAVAILABLE", assertThrows<ProductApiException> {
            service().issue(ctx(), candidateId, request())
        }.code)
        assertEquals("PROCUREMENT_PO_ISSUE_UNAVAILABLE", assertThrows<ProductApiException> {
            service(enabled = false).issue(ctx(), candidateId, request())
        }.code)
        assertEquals("PROCUREMENT_PO_NUMBERING_REQUIRED", assertThrows<ProductApiException> {
            service(prefix = "").issue(ctx(), candidateId, request())
        }.code)
        assertEquals("PROCUREMENT_PO_ISSUE_UNAVAILABLE", assertThrows<ProductApiException> {
            service(authorityKey = "").issue(ctx(), candidateId, request())
        }.code)
    }
}
