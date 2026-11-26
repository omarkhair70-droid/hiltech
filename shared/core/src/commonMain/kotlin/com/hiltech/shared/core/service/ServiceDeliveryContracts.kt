package com.hiltech.shared.core.service

import kotlinx.serialization.Serializable

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

@Serializable
data class ServiceDeliveryProposeRequest(val operationId: String, val action: String, val baseEventNumber: Int, val details: ServiceDeliveryDetails)
@Serializable
data class ServiceDeliveryReviewRequest(val operationId: String, val decision: String, val comment: String?)
@Serializable
data class ServiceDeliveryDocumentRequest(val operationId: String, val purpose: String, val title: String)
@Serializable
data class ServiceDeliveryReceipt(val operationId: String, val organizationId: String, val projectId: String, val recordId: String, val result: String, val replayed: Boolean)
@Serializable
data class ServiceDeliveryDocumentSlotDto(val slotId: String, val organizationId: String, val projectId: String, val purpose: String, val title: String, val evidenceType: String, val replayed: Boolean)
@Serializable
data class ServiceDeliveryChoice(val id: String, val code: String, val title: String, val kind: String)
@Serializable
data class ServiceDeliveryProofDto(val evidenceId: String, val title: String, val purpose: String, val contentType: String, val sizeBytes: Long, val sha256: String)
@Serializable
data class ServiceDeliveryPolicyDto(val workflow: String, val configured: Boolean, val reviewerName: String?, val independent: Boolean)

@Serializable
data class ServiceDeliveryEventDto(
    val eventId: String, val proposalId: String, val action: String, val eventNumber: Int,
    val details: ServiceDeliveryDetails, val recordedAt: String, val recordedByName: String,
    val scopeName: String?, val sourceReference: String,
)

@Serializable
data class ServiceDeliveryProposalDto(
    val proposalId: String, val organizationId: String, val projectId: String, val projectName: String, val projectCode: String,
    val clientName: String, val action: String, val details: ServiceDeliveryDetails, val state: String,
    val sourceCurrent: Boolean, val canReview: Boolean, val canCloseStale: Boolean, val preparedByName: String, val preparedAt: String,
    val scopeName: String?, val proofDocuments: List<ServiceDeliveryProofDto>, val viewedEvidenceIds: List<String>,
    val requiredEvidenceIds: List<String>, val packageDocuments: List<HandoverDocumentDto>, val recordedEventId: String?,
    val packageFindings: List<HandoverSourceFindingDto> = emptyList(),
)

@Serializable
data class ServiceDeliveryWorkspaceDto(
    val organizationId: String, val projectId: String, val projectName: String, val projectCode: String, val clientName: String,
    val projectVersion: Long, val readiness: HandoverReadinessDto, val packages: List<HandoverPackageVersionDto>,
    val policies: List<ServiceDeliveryPolicyDto>, val scopeChoices: List<ServiceDeliveryChoice>,
    val tickets: List<ServiceDeliveryChoice>, val proofDocuments: List<ServiceDeliveryProofDto>,
    val handoverEventNumber: Int, val activeReleaseEventId: String?, val deliveryState: String,
    val events: List<ServiceDeliveryEventDto>, val proposals: List<ServiceDeliveryProposalDto>,
    val warrantyTerms: List<ServiceDeliveryEventDto>,
)

@Serializable
data class ServiceDeliveryReviewQueueDto(val organizationId: String, val items: List<ServiceDeliveryProposalDto>, val canPrepare: Boolean, val canReview: Boolean)
@Serializable
data class ServiceDeliveryEvidencePageDto(
    val organizationId: String, val projectId: String, val proposalId: String?, val evidenceId: String,
    val title: String, val originalContentType: String, val originalSha256: String, val pageNumber: Int, val pageCount: Int,
    val imageBase64: String, val viewReceiptId: String?,
)
@Serializable
data class ServiceDeliveryPackageDto(val projectId: String, val releaseEventId: String, val filename: String, val contentType: String, val base64: String, val sha256: String)
@Serializable
data class ServiceDeliveryDisplayDto(val organizationId: String, val projectId: String, val proposalId: String, val evidenceId: String, val viewReceiptId: String, val confirmed: Boolean)

