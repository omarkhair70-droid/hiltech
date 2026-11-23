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
 * P11B5a is a read-only decision-preparation boundary, NOT an entitlement engine.
 * A P10 signed source, even with READY evidence, does not establish coverage.
 * No asset, site, warranty start, SLA target or billing decision is inferred.
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
 * Deliberately never returns IN_WARRANTY or OUT_OF_WARRANTY.
 * The approved, versioned warranty policy + scoped asset/site + issue decision
 * do not yet exist in the canonical P10/P11 source contract.
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
) {
    @Transactional(readOnly = true)
    fun read(projectId: UUID, ticketId: UUID, actor: UUID): WarrantyCoverageReadinessDto {
        // Authorize against the canonical Project BEFORE looking up the ticket.
        // A cross-project ticket is indistinguishable from a missing ticket.
        val source = commercialSource.read(projectId, actor)
        val ticket = supportDesk.ticket(ticketId, projectId)
            ?: warrantyReadinessProblem("SUPPORT_TICKET_NOT_FOUND", HttpStatus.NOT_FOUND)
        return WarrantyCoverageReadinessPolicy.evaluate(source, ticket)
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
