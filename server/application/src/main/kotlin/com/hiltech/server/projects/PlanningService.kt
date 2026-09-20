package com.hiltech.server.projects

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

@Component
class PlanningService(
    private val projects: ProjectsPersistencePort,
    private val planning: PlanningPersistencePort,
    private val authorization: ProjectAuthorizationPort,
    private val idempotency: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    fun plan(
        actorUserId: UUID,
        projectId: UUID,
    ): ProjectPlanSnapshot {
        val project = requireProject(projectId)
        if (!authorization.canViewProject(actorUserId, project)) {
            hidden()
        }
        return buildPlan(
            project = project,
            canEdit = authorization.canManageProject(actorUserId, project),
        )
    }

    fun createArea(command: CreateAreaCommand): PlanningMutationResult {
        val areaId = deterministicId("area", command.operationId)
        val typeCode = code(command.typeCode, 64)
        val areaCode = code(command.code, 64)
        val name = required(command.name, 160)
        requireSequence(command.sequence)

        val replayed = execute(
            operationId = command.operationId,
            actorUserId = command.actorUserId,
            projectId = command.projectId,
            baseProjectVersion = command.baseProjectVersion,
            expectedBaselineVersion = command.expectedBaselineVersion,
            commandType = "PROJECT_PLAN_AREA_CREATE",
            targetType = "AREA",
            targetId = areaId,
            correlationId = command.correlationId,
            fingerprintValues = listOf(
                command.projectId, command.projectSiteId, command.parentAreaId,
                typeCode, areaCode, name, command.sequence, command.restrictedAccess,
                command.baseProjectVersion, command.expectedBaselineVersion,
                command.clientOccurredAt,
            ),
        ) { project, now ->
            val relation = requireProjectSite(project, command.projectSiteId)
            command.parentAreaId?.let { parentId ->
                val parent = requireArea(parentId)
                if (parent.organizationId != project.organizationId || parent.siteId != relation.site.siteId) {
                    conflict("AREA_PARENT_CONTEXT_INVALID", "The parent Area is outside the selected Site context.")
                }
            }
            bumpProject(project, now)
            planning.insertArea(
                areaId = areaId,
                organizationId = project.organizationId,
                siteId = relation.site.siteId,
                parentAreaId = command.parentAreaId,
                typeCode = typeCode,
                code = areaCode,
                name = name,
                sequence = command.sequence,
                restrictedAccess = command.restrictedAccess,
                actorUserId = command.actorUserId,
                at = now,
            )
            recordChange(command, project, now, "AREA_CREATED", "AREA", areaId)
        }
        return result(command.actorUserId, command.projectId, "AREA", areaId, replayed)
    }

    fun updateArea(command: UpdateAreaCommand): PlanningMutationResult {
        requirePositive(command.baseObjectVersion)
        val typeCode = code(command.typeCode, 64)
        val areaCode = code(command.code, 64)
        val name = required(command.name, 160)
        requireSequence(command.sequence)
        val sourceProject = requireProject(command.projectId)
        requireManage(command.actorUserId, sourceProject)
        requireAreaInProject(
            sourceProject,
            requireArea(command.areaId),
        )

        val replayed = execute(
            operationId = command.operationId,
            actorUserId = command.actorUserId,
            projectId = command.projectId,
            baseProjectVersion = command.baseProjectVersion,
            expectedBaselineVersion = command.expectedBaselineVersion,
            commandType = "PROJECT_PLAN_AREA_UPDATE",
            targetType = "AREA",
            targetId = command.areaId,
            correlationId = command.correlationId,
            fingerprintValues = listOf(
                command.areaId, command.projectId, command.parentAreaId, typeCode,
                areaCode, name, command.sequence, command.restrictedAccess,
                command.baseProjectVersion, command.baseObjectVersion,
                command.expectedBaselineVersion, command.clientOccurredAt,
            ),
        ) { project, now ->
            val current = requireArea(command.areaId)
            requireAreaInProject(project, current)
            command.parentAreaId?.let { parentId ->
                if (parentId == current.areaId) {
                    conflict("AREA_CYCLE", "An Area cannot be its own parent.")
                }
                val parent = requireArea(parentId)
                if (parent.organizationId != current.organizationId || parent.siteId != current.siteId) {
                    conflict("AREA_PARENT_CONTEXT_INVALID", "The parent Area is outside the same Site.")
                }
                ensureNoAreaCycle(current.areaId, parent)
            }
            bumpProject(project, now)
            if (!planning.updateArea(
                    areaId = current.areaId,
                    expectedVersion = command.baseObjectVersion,
                    parentAreaId = command.parentAreaId,
                    typeCode = typeCode,
                    code = areaCode,
                    name = name,
                    sequence = command.sequence,
                    restrictedAccess = command.restrictedAccess,
                    at = now,
                )
            ) {
                versionConflict()
            }
            recordChange(command, project, now, "AREA_UPDATED", "AREA", current.areaId)
        }
        return result(command.actorUserId, command.projectId, "AREA", command.areaId, replayed)
    }

    fun createMilestone(command: CreateMilestoneCommand): PlanningMutationResult {
        val milestoneId = deterministicId("milestone", command.operationId)
        val milestoneCode = code(command.code, 64)
        val name = required(command.name, 240)
        val acceptance = optional(command.acceptanceRequirement, 4000)
        requireSequence(command.sequence)

        val replayed = execute(
            operationId = command.operationId,
            actorUserId = command.actorUserId,
            projectId = command.projectId,
            baseProjectVersion = command.baseProjectVersion,
            expectedBaselineVersion = command.expectedBaselineVersion,
            commandType = "PROJECT_PLAN_MILESTONE_CREATE",
            targetType = "MILESTONE",
            targetId = milestoneId,
            correlationId = command.correlationId,
            fingerprintValues = listOf(
                command.projectId, milestoneCode, name, command.plannedDate,
                command.sequence, command.clientVisible, acceptance,
                command.baseProjectVersion, command.expectedBaselineVersion,
                command.clientOccurredAt,
            ),
        ) { project, now ->
            bumpProject(project, now)
            planning.insertMilestone(
                milestoneId, project, milestoneCode, name, command.plannedDate,
                command.sequence, command.clientVisible, acceptance,
                command.actorUserId, now,
            )
            recordChange(command, project, now, "MILESTONE_CREATED", "MILESTONE", milestoneId)
        }
        return result(command.actorUserId, command.projectId, "MILESTONE", milestoneId, replayed)
    }

    fun updateMilestone(command: UpdateMilestoneCommand): PlanningMutationResult {
        requirePositive(command.baseObjectVersion)
        val milestoneCode = code(command.code, 64)
        val name = required(command.name, 240)
        val acceptance = optional(command.acceptanceRequirement, 4000)
        requireSequence(command.sequence)

        val initial = requireMilestone(command.milestoneId)
        val replayed = execute(
            operationId = command.operationId,
            actorUserId = command.actorUserId,
            projectId = initial.projectId,
            baseProjectVersion = command.baseProjectVersion,
            expectedBaselineVersion = command.expectedBaselineVersion,
            commandType = "PROJECT_PLAN_MILESTONE_UPDATE",
            targetType = "MILESTONE",
            targetId = command.milestoneId,
            correlationId = command.correlationId,
            fingerprintValues = listOf(
                command.milestoneId, milestoneCode, name, command.plannedDate,
                command.sequence, command.clientVisible, acceptance,
                command.baseProjectVersion, command.baseObjectVersion,
                command.expectedBaselineVersion, command.clientOccurredAt,
            ),
        ) { project, now ->
            val current = requireMilestone(command.milestoneId)
            if (current.projectId != project.projectId || current.baselineVersion != project.baselineVersion) {
                conflict("MILESTONE_PROJECT_MISMATCH", "The Milestone is outside the current Project baseline.")
            }
            bumpProject(project, now)
            if (!planning.updateMilestone(
                    current.milestoneId, command.baseObjectVersion, milestoneCode, name,
                    command.plannedDate, command.sequence, command.clientVisible,
                    acceptance, now,
                )
            ) {
                versionConflict()
            }
            recordChange(command, project, now, "MILESTONE_UPDATED", "MILESTONE", current.milestoneId)
        }
        return result(command.actorUserId, initial.projectId, "MILESTONE", command.milestoneId, replayed)
    }

    fun createWorkPackage(command: CreateWorkPackageCommand): PlanningMutationResult {
        val packageId = deterministicId("work-package", command.operationId)
        val packageCode = code(command.code, 64)
        val name = required(command.name, 240)
        val description = optional(command.description, 8000)
        validateDates(command.plannedStart, command.plannedEnd)
        requireSequence(command.sequence)

        val replayed = execute(
            operationId = command.operationId,
            actorUserId = command.actorUserId,
            projectId = command.projectId,
            baseProjectVersion = command.baseProjectVersion,
            expectedBaselineVersion = command.expectedBaselineVersion,
            commandType = "PROJECT_PLAN_WORK_PACKAGE_CREATE",
            targetType = "WORK_PACKAGE",
            targetId = packageId,
            correlationId = command.correlationId,
            fingerprintValues = listOf(
                command.projectId, command.projectSiteId, command.siteId,
                command.milestoneId, packageCode, name, description,
                command.owner?.principalType, command.owner?.principalId,
                command.plannedStart, command.plannedEnd, command.sequence,
                command.baseProjectVersion, command.expectedBaselineVersion,
                command.clientOccurredAt,
            ),
        ) { project, now ->
            validatePackageContext(project, command.projectSiteId, command.siteId, command.milestoneId)
            val owner = command.owner?.let { requirePrincipal(project, it, now) }
            bumpProject(project, now)
            planning.insertWorkPackage(
                packageId, project, command.projectSiteId, command.siteId,
                command.milestoneId, packageCode, name, description, owner,
                command.plannedStart, command.plannedEnd, command.sequence,
                command.actorUserId, now,
            )
            recordChange(command, project, now, "WORK_PACKAGE_CREATED", "WORK_PACKAGE", packageId)
        }
        return result(command.actorUserId, command.projectId, "WORK_PACKAGE", packageId, replayed)
    }

    fun updateWorkPackage(command: UpdateWorkPackageCommand): PlanningMutationResult {
        requirePositive(command.baseObjectVersion)
        val initial = requireWorkPackage(command.workPackageId)
        val packageCode = code(command.code, 64)
        val name = required(command.name, 240)
        val description = optional(command.description, 8000)
        validateDates(command.plannedStart, command.plannedEnd)
        requireSequence(command.sequence)

        val replayed = execute(
            operationId = command.operationId,
            actorUserId = command.actorUserId,
            projectId = initial.projectId,
            baseProjectVersion = command.baseProjectVersion,
            expectedBaselineVersion = command.expectedBaselineVersion,
            commandType = "PROJECT_PLAN_WORK_PACKAGE_UPDATE",
            targetType = "WORK_PACKAGE",
            targetId = command.workPackageId,
            correlationId = command.correlationId,
            fingerprintValues = listOf(
                command.workPackageId, command.projectSiteId, command.siteId,
                command.milestoneId, packageCode, name, description,
                command.owner?.principalType, command.owner?.principalId,
                command.plannedStart, command.plannedEnd, command.sequence,
                command.baseProjectVersion, command.baseObjectVersion,
                command.expectedBaselineVersion, command.clientOccurredAt,
            ),
        ) { project, now ->
            val current = requireWorkPackage(command.workPackageId)
            if (current.projectId != project.projectId || current.baselineVersion != project.baselineVersion) {
                conflict("WORK_PACKAGE_PROJECT_MISMATCH", "The WorkPackage is outside the current Project baseline.")
            }
            validatePackageContext(project, command.projectSiteId, command.siteId, command.milestoneId)
            val owner = command.owner?.let { requirePrincipal(project, it, now) }
            bumpProject(project, now)
            if (!planning.updateWorkPackage(
                    current.workPackageId, command.baseObjectVersion,
                    command.projectSiteId, command.siteId, command.milestoneId,
                    packageCode, name, description, owner,
                    command.plannedStart, command.plannedEnd, command.sequence, now,
                )
            ) {
                versionConflict()
            }
            recordChange(command, project, now, "WORK_PACKAGE_UPDATED", "WORK_PACKAGE", current.workPackageId)
        }
        return result(command.actorUserId, initial.projectId, "WORK_PACKAGE", command.workPackageId, replayed)
    }

    fun addDependency(command: AddPlanDependencyCommand): PlanningMutationResult {
        val dependencyId = deterministicId("plan-dependency", command.operationId)
        if (command.predecessor == command.successor) {
            conflict("PLAN_DEPENDENCY_SELF_EDGE", "A planning node cannot depend on itself.")
        }
        if (command.lagMinutes < 0) {
            validation("PLAN_DEPENDENCY_LAG_INVALID", "Dependency lag cannot be negative.")
        }
        val replayed = execute(
            operationId = command.operationId,
            actorUserId = command.actorUserId,
            projectId = command.projectId,
            baseProjectVersion = command.baseProjectVersion,
            expectedBaselineVersion = command.expectedBaselineVersion,
            commandType = "PROJECT_PLAN_DEPENDENCY_ADD",
            targetType = "PROJECT_PLAN_DEPENDENCY",
            targetId = dependencyId,
            correlationId = command.correlationId,
            fingerprintValues = listOf(
                command.projectId, command.predecessor.type, command.predecessor.id,
                command.successor.type, command.successor.id, command.dependencyType,
                command.lagMinutes, command.baseProjectVersion,
                command.expectedBaselineVersion, command.clientOccurredAt,
            ),
        ) { project, now ->
            requirePlanNode(project, command.predecessor)
            requirePlanNode(project, command.successor)
            val proposed = planning.dependencies(project.projectId) +
                PlanDependencySnapshot(
                    dependencyId, project.organizationId, project.projectId,
                    command.predecessor, command.successor, command.dependencyType,
                    command.lagMinutes, now, 1,
                )
            if (hasDependencyCycle(proposed)) {
                conflict("PLAN_DEPENDENCY_CYCLE", "The dependency would create a directed cycle.")
            }
            bumpProject(project, now)
            planning.insertDependency(
                dependencyId, project, command.predecessor, command.successor,
                command.dependencyType, command.lagMinutes, command.actorUserId, now,
            )
            recordChange(command, project, now, "PLAN_DEPENDENCY_ADDED", "PROJECT_PLAN_DEPENDENCY", dependencyId)
        }
        return result(command.actorUserId, command.projectId, "PROJECT_PLAN_DEPENDENCY", dependencyId, replayed)
    }

    fun removeDependency(command: RemovePlanDependencyCommand): PlanningMutationResult {
        requirePositive(command.baseObjectVersion)
        val replayed = execute(
            operationId = command.operationId,
            actorUserId = command.actorUserId,
            projectId = command.projectId,
            baseProjectVersion = command.baseProjectVersion,
            expectedBaselineVersion = command.expectedBaselineVersion,
            commandType = "PROJECT_PLAN_DEPENDENCY_REMOVE",
            targetType = "PROJECT_PLAN_DEPENDENCY",
            targetId = command.dependencyId,
            correlationId = command.correlationId,
            fingerprintValues = listOf(
                command.projectId, command.dependencyId, command.baseProjectVersion,
                command.baseObjectVersion, command.expectedBaselineVersion,
                command.clientOccurredAt,
            ),
        ) { project, now ->
            val dependency = planning.dependency(command.dependencyId)
                ?: hidden()
            if (dependency.projectId != project.projectId) {
                hidden()
            }
            bumpProject(project, now)
            if (!planning.deleteDependency(command.dependencyId, command.baseObjectVersion)) {
                versionConflict()
            }
            recordChange(command, project, now, "PLAN_DEPENDENCY_REMOVED", "PROJECT_PLAN_DEPENDENCY", command.dependencyId)
        }
        return result(command.actorUserId, command.projectId, "PROJECT_PLAN_DEPENDENCY", command.dependencyId, replayed)
    }

    fun markReady(command: MarkProjectReadyCommand): PlanningMutationResult {
        val replayed = execute(
            operationId = command.operationId,
            actorUserId = command.actorUserId,
            projectId = command.projectId,
            baseProjectVersion = command.baseProjectVersion,
            expectedBaselineVersion = command.expectedBaselineVersion,
            commandType = "PROJECT_MARK_READY",
            targetType = "PROJECT",
            targetId = command.projectId,
            correlationId = command.correlationId,
            fingerprintValues = listOf(
                command.projectId, command.baseProjectVersion,
                command.expectedBaselineVersion, command.clientOccurredAt,
            ),
            requirePlanning = false,
        ) { project, now ->
            if (project.lifecycleState != ProjectLifecycleState.PLANNING) {
                conflict("REJECTED_STATE", "Only a PLANNING Project can be marked READY.")
            }
            val currentPlan = buildPlan(project, canEdit = true)
            if (!currentPlan.readyGate.ready) {
                throw ProductApiException(
                    code = "PROJECT_READY_GATE_BLOCKED",
                    message = "The Project planning baseline is not ready to freeze.",
                    status = HttpStatus.UNPROCESSABLE_ENTITY,
                    details = buildJsonObject {
                        put("reasonCodes", buildJsonArray {
                            currentPlan.readyGate.reasonCodes.forEach(::add)
                        })
                    },
                )
            }
            if (!projects.transitionProject(
                    project.projectId,
                    project.version,
                    ProjectLifecycleState.PLANNING,
                    ProjectLifecycleState.READY,
                    now,
                )
            ) {
                versionConflict()
            }
            val updated = requireProject(project.projectId)
            audit.append(
                AuditEventRecord(
                    actorUserId = command.actorUserId,
                    action = "PROJECT_READY",
                    targetType = "PROJECT",
                    targetId = project.projectId,
                    previousStateRef = ProjectLifecycleState.PLANNING.name,
                    newStateRef = ProjectLifecycleState.READY.name,
                    safeDiffJson = """{"fields":["lifecycleState","baselineVersion","planningFreeze"]}""",
                    occurredAt = now,
                    correlationId = command.correlationId,
                ),
            )
            events.publishEvent(
                ProjectLifecycleChanged(
                    projectId = project.projectId,
                    organizationId = project.organizationId,
                    fromState = ProjectLifecycleState.PLANNING,
                    toState = ProjectLifecycleState.READY,
                    sourceVersion = updated.version,
                    actorUserId = command.actorUserId,
                    occurredAt = now,
                    correlationId = command.correlationId,
                ),
            )
        }
        return result(command.actorUserId, command.projectId, "PROJECT", command.projectId, replayed)
    }

    private fun execute(
        operationId: UUID,
        actorUserId: UUID,
        projectId: UUID,
        baseProjectVersion: Long,
        expectedBaselineVersion: Int,
        commandType: String,
        targetType: String,
        targetId: UUID,
        correlationId: String,
        fingerprintValues: List<Any?>,
        requirePlanning: Boolean = true,
        action: (ProjectSnapshot, Instant) -> Unit,
    ): Boolean {
        requirePositive(baseProjectVersion)
        if (expectedBaselineVersion < 1) {
            validation("BASELINE_VERSION_INVALID", "expectedBaselineVersion must be positive.")
        }
        val initial = requireProject(projectId)
        requireManage(actorUserId, initial)

        val execution = idempotency.execute(
            IdempotentCommandSpec(
                operationId = operationId,
                actorUserId = actorUserId,
                commandType = commandType,
                targetType = targetType,
                targetId = targetId,
                requestFingerprint = IdempotencyKeyContract.fingerprint(
                    fingerprintValues.joinToString("|"),
                ),
                correlationId = correlationId,
            ),
        ) {
            val now = clock.instant()
            val project = requireProject(projectId)
            requireManage(actorUserId, project)
            if (project.version != baseProjectVersion || project.baselineVersion != expectedBaselineVersion) {
                versionConflict()
            }
            if (requirePlanning && project.lifecycleState != ProjectLifecycleState.PLANNING) {
                conflict("PLAN_FROZEN", "Planning mutation is allowed only while the Project is PLANNING.")
            }
            try {
                action(project, now)
            } catch (failure: DataIntegrityViolationException) {
                conflict("PLANNING_CONSTRAINT_CONFLICT", "The planning change conflicts with authoritative Project data.")
            }
            IdempotentCommandOutcome(
                resultCode = commandType,
                resultPayloadJson = buildJsonObject {
                    put("projectId", projectId.toString())
                    put("targetType", targetType)
                    put("targetId", targetId.toString())
                }.toString(),
            )
        }
        return execution.replayed
    }

    private fun result(
        actorUserId: UUID,
        projectId: UUID,
        targetType: String,
        targetId: UUID,
        replayed: Boolean,
    ): PlanningMutationResult =
        PlanningMutationResult(
            plan = plan(actorUserId, projectId),
            targetType = targetType,
            targetId = targetId,
            replayed = replayed,
        )

    private fun buildPlan(
        project: ProjectSnapshot,
        canEdit: Boolean,
    ): ProjectPlanSnapshot {
        val sites = projects.listProjectSites(project.projectId)
        val areas = planning.areas(project.projectId)
        val milestones = planning.milestones(project.projectId)
        val packages = planning.workPackages(project.projectId)
        val dependencies = planning.dependencies(project.projectId)
        val siteIds = sites.map { it.site.siteId }.toSet()
        val relationById = sites.associateBy { it.projectSiteId }
        val milestoneIds = milestones.map { it.milestoneId }.toSet()
        val nodeIds = milestones.map { PlanNodeRef(PlanNodeType.MILESTONE, it.milestoneId) }.toSet() +
            packages.map { PlanNodeRef(PlanNodeType.WORK_PACKAGE, it.workPackageId) }.toSet()
        val invalidReasons = buildList {
            if (areas.any { it.organizationId != project.organizationId || it.siteId !in siteIds }) {
                add("AREA_CONTEXT_INVALID")
            }
            if (milestones.any { it.organizationId != project.organizationId || it.baselineVersion != project.baselineVersion }) {
                add("MILESTONE_BASELINE_INVALID")
            }
            if (packages.any { pkg ->
                    pkg.organizationId != project.organizationId ||
                        pkg.baselineVersion != project.baselineVersion ||
                        (pkg.milestoneId != null && pkg.milestoneId !in milestoneIds) ||
                        (pkg.projectSiteId != null && relationById[pkg.projectSiteId]?.site?.siteId != pkg.siteId)
                }
            ) {
                add("WORK_PACKAGE_CONTEXT_INVALID")
            }
            if (dependencies.any { it.predecessor !in nodeIds || it.successor !in nodeIds } || hasDependencyCycle(dependencies)) {
                add("DEPENDENCY_GRAPH_INVALID")
            }
        }.distinct()
        val readyReasons = buildList {
            addAll(invalidReasons)
            if (sites.isEmpty()) add("PROJECT_SITE_REQUIRED")
            if (milestones.none { it.state != MilestoneState.CANCELLED } &&
                packages.none { it.state != WorkPackageState.CANCELLED }
            ) {
                add("PLANNING_NODE_REQUIRED")
            }
            if (project.responsibility == null) add("PROJECT_RESPONSIBILITY_REQUIRED")
            if (project.lifecycleState != ProjectLifecycleState.PLANNING) add("PROJECT_NOT_PLANNING")
        }.distinct()
        return ProjectPlanSnapshot(
            project = project,
            projectSites = sites,
            areas = areas,
            milestones = milestones,
            workPackages = packages,
            dependencies = dependencies,
            validation = PlanValidationSummary(invalidReasons.isEmpty(), invalidReasons),
            canEditPlan = canEdit && project.lifecycleState == ProjectLifecycleState.PLANNING,
            readyGate = ProjectReadyGate(readyReasons.isEmpty(), readyReasons),
        )
    }

    private fun validatePackageContext(
        project: ProjectSnapshot,
        projectSiteId: UUID?,
        siteId: UUID?,
        milestoneId: UUID?,
    ) {
        if ((projectSiteId == null) != (siteId == null)) {
            validation("WORK_PACKAGE_SITE_CONTEXT_INVALID", "projectSiteId and siteId must be provided together.")
        }
        projectSiteId?.let {
            val relation = requireProjectSite(project, it)
            if (relation.site.siteId != siteId) {
                conflict("WORK_PACKAGE_SITE_CONTEXT_INVALID", "The WorkPackage Site does not match its ProjectSite.")
            }
        }
        milestoneId?.let {
            val milestone = requireMilestone(it)
            if (milestone.projectId != project.projectId || milestone.baselineVersion != project.baselineVersion) {
                conflict("WORK_PACKAGE_MILESTONE_INVALID", "The Milestone is outside the current Project baseline.")
            }
        }
    }

    private fun requirePlanNode(project: ProjectSnapshot, ref: PlanNodeRef) {
        val valid = when (ref.type) {
            PlanNodeType.MILESTONE -> planning.milestone(ref.id)?.let {
                it.projectId == project.projectId && it.baselineVersion == project.baselineVersion && it.state != MilestoneState.CANCELLED
            } == true
            PlanNodeType.WORK_PACKAGE -> planning.workPackage(ref.id)?.let {
                it.projectId == project.projectId && it.baselineVersion == project.baselineVersion && it.state != WorkPackageState.CANCELLED
            } == true
        }
        if (!valid) {
            conflict("PLAN_DEPENDENCY_NODE_INVALID", "A dependency node is outside the current Project baseline.")
        }
    }

    private fun hasDependencyCycle(edges: List<PlanDependencySnapshot>): Boolean {
        val adjacency = edges.groupBy { it.predecessor }.mapValues { entry -> entry.value.map { it.successor } }
        val visiting = mutableSetOf<PlanNodeRef>()
        val visited = mutableSetOf<PlanNodeRef>()
        fun visit(node: PlanNodeRef): Boolean {
            if (node in visiting) return true
            if (!visited.add(node)) return false
            visiting += node
            val cycle = adjacency[node].orEmpty().any(::visit)
            visiting -= node
            return cycle
        }
        return edges.asSequence().flatMap { sequenceOf(it.predecessor, it.successor) }.any(::visit)
    }

    private fun ensureNoAreaCycle(areaId: UUID, parent: AreaSnapshot) {
        val visited = mutableSetOf<UUID>()
        var cursor: AreaSnapshot? = parent
        while (cursor != null) {
            if (cursor.areaId == areaId || !visited.add(cursor.areaId)) {
                conflict("AREA_CYCLE", "The Area hierarchy would contain a cycle.")
            }
            cursor = cursor.parentAreaId?.let(planning::area)
        }
    }

    private fun requireAreaInProject(project: ProjectSnapshot, area: AreaSnapshot) {
        if (area.organizationId != project.organizationId ||
            projects.listProjectSites(project.projectId).none { it.site.siteId == area.siteId }
        ) {
            hidden()
        }
    }

    private fun requireProjectSite(project: ProjectSnapshot, projectSiteId: UUID): ProjectSiteSnapshot {
        val relation = projects.projectSite(projectSiteId) ?: hidden()
        if (relation.projectId != project.projectId || relation.organizationId != project.organizationId ||
            relation.lifecycleState == ProjectSiteLifecycleState.CLOSED
        ) {
            conflict("PROJECT_SITE_CONTEXT_INVALID", "The ProjectSite is not valid for this planning change.")
        }
        return relation
    }

    private fun requirePrincipal(project: ProjectSnapshot, input: ProjectPrincipalInput, at: Instant): ProjectPrincipalContext =
        projects.principal(project.organizationId, input, at)
            ?: conflict("WORK_PACKAGE_OWNER_INVALID", "The planning owner is not current in this organization.")

    private fun bumpProject(project: ProjectSnapshot, at: Instant) {
        if (!projects.bumpProjectVersion(project.projectId, project.version, at)) {
            versionConflict()
        }
    }

    private fun recordChange(
        command: Any,
        project: ProjectSnapshot,
        now: Instant,
        action: String,
        targetType: String,
        targetId: UUID,
    ) {
        val actor = when (command) {
            is CreateAreaCommand -> command.actorUserId
            is UpdateAreaCommand -> command.actorUserId
            is CreateMilestoneCommand -> command.actorUserId
            is UpdateMilestoneCommand -> command.actorUserId
            is CreateWorkPackageCommand -> command.actorUserId
            is UpdateWorkPackageCommand -> command.actorUserId
            is AddPlanDependencyCommand -> command.actorUserId
            is RemovePlanDependencyCommand -> command.actorUserId
            else -> error("Unsupported planning command")
        }
        val correlation = when (command) {
            is CreateAreaCommand -> command.correlationId
            is UpdateAreaCommand -> command.correlationId
            is CreateMilestoneCommand -> command.correlationId
            is UpdateMilestoneCommand -> command.correlationId
            is CreateWorkPackageCommand -> command.correlationId
            is UpdateWorkPackageCommand -> command.correlationId
            is AddPlanDependencyCommand -> command.correlationId
            is RemovePlanDependencyCommand -> command.correlationId
            else -> error("Unsupported planning command")
        }
        val updated = requireProject(project.projectId)
        audit.append(
            AuditEventRecord(
                actorUserId = actor,
                action = action,
                targetType = targetType,
                targetId = targetId,
                newStateRef = ProjectLifecycleState.PLANNING.name,
                safeDiffJson = """{"fields":["planningStructure","projectVersion","baselineVersion"]}""",
                occurredAt = now,
                correlationId = correlation,
            ),
        )
        events.publishEvent(
            ProjectPlanChanged(
                projectId = project.projectId,
                organizationId = project.organizationId,
                changeType = action.lowercase(Locale.ROOT),
                changedTargetType = targetType,
                changedTargetId = targetId,
                sourceVersion = updated.version,
                actorUserId = actor,
                occurredAt = now,
                correlationId = correlation,
            ),
        )
    }

    private fun requireManage(actorUserId: UUID, project: ProjectSnapshot) {
        if (!authorization.canManageProject(actorUserId, project)) {
            hidden()
        }
    }

    private fun requireProject(projectId: UUID): ProjectSnapshot =
        projects.project(projectId, clock.instant()) ?: hidden()

    private fun requireArea(areaId: UUID): AreaSnapshot = planning.area(areaId) ?: hidden()
    private fun requireMilestone(milestoneId: UUID): MilestoneSnapshot = planning.milestone(milestoneId) ?: hidden()
    private fun requireWorkPackage(workPackageId: UUID): WorkPackageSnapshot = planning.workPackage(workPackageId) ?: hidden()

    private fun validateDates(start: LocalDate?, end: LocalDate?) {
        if (start != null && end != null && end < start) {
            validation("PLANNED_DATE_RANGE_INVALID", "plannedEnd must not be before plannedStart.")
        }
    }

    private fun requireSequence(sequence: Int?) {
        if (sequence != null && sequence < 0) {
            validation("PLANNING_SEQUENCE_INVALID", "sequence cannot be negative.")
        }
    }

    private fun code(value: String, max: Int): String =
        required(value, max).uppercase(Locale.ROOT).also {
            if (!it.matches(Regex("[A-Z0-9][A-Z0-9._-]*"))) {
                validation("PLANNING_CODE_INVALID", "Planning codes may contain letters, numbers, dot, underscore and hyphen.")
            }
        }

    private fun required(value: String, max: Int): String =
        value.trim().takeIf { it.isNotEmpty() && it.length <= max }
            ?: validation("REJECTED_VALIDATION", "A required planning value is invalid.")

    private fun optional(value: String?, max: Int): String? {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty)
        if (normalized != null && normalized.length > max) {
            validation("REJECTED_VALIDATION", "A planning value exceeds its maximum length.")
        }
        return normalized
    }

    private fun requirePositive(value: Long) {
        if (value < 1) validation("VERSION_INVALID", "Version must be positive.")
    }

    private fun deterministicId(namespace: String, operationId: UUID): UUID =
        UUID.nameUUIDFromBytes("hiltech:$namespace:$operationId".toByteArray(StandardCharsets.UTF_8))

    private fun versionConflict(): Nothing =
        throw ProductApiException(
            code = "VERSION_CONFLICT",
            message = "The Project or planning object changed before this command was applied.",
            status = HttpStatus.CONFLICT,
        )

    private fun conflict(code: String, message: String): Nothing =
        throw ProductApiException(code = code, message = message, status = HttpStatus.CONFLICT)

    private fun validation(code: String, message: String): Nothing =
        throw ProductApiException(code = code, message = message, status = HttpStatus.BAD_REQUEST)

    private fun hidden(): Nothing =
        throw ProductApiException(
            code = "OBJECT_NOT_VISIBLE",
            message = "The requested planning object is unavailable.",
            status = HttpStatus.NOT_FOUND,
        )
}
