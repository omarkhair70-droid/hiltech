package com.hiltech.server.projects

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.HiltechRequestContextSnapshot
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class ProjectPrincipalRequest(
    val principalType: String,
    val principalId: String,
)

data class CreateProjectRequest(
    val operationId: String,
    val organizationId: String,
    val sourceType: String,
    val sourceExternalReference: String? = null,
    val explicitProjectCode: String? = null,
    val name: String,
    val clientOrganizationId: String,
    val initialResponsibility: ProjectPrincipalRequest? = null,
    val startDatePlanned: String? = null,
    val endDatePlanned: String? = null,
    val clientOccurredAt: String,
)

data class UpdateProjectDetailsRequest(
    val operationId: String,
    val baseVersion: Long,
    val name: String,
    val startDatePlanned: String? = null,
    val endDatePlanned: String? = null,
    val clientOccurredAt: String,
)

data class ChangeProjectManagerRequest(
    val operationId: String,
    val baseVersion: Long,
    val principal: ProjectPrincipalRequest,
    val reason: String? = null,
    val clientOccurredAt: String,
)

data class ProjectTransitionRequest(
    val operationId: String,
    val baseVersion: Long,
    val clientOccurredAt: String,
)

data class CreateSiteRequest(
    val operationId: String,
    val organizationId: String,
    val clientOrganizationId: String,
    val siteCode: String,
    val name: String,
    val addressText: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timezone: String? = null,
    val clientOccurredAt: String,
)

data class UpdateSiteRequest(
    val operationId: String,
    val baseVersion: Long,
    val name: String,
    val addressText: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timezone: String? = null,
    val status: String,
    val clientOccurredAt: String,
)

data class AttachProjectSiteRequest(
    val operationId: String,
    val baseProjectVersion: Long,
    val siteId: String,
    val projectSiteCode: String? = null,
    val accessInstructions: String? = null,
    val projectSpecificNotes: String? = null,
    val clientOccurredAt: String,
)

data class ProjectResponsibilityResponse(
    val responsibilityId: String?,
    val principalType: String?,
    val principalId: String?,
    val principalLabel: String?,
    val resolutionState: String,
    val effectiveFrom: String?,
    val version: Long?,
)

data class ProjectSummaryResponse(
    val projectId: String,
    val organizationId: String,
    val projectCode: String,
    val name: String,
    val clientOrganizationId: String,
    val clientDisplayName: String,
    val sourceType: String,
    val sourceExternalReference: String?,
    val lifecycleState: String,
    val currentResponsibility: ProjectResponsibilityResponse,
    val startDatePlanned: String?,
    val endDatePlanned: String?,
    val baselineVersion: Int,
    val siteCount: Int,
    val createdAt: String,
    val updatedAt: String,
    val version: Long,
)

data class ProjectListResponse(
    val items: List<ProjectSummaryResponse>,
    val correlationId: String,
)

data class ProjectCommandResponse(
    val project: ProjectSummaryResponse,
    val replayed: Boolean,
    val correlationId: String,
)

data class SiteResponse(
    val siteId: String,
    val organizationId: String,
    val clientOrganizationId: String,
    val clientDisplayName: String,
    val siteCode: String,
    val name: String,
    val addressText: String?,
    val latitude: Double?,
    val longitude: Double?,
    val timezone: String?,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val version: Long,
)

data class SiteCommandResponse(
    val site: SiteResponse,
    val replayed: Boolean,
    val correlationId: String,
)

data class ProjectSiteResponse(
    val projectSiteId: String,
    val organizationId: String,
    val projectId: String,
    val site: SiteResponse,
    val projectSiteCode: String?,
    val lifecycleState: String,
    val accessInstructions: String?,
    val projectSpecificNotes: String?,
    val activeFrom: String?,
    val activeUntil: String?,
    val version: Long,
)

data class ProjectSiteListResponse(
    val items: List<ProjectSiteResponse>,
    val correlationId: String,
)

