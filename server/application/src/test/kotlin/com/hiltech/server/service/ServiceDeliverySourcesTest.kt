package com.hiltech.server.service

import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.projects.ProjectAuthorizationPort
import com.hiltech.server.projects.ProjectLifecycleState
import com.hiltech.server.projects.ProjectSnapshot
import com.hiltech.server.projects.ProjectSourceType
import com.hiltech.server.projects.ProjectsPersistencePort
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class ServiceDeliverySourcesTest {
    private val at = Instant.parse("2026-11-25T14:00:00Z")
    private val org = UUID.randomUUID(); private val projectId = UUID.randomUUID(); private val maker = UUID.randomUUID()
    private val work = UUID.randomUUID(); private val agreement = UUID.randomUUID(); private val evidence = UUID.randomUUID(); private val version = UUID.randomUUID()
    private val project = ProjectSnapshot(projectId, org, "PROJECT-SYNTHETIC", "Synthetic installation", UUID.randomUUID(), "Synthetic client",
        ProjectSourceType.INTERNAL, null, ProjectLifecycleState.ACTIVE, null, null, null, 1, 1, at, at, 3)
    private val projects = Mockito.mock(ProjectsPersistencePort::class.java)
    private val authority = Mockito.mock(ProjectAuthorizationPort::class.java)
    private val repo = Mockito.mock(ServiceDeliveryRepository::class.java)
    private val handover = Mockito.mock(JdbcHandoverSource::class.java)
    private val source = ServiceDeliverySources(projects, authority, repo, handover, Clock.fixed(at, ZoneOffset.UTC))
    private val fact = HandoverDocumentFact(work, UUID.randomUUID(), "AS_BUILT", UUID.randomUUID(), evidence, "a".repeat(64))
    private val document = HandoverDocumentDto(work.toString(), fact.documentId.toString(), fact.revisionId.toString(), evidence.toString(), fact.sourceSha256,
        "WORK-1", "Synthetic equipment work", "DOC-1", "Approved as-built drawing", "A")
    private val details = ServiceDeliveryDetails(packageVersionId = version.toString(), agreementEvidenceId = agreement.toString(),
        contractReference = "SYNTHETIC-AGREEMENT", scopeConfirmed = true, disclosureConfirmed = true, reason = "Exact source documents checked")

    private fun configure(works: List<HandoverWorkFact> = listOf(HandoverWorkFact(work, "ACCEPTED")),
        requirements: List<HandoverRequirementFact> = listOf(HandoverRequirementFact(work, "AS_BUILT", "SATISFIED")), documents: List<HandoverDocumentFact> = listOf(fact)) {
        Mockito.`when`(projects.project(projectId, at)).thenReturn(project)
        Mockito.`when`(authority.canManageProject(maker, project)).thenReturn(true)
        Mockito.`when`(handover.work(projectId)).thenReturn(works)
        Mockito.`when`(handover.requirements(projectId)).thenReturn(requirements)
        Mockito.`when`(handover.approvedDocuments(projectId, org)).thenReturn(documents)
        val snapshot = HandoverSnapshotRow(UUID.randomUUID(), version, projectId, 1, "DRAFT", 3,
            HandoverReadinessPolicy.digest(documents, works, requirements, 3), "[]", at)
        Mockito.`when`(handover.version(projectId, version)).thenReturn(snapshot)
        Mockito.`when`(handover.latest(projectId)).thenReturn(snapshot)
        Mockito.`when`(handover.snapshotDocuments(version)).thenReturn(if (documents.isEmpty()) emptyList() else listOf(document))
        Mockito.`when`(repo.evidence(org, projectId, evidence, false)).thenReturn(proof(evidence, "WORK_DOCUMENT", "a".repeat(64), "INTERNAL"))
        Mockito.`when`(repo.evidence(org, projectId, agreement, false)).thenReturn(proof(agreement, "AGREEMENT", "b".repeat(64), "HIGHLY_RESTRICTED"))
    }
    private fun proof(id: UUID, purpose: String, sha: String, classification: String) = ServiceDeliveryEvidenceSource(id, org, projectId,
        "Synthetic original", purpose, sha, "application/pdf", 200, "synthetic/$id", if (purpose == "AGREEMENT") "SERVICE_DELIVERY_DOCUMENT" else "WORK_ORDER",
        if (purpose == "AGREEMENT") UUID.randomUUID() else work, classification, "INTERNAL_ONLY")

    @Test fun physicalOmissionsCannotBeHiddenByExceptionConfirmationOrExistingApprovalDocuments() {
        configure(works = listOf(HandoverWorkFact(work, "IN_PROGRESS")))
        assertEquals("SERVICE_DELIVERY_PACKAGE_NOT_READY", assertThrows<ProductApiException> {
            source.resolve(projectId, maker, "HANDOVER_RELEASE", 0, details.copy(sourceExceptionsReviewed = true))
        }.code)
        configure(requirements = listOf(HandoverRequirementFact(work, "AS_BUILT", "BLOCKED")))
        assertThrows<ProductApiException> { source.resolve(projectId, maker, "HANDOVER_RELEASE", 0, details.copy(sourceExceptionsReviewed = true)) }
        configure(documents = emptyList())
        assertThrows<ProductApiException> { source.resolve(projectId, maker, "HANDOVER_RELEASE", 0, details) }
    }

    @Test fun actualWaiverReasonsArePinnedForIndependentReviewAndNeverSilentlyIgnored() {
        val requirements = listOf(HandoverRequirementFact(work, "AS_BUILT", "SATISFIED"), HandoverRequirementFact(work, "OPTIONAL_DIAGRAM", "WAIVED", 4))
        configure(requirements = requirements)
        val finding = HandoverSourceFindingDto("WORK-1", "Synthetic equipment work", "OPTIONAL_DIAGRAM", "WAIVED", "Client agreed that this diagram is outside this delivery scope")
        Mockito.`when`(handover.sourceFindings(projectId)).thenReturn(listOf(finding))
        assertThrows<ProductApiException> { source.resolve(projectId, maker, "HANDOVER_RELEASE", 0, details) }
        val accepted = source.resolve(projectId, maker, "HANDOVER_RELEASE", 0, details.copy(sourceExceptionsReviewed = true))
        assertEquals(listOf(finding), accepted.packageFindings)
        assertEquals(listOf(document), accepted.packageDocuments)
        assertTrue(accepted.proofs.single { it.source.id == agreement }.requiredToOpen)
        Mockito.`when`(handover.sourceFindings(projectId)).thenReturn(listOf(finding.copy(waiverReason = "Changed source reason")))
        assertNotEquals(accepted.stamp, source.resolve(projectId, maker, "HANDOVER_RELEASE", 0, details.copy(sourceExceptionsReviewed = true)).stamp)
    }

    @Test fun changedWorkSourceAuthorityAndForeignDocumentAreRejectedBeforePreparingAnyProposal() {
        configure()
        assertEquals("HANDOVER", source.resolve(projectId, maker, "HANDOVER_RELEASE", 0, details).stream)
        Mockito.`when`(handover.work(projectId)).thenReturn(listOf(HandoverWorkFact(work, "ACCEPTED", 2)))
        assertThrows<ProductApiException> { source.resolve(projectId, maker, "HANDOVER_RELEASE", 0, details) }
        configure()
        assertThrows<ProductApiException> { source.resolve(projectId, maker, "HANDOVER_RELEASE", 0, details.copy(agreementEvidenceId = UUID.randomUUID().toString())) }
        Mockito.`when`(authority.canManageProject(maker, project)).thenReturn(false)
        assertEquals("SERVICE_DELIVERY_NOT_FOUND", assertThrows<ProductApiException> { source.resolve(projectId, maker, "HANDOVER_RELEASE", 0, details) }.code)
        assertTrue(Mockito.mockingDetails(repo).invocations.none { it.method.name.startsWith("insertProposal") })
    }

    @Test fun warrantyRequiresActualDatesExplicitScopeAndEvidenceAndAllowsHonestInsufficientInformation() {
        val terms = ServiceDeliveryDetails(scopeType = "WORK", scopeId = work.toString(), coveredItem = "Installed control unit", agreementEvidenceId = agreement.toString(),
            startsOn = "2026-01-01", endsOn = "2026-12-31", writtenTerms = "Synthetic agreed limited scope", contractReference = "AGREEMENT-1", scopeConfirmed = true, reason = "Explicit agreement reviewed")
        assertEquals("2026-01-01", ServiceDeliveryRules.normalized("WARRANTY_TERMS", terms, LocalDate.of(2026,11,25)).startsOn)
        assertThrows<ProductApiException> { ServiceDeliveryRules.normalized("WARRANTY_TERMS", terms.copy(startsOn = null), LocalDate.of(2026,11,25)) }
        assertThrows<ProductApiException> { ServiceDeliveryRules.normalized("WARRANTY_TERMS", terms.copy(endsOn = "2025-01-01"), LocalDate.of(2026,11,25)) }
        val incident = ServiceDeliveryDetails(ticketId = UUID.randomUUID().toString(), coverageDecision = "INSUFFICIENT_INFORMATION", incidentDescription = "The cause is not yet established", reason = "Awaiting the original incident evidence")
        assertNull(ServiceDeliveryRules.normalized("WARRANTY_ASSESSMENT", incident, LocalDate.of(2026,11,25)).incidentOn)
        assertThrows<ProductApiException> { ServiceDeliveryRules.normalized("WARRANTY_ASSESSMENT", incident.copy(coverageDecision = "COVERED"), LocalDate.of(2026,11,25)) }
        assertFalse(ServiceDeliveryRules.coveredDateAllowed(terms, incident.copy(coverageDecision = "COVERED", incidentOn = "2027-01-01", scopeConfirmed = true)))
        assertFalse(ServiceDeliveryRules.coveredDateAllowed(terms, incident.copy(coverageDecision = "COVERED", incidentOn = "2026-11-01", scopeConfirmed = true, exclusionApplies = true)))
    }

    @Test fun clientResponseCannotClaimFutureAcceptanceOrOmitRepresentativeAuthority() {
        val response = ServiceDeliveryDetails(releaseEventId = UUID.randomUUID().toString(), proofEvidenceId = evidence.toString(), responseOutcome = "ACCEPTED",
            responseOn = "2026-11-25", representativeName = "Synthetic client representative", representativeCapacity = "Project delegate",
            representativeAuthoritySource = "Named in the synthetic client letter", representativeVerified = true, reason = "Original written client response checked")
        val today = LocalDate.of(2026,11,25)
        assertEquals(response, ServiceDeliveryRules.normalized("HANDOVER_CLIENT_RESPONSE", response, today))
        assertThrows<ProductApiException> { ServiceDeliveryRules.normalized("HANDOVER_CLIENT_RESPONSE", response.copy(responseOn = "2026-11-26"), today) }
        assertThrows<ProductApiException> { ServiceDeliveryRules.normalized("HANDOVER_CLIENT_RESPONSE", response.copy(representativeAuthoritySource = ""), today) }
        assertThrows<ProductApiException> { ServiceDeliveryRules.normalized("HANDOVER_CLIENT_RESPONSE", response.copy(representativeVerified = false), today) }
    }
}
