package com.hiltech.server.projects

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class CreateAreaRequest(
    val operationId: String,
    val projectSiteId: String,
    val parentAreaId: String? = null,
    val typeCode: String,
    val code: String,
    val name: String,
    val sequence: Int? = null,
    val restrictedAccess: Boolean = false,
    val baseProjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: String,
)

data class UpdateAreaRequest(
    val operationId: String,
    val projectId: String,
    val parentAreaId: String? = null,
    val typeCode: String,
    val code: String,
    val name: String,
    val sequence: Int? = null,
    val restrictedAccess: Boolean = false,
    val baseProjectVersion: Long,
    val baseObjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: String,
)

data class CreateMilestoneRequest(
    val operationId: String,
    val code: String,
    val name: String,
    val plannedDate: String? = null,
    val sequence: Int? = null,
    val clientVisible: Boolean = false,
    val acceptanceRequirement: String? = null,
    val baseProjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: String,
)

data class UpdateMilestoneRequest(
    val operationId: String,
    val code: String,
    val name: String,
    val plannedDate: String? = null,
    val sequence: Int? = null,
    val clientVisible: Boolean = false,
    val acceptanceRequirement: String? = null,
    val baseProjectVersion: Long,
    val baseObjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: String,
)

data class PlanningOwnerRequest(
    val principalType: String,
    val principalId: String,
)

data class CreateWorkPackageRequest(
    val operationId: String,
    val projectSiteId: String? = null,
    val siteId: String? = null,
    val milestoneId: String? = null,
    val code: String,
    val name: String,
    val description: String? = null,
    val owner: PlanningOwnerRequest? = null,
    val plannedStart: String? = null,
    val plannedEnd: String? = null,
    val sequence: Int? = null,
    val baseProjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: String,
)

data class UpdateWorkPackageRequest(
    val operationId: String,
    val projectSiteId: String? = null,
    val siteId: String? = null,
    val milestoneId: String? = null,
    val code: String,
    val name: String,
    val description: String? = null,
    val owner: PlanningOwnerRequest? = null,
    val plannedStart: String? = null,
    val plannedEnd: String? = null,
    val sequence: Int? = null,
    val baseProjectVersion: Long,
    val baseObjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: String,
)

data class PlanNodeRequest(
    val type: String,
    val id: String,
)

data class AddPlanDependencyRequest(
    val operationId: String,
    val predecessor: PlanNodeRequest,
    val successor: PlanNodeRequest,
    val dependencyType: String = "FINISH_TO_START",
    val lagMinutes: Long = 0,
    val baseProjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: String,
)

data class RemovePlanDependencyRequest(
    val operationId: String,
    val baseProjectVersion: Long,
    val baseObjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: String,
)

data class MarkProjectReadyRequest(
    val operationId: String,
    val baseProjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: String,
)

data class ProjectPlanProjectResponse(
    val projectId: String,
    val projectCode: String,
    val name: String,
    val lifecycleState: String,
    val version: Long,
    val baselineVersion: Int,
)

data class ProjectPlanSiteResponse(
    val projectSiteId: String,
    val siteId: String,
    val siteCode: String,
    val name: String,
    val lifecycleState: String,
)

data class AreaResponse(
    val areaId: String,
    val siteId: String,
    val parentAreaId: String?,
    val typeCode: String,
    val code: String,
    val name: String,
    val sequence: Int?,
    val restrictedAccess: Boolean,
    val version: Long,
)

data class MilestoneResponse(
    val milestoneId: String,
    val code: String,
    val name: String,
    val state: String,
    val plannedDate: String?,
    val sequence: Int?,
    val clientVisible: Boolean,
    val acceptanceRequirement: String?,
    val baselineVersion: Int,
    val version: Long,
)

data class WorkPackageOwnerResponse(
    val principalType: String,
    val principalId: String,
    val principalLabel: String,
)

