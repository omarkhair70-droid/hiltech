package com.hiltech.server.executive

import com.hiltech.server.inbox.InboxService
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.projects.ProjectsService
import com.hiltech.server.security.RoleTeamAuthorizationService
import com.hiltech.server.warehouse.AssetService
import com.hiltech.server.warehouse.WarehouseReadService
import com.hiltech.server.work.ReviewProgressHealthService
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

/**
 * Phase 13A: a read-only aggregation of source-owned, permission-filtered views.
 * This endpoint cannot grant authority or execute source-domain commands.
 */
data class ExecutiveSourceReference(
    val module: String,
    val sourceType: String,
    val sourceId: String,
    val sourceVersion: Long?,
    val readPath: String,
)

data class ExecutiveDecision(
    val safeTitleCode: String,
    val safeSummary: String?,
    val attentionClass: String,
    val updatedAt: String,
    val reference: ExecutiveSourceReference,
)

data class ExecutiveHealthSignal(
    val code: String,
    val severity: String,
    val summary: String,
    val lastObservedAt: String,
    val reference: ExecutiveSourceReference,
)

data class ExecutiveProject(
    val projectId: String,
    val projectCode: String,
    val name: String,
    val lifecycleState: String,
    val projectVersion: Long,
    val projectUpdatedAt: String,
    val healthState: String,
    val healthAsOf: String?,
    val healthPolicyRevision: Int?,
    val reasonCodes: List<String>,
    val signals: List<ExecutiveHealthSignal>,
    val reference: ExecutiveSourceReference,
)

data class ExecutiveAssetException(
    val warehouseId: String,
    val assetId: String,
    val assetCode: String,
    val name: String?,
    val availabilityState: String,
    val calibrationStatus: String,
    val maintenanceStatus: String,
    val reasonCodes: List<String>,
    val evaluatedAt: String,
    val reference: ExecutiveSourceReference,
)

data class ExecutiveSection<T>(
    val status: String,
    val items: List<T>,
    val source: String,
    val sourceAsOf: String?,
    val partial: Boolean,
    val nextCursor: String? = null,
    val unavailableReason: String? = null,
)

data class ExecutivePendingDomain(
    val domain: String,
    val status: String = "AWAITING_AUTHORIZED_SOURCE_CONTRACT",
)

data class ExecutiveHomeResponse(
    val viewerIdentityId: String,
    val organizationId: String,
    val asOf: String,
    val correlationId: String,
    val needsYouScope: String,
    val needsYou: ExecutiveSection<ExecutiveDecision>,
    val projects: ExecutiveSection<ExecutiveProject>,
    val assetExceptions: ExecutiveSection<ExecutiveAssetException>,
    val pendingDomains: List<ExecutivePendingDomain>,
)

/** Port is a test seam; production implementation MUST reuse current domain ACL. */
interface ExecutiveSources {
    fun needsYou(actor: UUID, correlationId: String): ExecutiveSection<ExecutiveDecision>
    fun projects(actor: UUID, organizationId: UUID): ExecutiveSection<ExecutiveProject>
    fun assetExceptions(actor: UUID, organizationId: UUID): ExecutiveSection<ExecutiveAssetException>
}

/**
 * The executive read surface cannot widen organization membership. This is
 * not a Mohamed-name or executive-role permission: source modules still decide
 * whether the actor may see each individual request, project or warehouse.
 */
interface ExecutiveOrganizationAccess {
    fun isCurrentMember(actor: UUID, organizationId: UUID): Boolean
}

@Component
class SourceExecutiveOrganizationAccess(
    private val roleTeamAuthorization: RoleTeamAuthorizationService,
) : ExecutiveOrganizationAccess {
    override fun isCurrentMember(actor: UUID, organizationId: UUID): Boolean =
        roleTeamAuthorization.isOrganizationMember(actor, organizationId)
}

