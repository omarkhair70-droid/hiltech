package com.hiltech.server.service

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.projects.ProjectAuthorizationPort
import com.hiltech.server.projects.ProjectsPersistencePort
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

/**
 * P10 provides the signed contract source; P11 does not replicate contract,
 * PO, payment, legal acceptance or entitlement state. Missing P10 view is an
 * explicit missing dependency, NEVER warranty-by-default.
 */
data class CommercialServiceSourceFact(
    val contractId: UUID,
    val clientPoId: UUID,
    val effectiveDate: String?,
    val endDate: String?,
    val signedEvidenceReady: Boolean,
    val verifiedPoEvidenceReady: Boolean,
    val hasWarrantyText: Boolean,
    val hasMaintenanceText: Boolean,
)

data class ProjectServiceContractSourceDto(
    val projectId: String,
    val sourceState: String,
    val contractId: String?,
    val clientPoId: String?,
    val sourceEffectiveDate: String?,
    val sourceEndDate: String?,
    val hasWrittenWarrantyTerms: Boolean,
    val hasWrittenMaintenanceTerms: Boolean,
    val warrantyEntitlementState: String = "NOT_EVALUATED",
    val slaPolicyState: String = "NOT_CONFIGURED",
    val maintenanceScheduleState: String = "NOT_APPROVED",
    val renewalEligibilityState: String = "NOT_EVALUATED",
    val clientVisibility: String = "INTERNAL_ONLY",
)

object ServiceContractSourcePolicy {
    fun evaluate(
        projectId: UUID,
        sourceSchemaAvailable: Boolean,
        source: CommercialServiceSourceFact?,
    ): ProjectServiceContractSourceDto {
        val state = when {
            !sourceSchemaAvailable -> "P10_SOURCE_NOT_DEPLOYED"
            source == null -> "NO_SIGNED_COMMERCIAL_SOURCE"
            !source.signedEvidenceReady || !source.verifiedPoEvidenceReady ->
                "SIGNED_SOURCE_DOCUMENT_VERIFICATION_PENDING"
            else -> "SIGNED_SOURCE_DOCUMENTS_READY"
        }
        // Even READY signed Evidence and VERIFIED client PO do not interpret
        // warranty-text, renewals, ticket entitlements or SLA targets.
        return ProjectServiceContractSourceDto(
            projectId = projectId.toString(),
            sourceState = state,
            contractId = source?.contractId?.toString(),
            clientPoId = source?.clientPoId?.toString(),
            sourceEffectiveDate = source?.effectiveDate,
            sourceEndDate = source?.endDate,
            hasWrittenWarrantyTerms = source?.hasWarrantyText ?: false,
            hasWrittenMaintenanceTerms = source?.hasMaintenanceText ?: false,
        )
    }
}

@Repository
class JdbcCommercialServiceSourceStore(private val jdbc: JdbcTemplate) {
    /**
     * This checks a provider-owned P10 view at runtime. No P11 migration or
     * table constraint directly depends on the parallel unmerged P10 branch.
     */
    fun sourceViewAvailable(): Boolean =
        jdbc.queryForObject(
            "SELECT to_regclass('public.commercial_support_contract_source') IS NOT NULL",
            Boolean::class.java,
        ) == true

    fun projectSource(projectId: UUID, orgId: UUID): CommercialServiceSourceFact? =
        jdbc.query(
            """
            SELECT source.contract_id, handoff.client_po_id,
                   source.effective_date, source.end_date,
                   source.warranty_terms IS NOT NULL
                     AND length(btrim(source.warranty_terms))>0 AS has_warranty_text,
                   source.maintenance_terms IS NOT NULL
                     AND length(btrim(source.maintenance_terms))>0 AS has_maintenance_text,
                   signed_storage.storage_state='READY'
                     AND signed_storage.organization_id=source.organization_id
                     AS signed_evidence_ready,
                   po.verified AND po_storage.storage_state='READY'
                     AND po_storage.organization_id=source.organization_id
                     AS po_evidence_ready
              FROM commercial_support_contract_source source
              JOIN commercial_project_handoff handoff
                ON handoff.project_id=source.project_id
               AND handoff.organization_id=source.organization_id
               AND handoff.contract_id=source.contract_id
              JOIN commercial_client_po po
                ON po.id=handoff.client_po_id
               AND po.organization_id=source.organization_id
               AND po.contract_id=source.contract_id
              LEFT JOIN evidence signed_storage
                ON signed_storage.id=source.signed_evidence_id
              LEFT JOIN evidence po_storage
                ON po_storage.id=po.evidence_id
             WHERE source.project_id=? AND source.organization_id=?
             LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                CommercialServiceSourceFact(
                    contractId = rs.getObject("contract_id", UUID::class.java),
                    clientPoId = rs.getObject("client_po_id", UUID::class.java),
                    effectiveDate = rs.getDate("effective_date")?.toLocalDate()?.toString(),
                    endDate = rs.getDate("end_date")?.toLocalDate()?.toString(),
                    signedEvidenceReady = rs.getBoolean("signed_evidence_ready"),
                    verifiedPoEvidenceReady = rs.getBoolean("po_evidence_ready"),
                    hasWarrantyText = rs.getBoolean("has_warranty_text"),
                    hasMaintenanceText = rs.getBoolean("has_maintenance_text"),
                )
            }, projectId, orgId,
        ).singleOrNull()
}

@Service
class ProjectServiceContractSourceService(
    private val projects: ProjectsPersistencePort,
    private val authorization: ProjectAuthorizationPort,
    private val source: JdbcCommercialServiceSourceStore,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun read(projectId: UUID, actor: UUID): ProjectServiceContractSourceDto {
        val project = projects.project(projectId, clock.instant())
            ?: serviceSourceProblem("SERVICE_PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND)
        if (!authorization.canManageProject(actor, project))
            serviceSourceProblem("SERVICE_PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND)
        val available = source.sourceViewAvailable()
        return ServiceContractSourcePolicy.evaluate(
            projectId, available,
            if (available) source.projectSource(projectId, project.organizationId) else null,
        )
    }
}

@RestController
@RequestMapping("/v1/projects/{projectId}/service-contract-source")
@ConditionalOnProperty(prefix = "hiltech.phase11.contract", name = ["enabled"], havingValue = "true")
class ProjectServiceContractSourceController(
    private val service: ProjectServiceContractSourceService,
) {
    @GetMapping
    fun read(request: HttpServletRequest, @PathVariable projectId: UUID) =
        service.read(projectId, actor(request))

    private fun actor(request: HttpServletRequest): UUID =
        HiltechRequestContext.current(request).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: serviceSourceProblem("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED)
}

private fun serviceSourceProblem(code: String, status: HttpStatus): Nothing =
    throw ProductApiException(
        code = code, message = "Commercial service source is not available for this project.", status = status,
    )