data class WorkPackageResponse(
    val workPackageId: String,
    val projectSiteId: String?,
    val siteId: String?,
    val milestoneId: String?,
    val code: String,
    val name: String,
    val description: String?,
    val owner: WorkPackageOwnerResponse?,
    val state: String,
    val plannedStart: String?,
    val plannedEnd: String?,
    val sequence: Int?,
    val baselineVersion: Int,
    val version: Long,
)

data class PlanNodeResponse(
    val type: String,
    val id: String,
)

data class PlanDependencyResponse(
    val dependencyId: String,
    val predecessor: PlanNodeResponse,
    val successor: PlanNodeResponse,
    val dependencyType: String,
    val lagMinutes: Long,
    val version: Long,
)

data class ProjectPlanResponse(
    val project: ProjectPlanProjectResponse,
    val projectSites: List<ProjectPlanSiteResponse>,
    val areas: List<AreaResponse>,
    val milestones: List<MilestoneResponse>,
    val workPackages: List<WorkPackageResponse>,
    val dependencies: List<PlanDependencyResponse>,
    val validationValid: Boolean,
    val validationReasonCodes: List<String>,
    val canEditPlan: Boolean,
    val readyGateReady: Boolean,
    val readyGateReasonCodes: List<String>,
    val correlationId: String,
)

data class PlanningMutationResponse(
    val plan: ProjectPlanResponse,
    val targetType: String,
    val targetId: String,
    val replayed: Boolean,
    val correlationId: String,
)