data class ProjectSiteCommandResponse(
    val project: ProjectSummaryResponse,
    val projectSite: ProjectSiteResponse,
    val replayed: Boolean,
    val correlationId: String,
)

@RestController
@RequestMapping("/v1/projects")
class ProjectsController(
    private val service: ProjectsService,
) {
    @PostMapping
    fun create(
        servletRequest: HttpServletRequest,
        @RequestHeader(name = "Idempotency-Key")
        idempotencyKey: String,
        @RequestBody
        request: CreateProjectRequest,
    ): ProjectCommandResponse {
        val context =
            HiltechRequestContext.current(servletRequest)
        val operationId =
            request.operationId.toUuid(
                "INVALID_OPERATION_ID",
            )
        IdempotencyKeyContract.requireMatches(
            idempotencyKey,
            operationId,
        )

        val result =
            service.createProject(
                CreateProjectCommand(
                    operationId = operationId,
                    organizationId =
                        request.organizationId.toUuid(
                            "INVALID_ORGANIZATION_ID",
                        ),
                    sourceType =
                        request.sourceType.toEnum(
                            "PROJECT_SOURCE_INVALID",
                        ),
                    sourceExternalReference =
                        request.sourceExternalReference,
                    explicitProjectCode =
                        request.explicitProjectCode,
                    name = request.name,
                    clientOrganizationId =
                        request.clientOrganizationId
                            .toUuid(
                                "INVALID_CLIENT_ORGANIZATION_ID",
                            ),
                    initialResponsibility =
                        request.initialResponsibility
                            ?.toPrincipal(),
                    startDatePlanned =
                        request.startDatePlanned
                            ?.toLocalDate(
                                "REJECTED_VALIDATION",
                            ),
                    endDatePlanned =
                        request.endDatePlanned
                            ?.toLocalDate(
                                "REJECTED_VALIDATION",
                            ),
                    clientOccurredAt =
                        request.clientOccurredAt
                            .toInstant(
                                "REJECTED_VALIDATION",
                            ),
                    actorUserId =
                        context.requireIdentityId(),
                    correlationId =
                        context.correlationId,
                ),
            )

        return result.toResponse(
            context.correlationId,
        )
    }

    @GetMapping
    fun list(
        servletRequest: HttpServletRequest,
        @RequestParam
        organizationId: String,
        @RequestParam(defaultValue = "100")
        limit: Int,
    ): ProjectListResponse {
        val context =
            HiltechRequestContext.current(servletRequest)
        return ProjectListResponse(
            items =
                service.myProjects(
                    actorUserId =
                        context.requireIdentityId(),
                    organizationId =
                        organizationId.toUuid(
                            "INVALID_ORGANIZATION_ID",
                        ),
                    limit = limit,
                ).map {
                    it.toResponse()
                },
            correlationId =
                context.correlationId,
        )
    }

    @GetMapping("/{projectId}")
    fun detail(
        servletRequest: HttpServletRequest,
        @PathVariable
        projectId: String,
    ): ProjectSummaryResponse {
        val context =
            HiltechRequestContext.current(servletRequest)
        return service.projectDetail(
            actorUserId =
                context.requireIdentityId(),
            projectId =
                projectId.toUuid(
                    "INVALID_PROJECT_ID",
                ),
        ).toResponse()
    }

    @PutMapping("/{projectId}/details")
    fun updateDetails(
        servletRequest: HttpServletRequest,
        @PathVariable
        projectId: String,
        @RequestHeader(name = "Idempotency-Key")
        idempotencyKey: String,
        @RequestBody
        request: UpdateProjectDetailsRequest,
    ): ProjectCommandResponse {
        val context =
            HiltechRequestContext.current(servletRequest)
        val operationId =
            request.operationId.toUuid(
                "INVALID_OPERATION_ID",
            )
        IdempotencyKeyContract.requireMatches(
            idempotencyKey,
            operationId,
        )

        return service.updateProjectDetails(
            UpdateProjectDetailsCommand(
                operationId = operationId,
                projectId =
                    projectId.toUuid(
                        "INVALID_PROJECT_ID",
                    ),
                baseVersion =
                    request.baseVersion,
                name = request.name,
                startDatePlanned =
                    request.startDatePlanned
                        ?.toLocalDate(
                            "REJECTED_VALIDATION",
                        ),
                endDatePlanned =
                    request.endDatePlanned
                        ?.toLocalDate(
                            "REJECTED_VALIDATION",
                        ),
                clientOccurredAt =
                    request.clientOccurredAt
                        .toInstant(
                            "REJECTED_VALIDATION",
                        ),
                actorUserId =
                    context.requireIdentityId(),
                correlationId =
                    context.correlationId,
            ),
        ).toResponse(
            context.correlationId,
        )
    }

    @PostMapping("/{projectId}/change-manager")
    fun changeManager(
        servletRequest: HttpServletRequest,
        @PathVariable
        projectId: String,
        @RequestHeader(name = "Idempotency-Key")
        idempotencyKey: String,
        @RequestBody
        request: ChangeProjectManagerRequest,
    ): ProjectCommandResponse {
        val context =
            HiltechRequestContext.current(servletRequest)
        val operationId =
            request.operationId.toUuid(
                "INVALID_OPERATION_ID",
            )
        IdempotencyKeyContract.requireMatches(
            idempotencyKey,
            operationId,
        )

        return service.changeManager(
            ChangeProjectManagerCommand(
                operationId = operationId,
                projectId =
                    projectId.toUuid(
                        "INVALID_PROJECT_ID",
                    ),
                baseVersion =
                    request.baseVersion,
                principal =
                    request.principal.toPrincipal(),
                reason = request.reason,
                clientOccurredAt =
                    request.clientOccurredAt
                        .toInstant(
                            "REJECTED_VALIDATION",
                        ),
                actorUserId =
                    context.requireIdentityId(),
                correlationId =
                    context.correlationId,
            ),
        ).toResponse(
            context.correlationId,
        )
    }

    @PostMapping("/{projectId}/start-kickoff")
    fun startKickoff(
        servletRequest: HttpServletRequest,
        @PathVariable
        projectId: String,
        @RequestHeader(name = "Idempotency-Key")
        idempotencyKey: String,
        @RequestBody
        request: ProjectTransitionRequest,
    ): ProjectCommandResponse =
        transition(
            servletRequest,
            projectId,
            idempotencyKey,
            request,
            service::startKickoff,
        )

    @PostMapping("/{projectId}/complete-kickoff")
    fun completeKickoff(
        servletRequest: HttpServletRequest,
        @PathVariable
        projectId: String,
        @RequestHeader(name = "Idempotency-Key")
        idempotencyKey: String,
        @RequestBody
        request: ProjectTransitionRequest,
    ): ProjectCommandResponse =
        transition(
            servletRequest,
            projectId,
            idempotencyKey,
            request,
            service::completeKickoff,
        )

    @PostMapping("/{projectId}/sites")
    fun attachSite(
        servletRequest: HttpServletRequest,
        @PathVariable
        projectId: String,
        @RequestHeader(name = "Idempotency-Key")
        idempotencyKey: String,
        @RequestBody
        request: AttachProjectSiteRequest,
    ): ProjectSiteCommandResponse {
        val context =
            HiltechRequestContext.current(servletRequest)
        val operationId =
            request.operationId.toUuid(
                "INVALID_OPERATION_ID",
            )
        IdempotencyKeyContract.requireMatches(
            idempotencyKey,
            operationId,
        )

        val result =
            service.attachSite(
                AttachProjectSiteCommand(
                    operationId = operationId,
                    projectId =
                        projectId.toUuid(
                            "INVALID_PROJECT_ID",
                        ),
                    baseProjectVersion =
                        request.baseProjectVersion,
                    siteId =
                        request.siteId.toUuid(
                            "INVALID_SITE_ID",
                        ),
                    projectSiteCode =
                        request.projectSiteCode,
                    accessInstructions =
                        request.accessInstructions,
                    projectSpecificNotes =
                        request.projectSpecificNotes,
                    clientOccurredAt =
                        request.clientOccurredAt
                            .toInstant(
                                "REJECTED_VALIDATION",
                            ),
                    actorUserId =
                        context.requireIdentityId(),
                    correlationId =
                        context.correlationId,
                ),
            )

        return ProjectSiteCommandResponse(
            project =
                result.project.toResponse(),
            projectSite =
                result.projectSite.toResponse(
                    includeRestricted = true,
                ),
            replayed = result.replayed,
            correlationId =
                context.correlationId,
        )
    }

    @GetMapping("/{projectId}/sites")
    fun sites(
        servletRequest: HttpServletRequest,
        @PathVariable
        projectId: String,
    ): ProjectSiteListResponse {
        val context =
            HiltechRequestContext.current(servletRequest)
        val result =
            service.projectSites(
                actorUserId =
                    context.requireIdentityId(),
                projectId =
                    projectId.toUuid(
                        "INVALID_PROJECT_ID",
                    ),
            )
        val canManage =
            result.first
        return ProjectSiteListResponse(
            items =
                result.second.map {
                    it.toResponse(
                        includeRestricted =
                            canManage,
                    )
                },
            correlationId =
                context.correlationId,
        )
    }

    private fun transition(
        servletRequest: HttpServletRequest,
        projectId: String,
        idempotencyKey: String,
        request: ProjectTransitionRequest,
        action:
            (ProjectLifecycleCommand) ->
                ProjectCommandResult,
    ): ProjectCommandResponse {
        val context =
            HiltechRequestContext.current(servletRequest)
        val operationId =
            request.operationId.toUuid(
                "INVALID_OPERATION_ID",
            )
        IdempotencyKeyContract.requireMatches(
            idempotencyKey,
            operationId,
        )

        return action(
            ProjectLifecycleCommand(
                operationId = operationId,
                projectId =
                    projectId.toUuid(
                        "INVALID_PROJECT_ID",
                    ),
                baseVersion =
                    request.baseVersion,
                clientOccurredAt =
                    request.clientOccurredAt
                        .toInstant(
                            "REJECTED_VALIDATION",
                        ),
                actorUserId =
                    context.requireIdentityId(),
                correlationId =
                    context.correlationId,
            ),
        ).toResponse(
            context.correlationId,
        )
    }
}

