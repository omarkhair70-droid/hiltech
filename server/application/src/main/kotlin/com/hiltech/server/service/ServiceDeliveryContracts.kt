package com.hiltech.server.service

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

internal enum class DeliveryWorkflow(val subjectType: String, val policyKey: String, val reviewerKey: String) {
    HANDOVER("SERVICE_HANDOVER_ACTION", "SERVICE_HANDOVER_REVIEW", "SERVICE_HANDOVER_REVIEWER"),
    WARRANTY("SERVICE_WARRANTY_ACTION", "SERVICE_WARRANTY_REVIEW", "SERVICE_WARRANTY_REVIEWER"),
}

/** Typed business fields; dates and scope are explicitly chosen from the actual agreement. */
@Serializable
data class ServiceDeliveryDetails(
    val packageVersionId: String? = null,
    val releaseEventId: String? = null,
    val correctsEventId: String? = null,
    val agreementEvidenceId: String? = null,
    val proofEvidenceId: String? = null,
    val contractReference: String = "",
    val responseOutcome: String? = null,
    val responseOn: String? = null,
    val representativeName: String = "",
    val representativeCapacity: String = "",
    val representativeAuthoritySource: String = "",
    val scopeType: String? = null,
    val scopeId: String? = null,
    val coveredItem: String = "",
    val startsOn: String? = null,
    val endsOn: String? = null,
    val writtenTerms: String = "",
    val exclusions: String = "",
    val responseObligations: String = "",
    val termsEventId: String? = null,
    val ticketId: String? = null,
    val incidentOn: String? = null,
    val coverageDecision: String? = null,
    val incidentDescription: String = "",
    val exclusionApplies: Boolean = false,
    val exclusionReason: String = "",
    val scopeConfirmed: Boolean = false,
    val disclosureConfirmed: Boolean = false,
    val sourceExceptionsReviewed: Boolean = false,
    val representativeVerified: Boolean = false,
    val reason: String = "",
)

data class ServiceDeliveryProposeRequest(val operationId: String, val action: String, val baseEventNumber: Int, val details: ServiceDeliveryDetails)
data class ServiceDeliveryReviewRequest(val operationId: String, val decision: String, val comment: String?)
data class ServiceDeliveryDocumentRequest(val operationId: String, val purpose: String, val title: String)
data class ServiceDeliveryReceipt(val operationId: String, val organizationId: String, val projectId: String, val recordId: String, val result: String, val replayed: Boolean)
data class ServiceDeliveryDocumentSlotDto(val slotId: String, val organizationId: String, val projectId: String, val purpose: String, val title: String, val evidenceType: String, val replayed: Boolean)
data class ServiceDeliveryChoice(val id: String, val code: String, val title: String, val kind: String)
data class ServiceDeliveryProofDto(val evidenceId: String, val title: String, val purpose: String, val contentType: String, val sizeBytes: Long, val sha256: String)
data class ServiceDeliveryPolicyDto(val workflow: String, val configured: Boolean, val reviewerName: String?, val independent: Boolean)

data class ServiceDeliveryEventDto(
    val eventId: String, val proposalId: String, val action: String, val eventNumber: Int,
    val details: ServiceDeliveryDetails, val recordedAt: String, val recordedByName: String,
    val scopeName: String?, val sourceReference: String,
)

data class ServiceDeliveryProposalDto(
    val proposalId: String, val organizationId: String, val projectId: String, val projectName: String, val projectCode: String,
    val clientName: String, val action: String, val details: ServiceDeliveryDetails, val state: String,
    val sourceCurrent: Boolean, val canReview: Boolean, val canCloseStale: Boolean, val preparedByName: String, val preparedAt: String,
    val scopeName: String?, val proofDocuments: List<ServiceDeliveryProofDto>, val viewedEvidenceIds: List<String>,
    val requiredEvidenceIds: List<String>, val packageDocuments: List<HandoverDocumentDto>, val recordedEventId: String?,
    val packageFindings: List<HandoverSourceFindingDto> = emptyList(),
)

data class ServiceDeliveryWorkspaceDto(
    val organizationId: String, val projectId: String, val projectName: String, val projectCode: String, val clientName: String,
    val projectVersion: Long, val readiness: HandoverReadinessDto, val packages: List<HandoverPackageVersionDto>,
    val policies: List<ServiceDeliveryPolicyDto>, val scopeChoices: List<ServiceDeliveryChoice>,
    val tickets: List<ServiceDeliveryChoice>, val proofDocuments: List<ServiceDeliveryProofDto>,
    val handoverEventNumber: Int, val activeReleaseEventId: String?, val deliveryState: String,
    val events: List<ServiceDeliveryEventDto>, val proposals: List<ServiceDeliveryProposalDto>,
    val warrantyTerms: List<ServiceDeliveryEventDto>,
)

data class ServiceDeliveryReviewQueueDto(val organizationId: String, val items: List<ServiceDeliveryProposalDto>, val canPrepare: Boolean, val canReview: Boolean)
data class ServiceDeliveryEvidencePageDto(
    val organizationId: String, val projectId: String, val proposalId: String?, val evidenceId: String,
    val title: String, val originalContentType: String, val originalSha256: String, val pageNumber: Int, val pageCount: Int,
    val imageBase64: String, val viewReceiptId: String?,
)
data class ServiceDeliveryPackageDto(val projectId: String, val releaseEventId: String, val filename: String, val contentType: String, val base64: String, val sha256: String)
data class ServiceDeliveryDisplayDto(val organizationId: String, val projectId: String, val proposalId: String, val evidenceId: String, val viewReceiptId: String, val confirmed: Boolean)

internal data class ServiceDeliveryProposal(
    val id: UUID, val organizationId: UUID, val projectId: UUID, val action: String, val stream: String, val baseEventNumber: Int,
    val details: ServiceDeliveryDetails, val sourceStamp: String, val reviewVersion: Long, val policyVersion: Int,
    val bindingId: UUID, val bindingVersion: Long, val reviewer: UUID, val preparedBy: UUID, val preparedAt: Instant,
    val projectName: String, val projectCode: String, val clientName: String, val scopeName: String?,
    val packageDocuments: List<HandoverDocumentDto> = emptyList(),
    val packageFindings: List<HandoverSourceFindingDto> = emptyList(),
) {
    val workflow: DeliveryWorkflow get() = if (action.startsWith("HANDOVER_")) DeliveryWorkflow.HANDOVER else DeliveryWorkflow.WARRANTY
}

internal data class ServiceDeliveryEvent(val id: UUID, val proposal: ServiceDeliveryProposal, val number: Int, val recordedBy: UUID, val recordedAt: Instant)
internal data class ServiceDeliveryReviewPolicy(val workflow: DeliveryWorkflow, val version: Int, val bindingId: UUID, val bindingVersion: Long, val reviewer: UUID)
internal data class ServiceDeliveryEvidenceSource(
    val id: UUID, val organizationId: UUID, val projectId: UUID, val title: String, val purpose: String,
    val sha256: String, val contentType: String, val sizeBytes: Long, val objectKey: String,
    val targetType: String, val targetId: UUID, val classification: String, val visibility: String,
)
internal data class ServiceDeliveryProposalProof(val source: ServiceDeliveryEvidenceSource, val requiredToOpen: Boolean)
internal data class ServiceDeliveryDocumentSlot(val id: UUID, val organizationId: UUID, val projectId: UUID, val purpose: String, val title: String, val createdBy: UUID)