@RestController
@RequestMapping("/v1/projects")
class ProjectPlanningController(
    private val service: PlanningService,
) {
    @GetMapping("/{projectId}/plan")
    fun plan(
        servletRequest: HttpServletRequest,
        @PathVariable projectId: String,
    ): ProjectPlanResponse {
        val context = HiltechRequestContext.current(servletRequest)
        return service.plan(
            context.planningIdentity(),
            projectId.planningUuid("INVALID_PROJECT_ID"),
        ).toResponse(context.correlationId)
    }

    @PostMapping("/{projectId}/areas")
    fun createArea(
        servletRequest: HttpServletRequest,
        @RequestHeader("Idempotency-Key") key: String,
        @PathVariable projectId: String,
        @RequestBody request: CreateAreaRequest,
    ): PlanningMutationResponse = command(servletRequest, key, request.operationId) { context, operationId ->
        service.createArea(
            CreateAreaCommand(
                operationId, projectId.planningUuid("INVALID_PROJECT_ID"),
                request.projectSiteId.planningUuid("INVALID_PROJECT_SITE_ID"),
                request.parentAreaId?.planningUuid("INVALID_AREA_ID"),
                request.typeCode, request.code, request.name, request.sequence,
                request.restrictedAccess, request.baseProjectVersion,
                request.expectedBaselineVersion,
                request.clientOccurredAt.planningInstant(), context.planningIdentity(),
                context.correlationId,
            ),
        ).toResponse(context.correlationId)
    }

    @PostMapping("/{projectId}/milestones")
    fun createMilestone(
        servletRequest: HttpServletRequest,
        @RequestHeader("Idempotency-Key") key: String,
        @PathVariable projectId: String,
        @RequestBody request: CreateMilestoneRequest,
    ): PlanningMutationResponse = command(servletRequest, key, request.operationId) { context, operationId ->
        service.createMilestone(
            CreateMilestoneCommand(
                operationId, projectId.planningUuid("INVALID_PROJECT_ID"),
                request.code, request.name, request.plannedDate?.planningDate(),
                request.sequence, request.clientVisible, request.acceptanceRequirement,
                request.baseProjectVersion, request.expectedBaselineVersion,
                request.clientOccurredAt.planningInstant(), context.planningIdentity(), context.correlationId,
            ),
        ).toResponse(context.correlationId)
    }

    @PostMapping("/{projectId}/work-packages")
    fun createWorkPackage(
        servletRequest: HttpServletRequest,
        @RequestHeader("Idempotency-Key") key: String,
        @PathVariable projectId: String,
        @RequestBody request: CreateWorkPackageRequest,
    ): PlanningMutationResponse = command(servletRequest, key, request.operationId) { context, operationId ->
        service.createWorkPackage(
            CreateWorkPackageCommand(
                operationId, projectId.planningUuid("INVALID_PROJECT_ID"),
                request.projectSiteId?.planningUuid("INVALID_PROJECT_SITE_ID"),
                request.siteId?.planningUuid("INVALID_SITE_ID"),
                request.milestoneId?.planningUuid("INVALID_MILESTONE_ID"),
                request.code, request.name, request.description, request.owner?.toPrincipal(),
                request.plannedStart?.planningDate(), request.plannedEnd?.planningDate(),
                request.sequence, request.baseProjectVersion, request.expectedBaselineVersion,
                request.clientOccurredAt.planningInstant(), context.planningIdentity(), context.correlationId,
            ),
        ).toResponse(context.correlationId)
    }

    @PostMapping("/{projectId}/plan-dependencies")
    fun addDependency(
        servletRequest: HttpServletRequest,
        @RequestHeader("Idempotency-Key") key: String,
        @PathVariable projectId: String,
        @RequestBody request: AddPlanDependencyRequest,
    ): PlanningMutationResponse = command(servletRequest, key, request.operationId) { context, operationId ->
        service.addDependency(
            AddPlanDependencyCommand(
                operationId, projectId.planningUuid("INVALID_PROJECT_ID"),
                request.predecessor.toNode(), request.successor.toNode(),
                request.dependencyType.planningEnum("INVALID_DEPENDENCY_TYPE"),
                request.lagMinutes, request.baseProjectVersion, request.expectedBaselineVersion,
                request.clientOccurredAt.planningInstant(), context.planningIdentity(), context.correlationId,
            ),
        ).toResponse(context.correlationId)
    }

    @DeleteMapping("/{projectId}/plan-dependencies/{dependencyId}")
    fun removeDependency(
        servletRequest: HttpServletRequest,
        @RequestHeader("Idempotency-Key") key: String,
        @PathVariable projectId: String,
        @PathVariable dependencyId: String,
        @RequestBody request: RemovePlanDependencyRequest,
    ): PlanningMutationResponse = command(servletRequest, key, request.operationId) { context, operationId ->
        service.removeDependency(
            RemovePlanDependencyCommand(
                operationId, projectId.planningUuid("INVALID_PROJECT_ID"),
                dependencyId.planningUuid("INVALID_DEPENDENCY_ID"),
                request.baseProjectVersion, request.baseObjectVersion,
                request.expectedBaselineVersion, request.clientOccurredAt.planningInstant(),
                context.planningIdentity(), context.correlationId,
            ),
        ).toResponse(context.correlationId)
    }

    @PostMapping("/{projectId}/mark-ready")
    fun markReady(
        servletRequest: HttpServletRequest,
        @RequestHeader("Idempotency-Key") key: String,
        @PathVariable projectId: String,
        @RequestBody request: MarkProjectReadyRequest,
    ): PlanningMutationResponse = command(servletRequest, key, request.operationId) { context, operationId ->
        service.markReady(
            MarkProjectReadyCommand(
                operationId, projectId.planningUuid("INVALID_PROJECT_ID"),
                request.baseProjectVersion, request.expectedBaselineVersion,
                request.clientOccurredAt.planningInstant(), context.planningIdentity(), context.correlationId,
            ),
        ).toResponse(context.correlationId)
    }
}