@RestController
@RequestMapping("/v1/sites")
class SitesController(
    private val service: ProjectsService,
) {
    @PostMapping
    fun create(
        servletRequest: HttpServletRequest,
        @RequestHeader(name = "Idempotency-Key")
        idempotencyKey: String,
        @RequestBody
        request: CreateSiteRequest,
    ): SiteCommandResponse {
        val context =
            HiltechRequestContext.current(servletRequest)
        val operationId =
            request.operationId.toUuid(
                "INVALID_OPERATION_ID",
            )
        IdempotencyKeyContract.requireMatches(
            idempotencyKey,
            operationId,
        )

        val result =
            service.createSite(
                CreateSiteCommand(
                    operationId = operationId,
                    organizationId =
                        request.organizationId.toUuid(
                            "INVALID_ORGANIZATION_ID",
                        ),
                    clientOrganizationId =
                        request.clientOrganizationId
                            .toUuid(
                                "INVALID_CLIENT_ORGANIZATION_ID",
                            ),
                    siteCode = request.siteCode,
                    name = request.name,
                    addressText =
                        request.addressText,
                    latitude = request.latitude,
                    longitude = request.longitude,
                    timezone = request.timezone,
                    clientOccurredAt =
                        request.clientOccurredAt
                            .toInstant(
                                "REJECTED_VALIDATION",
                            ),
                    actorUserId =
                        context.requireIdentityId(),
                    correlationId =
                        context.correlationId,
                ),
            )

        return SiteCommandResponse(
            site =
                result.site.toResponse(
                    includeRestricted = true,
                ),
            replayed = result.replayed,
            correlationId =
                context.correlationId,
        )
    }

    @GetMapping("/{siteId}")
    fun detail(
        servletRequest: HttpServletRequest,
        @PathVariable
        siteId: String,
    ): SiteResponse {
        val context =
            HiltechRequestContext.current(servletRequest)
        return service.siteDetail(
            actorUserId =
                context.requireIdentityId(),
            siteId =
                siteId.toUuid(
                    "INVALID_SITE_ID",
                ),
        ).toResponse(
            includeRestricted = true,
        )
    }

    @PutMapping("/{siteId}/details")
    fun update(
        servletRequest: HttpServletRequest,
        @PathVariable
        siteId: String,
        @RequestHeader(name = "Idempotency-Key")
        idempotencyKey: String,
        @RequestBody
        request: UpdateSiteRequest,
    ): SiteCommandResponse {
        val context =
            HiltechRequestContext.current(servletRequest)
        val operationId =
            request.operationId.toUuid(
                "INVALID_OPERATION_ID",
            )
        IdempotencyKeyContract.requireMatches(
            idempotencyKey,
            operationId,
        )

        val result =
            service.updateSite(
                UpdateSiteCommand(
                    operationId = operationId,
                    siteId =
                        siteId.toUuid(
                            "INVALID_SITE_ID",
                        ),
                    baseVersion =
                        request.baseVersion,
                    name = request.name,
                    addressText =
                        request.addressText,
                    latitude = request.latitude,
                    longitude = request.longitude,
                    timezone = request.timezone,
                    status =
                        request.status.toEnum(
                            "REJECTED_VALIDATION",
                        ),
                    clientOccurredAt =
                        request.clientOccurredAt
                            .toInstant(
                                "REJECTED_VALIDATION",
                            ),
                    actorUserId =
                        context.requireIdentityId(),
                    correlationId =
                        context.correlationId,
                ),
            )

        return SiteCommandResponse(
            site =
                result.site.toResponse(
                    includeRestricted = true,
                ),
            replayed = result.replayed,
            correlationId =
                context.correlationId,
        )
    }
}

