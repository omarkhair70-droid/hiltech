package com.hiltech.server.work

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.projects.ProjectAuthorizationPort
import com.hiltech.server.projects.ProjectSnapshot
import com.hiltech.server.projects.ProjectsPersistencePort
import com.hiltech.server.security.AuthorizationDesiredState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Component
class ReadinessAssignmentService(
    private val projects: ProjectsPersistencePort,
    private val work: WorkPersistencePort,
    private val persistence: ReadinessAssignmentPersistencePort,
    private val eligibility: WorkEligibilityResolverPort,
    private val authorization: ProjectAuthorizationPort,
    private val projection: WorkAssignmentAuthorizationProjectionBridge,
    private val idempotency: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    fun readiness(
        actorUserId: UUID,
        workOrderId: UUID,
    ): WorkReadinessSnapshot {
        val order = order(workOrderId)
        requireAssign(actorUserId, project(order.projectId))
        return snapshot(order)
    }

    fun eligibleTargets(
        actorUserId: UUID,
        workOrderId: UUID,
    ): List<EligibleTargetResult> {
        val order = order(workOrderId)
        requireAssign(actorUserId, project(order.projectId))
        val assignmentPolicy = assignmentPolicy(order)
        return eligibility.eligibleTargets(
            order,
            assignmentPolicy,
            clock.instant(),
        )
    }

    fun workQueueContext(
        actorUserId: UUID,
        projectId: UUID,
    ): List<WorkQueueContextItem> {
        val project = project(projectId)
        requireAssign(actorUserId, project)
        val owner =
            project.responsibility?.linkedUserIdentityId
        return work.workOrders(projectId)
            .mapNotNull { order ->
                when {
                    order.lifecycleState !in
                        setOf(
                            WorkOrderLifecycle.PLANNED,
                            WorkOrderLifecycle.ASSIGNED,
                        ) -> null

                    order.readinessState ==
                        WorkReadiness.NOT_EVALUATED ->
                        WorkQueueContextItem(
                            workOrderId = order.workOrderId,
                            workOrderCode = order.workOrderCode,
                            title = order.title,
                            actionCode = "EVALUATE_READINESS",
                            reasonCodes =
                                listOf("READINESS_NOT_EVALUATED"),
                            ownerUserId = owner,
                        )

                    order.readinessState ==
                        WorkReadiness.BLOCKED -> {
                        val state = snapshot(order)
                        WorkQueueContextItem(
                            workOrderId = order.workOrderId,
                            workOrderCode = order.workOrderCode,
                            title = order.title,
                            actionCode = "RESOLVE_READINESS",
                            reasonCodes =
                                state.waitingOnReasonCodes,
                            ownerUserId = owner,
                        )
                    }

                    order.lifecycleState ==
                        WorkOrderLifecycle.PLANNED &&
                        persistence.activeAssignment(
                            order.workOrderId,
                        ) == null ->
                        WorkQueueContextItem(
                            workOrderId = order.workOrderId,
                            workOrderCode = order.workOrderCode,
                            title = order.title,
                            actionCode = "ASSIGN_WORK",
                            reasonCodes =
                                listOf("READY_UNASSIGNED"),
                            ownerUserId = owner,
                        )

                    else -> null
                }
            }
    }

    @Transactional
    fun evaluate(
        command: EvaluateReadinessCommand,
    ): WorkReadinessMutationResult {
        positive(command.baseVersion)
        val initial = order(command.workOrderId)
        requireAssign(
            command.actorUserId,
            project(initial.projectId),
        )

        val replayed =
            execute(
                operationId = command.operationId,
                actor = command.actorUserId,
                type = "WORK_READINESS_EVALUATE",
                target = command.workOrderId,
                correlation = command.correlationId,
                fingerprint =
                    listOf(
                        command.workOrderId,
                        command.baseVersion,
                        command.clientOccurredAt,
                    ),
            ) {
                val now = clock.instant()
                val current = order(command.workOrderId)
                val project = project(current.projectId)
                requireAssign(command.actorUserId, project)
                if (current.version != command.baseVersion) {
                    version()
                }
                requireReadinessLifecycle(current)

                val assignmentPolicy =
                    assignmentPolicy(current)
                val readinessPolicy =
                    readinessPolicy(current)
                val candidates =
                    eligibility.eligibleTargets(
                        current,
                        assignmentPolicy,
                        now,
                    )
                val activeAssignment =
                    persistence.activeAssignment(
                        current.workOrderId,
                    )
                val requirements =
                    readinessRequirements(current)

                persistence.resolveReadinessBlockers(
                    current.workOrderId,
                    now,
                )

                requirements
                    .filterNot { it.waived }
                    .forEach { requirement ->
                        val evaluation =
                            evaluateRequirement(
                                requirement = requirement,
                                order = current,
                                candidates = candidates,
                                activeAssignment =
                                    activeAssignment,
                            )
                        if (
                            !persistence
                                .updateRequirementEvaluation(
                                    requirementId =
                                        requirement.requirementId,
                                    expectedVersion =
                                        requirement.version,
                                    state = evaluation.state,
                                    reasonCode =
                                        evaluation.reasonCode,
                                    sourceAsOf = now,
                                    evaluatedAt = now,
                                    satisfiedByRef =
                                        evaluation.satisfiedByRef,
                                )
                        ) {
                            version()
                        }
                    }

                val evaluated =
                    readinessRequirements(current)
                evaluated
                    .filter {
                        it.required &&
                            it.satisfactionState ==
                            "BLOCKED"
                    }
                    .forEachIndexed { index, requirement ->
                        persistence.insertReadinessBlocker(
                            blockerId =
                                deterministic(
                                    "readiness-blocker",
                                    command.operationId,
                                    "$index:${requirement.requirementId}",
                                ),
                            workOrderId =
                                current.workOrderId,
                            requirementId =
                                requirement.requirementId,
                            reasonCode =
                                requirement
                                    .evaluationReasonCode
                                    ?: "READINESS_REQUIREMENT_BLOCKED",
                            actorUserId =
                                command.actorUserId,
                            at = now,
                        )
                    }

                val nextState =
                    readinessState(
                        readinessPolicy,
                        evaluated,
                    )
                if (
                    !persistence.updateReadiness(
                        current.workOrderId,
                        current.version,
                        nextState,
                        now,
                    )
                ) {
                    version()
                }

                val updated = order(current.workOrderId)
                audit.append(
                    AuditEventRecord(
                        actorUserId =
                            command.actorUserId,
                        action = "WORK_READINESS_EVALUATED",
                        targetType = "WORK_ORDER",
                        targetId = current.workOrderId,
                        previousStateRef =
                            current.readinessState.name,
                        newStateRef = nextState.name,
                        safeDiffJson =
                            """{"fields":["readinessState","requirements","blockers"]}""",
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                        configRevisionRefsJson =
                            """["${requireNotNull(current.binding).readinessPolicy.id}","${current.binding.assignmentPolicy.id}"]""",
                    ),
                )
                if (current.readinessState != nextState) {
                    events.publishEvent(
                        WorkReadinessChanged(
                            workOrderId =
                                updated.workOrderId,
                            organizationId =
                                updated.organizationId,
                            projectId = updated.projectId,
                            previousState =
                                current.readinessState,
                            newState = nextState,
                            sourceVersion = updated.version,
                            actorUserId =
                                command.actorUserId,
                            occurredAt = now,
                            correlationId =
                                command.correlationId,
                        ),
                    )
                }
                outcome(
                    current.projectId,
                    current.workOrderId,
                )
            }

        return WorkReadinessMutationResult(
            readiness =
                snapshot(order(command.workOrderId)),
            replayed = replayed,
        )
    }

    @Transactional
    fun assign(
        command: AssignWorkCommand,
    ): WorkAssignmentMutationResult {
        positive(command.baseVersion)
        val initial = order(command.workOrderId)
        requireAssign(
            command.actorUserId,
            project(initial.projectId),
        )
        val assignmentId =
            deterministic(
                "work-assignment",
                command.operationId,
            )

        val replayed =
            execute(
                operationId = command.operationId,
                actor = command.actorUserId,
                type = "WORK_ASSIGN",
                target = command.workOrderId,
                correlation = command.correlationId,
                fingerprint =
                    listOf(
                        command.workOrderId,
                        command.targetType,
                        command.targetId,
                        command.baseVersion,
                        command.clientOccurredAt,
                    ),
            ) {
                val now = clock.instant()
                val current = order(command.workOrderId)
                val project = project(current.projectId)
                requireAssign(command.actorUserId, project)
                if (current.version != command.baseVersion) {
                    version()
                }
                if (
                    current.lifecycleState !=
                    WorkOrderLifecycle.PLANNED
                ) {
                    conflict(
                        "ASSIGNMENT_STATE_INVALID",
                        "Only PLANNED Work may receive its initial assignment.",
                    )
                }
                if (
                    current.readinessState !=
                    WorkReadiness.READY
                ) {
                    conflict(
                        "WORK_NOT_READY",
                        "Blocked or unevaluated Work cannot be assigned.",
                    )
                }
                if (
                    persistence.activeAssignment(
                        current.workOrderId,
                    ) != null
                ) {
                    conflict(
                        "ACTIVE_ASSIGNMENT_EXISTS",
                        "The WorkOrder already has an active assignment.",
                    )
                }

                val policy =
                    assignmentPolicy(current)
                val candidates =
                    eligibility.eligibleTargets(
                        current,
                        policy,
                        now,
                    )
                val target =
                    resolveTarget(
                        policy = policy,
                        candidates = candidates,
                        requestedType =
                            command.targetType,
                        requestedId =
                            command.targetId,
                    )

                persistence.insertAssignment(
                    assignmentId = assignmentId,
                    order = current,
                    target = target,
                    operationId = command.operationId,
                    actorUserId =
                        command.actorUserId,
                    at = now,
                    supersedesAssignmentId = null,
                    reason = null,
                )
                val inserted =
                    persistence.assignment(assignmentId)
                        ?: conflict(
                            "ASSIGNMENT_WRITE_FAILED",
                            "The Work assignment could not be loaded after creation.",
                        )

                projection.assignment(
                    inserted,
                    AuthorizationDesiredState.PRESENT,
                    eventId =
                        deterministic(
                            "assignment-projection",
                            command.operationId,
                        ),
                    at = now,
                )

                if (
                    !persistence.transitionToAssigned(
                        current.workOrderId,
                        current.version,
                        now,
                    )
                ) {
                    version()
                }
                val updated = order(current.workOrderId)
                recordAssignment(
                    action = "WORK_ASSIGNED",
                    previous = null,
                    assignment = inserted,
                    order = updated,
                    actor = command.actorUserId,
                    at = now,
                    correlation =
                        command.correlationId,
                )
                outcome(
                    updated.projectId,
                    assignmentId,
                )
            }

        return WorkAssignmentMutationResult(
            readiness =
                snapshot(order(command.workOrderId)),
            assignmentId = assignmentId,
            replayed = replayed,
        )
    }

    @Transactional
    fun reassign(
        command: ReassignWorkCommand,
    ): WorkAssignmentMutationResult {
        positive(command.baseVersion)
        positive(command.baseAssignmentVersion)
        val initial = order(command.workOrderId)
        requireAssign(
            command.actorUserId,
            project(initial.projectId),
        )
        val assignmentId =
            deterministic(
                "work-assignment",
                command.operationId,
            )

        val replayed =
            execute(
                operationId = command.operationId,
                actor = command.actorUserId,
                type = "WORK_REASSIGN",
                target = command.workOrderId,
                correlation = command.correlationId,
                fingerprint =
                    listOf(
                        command.workOrderId,
                        command.targetType,
                        command.targetId,
                        command.baseVersion,
                        command.currentAssignmentId,
                        command.baseAssignmentVersion,
                        command.reason,
                        command.clientOccurredAt,
                    ),
            ) {
                val now = clock.instant()
                val current = order(command.workOrderId)
                val project = project(current.projectId)
                requireAssign(command.actorUserId, project)
                if (current.version != command.baseVersion) {
                    version()
                }
                if (
                    current.lifecycleState !=
                    WorkOrderLifecycle.ASSIGNED
                ) {
                    conflict(
                        "REASSIGNMENT_STATE_INVALID",
                        "Only ASSIGNED Work can be reassigned.",
                    )
                }

                val policy =
                    assignmentPolicy(current)
                val reason =
                    optionalReason(command.reason)
                if (
                    policy.reassignmentRequiresReason &&
                    reason == null
                ) {
                    validation(
                        "REASSIGNMENT_REASON_REQUIRED",
                        "The bound AssignmentPolicy requires a reassignment reason.",
                    )
                }

                val active =
                    persistence.activeAssignment(
                        current.workOrderId,
                    ) ?: conflict(
                        "ACTIVE_ASSIGNMENT_REQUIRED",
                        "Reassignment requires a current active assignment.",
                    )
                if (
                    active.assignmentId !=
                        command.currentAssignmentId ||
                    active.version !=
                        command.baseAssignmentVersion
                ) {
                    version()
                }
                if (
                    active.targetType ==
                        command.targetType &&
                    active.targetId ==
                        command.targetId
                ) {
                    conflict(
                        "ASSIGNMENT_TARGET_UNCHANGED",
                        "The replacement target must differ from the current target.",
                    )
                }

                val candidates =
                    eligibility.eligibleTargets(
                        current,
                        policy,
                        now,
                    )
                val target =
                    explicitTarget(
                        candidates,
                        command.targetType,
                        command.targetId,
                    )

                if (
                    !persistence.endAssignment(
                        active.assignmentId,
                        active.version,
                        now,
                    )
                ) {
                    version()
                }
                val ended =
                    persistence.assignment(
                        active.assignmentId,
                    ) ?: version()
                projection.assignment(
                    ended,
                    AuthorizationDesiredState.ABSENT,
                    eventId =
                        deterministic(
                            "assignment-revoke",
                            command.operationId,
                        ),
                    at = now,
                )

                persistence.insertAssignment(
                    assignmentId = assignmentId,
                    order = current,
                    target = target,
                    operationId = command.operationId,
                    actorUserId =
                        command.actorUserId,
                    at = now,
                    supersedesAssignmentId =
                        active.assignmentId,
                    reason = reason,
                )
                val replacement =
                    persistence.assignment(assignmentId)
                        ?: conflict(
                            "ASSIGNMENT_WRITE_FAILED",
                            "The replacement assignment could not be loaded.",
                        )
                projection.assignment(
                    replacement,
                    AuthorizationDesiredState.PRESENT,
                    eventId =
                        deterministic(
                            "assignment-projection",
                            command.operationId,
                        ),
                    at = now,
                )

                if (
                    !persistence.bumpAssignedWorkOrder(
                        current.workOrderId,
                        current.version,
                        now,
                    )
                ) {
                    version()
                }
                val updated = order(current.workOrderId)
                recordAssignment(
                    action = "WORK_REASSIGNED",
                    previous = active,
                    assignment = replacement,
                    order = updated,
                    actor = command.actorUserId,
                    at = now,
                    correlation =
                        command.correlationId,
                )
                outcome(
                    updated.projectId,
                    assignmentId,
                )
            }

        return WorkAssignmentMutationResult(
            readiness =
                snapshot(order(command.workOrderId)),
            assignmentId = assignmentId,
            replayed = replayed,
        )
    }

    @Transactional
    fun waive(
        command: WaiveReadinessRequirementCommand,
    ): WorkReadinessMutationResult {
        positive(command.baseWorkOrderVersion)
        positive(command.baseRequirementVersion)
        val initial = order(command.workOrderId)
        requireAssign(
            command.actorUserId,
            project(initial.projectId),
        )
        val waiverId =
            deterministic(
                "readiness-waiver",
                command.operationId,
            )

        val replayed =
            execute(
                operationId = command.operationId,
                actor = command.actorUserId,
                type = "WORK_READINESS_WAIVE",
                target = command.workOrderId,
                correlation = command.correlationId,
                fingerprint =
                    listOf(
                        command.workOrderId,
                        command.requirementId,
                        command.baseWorkOrderVersion,
                        command.baseRequirementVersion,
                        command.reason,
                        command.clientOccurredAt,
                    ),
            ) {
                val now = clock.instant()
                val current = order(command.workOrderId)
                val project = project(current.projectId)
                requireAssign(command.actorUserId, project)
                if (
                    current.version !=
                    command.baseWorkOrderVersion
                ) {
                    version()
                }
                requireReadinessLifecycle(current)

                val policy =
                    readinessPolicy(current)
                val requirement =
                    readinessRequirements(current)
                        .firstOrNull {
                            it.requirementId ==
                                command.requirementId
                        } ?: hidden()
                if (
                    requirement.version !=
                    command.baseRequirementVersion
                ) {
                    version()
                }
                if (!requirement.waiverAllowed) {
                    conflict(
                        "READINESS_WAIVER_NOT_ALLOWED",
                        "The bound ReadinessPolicy does not allow this requirement to be waived.",
                    )
                }
                if (requirement.waived) {
                    conflict(
                        "READINESS_ALREADY_WAIVED",
                        "The requirement is already waived.",
                    )
                }
                val suppliedReason =
                    optionalReason(command.reason)
                if (
                    requirement.waiverReasonRequired &&
                    suppliedReason == null
                ) {
                    validation(
                        "READINESS_WAIVER_REASON_REQUIRED",
                        "The bound ReadinessPolicy requires an explicit waiver reason.",
                    )
                }
                val reason =
                    suppliedReason
                        ?: "Policy-approved waiver; reason not required"

                persistence.insertWaiver(
                    waiverId = waiverId,
                    order = current,
                    requirement = requirement,
                    operationId = command.operationId,
                    reason = reason,
                    actorUserId =
                        command.actorUserId,
                    at = now,
                )
                if (
                    !persistence.applyWaiver(
                        requirementId =
                            requirement.requirementId,
                        expectedVersion =
                            requirement.version,
                        waiverId = waiverId,
                        reason = reason,
                        actorUserId =
                            command.actorUserId,
                        at = now,
                    )
                ) {
                    version()
                }
                persistence.resolveReadinessBlocker(
                    current.workOrderId,
                    requirement.requirementId,
                    now,
                )

                val evaluated =
                    readinessRequirements(current)
                val nextState =
                    readinessState(
                        policy,
                        evaluated,
                    )
                if (
                    !persistence.updateReadiness(
                        current.workOrderId,
                        current.version,
                        nextState,
                        now,
                    )
                ) {
                    version()
                }

                val updated = order(current.workOrderId)
                audit.append(
                    AuditEventRecord(
                        actorUserId =
                            command.actorUserId,
                        action =
                            "WORK_READINESS_REQUIREMENT_WAIVED",
                        targetType = "WORK_ORDER",
                        targetId = current.workOrderId,
                        previousStateRef =
                            current.readinessState.name,
                        newStateRef = nextState.name,
                        safeDiffJson =
                            """{"fields":["requirementWaiver","readinessState"]}""",
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                        configRevisionRefsJson =
                            """["${requireNotNull(current.binding).readinessPolicy.id}"]""",
                    ),
                )
                if (
                    current.readinessState !=
                    nextState
                ) {
                    events.publishEvent(
                        WorkReadinessChanged(
                            workOrderId =
                                updated.workOrderId,
                            organizationId =
                                updated.organizationId,
                            projectId = updated.projectId,
                            previousState =
                                current.readinessState,
                            newState = nextState,
                            sourceVersion = updated.version,
                            actorUserId =
                                command.actorUserId,
                            occurredAt = now,
                            correlationId =
                                command.correlationId,
                        ),
                    )
                }
                outcome(
                    current.projectId,
                    waiverId,
                )
            }

        return WorkReadinessMutationResult(
            readiness =
                snapshot(order(command.workOrderId)),
            replayed = replayed,
        )
    }

    private fun snapshot(
        order: WorkOrderSnapshot,
    ): WorkReadinessSnapshot {
        val assignmentPolicy =
            assignmentPolicy(order)
        val requirements =
            readinessRequirements(order)
        val history =
            persistence.assignments(
                order.workOrderId,
            )
        val current =
            history.firstOrNull {
                it.state == "ACTIVE" &&
                    it.validUntil == null
            }
        val targets =
            eligibility.eligibleTargets(
                order,
                assignmentPolicy,
                clock.instant(),
            )
        val waiting =
            requirements
                .filter {
                    it.required &&
                        it.satisfactionState !in
                        setOf(
                            "SATISFIED",
                            "WAIVED",
                            "NOT_APPLICABLE",
                        )
                }
                .map {
                    it.evaluationReasonCode
                        ?: it.typeCode
                }
                .distinct()

        return WorkReadinessSnapshot(
            workOrderId = order.workOrderId,
            workOrderVersion = order.version,
            lifecycleState = order.lifecycleState,
            readinessState = order.readinessState,
            assignmentMode =
                assignmentPolicy.mode,
            requirements = requirements,
            blockers =
                persistence.blockers(
                    order.workOrderId,
                ),
            eligibleTargets = targets,
            currentAssignment = current,
            assignmentHistory = history,
            waitingOnReasonCodes = waiting,
            evaluatedAt =
                requirements
                    .mapNotNull { it.evaluatedAt }
                    .maxOrNull(),
        )
    }

    private fun evaluateRequirement(
        requirement:
            ReadinessRequirementExecutionSnapshot,
        order: WorkOrderSnapshot,
        candidates: List<EligibleTargetResult>,
        activeAssignment: WorkAssignmentRecord?,
    ): RequirementEvaluation =
        when (
            requirement.typeCode
                .trim()
                .uppercase()
        ) {
            "ASSIGNEE",
            "ASSIGNMENT",
            -> {
                if (activeAssignment != null) {
                    val target =
                        candidates.firstOrNull {
                            it.targetType ==
                                activeAssignment.targetType &&
                                it.targetId ==
                                activeAssignment.targetId
                        }
                    if (target?.eligible == true) {
                        RequirementEvaluation(
                            state = "SATISFIED",
                            reasonCode =
                                "CURRENT_ASSIGNEE_ELIGIBLE",
                            satisfiedByRef =
                                "${target.targetType}:${target.targetId}",
                        )
                    } else {
                        RequirementEvaluation(
                            state = "BLOCKED",
                            reasonCode =
                                target?.reasonCodes
                                    ?.firstOrNull()
                                    ?: "ASSIGNEE_NOT_ELIGIBLE",
                        )
                    }
                } else {
                    val eligible =
                        candidates.filter {
                            it.eligible
                        }
                    if (eligible.isNotEmpty()) {
                        RequirementEvaluation(
                            state = "SATISFIED",
                            reasonCode =
                                "ELIGIBLE_TARGET_AVAILABLE",
                            satisfiedByRef =
                                "eligible-target-count:${eligible.size}",
                        )
                    } else {
                        RequirementEvaluation(
                            state = "BLOCKED",
                            reasonCode =
                                "NO_ELIGIBLE_TARGET",
                        )
                    }
                }
            }

            "DEPENDENCY" ->
                if (
                    persistence.dependenciesSatisfied(
                        order.workOrderId,
                    )
                ) {
                    RequirementEvaluation(
                        state = "SATISFIED",
                        reasonCode =
                            "DEPENDENCIES_SATISFIED",
                        satisfiedByRef =
                            "work-order-dependencies",
                    )
                } else {
                    RequirementEvaluation(
                        state = "BLOCKED",
                        reasonCode =
                            "PREDECESSOR_NOT_COMPLETE",
                    )
                }

            "MATERIAL",
            "ASSET",
            "ASSET_TOOL",
            "TOOL",
            ->
                RequirementEvaluation(
                    state = "BLOCKED",
                    reasonCode =
                        "PHASE5_RESOURCE_SOURCE_UNAVAILABLE",
                )

            "SITE_ACCESS" ->
                RequirementEvaluation(
                    state = "BLOCKED",
                    reasonCode =
                        "SITE_ACCESS_CONFIRMATION_REQUIRED",
                )

            "DRAWING_REVISION",
            "DOCUMENT",
            ->
                RequirementEvaluation(
                    state = "BLOCKED",
                    reasonCode =
                        "AUTHORITATIVE_DOCUMENT_SOURCE_UNAVAILABLE",
                )

            "CLIENT_PERMISSION" ->
                RequirementEvaluation(
                    state = "BLOCKED",
                    reasonCode =
                        "CLIENT_PERMISSION_CONFIRMATION_REQUIRED",
                )

            "SAFETY_PPE" ->
                RequirementEvaluation(
                    state = "BLOCKED",
                    reasonCode =
                        "SAFETY_PPE_CONFIRMATION_REQUIRED",
                )

            else ->
                RequirementEvaluation(
                    state = "BLOCKED",
                    reasonCode =
                        "CAPABILITY_SOURCE_UNAVAILABLE",
                )
        }

    private fun readinessState(
        policy: ReadinessPolicyExecutionSnapshot,
        requirements:
            List<ReadinessRequirementExecutionSnapshot>,
    ): WorkReadiness {
        val required =
            requirements.filter { it.required }
        val allSatisfied =
            required.all {
                it.satisfactionState in
                    setOf(
                        "SATISFIED",
                        "WAIVED",
                        "NOT_APPLICABLE",
                    )
            }
        // V0003 has no threshold/score semantics. The boolean policy can
        // relax optional requirements, never required-source truth.
        return if (
            allSatisfied ||
            (
                !policy.allRequiredMustBeSatisfied &&
                    required.none {
                        it.satisfactionState ==
                            "BLOCKED"
                    } &&
                    required.none {
                        it.satisfactionState ==
                            "PENDING"
                    }
            )
        ) {
            WorkReadiness.READY
        } else {
            WorkReadiness.BLOCKED
        }
    }

    private fun resolveTarget(
        policy: AssignmentPolicyExecutionSnapshot,
        candidates: List<EligibleTargetResult>,
        requestedType: AssignmentTargetType?,
        requestedId: UUID?,
    ): EligibleTargetResult {
        if (
            (requestedType == null) !=
            (requestedId == null)
        ) {
            validation(
                "ASSIGNMENT_TARGET_INCOMPLETE",
                "targetType and targetId must be supplied together.",
            )
        }
        if (
            requestedType != null &&
            requestedId != null
        ) {
            return explicitTarget(
                candidates,
                requestedType,
                requestedId,
            )
        }

        if (policy.mode != AssignmentMode.AUTO) {
            validation(
                "ASSIGNMENT_TARGET_REQUIRED",
                "The bound AssignmentPolicy requires explicit target confirmation.",
            )
        }

        val eligible =
            candidates.filter { it.eligible }
        return when (eligible.size) {
            0 ->
                conflict(
                    "NO_ELIGIBLE_TARGET",
                    "No eligible assignment target is currently available.",
                )
            1 -> eligible.single()
            else ->
                throw ProductApiException(
                    code =
                        "ASSIGNMENT_CONFIRMATION_REQUIRED",
                    message =
                        "AUTO mode found multiple eligible targets and will not choose arbitrarily.",
                    status =
                        HttpStatus.CONFLICT,
                    details =
                        buildJsonObject {
                            put(
                                "eligibleTargetCount",
                                eligible.size,
                            )
                        },
                )
        }
    }

    private fun explicitTarget(
        candidates: List<EligibleTargetResult>,
        type: AssignmentTargetType,
        id: UUID,
    ): EligibleTargetResult {
        val target =
            candidates.firstOrNull {
                it.targetType == type &&
                    it.targetId == id
            } ?: conflict(
                "ASSIGNMENT_TARGET_NOT_FOUND",
                "The requested assignment target is unavailable.",
            )
        if (!target.eligible) {
            throw ProductApiException(
                code =
                    "ASSIGNMENT_TARGET_INELIGIBLE",
                message =
                    "The requested assignment target does not satisfy current source truth.",
                status = HttpStatus.CONFLICT,
                details =
                    buildJsonObject {
                        put(
                            "reasonCodes",
                            target.reasonCodes
                                .joinToString(","),
                        )
                    },
            )
        }
        return target
    }

    private fun assignmentPolicy(
        order: WorkOrderSnapshot,
    ): AssignmentPolicyExecutionSnapshot {
        val binding =
            order.binding ?: conflict(
                "WORK_BINDING_REQUIRED",
                "The WorkOrder must be planned before readiness or assignment.",
            )
        val policy =
            persistence.assignmentPolicy(
                binding.assignmentPolicy.id,
            ) ?: conflict(
                "ASSIGNMENT_POLICY_UNAVAILABLE",
                "The exact bound AssignmentPolicy is unavailable.",
            )
        if (
            policy.revision !=
            binding.assignmentPolicy.revision
        ) {
            conflict(
                "ASSIGNMENT_POLICY_REVISION_MISMATCH",
                "The exact bound AssignmentPolicy revision is inconsistent.",
            )
        }
        return policy
    }

    private fun readinessPolicy(
        order: WorkOrderSnapshot,
    ): ReadinessPolicyExecutionSnapshot {
        val binding =
            order.binding ?: conflict(
                "WORK_BINDING_REQUIRED",
                "The WorkOrder must be planned before readiness or assignment.",
            )
        val policy =
            persistence.readinessPolicy(
                binding.readinessPolicy.id,
            ) ?: conflict(
                "READINESS_POLICY_UNAVAILABLE",
                "The exact bound ReadinessPolicy is unavailable.",
            )
        if (
            policy.revision !=
            binding.readinessPolicy.revision
        ) {
            conflict(
                "READINESS_POLICY_REVISION_MISMATCH",
                "The exact bound ReadinessPolicy revision is inconsistent.",
            )
        }
        return policy
    }

    private fun readinessRequirements(
        order: WorkOrderSnapshot,
    ): List<ReadinessRequirementExecutionSnapshot> {
        val binding =
            order.binding ?: conflict(
                "WORK_BINDING_REQUIRED",
                "The WorkOrder must be planned before readiness.",
            )
        return persistence.readinessRequirements(
            workOrderId = order.workOrderId,
            readinessPolicyId =
                binding.readinessPolicy.id,
        )
    }

    private fun recordAssignment(
        action: String,
        previous: WorkAssignmentRecord?,
        assignment: WorkAssignmentRecord,
        order: WorkOrderSnapshot,
        actor: UUID,
        at: Instant,
        correlation: String,
    ) {
        audit.append(
            AuditEventRecord(
                actorUserId = actor,
                action = action,
                targetType = "WORK_ORDER",
                targetId = order.workOrderId,
                previousStateRef =
                    previous?.let {
                        "${it.targetType}:${it.targetId}"
                    },
                newStateRef =
                    "${assignment.targetType}:${assignment.targetId}",
                safeDiffJson =
                    """{"fields":["assignment","lifecycleState"]}""",
                occurredAt = at,
                correlationId = correlation,
                configRevisionRefsJson =
                    order.binding?.assignmentPolicy?.id
                        ?.let { """["$it"]""" },
            ),
        )
        events.publishEvent(
            WorkAssignmentChanged(
                workOrderId = order.workOrderId,
                organizationId =
                    order.organizationId,
                projectId = order.projectId,
                previousAssignmentId =
                    previous?.assignmentId,
                assignmentId =
                    assignment.assignmentId,
                targetType =
                    assignment.targetType,
                targetId = assignment.targetId,
                sourceVersion = order.version,
                actorUserId = actor,
                occurredAt = at,
                correlationId = correlation,
            ),
        )
    }

    private fun execute(
        operationId: UUID,
        actor: UUID,
        type: String,
        target: UUID,
        correlation: String,
        fingerprint: List<Any?>,
        action: () -> IdempotentCommandOutcome,
    ): Boolean =
        try {
            idempotency.execute(
                IdempotentCommandSpec(
                    operationId,
                    actor,
                    type,
                    "WORK_ORDER",
                    target,
                    IdempotencyKeyContract.fingerprint(
                        fingerprint.joinToString("|"),
                    ),
                    correlation,
                ),
            ) {
                action()
            }.replayed
        } catch (
            exception: DataIntegrityViolationException,
        ) {
            conflict(
                "WORK_ASSIGNMENT_CONSTRAINT_CONFLICT",
                "The change conflicts with authoritative readiness or assignment data.",
            )
        }

    private fun outcome(
        projectId: UUID,
        targetId: UUID,
    ) =
        IdempotentCommandOutcome(
            "APPLIED",
            buildJsonObject {
                put(
                    "projectId",
                    projectId.toString(),
                )
                put(
                    "targetId",
                    targetId.toString(),
                )
            }.toString(),
        )

    private fun requireAssign(
        actor: UUID,
        project: ProjectSnapshot,
    ) {
        if (
            !authorization.canAssignWork(
                actor,
                project,
            )
        ) {
            hidden()
        }
    }

    private fun requireReadinessLifecycle(
        order: WorkOrderSnapshot,
    ) {
        if (
            order.lifecycleState !in
            setOf(
                WorkOrderLifecycle.PLANNED,
                WorkOrderLifecycle.ASSIGNED,
            )
        ) {
            conflict(
                "READINESS_STATE_INVALID",
                "Readiness can be evaluated only for PLANNED or ASSIGNED Work.",
            )
        }
    }

    private fun project(id: UUID) =
        projects.project(
            id,
            clock.instant(),
        ) ?: hidden()

    private fun order(id: UUID) =
        work.workOrder(id) ?: hidden()

    private fun deterministic(
        namespace: String,
        operationId: UUID,
        suffix: String = "",
    ) =
        UUID.nameUUIDFromBytes(
            "hiltech:$namespace:$operationId:$suffix"
                .toByteArray(
                    StandardCharsets.UTF_8,
                ),
        )

    private fun positive(value: Long) {
        if (value < 1) {
            validation(
                "VERSION_INVALID",
                "Version must be positive.",
            )
        }
    }

    private fun optionalReason(
        value: String?,
    ): String? {
        val reason =
            value?.trim()
                ?.takeIf { it.isNotEmpty() }
        if (reason != null && reason.length > 2000) {
            validation(
                "REASON_TOO_LONG",
                "The reason exceeds the maximum length.",
            )
        }
        return reason
    }

    private fun requiredReason(
        value: String?,
    ): String =
        optionalReason(value)
            ?: validation(
                "READINESS_WAIVER_REASON_REQUIRED",
                "A readiness waiver requires an explicit reason.",
            )

    private fun version(): Nothing =
        throw ProductApiException(
            "VERSION_CONFLICT",
            "The WorkOrder, requirement or assignment changed before this command was applied.",
            HttpStatus.CONFLICT,
        )

    private fun conflict(
        code: String,
        message: String,
    ): Nothing =
        throw ProductApiException(
            code,
            message,
            HttpStatus.CONFLICT,
        )

    private fun validation(
        code: String,
        message: String,
    ): Nothing =
        throw ProductApiException(
            code,
            message,
            HttpStatus.BAD_REQUEST,
        )

    private fun hidden(): Nothing =
        throw ProductApiException(
            "OBJECT_NOT_VISIBLE",
            "The requested work object is unavailable.",
            HttpStatus.NOT_FOUND,
        )

    private data class RequirementEvaluation(
        val state: String,
        val reasonCode: String?,
        val satisfiedByRef: String? = null,
    )
}