@RestController
@RequestMapping("/v1/areas")
class AreasPlanningController(private val service: PlanningService) {
    @PutMapping("/{areaId}")
    fun update(
        servletRequest: HttpServletRequest,
        @RequestHeader("Idempotency-Key") key: String,
        @PathVariable areaId: String,
        @RequestBody request: UpdateAreaRequest,
    ): PlanningMutationResponse = command(servletRequest, key, request.operationId) { context, operationId ->
        service.updateArea(
            UpdateAreaCommand(
                operationId, areaId.planningUuid("INVALID_AREA_ID"),
                request.projectId.planningUuid("INVALID_PROJECT_ID"),
                request.parentAreaId?.planningUuid("INVALID_AREA_ID"),
                request.typeCode, request.code, request.name, request.sequence,
                request.restrictedAccess, request.baseProjectVersion,
                request.baseObjectVersion, request.expectedBaselineVersion,
                request.clientOccurredAt.planningInstant(), context.planningIdentity(), context.correlationId,
            ),
        ).toResponse(context.correlationId)
    }
}

@RestController
@RequestMapping("/v1/milestones")
class MilestonesPlanningController(private val service: PlanningService) {
    @PutMapping("/{milestoneId}")
    fun update(
        servletRequest: HttpServletRequest,
        @RequestHeader("Idempotency-Key") key: String,
        @PathVariable milestoneId: String,
        @RequestBody request: UpdateMilestoneRequest,
    ): PlanningMutationResponse = command(servletRequest, key, request.operationId) { context, operationId ->
        service.updateMilestone(
            UpdateMilestoneCommand(
                operationId, milestoneId.planningUuid("INVALID_MILESTONE_ID"),
                request.code, request.name, request.plannedDate?.planningDate(),
                request.sequence, request.clientVisible, request.acceptanceRequirement,
                request.baseProjectVersion, request.baseObjectVersion,
                request.expectedBaselineVersion, request.clientOccurredAt.planningInstant(),
                context.planningIdentity(), context.correlationId,
            ),
        ).toResponse(context.correlationId)
    }
}

@RestController
@RequestMapping("/v1/work-packages")
class WorkPackagesPlanningController(private val service: PlanningService) {
    @PutMapping("/{workPackageId}")
    fun update(
        servletRequest: HttpServletRequest,
        @RequestHeader("Idempotency-Key") key: String,
        @PathVariable workPackageId: String,
        @RequestBody request: UpdateWorkPackageRequest,
    ): PlanningMutationResponse = command(servletRequest, key, request.operationId) { context, operationId ->
        service.updateWorkPackage(
            UpdateWorkPackageCommand(
                operationId, workPackageId.planningUuid("INVALID_WORK_PACKAGE_ID"),
                request.projectSiteId?.planningUuid("INVALID_PROJECT_SITE_ID"),
                request.siteId?.planningUuid("INVALID_SITE_ID"),
                request.milestoneId?.planningUuid("INVALID_MILESTONE_ID"),
                request.code, request.name, request.description, request.owner?.toPrincipal(),
                request.plannedStart?.planningDate(), request.plannedEnd?.planningDate(),
                request.sequence, request.baseProjectVersion, request.baseObjectVersion,
                request.expectedBaselineVersion, request.clientOccurredAt.planningInstant(),
                context.planningIdentity(), context.correlationId,
            ),
        ).toResponse(context.correlationId)
    }
}

private inline fun command(
    servletRequest: HttpServletRequest,
    key: String,
    rawOperationId: String,
    block: (com.hiltech.server.platform.HiltechRequestContextSnapshot, UUID) -> PlanningMutationResponse,
): PlanningMutationResponse {
    val context = HiltechRequestContext.current(servletRequest)
    val operationId = rawOperationId.planningUuid("INVALID_OPERATION_ID")
    IdempotencyKeyContract.requireMatches(key, operationId)
    return block(context, operationId)
}

private fun PlanningOwnerRequest.toPrincipal(): ProjectPrincipalInput =
    ProjectPrincipalInput(
        principalType = principalType.planningEnum("INVALID_OWNER_TYPE"),
        principalId = principalId.planningUuid("INVALID_OWNER_ID"),
    )

private fun PlanNodeRequest.toNode(): PlanNodeRef =
    PlanNodeRef(
        type = type.planningEnum("INVALID_PLAN_NODE_TYPE"),
        id = id.planningUuid("INVALID_PLAN_NODE_ID"),
    )