@RestController
@RequestMapping("/v1/project-sites")
class ProjectSitesController(
    private val service: ProjectsService,
) {
    @GetMapping("/{projectSiteId}")
    fun detail(
        servletRequest: HttpServletRequest,
        @PathVariable
        projectSiteId: String,
    ): ProjectSiteResponse {
        val context =
            HiltechRequestContext.current(servletRequest)
        val result =
            service.projectSiteDetail(
                actorUserId =
                    context.requireIdentityId(),
                projectSiteId =
                    projectSiteId.toUuid(
                        "INVALID_PROJECT_SITE_ID",
                    ),
            )

        return result.second.toResponse(
            includeRestricted =
                result.first,
        )
    }
}

private fun ProjectPrincipalRequest.toPrincipal():
    ProjectPrincipalInput =
    ProjectPrincipalInput(
        principalType =
            principalType.toEnum(
                "PROJECT_RESPONSIBILITY_INVALID",
            ),
        principalId =
            principalId.toUuid(
                "PROJECT_RESPONSIBILITY_INVALID",
            ),
    )

private fun ProjectCommandResult.toResponse(
    correlationId: String,
): ProjectCommandResponse =
    ProjectCommandResponse(
        project =
            project.toResponse(),
        replayed = replayed,
        correlationId = correlationId,
    )