@Component
class CurrentExecutiveSources(
    private val inbox: InboxService,
    private val projectService: ProjectsService,
    private val projectHealth: ReviewProgressHealthService,
    private val warehouses: WarehouseReadService,
    private val assetService: AssetService,
) : ExecutiveSources {
    override fun needsYou(actor: UUID, correlationId: String): ExecutiveSection<ExecutiveDecision> {
        // WorkQueue revalidates current approval authority and ability to decide.
        val page = inbox.workQueue(actor, null, 20, correlationId)
        return ExecutiveSection(
            status = if (page.nextCursor != null) "PARTIAL" else "CURRENT",
            source = "INBOX_WORK_QUEUE",
            sourceAsOf = page.asOf,
            partial = page.nextCursor != null,
            nextCursor = page.nextCursor,
            items = page.items.filter { it.actionable && it.state == "OPEN" }.map { item ->
                val readPath = when (item.sourceType to item.actionKey) {
                    "APPROVAL_REQUEST" to "DECIDE_APPROVAL" -> "/v1/approvals/" + item.sourceId
                    else -> "/v1/inbox/" + item.inboxItemId
                }
                ExecutiveDecision(
                    safeTitleCode = item.safeTitleCode,
                    safeSummary = item.safeSummary,
                    attentionClass = item.attentionClass,
                    updatedAt = item.updatedAt,
                    reference = ExecutiveSourceReference(
                        module = "INBOX",
                        sourceType = item.sourceType,
                        sourceId = item.sourceId,
                        sourceVersion = item.sourceVersion,
                        readPath = readPath,
                    ),
                )
            },
        )
    }

    override fun projects(actor: UUID, organizationId: UUID): ExecutiveSection<ExecutiveProject> {
        // ProjectService and ProjectHealth independently enforce current read access.
        val window = projectService.myProjects(actor, organizationId, 31)
            .filter { it.organizationId == organizationId }
        var someHealthUnavailable = false
        val items = window.take(30).map { project ->
            val health = try {
                projectHealth.health(actor, project.projectId)
            } catch (_: ProductApiException) {
                someHealthUnavailable = true
                null
            }
            ExecutiveProject(
                projectId = project.projectId.toString(),
                projectCode = project.projectCode,
                name = project.name,
                lifecycleState = project.lifecycleState.name,
                projectVersion = project.version,
                projectUpdatedAt = project.updatedAt.toString(),
                healthState = health?.state?.name ?: "UNAVAILABLE",
                healthAsOf = health?.asOf?.toString(),
                healthPolicyRevision = health?.healthPolicyRevision,
                reasonCodes = health?.reasonCodes.orEmpty(),
                signals = health?.signals.orEmpty().filter { it.current }.map { signal ->
                    ExecutiveHealthSignal(
                        code = signal.signalCode.name,
                        severity = signal.severity.name,
                        summary = signal.summary,
                        lastObservedAt = signal.lastObservedAt.toString(),
                        reference = ExecutiveSourceReference(
                            module = "PROJECTS",
                            sourceType = signal.sourceType,
                            sourceId = signal.sourceId.toString(),
                            sourceVersion = signal.sourceVersion,
                            readPath = "/v1/projects/" + project.projectId + "/health",
                        ),
                    )
                },
                reference = ExecutiveSourceReference(
                    module = "PROJECTS",
                    sourceType = "PROJECT",
                    sourceId = project.projectId.toString(),
                    sourceVersion = project.version,
                    readPath = "/v1/projects/" + project.projectId,
                ),
            )
        }
        // The source bounds its persistence query BEFORE visibility filtering;
        // fewer than 30 visible projects cannot prove portfolio completeness.
        return ExecutiveSection(
            status = "PARTIAL",
            items = items,
            source = "PROJECTS_AND_PROJECT_HEALTH",
            sourceAsOf = null, // Each record has its own real updatedAt/healthAsOf.
            partial = true,
            unavailableReason = if (someHealthUnavailable) "SOME_PROJECT_HEALTH_UNAVAILABLE" else null,
        )
    }

    override fun assetExceptions(actor: UUID, organizationId: UUID): ExecutiveSection<ExecutiveAssetException> {
        val allowed = warehouses.warehouses(actor, organizationId).filter { it.organizationId == organizationId }
        val warehouseWindow = allowed.take(8)
        var capped = allowed.size > warehouseWindow.size
        var inaccessibleQueue = false
        val items = mutableListOf<ExecutiveAssetException>()
        warehouseWindow.forEach { warehouse ->
            val queue = try {
                // AssetService checks the actor's warehouse access again at read time.
                assetService.queue(actor, warehouse.warehouseId, null)
            } catch (_: ProductApiException) {
                inaccessibleQueue = true
                emptyList()
            }
            if (queue.size >= 100) capped = true // Source's own page cap.
            queue.forEach { asset ->
                if (items.size == 30) {
                    capped = true
                } else {
                    items.add(
                        ExecutiveAssetException(
                            warehouseId = warehouse.warehouseId.toString(),
                            assetId = asset.id.toString(),
                            assetCode = asset.assetCode,
                            name = asset.name,
                            availabilityState = asset.availability.state,
                            calibrationStatus = asset.availability.calibrationStatus,
                            maintenanceStatus = asset.availability.maintenanceStatus,
                            reasonCodes = asset.availability.reasons.map { it.code },
                            evaluatedAt = asset.availability.evaluatedAt,
                            reference = ExecutiveSourceReference(
                                module = "WAREHOUSE",
                                sourceType = "ASSET",
                                sourceId = asset.id.toString(),
                                sourceVersion = asset.availability.assetVersion,
                                readPath = "/v1/assets/" + asset.id + "/availability",
                            ),
                        ),
                    )
                }
            }
        }
        return ExecutiveSection(
            status = if (capped || inaccessibleQueue) "PARTIAL" else "CURRENT",
            items = items,
            source = "ASSET_SERVICE_QUEUE",
            sourceAsOf = null, // Each asset includes actual source evaluation time.
            partial = capped,
            unavailableReason = if (inaccessibleQueue) "SOME_WAREHOUSE_QUEUES_UNAVAILABLE" else null,
        )
    }
}

