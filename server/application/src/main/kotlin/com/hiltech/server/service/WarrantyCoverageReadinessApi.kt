package com.hiltech.server.service

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Read-only projection of the source-owned warranty decision and remaining checks.
 * A signed source or READY evidence alone does not establish coverage. An actual
 * independently reviewed service decision supplies the recorded coverage state.
 * No SLA target, client communication or billing decision is inferred.
 */
data class WarrantyCoverageReadinessDto(
    val projectId: String,
    val ticketId: String,
    val contractId: String?,
    val clientPoId: String?,
    val commercialSourceState: String,
    val writtenWarrantyTermsPresent: Boolean,
    val coverageDecisionState: String,
    val approvalState: String,
    val coveredAssetOrSiteState: String,
    val blockerCodes: List<String>,
    val warrantyApproved: Boolean = false,
    val slaAuthorized: Boolean = false,
    val clientMayBeNotified: Boolean = false,
    val billableServiceAuthorized: Boolean = false,
    val clientVisibility: String = "INTERNAL_ONLY",
)

/**
 * Conservative legacy-source assessment for callers that have no approved
 * service decision. Current service reads additionally use the immutable
 * service-delivery decision source below; commercial text alone is insufficient.
 */
object WarrantyCoverageReadinessPolicy {
    fun evaluate(
        source: ProjectServiceContractSourceDto,
        ticket: InternalSupportTicketDto,
    ): WarrantyCoverageReadinessDto {
        require(source.projectId == ticket.projectId) {
            "Warranty assessment must not combine different projects"
        }
        val blockers = mutableListOf<String>()
        when (source.sourceState) {
            "P10_SOURCE_NOT_DEPLOYED" -> blockers += "P10_SOURCE_NOT_DEPLOYED"
            "NO_SIGNED_COMMERCIAL_SOURCE" -> blockers += "SIGNED_PROJECT_CONTRACT_MISSING"
            "SIGNED_SOURCE_DOCUMENT_VERIFICATION_PENDING" ->
                blockers += "SIGNED_CONTRACT_AND_PO_EVIDENCE_NOT_VERIFIED"
            "SIGNED_RECORD_STORAGE_READY_AWARD_NOT_REVALIDATED" ->
                blockers += "P10_AWARD_GATE_NOT_REVALIDATED"
            else -> blockers += "COMMERCIAL_SOURCE_STATE_NOT_TRUSTED"
        }
        if (!source.hasWrittenWarrantyTerms) {
            blockers += "WRITTEN_WARRANTY_TERMS_MISSING"
        }
        // These are hard contractual boundaries. Do not infer approval from text,
        // contract dates, a captured asset, an internal case closure or a READY file.
        blockers += "COMPANY_WARRANTY_POLICY_NOT_APPROVED"
        blockers += "COVERED_ASSET_OR_SITE_SCOPE_NOT_VERIFIED"
        blockers += "INCIDENT_AND_EXCLUSIONS_NOT_ASSESSED"
        blockers += "WARRANTY_START_AND_END_NOT_APPROVED"
        return WarrantyCoverageReadinessDto(
            projectId = source.projectId,
            ticketId = ticket.ticketId,
            contractId = source.contractId,
            clientPoId = source.clientPoId,
            commercialSourceState = source.sourceState,
            writtenWarrantyTermsPresent = source.hasWrittenWarrantyTerms,
            coverageDecisionState = "NOT_EVALUATED",
            approvalState = "NOT_APPROVED",
            coveredAssetOrSiteState = "NOT_VERIFIED",
            blockerCodes = blockers,
        )
    }
}

@Service
class WarrantyCoverageReadinessService(
    private val commercialSource: ProjectServiceContractSourceService,
    private val supportDesk: JdbcSupportDeskStore,
    private val decisions: ServiceDeliveryRepository,
    private val sources: ServiceDeliverySources,
    private val review: ServiceDeliveryApprovalSubject,
) {
    @Transactional(readOnly = true)
    fun read(projectId: UUID, ticketId: UUID, actor: UUID): WarrantyCoverageReadinessDto {
        // Authorize against the canonical Project BEFORE looking up the ticket.
        // A cross-project ticket is indistinguishable from a missing ticket.
        val source = commercialSource.read(projectId, actor)
        val ticket = supportDesk.ticket(ticketId, projectId)
            ?: warrantyReadinessProblem("SUPPORT_TICKET_NOT_FOUND", HttpStatus.NOT_FOUND)
        val owner = sources.manage(projectId, actor)
        val recorded = decisions.ticketCoverage(projectId, ticketId)
        if (recorded == null) {
            val currentTerms = decisions.currentTerms(owner.organizationId, projectId)
            val configured = review.policy(owner.organizationId, DeliveryWorkflow.WARRANTY) != null
            val blockers = buildList {
                if (currentTerms.isEmpty()) add("APPROVED_WARRANTY_TERMS_REQUIRED")
                if (!configured) add("WARRANTY_REVIEW_SETUP_REQUIRED")
                add("INCIDENT_AND_EXCLUSIONS_NOT_ASSESSED")
            }
            return WarrantyCoverageReadinessDto(projectId.toString(), ticketId.toString(), source.contractId, source.clientPoId, source.sourceState,
                currentTerms.isNotEmpty(), "NOT_EVALUATED", "NOT_APPROVED", if (currentTerms.isEmpty()) "NOT_VERIFIED" else "APPROVED_TERMS_AVAILABLE", blockers)
        }
        val state = requireNotNull(recorded.proposal.details.coverageDecision)
        val terms = recorded.proposal.details.termsEventId?.let { decisions.event(recorded.proposal.organizationId, UUID.fromString(it)) }
        return WarrantyCoverageReadinessDto(projectId.toString(), ticketId.toString(), source.contractId, source.clientPoId,
            source.sourceState, terms?.proposal?.details?.writtenTerms?.isNotBlank() == true,
            state, "APPROVED_RECORDED", if (recorded.proposal.details.scopeConfirmed) "VERIFIED_FOR_INCIDENT" else "NOT_CONFIRMED_FOR_INCIDENT",
            if (state == "INSUFFICIENT_INFORMATION") listOf("INCIDENT_INFORMATION_REQUIRED") else emptyList(),
            warrantyApproved = state == "COVERED", slaAuthorized = false, clientMayBeNotified = false, billableServiceAuthorized = false)
    }
}

@RestController
@RequestMapping("/v1/projects/{projectId}/support-tickets/{ticketId}/warranty-readiness")
@ConditionalOnProperty(
    prefix = "hiltech.phase11",
    name = ["support.enabled", "warranty.enabled"],
    havingValue = "true",
)
class WarrantyCoverageReadinessController(
    private val service: WarrantyCoverageReadinessService,
) {
    @GetMapping
    fun read(
        request: HttpServletRequest,
        @PathVariable projectId: UUID,
        @PathVariable ticketId: UUID,
    ): WarrantyCoverageReadinessDto = service.read(projectId, ticketId, actor(request))

    private fun actor(request: HttpServletRequest): UUID =
        HiltechRequestContext.current(request).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: warrantyReadinessProblem("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED)
}

private fun warrantyReadinessProblem(code: String, status: HttpStatus): Nothing =
    throw ProductApiException(
        code = code,
        message = "Warranty coverage readiness is not available for this service ticket.",
        status = status,
    )