private fun ProjectSnapshot.toResponse():
    ProjectSummaryResponse =
    ProjectSummaryResponse(
        projectId = projectId.toString(),
        organizationId =
            organizationId.toString(),
        projectCode = projectCode,
        name = name,
        clientOrganizationId =
            clientOrganizationId.toString(),
        clientDisplayName =
            clientDisplayName,
        sourceType = sourceType.name,
        sourceExternalReference =
            sourceExternalReference,
        lifecycleState =
            lifecycleState.name,
        currentResponsibility =
            responsibility?.let {
                ProjectResponsibilityResponse(
                    responsibilityId =
                        it.responsibilityId.toString(),
                    principalType =
                        it.principalType.name,
                    principalId =
                        it.principalId.toString(),
                    principalLabel =
                        it.principalLabel,
                    resolutionState =
                        it.resolutionState.name,
                    effectiveFrom =
                        it.effectiveFrom.toString(),
                    version = it.version,
                )
            } ?: ProjectResponsibilityResponse(
                responsibilityId = null,
                principalType = null,
                principalId = null,
                principalLabel = null,
                resolutionState =
                    ProjectResponsibilityResolutionState
                        .UNASSIGNED.name,
                effectiveFrom = null,
                version = null,
            ),
        startDatePlanned =
            startDatePlanned?.toString(),
        endDatePlanned =
            endDatePlanned?.toString(),
        baselineVersion =
            baselineVersion,
        siteCount = siteCount,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        version = version,
    )