@Service
class ExecutiveHomeService(
    private val sources: ExecutiveSources,
    private val access: ExecutiveOrganizationAccess,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun home(actor: UUID, organizationId: UUID, correlationId: String): ExecutiveHomeResponse {
        // Reject a foreign or revoked org BEFORE evaluating any actor-wide Inbox
        // data; a guessed organization ID cannot reveal another membership.
        if (!access.isCurrentMember(actor, organizationId)) {
            throw ProductApiException(
                code = "EXECUTIVE_HOME_NOT_FOUND",
                message = "The executive home is unavailable.",
                status = HttpStatus.NOT_FOUND,
            )
        }
        return ExecutiveHomeResponse(
            viewerIdentityId = actor.toString(),
            organizationId = organizationId.toString(),
            asOf = clock.instant().toString(),
            correlationId = correlationId,
            needsYouScope = "CURRENT_ACTOR_ACROSS_AUTHORIZED_MEMBERSHIPS",
            needsYou = safely("INBOX_WORK_QUEUE") { sources.needsYou(actor, correlationId) },
            projects = safely("PROJECTS_AND_PROJECT_HEALTH") { sources.projects(actor, organizationId) },
            assetExceptions = safely("ASSET_SERVICE_QUEUE") { sources.assetExceptions(actor, organizationId) },
            pendingDomains = listOf(
                ExecutivePendingDomain("FINANCE"),
                ExecutivePendingDomain("PAYROLL"),
                ExecutivePendingDomain("SALES"),
                ExecutivePendingDomain("HANDOVER_SUPPORT_MAINTENANCE"),
                ExecutivePendingDomain("SECURITY_HARDWARE"),
            ),
        )
    }

    private inline fun <T> safely(source: String, block: () -> ExecutiveSection<T>): ExecutiveSection<T> =
        try {
            block()
        } catch (error: Exception) {
            log.warn("Executive source {} unavailable; fail-closed", source, error)
            ExecutiveSection(
                status = "UNAVAILABLE",
                items = emptyList(),
                source = source,
                sourceAsOf = null,
                partial = false,
                unavailableReason = "SOURCE_READ_FAILED_OR_NOT_AUTHORIZED",
            )
        }
}

@RestController
class ExecutiveHomeController(
    private val service: ExecutiveHomeService,
    @Value("\${hiltech.executive.home-enabled:false}") private val enabled: Boolean,
) {
    @GetMapping("/v1/executive/home")
    fun home(request: HttpServletRequest, @RequestParam organizationId: String): ExecutiveHomeResponse {
        if (!enabled) {
            throw ProductApiException(
                code = "EXECUTIVE_HOME_DISABLED",
                message = "Executive Home is not enabled for this deployment.",
                status = HttpStatus.SERVICE_UNAVAILABLE,
            )
        }
        val context = HiltechRequestContext.current(request)
        val actor = context.identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException(
                code = "UNAUTHENTICATED",
                message = "Authentication is required.",
                status = HttpStatus.UNAUTHORIZED,
            )
        val org = try {
            UUID.fromString(organizationId)
        } catch (_: IllegalArgumentException) {
            throw ProductApiException(
                code = "INVALID_ORGANIZATION_ID",
                message = "A valid organization ID is required.",
                status = HttpStatus.BAD_REQUEST,
            )
        }
        return service.home(actor, org, context.correlationId)
    }
}
