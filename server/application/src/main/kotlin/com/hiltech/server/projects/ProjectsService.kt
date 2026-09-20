package com.hiltech.server.projects

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.security.AuthorizationDesiredState
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

@Component
class ProjectsService(
    private val persistence: ProjectsPersistencePort,
    private val authorization: ProjectAuthorizationPort,
    private val idempotency: IdempotentCommandExecutor,
    private val authorizationProjection: ProjectAuthorizationProjectionBridge,
    private val audit: AuditEventWriter,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    fun createProject(command: CreateProjectCommand): ProjectCommandResult {
        requireProjectAdmin(command.actorUserId, command.organizationId)
        val name = requiredText(command.name, 240, "REJECTED_VALIDATION")
        val sourceReference =
            normalizeSourceReference(
                command.sourceType,
                command.sourceExternalReference,
            )
        validateDates(command.startDatePlanned, command.endDatePlanned)

        val projectId = deterministicId("project", command.operationId)
        val fingerprint =
            IdempotencyKeyContract.fingerprint(
                listOf(
                    command.organizationId,
                    command.sourceType,
                    sourceReference,
                    command.explicitProjectCode?.trim(),
                    name,
                    command.clientOrganizationId,
                    command.initialResponsibility?.principalType,
                    command.initialResponsibility?.principalId,
                    command.startDatePlanned,
                    command.endDatePlanned,
                    command.clientOccurredAt,
                ).joinToString("|"),
            )

        val execution =
            idempotency.execute(
                IdempotentCommandSpec(
                    operationId = command.operationId,
                    actorUserId = command.actorUserId,
                    commandType = "PROJECT_CREATE",
                    targetType = "PROJECT",
                    targetId = projectId,
                    requestFingerprint = fingerprint,
                    correlationId = command.correlationId,
                ),
            ) {
                val now = clock.instant()
                requireActiveOrganizations(
                    command.organizationId,
                    command.clientOrganizationId,
                )
                requireProjectAdmin(
                    command.actorUserId,
                    command.organizationId,
                )

                val policy =
                    requireProjectCodePolicy(
                        command.organizationId,
                        now,
                    )
                val projectCode =
                    allocateProjectCode(
                        organizationId = command.organizationId,
                        explicitCode = command.explicitProjectCode,
                        policy = policy,
                        at = now,
                    )
                val principal =
                    command.initialResponsibility?.let {
                        requirePrincipal(
                            command.organizationId,
                            it,
                            now,
                        )
                    }

                try {
                    persistence.insertProject(
                        projectId = projectId,
                        organizationId = command.organizationId,
                        projectCode = projectCode,
                        name = name,
                        clientOrganizationId =
                            command.clientOrganizationId,
                        sourceType = command.sourceType,
                        sourceExternalReference = sourceReference,
                        startDatePlanned =
                            command.startDatePlanned,
                        endDatePlanned =
                            command.endDatePlanned,
                        actorUserId = command.actorUserId,
                        createdAt = now,
                    )
                } catch (
                    failure: DataIntegrityViolationException,
                ) {
                    throw projectError(
                        "PROJECT_CONSTRAINT_CONFLICT",
                        "The Project conflicts with current authoritative data.",
                        HttpStatus.CONFLICT,
                    )
                }

                val createdEvent =
                    ProjectCreated(
                        projectId = projectId,
                        organizationId = command.organizationId,
                        sourceVersion = 1,
                        actorUserId = command.actorUserId,
                        occurredAt = now,
                        correlationId = command.correlationId,
                    )

                authorizationProjection.projectCreated(
                    projectId = projectId,
                    organizationId = command.organizationId,
                    sourceVersion = 1,
                    eventId = createdEvent.eventId,
                    occurredAt = now,
                )

                principal?.let {
                    val responsibilityId =
                        deterministicId(
                            "project-responsibility",
                            command.operationId,
                        )
                    val responsibility =
                        persistence.insertInitialResponsibility(
                            responsibilityId = responsibilityId,
                            projectId = projectId,
                            organizationId = command.organizationId,
                            principal = it,
                            operationId = command.operationId,
                            actorUserId = command.actorUserId,
                            at = now,
                        )

                    authorizationProjection.responsibility(
                        responsibility = responsibility,
                        desiredState =
                            AuthorizationDesiredState.PRESENT,
                        eventId = UUID.randomUUID(),
                        occurredAt = now,
                    )
                    events.publishEvent(
                        ProjectResponsibilityChanged(
                            projectId = projectId,
                            organizationId = command.organizationId,
                            responsibilityId =
                                responsibility.responsibilityId,
                            sourceVersion =
                                responsibility.version,
                            actorUserId = command.actorUserId,
                            occurredAt = now,
                            correlationId = command.correlationId,
                        ),
                    )
                }

                audit.append(
                    AuditEventRecord(
                        actorUserId = command.actorUserId,
                        action = "PROJECT_CREATED",
                        targetType = "PROJECT",
                        targetId = projectId,
                        newStateRef =
                            ProjectLifecycleState.DRAFT.name,
                        safeDiffJson =
                            """{"fields":["projectCode","name","client","source","responsibility","plannedDates"]}""",
                        occurredAt = now,
                        correlationId = command.correlationId,
                        configRevisionRefsJson =
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("family", "code-policies")
                                        put(
                                            "revisionId",
                                            policy.configRevisionId.toString(),
                                        )
                                    },
                                )
                            }.toString(),
                    ),
                )
                events.publishEvent(createdEvent)

                IdempotentCommandOutcome(
                    resultCode = "PROJECT_CREATED",
                    resultPayloadJson =
                        buildJsonObject {
                            put("projectId", projectId.toString())
                            put("projectCode", projectCode)
                        }.toString(),
                )
            }

        return ProjectCommandResult(
            project =
                requireNotNull(
                    persistence.project(
                        projectId,
                        clock.instant(),
                    ),
                ),
            replayed = execution.replayed,
        )
    }

    fun updateProjectDetails(
        command: UpdateProjectDetailsCommand,
    ): ProjectCommandResult {
        requirePositiveVersion(command.baseVersion)
        val initial = requireProject(command.projectId)
        requireManageProject(command.actorUserId, initial)

        val name =
            requiredText(
                command.name,
                240,
                "REJECTED_VALIDATION",
            )
        validateDates(
            command.startDatePlanned,
            command.endDatePlanned,
        )

        val execution =
            idempotency.execute(
                IdempotentCommandSpec(
                    operationId = command.operationId,
                    actorUserId = command.actorUserId,
                    commandType = "PROJECT_UPDATE_DETAILS",
                    targetType = "PROJECT",
                    targetId = command.projectId,
                    requestFingerprint =
                        IdempotencyKeyContract.fingerprint(
                            listOf(
                                command.projectId,
                                command.baseVersion,
                                name,
                                command.startDatePlanned,
                                command.endDatePlanned,
                                command.clientOccurredAt,
                            ).joinToString("|"),
                        ),
                    correlationId = command.correlationId,
                ),
            ) {
                val now = clock.instant()
                val current = requireProject(command.projectId)
                requireManageProject(command.actorUserId, current)

                if (
                    !persistence.updateProjectDetails(
                        projectId = command.projectId,
                        expectedVersion = command.baseVersion,
                        name = name,
                        startDatePlanned =
                            command.startDatePlanned,
                        endDatePlanned =
                            command.endDatePlanned,
                        at = now,
                    )
                ) {
                    throw versionOrStateConflict()
                }

                audit.append(
                    AuditEventRecord(
                        actorUserId = command.actorUserId,
                        action = "PROJECT_UPDATED",
                        targetType = "PROJECT",
                        targetId = command.projectId,
                        previousStateRef =
                            current.lifecycleState.name,
                        newStateRef =
                            current.lifecycleState.name,
                        safeDiffJson =
                            """{"fields":["name","plannedDates"]}""",
                        occurredAt = now,
                        correlationId = command.correlationId,
                    ),
                )

                IdempotentCommandOutcome(
                    resultCode = "PROJECT_UPDATED",
                    resultPayloadJson =
                        buildJsonObject {
                            put(
                                "projectId",
                                command.projectId.toString(),
                            )
                        }.toString(),
                )
            }

        return ProjectCommandResult(
            project = requireProject(command.projectId),
            replayed = execution.replayed,
        )
    }

    fun changeManager(
        command: ChangeProjectManagerCommand,
    ): ProjectCommandResult {
        requirePositiveVersion(command.baseVersion)
        val initial = requireProject(command.projectId)
        requireManageProject(command.actorUserId, initial)
        val reason =
            optionalText(
                command.reason,
                1000,
            )

        val execution =
            idempotency.execute(
                IdempotentCommandSpec(
                    operationId = command.operationId,
                    actorUserId = command.actorUserId,
                    commandType = "PROJECT_CHANGE_MANAGER",
                    targetType = "PROJECT",
                    targetId = command.projectId,
                    requestFingerprint =
                        IdempotencyKeyContract.fingerprint(
                            listOf(
                                command.projectId,
                                command.baseVersion,
                                command.principal.principalType,
                                command.principal.principalId,
                                reason,
                                command.clientOccurredAt,
                            ).joinToString("|"),
                        ),
                    correlationId = command.correlationId,
                ),
            ) {
                val now = clock.instant()
                val current = requireProject(command.projectId)
                requireManageProject(command.actorUserId, current)
                val principal =
                    requirePrincipal(
                        current.organizationId,
                        command.principal,
                        now,
                    )

                if (
                    current.responsibility?.principalType ==
                    principal.principalType &&
                    current.responsibility?.principalId ==
                    principal.principalId
                ) {
                    throw projectError(
                        "PROJECT_RESPONSIBILITY_INVALID",
                        "The selected Project responsibility is already current.",
                        HttpStatus.CONFLICT,
                    )
                }

                if (
                    !persistence.bumpProjectVersion(
                        projectId = command.projectId,
                        expectedVersion = command.baseVersion,
                        at = now,
                    )
                ) {
                    throw versionOrStateConflict()
                }

                val responsibilityId =
                    deterministicId(
                        "project-responsibility",
                        command.operationId,
                    )
                val changed =
                    persistence.replaceResponsibility(
                        responsibilityId = responsibilityId,
                        projectId = command.projectId,
                        organizationId = current.organizationId,
                        principal = principal,
                        operationId = command.operationId,
                        actorUserId = command.actorUserId,
                        reason = reason,
                        at = now,
                    )

                changed.previous?.let {
                    authorizationProjection.responsibility(
                        responsibility = it,
                        desiredState =
                            AuthorizationDesiredState.ABSENT,
                        eventId = UUID.randomUUID(),
                        occurredAt = now,
                    )
                }
                authorizationProjection.responsibility(
                    responsibility = changed.current,
                    desiredState =
                        AuthorizationDesiredState.PRESENT,
                    eventId = UUID.randomUUID(),
                    occurredAt = now,
                )

                audit.append(
                    AuditEventRecord(
                        actorUserId = command.actorUserId,
                        action =
                            "PROJECT_RESPONSIBILITY_CHANGED",
                        targetType = "PROJECT",
                        targetId = command.projectId,
                        previousStateRef =
                            current.responsibility
                                ?.principalType
                                ?.name,
                        newStateRef =
                            changed.current.principalType.name,
                        safeDiffJson =
                            """{"fields":["projectResponsibility"]}""",
                        occurredAt = now,
                        correlationId = command.correlationId,
                        reason = reason,
                    ),
                )
                events.publishEvent(
                    ProjectResponsibilityChanged(
                        projectId = command.projectId,
                        organizationId = current.organizationId,
                        responsibilityId =
                            changed.current.responsibilityId,
                        sourceVersion =
                            changed.current.version,
                        actorUserId = command.actorUserId,
                        occurredAt = now,
                        correlationId = command.correlationId,
                    ),
                )

                IdempotentCommandOutcome(
                    resultCode =
                        "PROJECT_RESPONSIBILITY_CHANGED",
                    resultPayloadJson =
                        buildJsonObject {
                            put(
                                "projectId",
                                command.projectId.toString(),
                            )
                            put(
                                "responsibilityId",
                                changed.current
                                    .responsibilityId
                                    .toString(),
                            )
                        }.toString(),
                )
            }

        return ProjectCommandResult(
            project = requireProject(command.projectId),
            replayed = execution.replayed,
        )
    }

    fun startKickoff(
        command: ProjectLifecycleCommand,
    ): ProjectCommandResult =
        transition(
            command = command,
            expected = ProjectLifecycleState.DRAFT,
            target = ProjectLifecycleState.KICKOFF,
            action = "PROJECT_KICKOFF_STARTED",
        )

    fun completeKickoff(
        command: ProjectLifecycleCommand,
    ): ProjectCommandResult =
        transition(
            command = command,
            expected = ProjectLifecycleState.KICKOFF,
            target = ProjectLifecycleState.PLANNING,
            action = "PROJECT_KICKOFF_COMPLETED",
        )

    fun createSite(
        command: CreateSiteCommand,
    ): SiteCommandResult {
        requireProjectAdmin(
            command.actorUserId,
            command.organizationId,
        )
        val siteCode =
            normalizeCode(
                command.siteCode,
                64,
            )
        val name =
            requiredText(
                command.name,
                240,
                "REJECTED_VALIDATION",
            )
        val address =
            optionalText(
                command.addressText,
                4000,
            )
        val timezone =
            optionalText(
                command.timezone,
                64,
            )
        validateCoordinates(
            command.latitude,
            command.longitude,
        )

        val siteId =
            deterministicId("site", command.operationId)
        val execution =
            idempotency.execute(
                IdempotentCommandSpec(
                    operationId = command.operationId,
                    actorUserId = command.actorUserId,
                    commandType = "SITE_CREATE",
                    targetType = "SITE",
                    targetId = siteId,
                    requestFingerprint =
                        IdempotencyKeyContract.fingerprint(
                            listOf(
                                command.organizationId,
                                command.clientOrganizationId,
                                siteCode,
                                name,
                                address,
                                command.latitude,
                                command.longitude,
                                timezone,
                                command.clientOccurredAt,
                            ).joinToString("|"),
                        ),
                    correlationId = command.correlationId,
                ),
            ) {
                val now = clock.instant()
                requireActiveOrganizations(
                    command.organizationId,
                    command.clientOrganizationId,
                )
                requireProjectAdmin(
                    command.actorUserId,
                    command.organizationId,
                )

                if (
                    persistence.siteCodeExists(
                        command.organizationId,
                        command.clientOrganizationId,
                        siteCode,
                    )
                ) {
                    throw projectError(
                        "SITE_CODE_CONFLICT",
                        "The Site code is already in use for this client.",
                        HttpStatus.CONFLICT,
                    )
                }

                try {
                    persistence.insertSite(
                        siteId = siteId,
                        organizationId =
                            command.organizationId,
                        clientOrganizationId =
                            command.clientOrganizationId,
                        siteCode = siteCode,
                        name = name,
                        addressText = address,
                        latitude = command.latitude,
                        longitude = command.longitude,
                        timezone = timezone,
                        actorUserId = command.actorUserId,
                        at = now,
                    )
                } catch (
                    failure: DataIntegrityViolationException,
                ) {
                    throw projectError(
                        "SITE_CONSTRAINT_CONFLICT",
                        "The Site conflicts with current authoritative data.",
                        HttpStatus.CONFLICT,
                    )
                }

                val event =
                    SiteCreated(
                        siteId = siteId,
                        organizationId = command.organizationId,
                        sourceVersion = 1,
                        actorUserId = command.actorUserId,
                        occurredAt = now,
                        correlationId = command.correlationId,
                    )
                authorizationProjection.siteCreated(
                    siteId = siteId,
                    organizationId = command.organizationId,
                    sourceVersion = 1,
                    eventId = event.eventId,
                    occurredAt = now,
                )
                audit.append(
                    AuditEventRecord(
                        actorUserId = command.actorUserId,
                        action = "SITE_CREATED",
                        targetType = "SITE",
                        targetId = siteId,
                        newStateRef = "ACTIVE",
                        safeDiffJson =
                            """{"fields":["siteCode","name","client","location"]}""",
                        occurredAt = now,
                        correlationId = command.correlationId,
                    ),
                )
                events.publishEvent(event)

                IdempotentCommandOutcome(
                    resultCode = "SITE_CREATED",
                    resultPayloadJson =
                        buildJsonObject {
                            put("siteId", siteId.toString())
                        }.toString(),
                )
            }

        return SiteCommandResult(
            site = requireNotNull(persistence.site(siteId)),
            replayed = execution.replayed,
        )
    }

    fun updateSite(
        command: UpdateSiteCommand,
    ): SiteCommandResult {
        requirePositiveVersion(command.baseVersion)
        val initial = requireSite(command.siteId)
        requireManageSite(command.actorUserId, initial)

        val name =
            requiredText(
                command.name,
                240,
                "REJECTED_VALIDATION",
            )
        val address =
            optionalText(
                command.addressText,
                4000,
            )
        val timezone =
            optionalText(
                command.timezone,
                64,
            )
        validateCoordinates(
            command.latitude,
            command.longitude,
        )

        val execution =
            idempotency.execute(
                IdempotentCommandSpec(
                    operationId = command.operationId,
                    actorUserId = command.actorUserId,
                    commandType = "SITE_UPDATE",
                    targetType = "SITE",
                    targetId = command.siteId,
                    requestFingerprint =
                        IdempotencyKeyContract.fingerprint(
                            listOf(
                                command.siteId,
                                command.baseVersion,
                                name,
                                address,
                                command.latitude,
                                command.longitude,
                                timezone,
                                command.status,
                                command.clientOccurredAt,
                            ).joinToString("|"),
                        ),
                    correlationId = command.correlationId,
                ),
            ) {
                val now = clock.instant()
                val current = requireSite(command.siteId)
                requireManageSite(command.actorUserId, current)

                if (
                    !persistence.updateSite(
                        siteId = command.siteId,
                        expectedVersion = command.baseVersion,
                        name = name,
                        addressText = address,
                        latitude = command.latitude,
                        longitude = command.longitude,
                        timezone = timezone,
                        status = command.status,
                        at = now,
                    )
                ) {
                    throw versionOrStateConflict()
                }

                val updated = requireSite(command.siteId)
                audit.append(
                    AuditEventRecord(
                        actorUserId = command.actorUserId,
                        action = "SITE_UPDATED",
                        targetType = "SITE",
                        targetId = command.siteId,
                        previousStateRef =
                            current.status.name,
                        newStateRef = updated.status.name,
                        safeDiffJson =
                            """{"fields":["name","location","status"]}""",
                        occurredAt = now,
                        correlationId = command.correlationId,
                    ),
                )
                events.publishEvent(
                    SiteUpdated(
                        siteId = command.siteId,
                        organizationId =
                            updated.organizationId,
                        sourceVersion = updated.version,
                        actorUserId = command.actorUserId,
                        occurredAt = now,
                        correlationId = command.correlationId,
                    ),
                )

                IdempotentCommandOutcome(
                    resultCode = "SITE_UPDATED",
                    resultPayloadJson =
                        buildJsonObject {
                            put(
                                "siteId",
                                command.siteId.toString(),
                            )
                        }.toString(),
                )
            }

        return SiteCommandResult(
            site = requireSite(command.siteId),
            replayed = execution.replayed,
        )
    }

    fun attachSite(
        command: AttachProjectSiteCommand,
    ): ProjectSiteCommandResult {
        requirePositiveVersion(
            command.baseProjectVersion,
        )
        val initial = requireProject(command.projectId)
        requireManageProject(command.actorUserId, initial)

        val projectSiteCode =
            optionalText(
                command.projectSiteCode,
                64,
            )
        val accessInstructions =
            optionalText(
                command.accessInstructions,
                4000,
            )
        val notes =
            optionalText(
                command.projectSpecificNotes,
                4000,
            )
        val projectSiteId =
            deterministicId(
                "project-site",
                command.operationId,
            )

        val execution =
            idempotency.execute(
                IdempotentCommandSpec(
                    operationId = command.operationId,
                    actorUserId = command.actorUserId,
                    commandType = "PROJECT_SITE_ATTACH",
                    targetType = "PROJECT_SITE",
                    targetId = projectSiteId,
                    requestFingerprint =
                        IdempotencyKeyContract.fingerprint(
                            listOf(
                                command.projectId,
                                command.baseProjectVersion,
                                command.siteId,
                                projectSiteCode,
                                accessInstructions,
                                notes,
                                command.clientOccurredAt,
                            ).joinToString("|"),
                        ),
                    correlationId = command.correlationId,
                ),
            ) {
                val now = clock.instant()
                val project =
                    requireProject(command.projectId)
                requireManageProject(
                    command.actorUserId,
                    project,
                )
                val site = requireSite(command.siteId)

                if (
                    site.organizationId !=
                    project.organizationId ||
                    site.clientOrganizationId !=
                    project.clientOrganizationId
                ) {
                    throw projectError(
                        "SITE_CLIENT_MISMATCH",
                        "The Site does not belong to the Project tenant/client context.",
                        HttpStatus.CONFLICT,
                    )
                }

                if (
                    persistence.projectSiteExists(
                        project.projectId,
                        site.siteId,
                    )
                ) {
                    throw projectError(
                        "PROJECT_SITE_ALREADY_ATTACHED",
                        "The Site is already attached to this Project.",
                        HttpStatus.CONFLICT,
                    )
                }

                if (
                    !persistence.bumpProjectVersion(
                        projectId = project.projectId,
                        expectedVersion =
                            command.baseProjectVersion,
                        at = now,
                    )
                ) {
                    throw versionOrStateConflict()
                }

                try {
                    persistence.attachSite(
                        projectSiteId = projectSiteId,
                        organizationId =
                            project.organizationId,
                        projectId = project.projectId,
                        siteId = site.siteId,
                        projectSiteCode =
                            projectSiteCode,
                        accessInstructions =
                            accessInstructions,
                        projectSpecificNotes = notes,
                    )
                } catch (
                    failure: DataIntegrityViolationException,
                ) {
                    throw projectError(
                        "PROJECT_SITE_ALREADY_ATTACHED",
                        "The Site could not be attached because the current relation conflicts.",
                        HttpStatus.CONFLICT,
                    )
                }

                val projectSite =
                    requireNotNull(
                        persistence.projectSite(
                            projectSiteId,
                        ),
                    )
                val event =
                    ProjectSiteAttached(
                        projectSiteId = projectSiteId,
                        projectId = project.projectId,
                        siteId = site.siteId,
                        organizationId =
                            project.organizationId,
                        sourceVersion = 1,
                        actorUserId = command.actorUserId,
                        occurredAt = now,
                        correlationId = command.correlationId,
                    )
                authorizationProjection.projectSiteAttached(
                    projectSite = projectSite,
                    eventId = event.eventId,
                    occurredAt = now,
                )
                audit.append(
                    AuditEventRecord(
                        actorUserId = command.actorUserId,
                        action = "PROJECT_SITE_ATTACHED",
                        targetType = "PROJECT_SITE",
                        targetId = projectSiteId,
                        newStateRef = "PLANNED",
                        safeDiffJson =
                            """{"fields":["project","site","projectSiteCode"]}""",
                        occurredAt = now,
                        correlationId = command.correlationId,
                    ),
                )
                events.publishEvent(event)

                IdempotentCommandOutcome(
                    resultCode =
                        "PROJECT_SITE_ATTACHED",
                    resultPayloadJson =
                        buildJsonObject {
                            put(
                                "projectSiteId",
                                projectSiteId.toString(),
                            )
                        }.toString(),
                )
            }

        return ProjectSiteCommandResult(
            project = requireProject(command.projectId),
            projectSite =
                requireNotNull(
                    persistence.projectSite(
                        projectSiteId,
                    ),
                ),
            replayed = execution.replayed,
        )
    }

    fun myProjects(
        actorUserId: UUID,
        organizationId: UUID,
        limit: Int,
    ): List<ProjectSnapshot> =
        persistence.listProjects(
            organizationId = organizationId,
            at = clock.instant(),
            limit = limit.coerceIn(1, 200),
        ).filter {
            authorization.canViewProject(
                actorUserId,
                it,
            )
        }

    fun projectDetail(
        actorUserId: UUID,
        projectId: UUID,
    ): ProjectSnapshot {
        val project = requireProject(projectId)
        if (
            !authorization.canViewProject(
                actorUserId,
                project,
            )
        ) {
            hidden()
        }
        return project
    }

    fun siteDetail(
        actorUserId: UUID,
        siteId: UUID,
    ): SiteSnapshot {
        val site = requireSite(siteId)
        if (
            !authorization.canManageSite(
                actorUserId,
                site,
            )
        ) {
            hidden()
        }
        return site
    }

    fun projectSites(
        actorUserId: UUID,
        projectId: UUID,
    ): Pair<Boolean, List<ProjectSiteSnapshot>> {
        val project =
            projectDetail(
                actorUserId,
                projectId,
            )
        val canManage =
            authorization.canManageProject(
                actorUserId,
                project,
            )
        return canManage to
            persistence.listProjectSites(
                projectId,
            )
    }

    fun projectSiteDetail(
        actorUserId: UUID,
        projectSiteId: UUID,
    ): Pair<Boolean, ProjectSiteSnapshot> {
        val projectSite =
            persistence.projectSite(
                projectSiteId,
            ) ?: hidden()
        val project =
            projectDetail(
                actorUserId,
                projectSite.projectId,
            )
        return authorization.canManageProject(
            actorUserId,
            project,
        ) to projectSite
    }

    private fun transition(
        command: ProjectLifecycleCommand,
        expected: ProjectLifecycleState,
        target: ProjectLifecycleState,
        action: String,
    ): ProjectCommandResult {
        requirePositiveVersion(command.baseVersion)
        val initial = requireProject(command.projectId)
        requireManageProject(command.actorUserId, initial)

        val execution =
            idempotency.execute(
                IdempotentCommandSpec(
                    operationId = command.operationId,
                    actorUserId = command.actorUserId,
                    commandType = action,
                    targetType = "PROJECT",
                    targetId = command.projectId,
                    requestFingerprint =
                        IdempotencyKeyContract.fingerprint(
                            listOf(
                                command.projectId,
                                command.baseVersion,
                                expected,
                                target,
                                command.clientOccurredAt,
                            ).joinToString("|"),
                        ),
                    correlationId = command.correlationId,
                ),
            ) {
                val now = clock.instant()
                val current = requireProject(command.projectId)
                requireManageProject(command.actorUserId, current)

                if (current.lifecycleState != expected) {
                    throw projectError(
                        "REJECTED_STATE",
                        "The Project is not in the required lifecycle state.",
                        HttpStatus.CONFLICT,
                    )
                }

                if (
                    !persistence.transitionProject(
                        projectId = command.projectId,
                        expectedVersion = command.baseVersion,
                        expectedState = expected,
                        targetState = target,
                        at = now,
                    )
                ) {
                    throw versionOrStateConflict()
                }

                val updated = requireProject(command.projectId)
                audit.append(
                    AuditEventRecord(
                        actorUserId = command.actorUserId,
                        action = action,
                        targetType = "PROJECT",
                        targetId = command.projectId,
                        previousStateRef = expected.name,
                        newStateRef = target.name,
                        safeDiffJson =
                            """{"fields":["lifecycleState"]}""",
                        occurredAt = now,
                        correlationId = command.correlationId,
                    ),
                )
                events.publishEvent(
                    ProjectLifecycleChanged(
                        projectId = command.projectId,
                        organizationId =
                            updated.organizationId,
                        fromState = expected,
                        toState = target,
                        sourceVersion = updated.version,
                        actorUserId = command.actorUserId,
                        occurredAt = now,
                        correlationId = command.correlationId,
                    ),
                )

                IdempotentCommandOutcome(
                    resultCode = action,
                    resultPayloadJson =
                        buildJsonObject {
                            put(
                                "projectId",
                                command.projectId.toString(),
                            )
                            put("state", target.name)
                        }.toString(),
                )
            }

        return ProjectCommandResult(
            project = requireProject(command.projectId),
            replayed = execution.replayed,
        )
    }

    private fun requireProjectCodePolicy(
        organizationId: UUID,
        at: Instant,
    ): ProjectCodePolicySnapshot {
        val policies =
            persistence.activeProjectCodePolicies(
                organizationId,
                at,
            )

        if (policies.isEmpty()) {
            throw projectError(
                "PROJECT_CODE_POLICY_NOT_CONFIGURED",
                "No active Project CodePolicy is configured.",
                HttpStatus.CONFLICT,
            )
        }

        val preferredScope =
            policies.first().scopeType
        val selected =
            policies.filter {
                it.scopeType == preferredScope
            }

        if (selected.size != 1) {
            throw projectError(
                "PROJECT_CODE_POLICY_AMBIGUOUS",
                "More than one active Project CodePolicy applies.",
                HttpStatus.CONFLICT,
            )
        }

        val policy = selected.single()
        if (
            policy.sequenceScope !in
            setOf(
                "ORGANIZATION",
                "OBJECT_TYPE",
            )
        ) {
            throw projectError(
                "PROJECT_CODE_POLICY_INVALID",
                "The active Project CodePolicy has an unsupported sequence scope.",
                HttpStatus.CONFLICT,
            )
        }

        return policy
    }

    private fun allocateProjectCode(
        organizationId: UUID,
        explicitCode: String?,
        policy: ProjectCodePolicySnapshot,
        at: Instant,
    ): String {
        val explicit =
            explicitCode?.let {
                normalizeCode(
                    it,
                    64,
                )
            }

        if (explicit != null) {
            if (!policy.manualOverrideAllowed) {
                throw projectError(
                    "PROJECT_CODE_POLICY_REJECTED_MANUAL",
                    "The active Project CodePolicy does not allow manual codes.",
                    HttpStatus.CONFLICT,
                )
            }
            if (
                persistence.projectCodeExists(
                    organizationId,
                    explicit,
                )
            ) {
                throw projectError(
                    "PROJECT_CODE_CONFLICT",
                    "The Project code is already in use.",
                    HttpStatus.CONFLICT,
                )
            }
            return explicit
        }

        val sequence =
            persistence.allocateCodeSequence(
                organizationId,
                policy,
                at,
            )
        val number =
            sequence.toString()
                .padStart(
                    policy.sequencePadding,
                    '0',
                )
        val parts =
            buildList {
                policy.prefix
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(::add)
                if (policy.includeYear) {
                    add(
                        at.atZone(
                            ZoneOffset.UTC,
                        ).year.toString(),
                    )
                }
                add(number)
            }
        val code =
            parts.joinToString(
                policy.separator,
            )

        if (code.length > 64) {
            throw projectError(
                "PROJECT_CODE_POLICY_INVALID",
                "The active Project CodePolicy produces a code that is too long.",
                HttpStatus.CONFLICT,
            )
        }
        if (
            persistence.projectCodeExists(
                organizationId,
                code,
            )
        ) {
            throw projectError(
                "PROJECT_CODE_CONFLICT",
                "The generated Project code is already in use.",
                HttpStatus.CONFLICT,
            )
        }

        return code
    }

    private fun requirePrincipal(
        organizationId: UUID,
        input: ProjectPrincipalInput,
        at: Instant,
    ): ProjectPrincipalContext =
        persistence.principal(
            organizationId,
            input,
            at,
        ) ?: throw projectError(
            "PROJECT_RESPONSIBILITY_INVALID",
            "The selected Project responsibility is not current in this organization.",
            HttpStatus.CONFLICT,
        )

    private fun requireProjectAdmin(
        actorUserId: UUID,
        organizationId: UUID,
    ) {
        if (
            !authorization.canManageProjects(
                actorUserId,
                organizationId,
            )
        ) {
            throw projectError(
                "PERMISSION_DENIED",
                "Project administration is not allowed.",
                HttpStatus.FORBIDDEN,
            )
        }
    }

    private fun requireManageProject(
        actorUserId: UUID,
        project: ProjectSnapshot,
    ) {
        if (
            !authorization.canManageProject(
                actorUserId,
                project,
            )
        ) {
            hidden()
        }
    }

    private fun requireManageSite(
        actorUserId: UUID,
        site: SiteSnapshot,
    ) {
        if (
            !authorization.canManageSite(
                actorUserId,
                site,
            )
        ) {
            hidden()
        }
    }

    private fun requireActiveOrganizations(
        organizationId: UUID,
        clientOrganizationId: UUID,
    ) {
        val owner =
            persistence.organization(
                organizationId,
            )
        val client =
            persistence.organization(
                clientOrganizationId,
            )

        if (
            owner == null ||
            !owner.active ||
            owner.organizationType != "HILTECH"
        ) {
            throw projectError(
                "ORGANIZATION_NOT_ACTIVE",
                "The HILTECH organization is not active.",
                HttpStatus.CONFLICT,
            )
        }

        if (
            client == null ||
            !client.active
        ) {
            throw projectError(
                "CLIENT_ORGANIZATION_NOT_ACTIVE",
                "The client organization is not active.",
                HttpStatus.CONFLICT,
            )
        }
    }

    private fun requireProject(
        projectId: UUID,
    ): ProjectSnapshot =
        persistence.project(
            projectId,
            clock.instant(),
        ) ?: hidden()

    private fun requireSite(
        siteId: UUID,
    ): SiteSnapshot =
        persistence.site(siteId)
            ?: hidden()

    private fun normalizeSourceReference(
        type: ProjectSourceType,
        raw: String?,
    ): String? =
        when (type) {
            ProjectSourceType.INTERNAL -> {
                if (!raw.isNullOrBlank()) {
                    throw projectError(
                        "PROJECT_SOURCE_INVALID",
                        "Internal Project creation cannot carry an external source reference.",
                        HttpStatus.BAD_REQUEST,
                    )
                }
                null
            }

            ProjectSourceType.IMPORT ->
                requiredText(
                    raw,
                    255,
                    "PROJECT_SOURCE_INVALID",
                )
        }

    private fun validateDates(
        start: LocalDate?,
        end: LocalDate?,
    ) {
        if (
            start != null &&
            end != null &&
            end < start
        ) {
            throw projectError(
                "REJECTED_VALIDATION",
                "Planned end date cannot precede planned start date.",
                HttpStatus.BAD_REQUEST,
            )
        }
    }

    private fun validateCoordinates(
        latitude: Double?,
        longitude: Double?,
    ) {
        if (
            latitude != null &&
            latitude !in -90.0..90.0
        ) {
            throw projectError(
                "REJECTED_VALIDATION",
                "Latitude is invalid.",
                HttpStatus.BAD_REQUEST,
            )
        }
        if (
            longitude != null &&
            longitude !in -180.0..180.0
        ) {
            throw projectError(
                "REJECTED_VALIDATION",
                "Longitude is invalid.",
                HttpStatus.BAD_REQUEST,
            )
        }
    }

    private fun normalizeCode(
        raw: String,
        max: Int,
    ): String =
        requiredText(
            raw,
            max,
            "REJECTED_VALIDATION",
        ).uppercase(Locale.ROOT)

    private fun requiredText(
        raw: String?,
        max: Int,
        code: String,
    ): String {
        val normalized = raw?.trim()
        if (
            normalized.isNullOrEmpty() ||
            normalized.length > max
        ) {
            throw projectError(
                code,
                "A required Project/Site value is invalid.",
                HttpStatus.BAD_REQUEST,
            )
        }
        return normalized
    }

    private fun optionalText(
        raw: String?,
        max: Int,
    ): String? {
        val normalized =
            raw?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null

        if (normalized.length > max) {
            throw projectError(
                "REJECTED_VALIDATION",
                "A Project/Site value is too long.",
                HttpStatus.BAD_REQUEST,
            )
        }
        return normalized
    }

    private fun requirePositiveVersion(
        version: Long,
    ) {
        if (version < 1) {
            throw projectError(
                "REJECTED_VALIDATION",
                "baseVersion must be positive.",
                HttpStatus.BAD_REQUEST,
            )
        }
    }

    private fun deterministicId(
        scope: String,
        operationId: UUID,
    ): UUID =
        UUID.nameUUIDFromBytes(
            "$scope:$operationId"
                .toByteArray(
                    StandardCharsets.UTF_8,
                ),
        )

    private fun versionOrStateConflict():
        ProductApiException =
        projectError(
            "VERSION_CONFLICT",
            "The Project/Site changed or is no longer in the expected state.",
            HttpStatus.CONFLICT,
        )

    private fun hidden(): Nothing =
        throw projectError(
            "OBJECT_NOT_VISIBLE",
            "The requested object is not available.",
            HttpStatus.NOT_FOUND,
        )

    private fun projectError(
        code: String,
        message: String,
        status: HttpStatus,
    ): ProductApiException =
        ProductApiException(
            code = code,
            message = message,
            status = status,
        )
}