private fun PlanningMutationResult.toResponse(correlationId: String): PlanningMutationResponse =
    PlanningMutationResponse(
        plan = plan.toResponse(correlationId),
        targetType = targetType,
        targetId = targetId.toString(),
        replayed = replayed,
        correlationId = correlationId,
    )

private fun ProjectPlanSnapshot.toResponse(correlationId: String): ProjectPlanResponse =
    ProjectPlanResponse(
        project = ProjectPlanProjectResponse(
            project.projectId.toString(), project.projectCode, project.name,
            project.lifecycleState.name, project.version, project.baselineVersion,
        ),
        projectSites = projectSites.map {
            ProjectPlanSiteResponse(
                it.projectSiteId.toString(), it.site.siteId.toString(),
                it.site.siteCode, it.site.name, it.lifecycleState.name,
            )
        },
        areas = areas.map {
            AreaResponse(
                it.areaId.toString(), it.siteId.toString(), it.parentAreaId?.toString(),
                it.typeCode, it.code, it.name, it.sequence, it.restrictedAccess, it.version,
            )
        },
        milestones = milestones.map {
            MilestoneResponse(
                it.milestoneId.toString(), it.code, it.name, it.state.name,
                it.plannedDate?.toString(), it.sequence, it.clientVisible,
                it.acceptanceRequirement, it.baselineVersion, it.version,
            )
        },
        workPackages = workPackages.map {
            WorkPackageResponse(
                it.workPackageId.toString(), it.projectSiteId?.toString(), it.siteId?.toString(),
                it.milestoneId?.toString(), it.code, it.name, it.description,
                it.owner?.let { owner -> WorkPackageOwnerResponse(owner.principalType.name, owner.principalId.toString(), owner.principalLabel) },
                it.state.name, it.plannedStart?.toString(), it.plannedEnd?.toString(),
                it.sequence, it.baselineVersion, it.version,
            )
        },
        dependencies = dependencies.map {
            PlanDependencyResponse(
                it.dependencyId.toString(),
                PlanNodeResponse(it.predecessor.type.name, it.predecessor.id.toString()),
                PlanNodeResponse(it.successor.type.name, it.successor.id.toString()),
                it.dependencyType.name, it.lagMinutes, it.version,
            )
        },
        validationValid = validation.valid,
        validationReasonCodes = validation.reasonCodes,
        canEditPlan = canEditPlan,
        readyGateReady = readyGate.ready,
        readyGateReasonCodes = readyGate.reasonCodes,
        correlationId = correlationId,
    )

private fun com.hiltech.server.platform.HiltechRequestContextSnapshot.planningIdentity(): UUID =
    identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProductApiException("UNAUTHENTICATED", "Authentication is required.", HttpStatus.UNAUTHORIZED)

private fun String.planningUuid(code: String): UUID =
    runCatching { UUID.fromString(trim()) }.getOrElse {
        throw ProductApiException(code, "A planning identifier is invalid.", HttpStatus.BAD_REQUEST)
    }

private fun String.planningInstant(): Instant =
    runCatching { Instant.parse(trim()) }.getOrElse {
        throw ProductApiException("INVALID_CLIENT_OCCURRED_AT", "clientOccurredAt must be an ISO-8601 Instant.", HttpStatus.BAD_REQUEST)
    }

private fun String.planningDate(): LocalDate =
    runCatching { LocalDate.parse(trim()) }.getOrElse {
        throw ProductApiException("INVALID_PLANNING_DATE", "A planning date must use YYYY-MM-DD.", HttpStatus.BAD_REQUEST)
    }

private inline fun <reified T : Enum<T>> String.planningEnum(code: String): T =
    runCatching { enumValueOf<T>(trim().uppercase()) }.getOrElse {
        throw ProductApiException(code, "A planning enum value is invalid.", HttpStatus.BAD_REQUEST)
    }