private fun SiteSnapshot.toResponse(
    includeRestricted: Boolean,
): SiteResponse =
    SiteResponse(
        siteId = siteId.toString(),
        organizationId =
            organizationId.toString(),
        clientOrganizationId =
            clientOrganizationId.toString(),
        clientDisplayName =
            clientDisplayName,
        siteCode = siteCode,
        name = name,
        addressText =
            addressText.takeIf {
                includeRestricted
            },
        latitude =
            latitude.takeIf {
                includeRestricted
            },
        longitude =
            longitude.takeIf {
                includeRestricted
            },
        timezone = timezone,
        status = status.name,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        version = version,
    )

private fun ProjectSiteSnapshot.toResponse(
    includeRestricted: Boolean,
): ProjectSiteResponse =
    ProjectSiteResponse(
        projectSiteId =
            projectSiteId.toString(),
        organizationId =
            organizationId.toString(),
        projectId =
            projectId.toString(),
        site =
            site.toResponse(
                includeRestricted,
            ),
        projectSiteCode =
            projectSiteCode,
        lifecycleState =
            lifecycleState.name,
        accessInstructions =
            accessInstructions.takeIf {
                includeRestricted
            },
        projectSpecificNotes =
            projectSpecificNotes.takeIf {
                includeRestricted
            },
        activeFrom =
            activeFrom?.toString(),
        activeUntil =
            activeUntil?.toString(),
        version = version,
    )

private fun String.toUuid(
    code: String,
): UUID =
    runCatching {
        UUID.fromString(this)
    }.getOrElse {
        throw ProductApiException(
            code = code,
            message =
                "A Project/Site identifier is invalid.",
            status =
                HttpStatus.BAD_REQUEST,
        )
    }

private fun String.toLocalDate(
    code: String,
): LocalDate =
    runCatching {
        LocalDate.parse(this)
    }.getOrElse {
        throw ProductApiException(
            code = code,
            message =
                "A Project date is invalid.",
            status =
                HttpStatus.BAD_REQUEST,
        )
    }

private fun String.toInstant(
    code: String,
): Instant =
    runCatching {
        Instant.parse(this)
    }.getOrElse {
        throw ProductApiException(
            code = code,
            message =
                "clientOccurredAt is invalid.",
            status =
                HttpStatus.BAD_REQUEST,
        )
    }

private inline fun <
    reified T : Enum<T>,
> String.toEnum(
    code: String,
): T =
    runCatching {
        enumValueOf<T>(
            trim().uppercase(),
        )
    }.getOrElse {
        throw ProductApiException(
            code = code,
            message =
                "A Project/Site enum value is invalid.",
            status =
                HttpStatus.BAD_REQUEST,
        )
    }

private fun HiltechRequestContextSnapshot
    .requireIdentityId(): UUID =
    identityId
        ?.let {
            runCatching {
                UUID.fromString(it)
            }.getOrNull()
        }
        ?: throw ProductApiException(
            code = "UNAUTHENTICATED",
            message =
                "Authentication is required.",
            status =
                HttpStatus.UNAUTHORIZED,
        )
