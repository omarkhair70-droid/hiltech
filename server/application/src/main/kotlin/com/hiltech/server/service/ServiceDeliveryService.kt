package com.hiltech.server.service

import com.hiltech.server.approval.ApprovalDecisionType
import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.projects.ProjectAuthorizationPort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.encodeToString

@Service
class ServiceDeliveryService(
    private val repository: ServiceDeliveryRepository,
    private val sources: ServiceDeliverySources,
    private val subject: ServiceDeliveryApprovalSubject,
    private val handover: HandoverService,
    private val approvals: ApprovalEngineService,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    private val projectAuthorization: ProjectAuthorizationPort,
) {
    @Transactional(readOnly = true)
    fun workspace(actor: UUID, projectId: UUID): ServiceDeliveryWorkspaceDto {
        val project = sources.manage(projectId, actor)
        val org = project.organizationId
        val latest = repository.latest(org, projectId, "HANDOVER")
        val events = repository.events(org, projectId)
        return ServiceDeliveryWorkspaceDto(org.toString(), projectId.toString(), project.name, project.projectCode, project.clientDisplayName,
            project.version, handover.readiness(projectId, actor), handover.history(projectId, actor),
            DeliveryWorkflow.entries.map { workflow ->
                val policy = subject.policy(org, workflow)
                ServiceDeliveryPolicyDto(workflow.name, policy != null, policy?.reviewer?.let(repository::name), policy?.reviewer != actor)
            }, repository.scopeChoices(projectId), repository.tickets(projectId), repository.readyDocuments(org, projectId).map { it.dto() },
            latest?.number ?: 0, repository.activeRelease(org, projectId)?.id?.toString(), deliveryState(latest),
            events.map { it.dto() }, repository.proposals(org, projectId).map { proposalDto(actor, it) }, repository.currentTerms(org, projectId).map { it.dto() })
    }

    @Transactional(readOnly = true)
    fun reviewQueue(actor: UUID, org: UUID): ServiceDeliveryReviewQueueDto = ServiceDeliveryReviewQueueDto(org.toString(),
        repository.assigned(org, actor).filter { subject.assignedReviewer(actor, it) }.map { proposalDto(actor, it) },
        projectAuthorization.canManageProjects(actor, org), DeliveryWorkflow.entries.any { subject.policy(org, it)?.reviewer == actor })

    @Transactional(readOnly = true)
    fun proposal(actor: UUID, org: UUID, id: UUID): ServiceDeliveryProposalDto {
        val p = repository.proposal(org, id)?.takeIf { subject.canRead(actor, it) }
            ?: deliveryFail("SERVICE_DELIVERY_NOT_FOUND", HttpStatus.NOT_FOUND)
        return proposalDto(actor, p)
    }

    fun createDocument(actor: UUID, projectId: UUID, request: ServiceDeliveryDocumentRequest, correlation: String): ServiceDeliveryDocumentSlotDto {
        val project = sources.manage(projectId, actor)
        val operation = deliveryUuid(request.operationId)
        val title = request.title.trim()
        if (request.purpose !in setOf("AGREEMENT", "CLIENT_RESPONSE", "WARRANTY_SUPPORT", "INCIDENT") || title.length !in 1..240 || title.any { it.code < 32 || it.code == 127 })
            deliveryFail("SERVICE_DELIVERY_INPUT_INVALID", HttpStatus.BAD_REQUEST)
        val id = derived("document", operation)
        val result = commands.execute(spec(operation, actor, "SERVICE_DELIVERY_DOCUMENT_PREPARED", id, listOf(projectId, request.purpose, title), correlation)) {
            val current = sources.manage(projectId, actor)
            repository.insertSlot(id, operation, current.organizationId, projectId, request.purpose, title, actor, clock.instant())
            audit(actor, "SERVICE_DELIVERY_DOCUMENT_PREPARED", id, projectId, correlation, request.purpose)
            IdempotentCommandOutcome("SERVICE_DELIVERY_DOCUMENT_PREPARED")
        }
        val slot = repository.slot(id)?.takeIf { it.organizationId == project.organizationId && it.projectId == projectId }
            ?: deliveryFail("SERVICE_DELIVERY_NOT_FOUND", HttpStatus.NOT_FOUND)
        return ServiceDeliveryDocumentSlotDto(id.toString(), slot.organizationId.toString(), projectId.toString(), slot.purpose, slot.title, "SERVICE_DELIVERY_" + slot.purpose, result.replayed)
    }

    fun propose(actor: UUID, projectId: UUID, request: ServiceDeliveryProposeRequest, correlation: String): ServiceDeliveryReceipt {
        val project = sources.manage(projectId, actor)
        val operation = deliveryUuid(request.operationId)
        val normalized = ServiceDeliveryRules.normalized(request.action, request.details, LocalDate.now(clock))
        val id = derived("proposal", operation)
        val execution = commands.execute(spec(operation, actor, "SERVICE_DELIVERY_PROPOSED", id,
            listOf(projectId, request.action, request.baseEventNumber, deliveryJson.encodeToString(normalized)), correlation)) {
            if (!repository.lockProject(projectId, project.organizationId)) deliveryFail("SERVICE_DELIVERY_NOT_FOUND", HttpStatus.NOT_FOUND)
            repository.lockWorkSources(projectId)
            val source = sources.resolve(projectId, actor, request.action, request.baseEventNumber, normalized)
            repository.lockEvidence(source.proofs.map { it.source.id })
            val frozen = sources.resolve(projectId, actor, request.action, request.baseEventNumber, normalized)
            val workflow = if (request.action.startsWith("HANDOVER_")) DeliveryWorkflow.HANDOVER else DeliveryWorkflow.WARRANTY
            val policy = subject.policy(project.organizationId, workflow) ?: deliveryFail("SERVICE_DELIVERY_REVIEW_SETUP_REQUIRED")
            if (policy.reviewer == actor) deliveryFail("SERVICE_DELIVERY_INDEPENDENT_REVIEWER_REQUIRED")
            val p = ServiceDeliveryProposal(id, project.organizationId, projectId, request.action, frozen.stream, request.baseEventNumber,
                frozen.details, frozen.stamp, subject.version(frozen.stamp, actor, policy), policy.version, policy.bindingId, policy.bindingVersion,
                policy.reviewer, actor, clock.instant(), frozen.project.name, frozen.project.projectCode, frozen.project.clientDisplayName, frozen.scopeName, frozen.packageDocuments, frozen.packageFindings)
            repository.insertProposal(p, operation, frozen.proofs)
            val requested = approvals.requestException(actor, derived("approval-request", operation),
                ApprovalSubjectRef(project.organizationId, workflow.subjectType, id, p.reviewVersion), workflow.policyKey,
                "SERVICE_DOCUMENTED_DECISION", null, correlation, allowAuthorityBypass = false)
            check(requested.approvalRequired && requested.approvalRequestId != null && requested.state == ApprovalRequestState.PENDING)
            audit(actor, "SERVICE_DELIVERY_PROPOSED", id, projectId, correlation, request.action)
            IdempotentCommandOutcome("SERVICE_DELIVERY_PROPOSED")
        }
        return ServiceDeliveryReceipt(operation.toString(), project.organizationId.toString(), projectId.toString(), id.toString(), "SUBMITTED_FOR_REVIEW", execution.replayed)
    }

    fun review(actor: UUID, org: UUID, proposalId: UUID, request: ServiceDeliveryReviewRequest, correlation: String): ServiceDeliveryReceipt {
        val original = repository.proposal(org, proposalId)?.takeIf { subject.assignedReviewer(actor, it) }
            ?: deliveryFail("SERVICE_DELIVERY_NOT_FOUND", HttpStatus.NOT_FOUND)
        val operation = deliveryUuid(request.operationId)
        if (request.decision !in setOf("APPROVE", "REJECT", "CLOSE_STALE")) deliveryFail("SERVICE_DELIVERY_INPUT_INVALID", HttpStatus.BAD_REQUEST)
        val comment = request.comment?.trim()?.takeIf { it.isNotEmpty() }
        if ((request.decision == "REJECT" && comment == null) || comment?.let { it.length > 500 || it.any { c -> c.code < 32 || c.code == 127 } } == true)
            deliveryFail("SERVICE_DELIVERY_INPUT_INVALID", HttpStatus.BAD_REQUEST)
        val eventId = derived("event", operation)
        val execution = commands.execute(spec(operation, actor, "SERVICE_DELIVERY_REVIEWED", proposalId, listOf(org, proposalId, request.decision, comment), correlation)) {
            if (!repository.lockProject(original.projectId, org)) deliveryFail("SERVICE_DELIVERY_NOT_FOUND", HttpStatus.NOT_FOUND)
            repository.lockWorkSources(original.projectId)
            val p = repository.proposal(org, proposalId) ?: deliveryFail("SERVICE_DELIVERY_NOT_FOUND", HttpStatus.NOT_FOUND)
            if (!subject.assignedReviewer(actor, p)) deliveryFail("SERVICE_DELIVERY_NOT_FOUND", HttpStatus.NOT_FOUND)
            repository.lockEvidence(repository.proposalProofs(p).map { it.first })
            val current = subject.current(p)
            val reference = ApprovalSubjectRef(org, p.workflow.subjectType, p.id, p.reviewVersion)
            val status = approvals.subjectStatus(reference, p.workflow.policyKey) ?: deliveryFail("SERVICE_DELIVERY_SOURCE_CHANGED")
            if (request.decision == "CLOSE_STALE") {
                if (current != null || status.state != ApprovalRequestState.PENDING) deliveryFail("SERVICE_DELIVERY_SOURCE_CHANGED")
                val closed = approvals.decideForSourceWorkflow(actor, derived("approval-decision", operation), status.approvalRequestId,
                    ApprovalDecisionType.REJECT, "SOURCE_CHANGED_CLOSE_OUTDATED_SERVICE_REQUEST", correlation)
                if (closed.state != ApprovalRequestState.SUPERSEDED) deliveryFail("SERVICE_DELIVERY_SOURCE_CHANGED")
                audit(actor, "SERVICE_DELIVERY_REVIEW_SUPERSEDED", p.id, p.projectId, correlation, p.action)
                return@execute IdempotentCommandOutcome("SERVICE_DELIVERY_REVIEW_SUPERSEDED")
            }
            if (current == null || status.state !in setOf(ApprovalRequestState.PENDING, ApprovalRequestState.APPROVED)) deliveryFail("SERVICE_DELIVERY_SOURCE_CHANGED")
            val decision = if (request.decision == "APPROVE") ApprovalDecisionType.APPROVE else ApprovalDecisionType.REJECT
            if (decision == ApprovalDecisionType.APPROVE) {
                val viewed = repository.viewed(actor, p).toSet()
                if (current.proofs.any { it.requiredToOpen && it.source.id.toString() !in viewed }) deliveryFail("SERVICE_DELIVERY_ORIGINAL_REVIEW_REQUIRED")
            }
            if (status.state == ApprovalRequestState.PENDING) {
                val result = approvals.decide(actor, derived("approval-decision", operation), status.approvalRequestId, decision, comment, correlation)
                if (result.state != if (decision == ApprovalDecisionType.APPROVE) ApprovalRequestState.APPROVED else ApprovalRequestState.REJECTED)
                    deliveryFail("SERVICE_DELIVERY_SOURCE_CHANGED")
            } else if (decision != ApprovalDecisionType.APPROVE) deliveryFail("SERVICE_DELIVERY_SOURCE_CHANGED")
            if (decision == ApprovalDecisionType.APPROVE) repository.recordEvent(eventId, operation, p, status.approvalRequestId, actor, clock.instant())
            audit(actor, "SERVICE_DELIVERY_REVIEWED", p.id, p.projectId, correlation, request.decision)
            IdempotentCommandOutcome("SERVICE_DELIVERY_REVIEWED")
        }
        return ServiceDeliveryReceipt(operation.toString(), org.toString(), original.projectId.toString(), proposalId.toString(),
            when (request.decision) { "APPROVE" -> "RECORDED"; "REJECT" -> "REJECTED"; else -> "SUPERSEDED" }, execution.replayed)
    }

    internal fun proposalDto(actor: UUID, p: ServiceDeliveryProposal): ServiceDeliveryProposalDto {
        val current = subject.current(p)
        val recorded = repository.recorded(p)
        val state = repository.status(p)
        val reviewer = subject.assignedReviewer(actor, p)
        val proofPins = repository.proposalProofs(p)
        val proofDocuments = proofPins.mapNotNull { (id, pin) -> repository.evidence(p.organizationId, p.projectId, id, historical = true)?.takeIf { it.sha256 == pin.first }?.dto() }
        return ServiceDeliveryProposalDto(p.id.toString(), p.organizationId.toString(), p.projectId.toString(), p.projectName, p.projectCode, p.clientName,
            p.action, p.details, when { recorded != null -> "RECORDED"; current == null && state in setOf("PENDING", "APPROVED") -> "STALE"; else -> state },
            current != null, reviewer && current != null && recorded == null && state in setOf("PENDING", "APPROVED"),
            reviewer && current == null && state == "PENDING", repository.name(p.preparedBy), p.preparedAt.toString(), p.scopeName,
            proofDocuments, repository.viewed(actor, p), proofPins.filter { it.second.second }.map { it.first.toString() },
            p.packageDocuments, recorded?.id?.toString(), p.packageFindings)
    }

    private fun ServiceDeliveryEvidenceSource.dto() = ServiceDeliveryProofDto(id.toString(), title, purpose, contentType, sizeBytes, sha256)
    private fun ServiceDeliveryEvent.dto() = ServiceDeliveryEventDto(id.toString(), proposal.id.toString(), proposal.action, number, proposal.details,
        recordedAt.toString(), repository.name(recordedBy), proposal.scopeName, proposal.projectCode)
    private fun deliveryState(event: ServiceDeliveryEvent?): String = when (event?.proposal?.action) {
        null -> "NOT_ISSUED"; "HANDOVER_RELEASE" -> "ISSUED"; "HANDOVER_WITHDRAW" -> "WITHDRAWN"
        else -> "CLIENT_" + event.proposal.details.responseOutcome
    }
    private fun derived(kind: String, operation: UUID): UUID = UUID.nameUUIDFromBytes("hiltech:service-delivery:$kind:$operation".toByteArray(Charsets.UTF_8))
    private fun spec(operation: UUID, actor: UUID, action: String, target: UUID, values: List<Any?>, correlation: String) =
        IdempotentCommandSpec(operation, actor, action, "ServiceDelivery", target, IdempotencyKeyContract.fingerprint(values.joinToString("\u001f")), correlation)
    private fun audit(actor: UUID, action: String, target: UUID, project: UUID, correlation: String, reason: String) = audit.append(AuditEventRecord(
        actorUserId = actor, action = action, targetType = "ServiceDelivery", targetId = target, occurredAt = clock.instant(), correlationId = correlation,
        reason = reason, configRevisionRefsJson = """{"projectId":"$project"}"""))
}
