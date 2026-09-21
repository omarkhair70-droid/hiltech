package com.hiltech.server.work

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.projects.ProjectAuthorizationPort
import com.hiltech.server.projects.ProjectLifecycleState
import com.hiltech.server.projects.ProjectSnapshot
import com.hiltech.server.projects.ProjectsPersistencePort
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

@Component
class ReviewProgressHealthService(
    private val projects: ProjectsPersistencePort,
    private val work: WorkPersistencePort,
    private val persistence: ReviewProgressHealthPersistencePort,
    private val reviewerEligibility: ReviewEligibilityResolverPort,
    private val authorization: ProjectAuthorizationPort,
    private val readiness: ReadinessAssignmentService,
    private val idempotency: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    fun reviewWorkItems(
        actorUserId: UUID,
        limit: Int = 200,
    ): List<ReviewWorkItemSnapshot> =
        persistence.submittedWorkOrders(limit)
            .mapNotNull {
                reviewItem(
                    actorUserId,
                    it,
                )
            }
            .filter {
                it.reviewer.eligible
            }

    fun progress(
        actorUserId: UUID,
        projectId: UUID,
    ): ProjectProgressSnapshot {
        val project = project(projectId)
        requireView(actorUserId, project)
        return persistence.recomputeProgress(
            project,
            clock.instant(),
        )
    }

    fun health(
        actorUserId: UUID,
        projectId: UUID,
    ): ProjectHealthSnapshot {
        val project = project(projectId)
        requireView(actorUserId, project)
        return rebuildHealth(
            project,
            actorUserId,
            "health-read",
            publishChange = false,
        )
    }

    fun commandCenter(
        actorUserId: UUID,
        projectId: UUID,
    ): ProjectCommandCenterSnapshot {
        val project = project(projectId)
        requireManage(actorUserId, project)
        val now = clock.instant()
        val progress =
            persistence.recomputeProgress(
                project,
                now,
            )
        val health =
            rebuildHealth(
                project,
                actorUserId,
                "command-center-read",
                publishChange = false,
            )
        val orders =
            work.workOrders(
                project.projectId,
            )
        val byId =
            orders.associateBy {
                it.workOrderId
            }
        val waitingOn =
            readiness.workQueueContext(
                actorUserId,
                project.projectId,
            ).mapNotNull { item ->
                byId[item.workOrderId]
                    ?.let { order ->
                        CommandCenterWorkReference(
                            workOrderId =
                                order.workOrderId,
                            workOrderCode =
                                order.workOrderCode,
                            title = order.title,
                            lifecycleState =
                                order.lifecycleState,
                            readinessState =
                                order.readinessState,
                            actionCode =
                                item.actionCode,
                            reasonCodes =
                                item.reasonCodes,
                            projectSiteId =
                                order.projectSiteId,
                            workPackageId =
                                order.workPackageId,
                        )
                    }
            }
        val reviewItems =
            persistence.submittedWorkOrders(500)
                .filter {
                    it.projectId ==
                        project.projectId
                }
                .mapNotNull {
                    reviewItem(
                        actorUserId,
                        it,
                    )
                }
        val reworkItems =
            orders.filter {
                it.lifecycleState ==
                    WorkOrderLifecycle.REWORK_REQUIRED
            }.map {
                CommandCenterWorkReference(
                    workOrderId = it.workOrderId,
                    workOrderCode =
                        it.workOrderCode,
                    title = it.title,
                    lifecycleState =
                        it.lifecycleState,
                    readinessState =
                        it.readinessState,
                    actionCode =
                        "REWORK_REQUIRED",
                    reasonCodes =
                        listOf(
                            "REWORK_REQUIRED",
                        ),
                    projectSiteId =
                        it.projectSiteId,
                    workPackageId =
                        it.workPackageId,
                )
            }

        return ProjectCommandCenterSnapshot(
            projectId = project.projectId,
            projectCode =
                project.projectCode,
            projectName = project.name,
            lifecycleState =
                project.lifecycleState,
            projectManagerLabel =
                project.responsibility
                    ?.principalLabel,
            baselineVersion =
                project.baselineVersion,
            progress = progress,
            health = health,
            nextMilestone =
                persistence.nextMilestone(
                    project,
                    now.atZone(
                        ZoneOffset.UTC,
                    ).toLocalDate(),
                ),
            waitingOn = waitingOn,
            submittedReviewItems =
                reviewItems,
            reworkItems = reworkItems,
            projectSiteIds =
                persistence.projectSiteIds(
                    project.projectId,
                ),
            workPackageIds =
                persistence.workPackageIds(
                    project.projectId,
                    project.baselineVersion,
                ),
        )
    }

    @Transactional
    fun accept(
        command: AcceptWorkCommand,
    ): ReviewMutationResult {
        positive(command.baseVersion)
        val initial = order(command.workOrderId)
        val project = project(initial.projectId)
        requireView(
            command.actorUserId,
            project,
        )
        val submittedVersion =
            persistence.submittedReviewVersion(
                command.workOrderId,
            ) ?: conflict(
                "SUBMITTED_REVIEW_VERSION_REQUIRED",
                "The WorkOrder does not have an authoritative submitted review version.",
            )
        val decisionId =
            deterministic(
                "work-review-accept",
                command.operationId,
            )

        val replayed =
            execute(
                operationId = command.operationId,
                actor = command.actorUserId,
                type = "WORK_REVIEW_ACCEPT",
                targetType = "WORK_ORDER",
                target = command.workOrderId,
                correlation =
                    command.correlationId,
                fingerprint =
                    listOf(
                        command.workOrderId,
                        command.baseVersion,
                        command.reason,
                        command.clientOccurredAt,
                    ),
            ) {
                val now = clock.instant()
                val current =
                    order(command.workOrderId)
                val currentProject =
                    project(current.projectId)
                requireReviewState(
                    current,
                    command.baseVersion,
                    submittedVersion,
                )
                val policy =
                    reviewPolicy(current)
                val decisions =
                    persistence.decisions(
                        current.workOrderId,
                        submittedVersion,
                    )
                val eligibility =
                    reviewerEligibility.resolve(
                        command.actorUserId,
                        currentProject,
                        policy,
                        decisions,
                        now,
                    )
                if (!eligibility.eligible) {
                    hidden()
                }
                val step =
                    policy.steps.singleOrNull {
                        it.stepKey ==
                            eligibility.reviewStepKey
                    } ?: conflict(
                        "REVIEW_STEP_REQUIRED",
                        "The reviewer is not mapped to a current ReviewPolicy step.",
                    )
                val evidenceGate =
                    requireNotNull(
                        current.binding,
                    ).let {
                        persistence.beforeAcceptEvidenceGate(
                            current.workOrderId,
                            it.evidencePolicy.id,
                            it.evidencePolicy.revision,
                        )
                    }
                if (!evidenceGate.satisfied) {
                    throw ProductApiException(
                        "BEFORE_ACCEPT_EVIDENCE_BLOCKED",
                        "Required BEFORE_ACCEPT evidence is not READY.",
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        details =
                            buildJsonObject {
                                put(
                                    "reasonCodes",
                                    evidenceGate
                                        .reasonCodes
                                        .joinToString(","),
                                )
                            },
                    )
                }

                val reason =
                    optionalReason(
                        command.reason,
                    )
                persistence.insertDecision(
                    decisionId =
                        decisionId,
                    operationId =
                        command.operationId,
                    workOrder = current,
                    submittedVersion =
                        submittedVersion,
                    policy = policy,
                    step = step,
                    reviewerUserId =
                        command.actorUserId,
                    decision =
                        WorkReviewDecisionType.ACCEPT,
                    reason = reason,
                    reviewerSourceType =
                        eligibility.sourceType,
                    reviewerSourceRef =
                        eligibility.sourceRef,
                    at = now,
                    correlationId =
                        command.correlationId,
                )

                val allDecisions =
                    persistence.decisions(
                        current.workOrderId,
                        submittedVersion,
                    )
                val complete =
                    reviewComplete(
                        policy,
                        allDecisions,
                    )

                var updated = current
                if (complete) {
                    if (
                        !persistence.acceptWorkOrder(
                            current.workOrderId,
                            current.version,
                            submittedVersion,
                            decisionId,
                            now,
                        )
                    ) {
                        version()
                    }
                    updated =
                        order(current.workOrderId)
                    persistence.recomputeProgress(
                        project(updated.projectId),
                        now,
                    )
                    rebuildHealth(
                        project(updated.projectId),
                        command.actorUserId,
                        command.correlationId,
                        publishChange = true,
                    )
                    events.publishEvent(
                        WorkAccepted(
                            workOrderId =
                                updated.workOrderId,
                            projectId =
                                updated.projectId,
                            decisionId =
                                decisionId,
                            sourceVersion =
                                updated.version,
                            actorUserId =
                                command.actorUserId,
                            occurredAt = now,
                            correlationId =
                                command.correlationId,
                        ),
                    )
                }

                audit.append(
                    AuditEventRecord(
                        actorUserId =
                            command.actorUserId,
                        action =
                            if (complete) {
                                "WORK_ACCEPTED"
                            } else {
                                "WORK_REVIEW_ACCEPTED_STEP"
                            },
                        targetType =
                            "WORK_ORDER",
                        targetId =
                            current.workOrderId,
                        previousStateRef =
                            current.lifecycleState.name,
                        newStateRef =
                            updated.lifecycleState.name,
                        safeDiffJson =
                            """{"fields":["reviewDecision","lifecycleState","acceptedProgress"]}""",
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                        configRevisionRefsJson =
                            """["""" +
                                policy.configId +
                                """"]""",
                    ),
                )
                outcome(
                    updated.projectId,
                    decisionId,
                )
            }

        val current =
            order(command.workOrderId)
        val decision =
            persistence.decisions(
                command.workOrderId,
                submittedVersion,
            ).single {
                it.decisionId == decisionId
            }
        return ReviewMutationResult(
            workOrder = current,
            decision = decision,
            replayed = replayed,
        )
    }

    @Transactional
    fun requestRework(
        command: RequestReworkCommand,
    ): ReviewMutationResult {
        positive(command.baseVersion)
        val initial =
            order(command.workOrderId)
        val project =
            project(initial.projectId)
        requireView(
            command.actorUserId,
            project,
        )
        val submittedVersion =
            persistence.submittedReviewVersion(
                command.workOrderId,
            ) ?: conflict(
                "SUBMITTED_REVIEW_VERSION_REQUIRED",
                "The WorkOrder does not have an authoritative submitted review version.",
            )
        val decisionId =
            deterministic(
                "work-review-rework",
                command.operationId,
            )
        val replayed =
            execute(
                operationId =
                    command.operationId,
                actor = command.actorUserId,
                type = "WORK_REVIEW_REWORK",
                targetType = "WORK_ORDER",
                target = command.workOrderId,
                correlation =
                    command.correlationId,
                fingerprint =
                    listOf(
                        command.workOrderId,
                        command.baseVersion,
                        command.reason,
                        command.clientOccurredAt,
                    ),
            ) {
                val now = clock.instant()
                val current =
                    order(command.workOrderId)
                val currentProject =
                    project(current.projectId)
                requireReviewState(
                    current,
                    command.baseVersion,
                    submittedVersion,
                )
                val policy =
                    reviewPolicy(current)
                val decisions =
                    persistence.decisions(
                        current.workOrderId,
                        submittedVersion,
                    )
                val eligibility =
                    reviewerEligibility.resolve(
                        command.actorUserId,
                        currentProject,
                        policy,
                        decisions,
                        now,
                    )
                if (!eligibility.eligible) {
                    hidden()
                }
                val step =
                    policy.steps.singleOrNull {
                        it.stepKey ==
                            eligibility.reviewStepKey
                    } ?: conflict(
                        "REVIEW_STEP_REQUIRED",
                        "The reviewer is not mapped to a current ReviewPolicy step.",
                    )
                val reason =
                    optionalReason(
                        command.reason,
                    )
                if (
                    step.reasonRequiredOnRework &&
                    reason == null
                ) {
                    validation(
                        "REWORK_REASON_REQUIRED",
                        "The bound ReviewPolicy requires a rework reason.",
                    )
                }
                persistence.insertDecision(
                    decisionId =
                        decisionId,
                    operationId =
                        command.operationId,
                    workOrder = current,
                    submittedVersion =
                        submittedVersion,
                    policy = policy,
                    step = step,
                    reviewerUserId =
                        command.actorUserId,
                    decision =
                        WorkReviewDecisionType.REWORK,
                    reason = reason,
                    reviewerSourceType =
                        eligibility.sourceType,
                    reviewerSourceRef =
                        eligibility.sourceRef,
                    at = now,
                    correlationId =
                        command.correlationId,
                )
                if (
                    !persistence.requestRework(
                        current.workOrderId,
                        current.version,
                        submittedVersion,
                        now,
                    )
                ) {
                    version()
                }
                val updated =
                    order(current.workOrderId)
                persistence.recomputeProgress(
                    currentProject,
                    now,
                )
                rebuildHealth(
                    project(updated.projectId),
                    command.actorUserId,
                    command.correlationId,
                    publishChange = true,
                )
                audit.append(
                    AuditEventRecord(
                        actorUserId =
                            command.actorUserId,
                        action =
                            "WORK_REWORK_REQUESTED",
                        targetType =
                            "WORK_ORDER",
                        targetId =
                            current.workOrderId,
                        previousStateRef =
                            current.lifecycleState.name,
                        newStateRef =
                            updated.lifecycleState.name,
                        safeDiffJson =
                            """{"fields":["reviewDecision","lifecycleState","reworkReason"]}""",
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                        configRevisionRefsJson =
                            """["""" +
                                policy.configId +
                                """"]""",
                    ),
                )
                events.publishEvent(
                    WorkReworkRequested(
                        workOrderId =
                            updated.workOrderId,
                        projectId =
                            updated.projectId,
                        decisionId =
                            decisionId,
                        sourceVersion =
                            updated.version,
                        actorUserId =
                            command.actorUserId,
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )
                outcome(
                    updated.projectId,
                    decisionId,
                )
            }

        val current =
            order(command.workOrderId)
        val decision =
            persistence.decisions(
                command.workOrderId,
                submittedVersion,
            ).single {
                it.decisionId == decisionId
            }
        return ReviewMutationResult(
            workOrder = current,
            decision = decision,
            replayed = replayed,
        )
    }

    @Transactional
    fun putOnHold(
        command: PutProjectOnHoldCommand,
    ): ProjectHoldMutationResult {
        positive(command.baseVersion)
        val initial =
            project(command.projectId)
        requireManage(
            command.actorUserId,
            initial,
        )
        val reason =
            requiredReason(command.reason)
        val holdId =
            deterministic(
                "project-hold",
                command.operationId,
            )

        val replayed =
            execute(
                operationId =
                    command.operationId,
                actor =
                    command.actorUserId,
                type = "PROJECT_PUT_ON_HOLD",
                targetType = "PROJECT",
                target =
                    command.projectId,
                correlation =
                    command.correlationId,
                fingerprint =
                    listOf(
                        command.projectId,
                        command.baseVersion,
                        reason,
                        command.clientOccurredAt,
                    ),
            ) {
                val now = clock.instant()
                val current =
                    project(command.projectId)
                requireManage(
                    command.actorUserId,
                    current,
                )
                if (
                    current.version !=
                    command.baseVersion
                ) {
                    version()
                }
                if (
                    current.lifecycleState !=
                    ProjectLifecycleState.ACTIVE
                ) {
                    conflict(
                        "PROJECT_HOLD_STATE_INVALID",
                        "Only an ACTIVE Project can be put on hold.",
                    )
                }
                if (
                    persistence.openHold(
                        current.projectId,
                    ) != null
                ) {
                    conflict(
                        "PROJECT_HOLD_ALREADY_OPEN",
                        "The Project already has an open hold.",
                    )
                }
                persistence.insertHold(
                    holdId,
                    current,
                    command.operationId,
                    reason,
                    command.actorUserId,
                    now,
                )
                if (
                    !projects.transitionProject(
                        current.projectId,
                        current.version,
                        ProjectLifecycleState.ACTIVE,
                        ProjectLifecycleState.ON_HOLD,
                        now,
                    )
                ) {
                    version()
                }
                val updated =
                    project(current.projectId)
                val health =
                    rebuildHealth(
                        updated,
                        command.actorUserId,
                        command.correlationId,
                        publishChange = true,
                    )
                audit.append(
                    AuditEventRecord(
                        actorUserId =
                            command.actorUserId,
                        action =
                            "PROJECT_PUT_ON_HOLD",
                        targetType = "PROJECT",
                        targetId =
                            updated.projectId,
                        previousStateRef =
                            ProjectLifecycleState.ACTIVE.name,
                        newStateRef =
                            ProjectLifecycleState.ON_HOLD.name,
                        safeDiffJson =
                            """{"fields":["lifecycleState","holdReason","healthState"]}""",
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )
                events.publishEvent(
                    ProjectHoldChanged(
                        projectId =
                            updated.projectId,
                        fromState =
                            ProjectLifecycleState.ACTIVE,
                        toState =
                            ProjectLifecycleState.ON_HOLD,
                        sourceVersion =
                            updated.version,
                        actorUserId =
                            command.actorUserId,
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )
                outcome(
                    updated.projectId,
                    holdId,
                )
            }

        val updated =
            project(command.projectId)
        return ProjectHoldMutationResult(
            projectId = updated.projectId,
            lifecycleState =
                updated.lifecycleState,
            projectVersion =
                updated.version,
            health =
                rebuildHealth(
                    updated,
                    command.actorUserId,
                    command.correlationId,
                    publishChange = false,
                ),
            replayed = replayed,
        )
    }

    @Transactional
    fun resume(
        command: ResumeProjectCommand,
    ): ProjectHoldMutationResult {
        positive(command.baseVersion)
        val initial =
            project(command.projectId)
        requireManage(
            command.actorUserId,
            initial,
        )
        val resolution =
            requiredReason(
                command.resolution,
            )

        val replayed =
            execute(
                operationId =
                    command.operationId,
                actor =
                    command.actorUserId,
                type = "PROJECT_RESUME",
                targetType = "PROJECT",
                target =
                    command.projectId,
                correlation =
                    command.correlationId,
                fingerprint =
                    listOf(
                        command.projectId,
                        command.baseVersion,
                        resolution,
                        command.clientOccurredAt,
                    ),
            ) {
                val now = clock.instant()
                val current =
                    project(command.projectId)
                requireManage(
                    command.actorUserId,
                    current,
                )
                if (
                    current.version !=
                    command.baseVersion
                ) {
                    version()
                }
                if (
                    current.lifecycleState !=
                    ProjectLifecycleState.ON_HOLD
                ) {
                    conflict(
                        "PROJECT_RESUME_STATE_INVALID",
                        "Only an ON_HOLD Project can be resumed.",
                    )
                }
                val hold =
                    persistence.openHold(
                        current.projectId,
                    ) ?: conflict(
                        "PROJECT_HOLD_RECORD_REQUIRED",
                        "The Project has no current hold blocker to resolve.",
                    )
                if (
                    !persistence.resolveHold(
                        hold.holdId,
                        hold.version,
                        command.operationId,
                        resolution,
                        command.actorUserId,
                        now,
                    )
                ) {
                    version()
                }
                if (
                    !projects.transitionProject(
                        current.projectId,
                        current.version,
                        ProjectLifecycleState.ON_HOLD,
                        ProjectLifecycleState.ACTIVE,
                        now,
                    )
                ) {
                    version()
                }
                val updated =
                    project(current.projectId)
                rebuildHealth(
                    updated,
                    command.actorUserId,
                    command.correlationId,
                    publishChange = true,
                )
                audit.append(
                    AuditEventRecord(
                        actorUserId =
                            command.actorUserId,
                        action =
                            "PROJECT_RESUMED",
                        targetType = "PROJECT",
                        targetId =
                            updated.projectId,
                        previousStateRef =
                            ProjectLifecycleState.ON_HOLD.name,
                        newStateRef =
                            ProjectLifecycleState.ACTIVE.name,
                        safeDiffJson =
                            """{"fields":["lifecycleState","holdResolution","healthState"]}""",
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )
                events.publishEvent(
                    ProjectHoldChanged(
                        projectId =
                            updated.projectId,
                        fromState =
                            ProjectLifecycleState.ON_HOLD,
                        toState =
                            ProjectLifecycleState.ACTIVE,
                        sourceVersion =
                            updated.version,
                        actorUserId =
                            command.actorUserId,
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )
                outcome(
                    updated.projectId,
                    hold.holdId,
                )
            }

        val updated =
            project(command.projectId)
        return ProjectHoldMutationResult(
            projectId = updated.projectId,
            lifecycleState =
                updated.lifecycleState,
            projectVersion =
                updated.version,
            health =
                rebuildHealth(
                    updated,
                    command.actorUserId,
                    command.correlationId,
                    publishChange = false,
                ),
            replayed = replayed,
        )
    }

    private fun reviewItem(
        actorUserId: UUID,
        row: SubmittedReviewWorkRow,
    ): ReviewWorkItemSnapshot? {
        val order =
            work.workOrder(row.workOrderId)
                ?: return null
        val project =
            projects.project(
                row.projectId,
                clock.instant(),
            ) ?: return null
        if (
            !authorization.canViewProject(
                actorUserId,
                project,
            )
        ) {
            return null
        }
        val policy =
            persistence.reviewPolicy(
                row.reviewPolicyId,
            ) ?: return null
        val submittedVersion =
            row.submittedReviewVersion
                ?: return null
        val decisions =
            persistence.decisions(
                row.workOrderId,
                submittedVersion,
            )
        val reviewer =
            reviewerEligibility.resolve(
                actorUserId,
                project,
                policy,
                decisions,
                clock.instant(),
            )
        val binding =
            order.binding
                ?: return null
        val evidence =
            persistence.beforeAcceptEvidenceGate(
                order.workOrderId,
                binding.evidencePolicy.id,
                binding.evidencePolicy.revision,
            )
        return ReviewWorkItemSnapshot(
            workOrderId =
                row.workOrderId,
            workOrderCode =
                row.workOrderCode,
            projectId = row.projectId,
            projectCode =
                row.projectCode,
            projectName =
                row.projectName,
            siteId = row.siteId,
            title = row.title,
            submittedAt =
                row.submittedAt,
            submittedVersion =
                row.submittedReviewVersion,
            currentVersion =
                row.currentVersion,
            reviewPolicy = policy,
            decisions = decisions,
            reviewer = reviewer,
            beforeAcceptEvidenceSatisfied =
                evidence.satisfied,
            evidenceReasonCodes =
                evidence.reasonCodes,
        )
    }

    private fun reviewPolicy(
        order: WorkOrderSnapshot,
    ): ReviewPolicyExecutionSnapshot {
        val binding =
            order.binding
                ?: conflict(
                    "WORK_POLICY_BINDING_REQUIRED",
                    "The submitted WorkOrder has no bound policy revision.",
                )
        val policy =
            persistence.reviewPolicy(
                binding.reviewPolicy.id,
            ) ?: conflict(
                "REVIEW_POLICY_UNAVAILABLE",
                "The bound ReviewPolicy revision is unavailable.",
            )
        if (
            policy.revision !=
                binding.reviewPolicy.revision ||
            !policy.bindExactSubmittedVersion
        ) {
            conflict(
                "REVIEW_POLICY_BINDING_INVALID",
                "The WorkOrder review must use its exact bound ReviewPolicy revision.",
            )
        }
        if (policy.steps.isEmpty()) {
            conflict(
                "REVIEW_POLICY_STEPS_REQUIRED",
                "The bound ReviewPolicy has no executable review steps.",
            )
        }
        return policy
    }

    private fun reviewComplete(
        policy: ReviewPolicyExecutionSnapshot,
        decisions: List<ReviewDecisionSnapshot>,
    ): Boolean {
        val accepts =
            decisions.filter {
                it.decision ==
                    WorkReviewDecisionType.ACCEPT
            }
        if (accepts.isEmpty()) {
            return false
        }
        return when (policy.mode) {
            ReviewMode.ANY_ONE ->
                accepts.isNotEmpty()

            ReviewMode.ALL,
            ReviewMode.SEQUENTIAL,
            ->
                policy.steps.all { step ->
                    accepts.any {
                        it.reviewStepKey ==
                            step.stepKey
                    }
                }

            ReviewMode.QUORUM ->
                policy.steps.all { step ->
                    accepts.filter {
                        it.reviewStepKey ==
                            step.stepKey
                    }
                        .map {
                            it.reviewerUserId
                        }
                        .distinct()
                        .size >=
                        (step.quorumCount ?: 1)
                }
        }
    }

    private fun rebuildHealth(
        project: ProjectSnapshot,
        actorUserId: UUID,
        correlationId: String,
        publishChange: Boolean,
    ): ProjectHealthSnapshot {
        val now = clock.instant()
        val previous =
            persistence.health(
                project.projectId,
            )
        val policy =
            persistence.activeHealthPolicy(
                project.organizationId,
                now,
            )
        val signals =
            persistence.deriveHealthSignals(
                project,
                policy,
                now,
            )
        persistence.replaceHealthSignals(
            project.projectId,
            project.baselineVersion,
            signals,
            now,
        )
        val state =
            when {
                project.lifecycleState ==
                    ProjectLifecycleState.ON_HOLD ->
                    ProjectHealthState.ON_HOLD

                policy == null ->
                    ProjectHealthState.UNKNOWN

                signals.isEmpty() ->
                    ProjectHealthState.HEALTHY

                else ->
                    aggregateHealth(
                        policy,
                        signals,
                    )
            }
        val stored =
            persistence.storeHealth(
                project,
                policy,
                state,
                signals,
                now,
            )
        if (
            publishChange &&
            previous?.state != stored.state
        ) {
            events.publishEvent(
                ProjectHealthChanged(
                    projectId =
                        project.projectId,
                    previousState =
                        previous?.state
                            ?: ProjectHealthState.UNKNOWN,
                    newState = stored.state,
                    sourceVersion =
                        project.version,
                    actorUserId =
                        actorUserId,
                    occurredAt = now,
                    correlationId =
                        correlationId,
                ),
            )
            audit.append(
                AuditEventRecord(
                    actorUserId =
                        actorUserId,
                    action =
                        "PROJECT_HEALTH_CHANGED",
                    targetType =
                        "PROJECT",
                    targetId =
                        project.projectId,
                    previousStateRef =
                        previous?.state?.name,
                    newStateRef =
                        stored.state.name,
                    safeDiffJson =
                        """{"fields":["healthState","healthSignals"]}""",
                    occurredAt = now,
                    correlationId =
                        correlationId,
                    configRevisionRefsJson =
                        policy?.configId
                            ?.let {
                                """["""" +
                                    it +
                                    """"]"""
                            },
                ),
            )
        }
        return stored
    }

    private fun aggregateHealth(
        policy: ProjectHealthPolicyExecutionSnapshot,
        signals: List<ProjectHealthSignalSnapshot>,
    ): ProjectHealthState {
        val present =
            signals.map {
                it.severity.name
            }.toSet()
        val precedence =
            policy.aggregationPrecedence
                .map {
                    it.uppercase()
                }
                .filter {
                    it in
                        setOf(
                            "CRITICAL",
                            "ATTENTION",
                        )
                }
        val selected =
            precedence.firstOrNull {
                it in present
            } ?: when {
                "CRITICAL" in present ->
                    "CRITICAL"

                else -> "ATTENTION"
            }
        return ProjectHealthState.valueOf(
            selected,
        )
    }

    private fun requireReviewState(
        order: WorkOrderSnapshot,
        baseVersion: Long,
        submittedVersion: Long,
    ) {
        if (
            order.lifecycleState !=
            WorkOrderLifecycle
                .SUBMITTED_FOR_REVIEW
        ) {
            conflict(
                "WORK_REVIEW_STATE_INVALID",
                "Only SUBMITTED_FOR_REVIEW Work can be reviewed.",
            )
        }
        if (
            order.version != baseVersion ||
            submittedVersion !=
                baseVersion
        ) {
            version()
        }
    }

    private fun execute(
        operationId: UUID,
        actor: UUID,
        type: String,
        targetType: String,
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
                    targetType,
                    target,
                    IdempotencyKeyContract.fingerprint(
                        fingerprint.joinToString(
                            "|",
                        ),
                    ),
                    correlation,
                ),
            ) {
                action()
            }.replayed
        } catch (
            exception:
                DataIntegrityViolationException,
        ) {
            conflict(
                "SLICE05_CONSTRAINT_CONFLICT",
                "The review or project state conflicts with authoritative current data.",
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

    private fun requireView(
        actor: UUID,
        project: ProjectSnapshot,
    ) {
        if (
            !authorization.canViewProject(
                actor,
                project,
            )
        ) {
            hidden()
        }
    }

    private fun requireManage(
        actor: UUID,
        project: ProjectSnapshot,
    ) {
        if (
            !authorization.canManageProject(
                actor,
                project,
            )
        ) {
            hidden()
        }
    }

    private fun project(
        id: UUID,
    ): ProjectSnapshot =
        projects.project(
            id,
            clock.instant(),
        ) ?: hidden()

    private fun order(
        id: UUID,
    ): WorkOrderSnapshot =
        work.workOrder(id)
            ?: hidden()

    private fun deterministic(
        namespace: String,
        operationId: UUID,
    ): UUID =
        UUID.nameUUIDFromBytes(
            (
                "hiltech:" +
                    namespace + ":" +
                    operationId
            ).toByteArray(
                StandardCharsets.UTF_8,
            ),
        )

    private fun positive(
        value: Long,
    ) {
        if (value < 1) {
            validation(
                "VERSION_INVALID",
                "Version must be positive.",
            )
        }
    }

    private fun optionalReason(
        value: String?,
    ): String? =
        value?.trim()
            ?.takeIf {
                it.isNotEmpty()
            }
            ?.also {
                if (it.length > 4000) {
                    validation(
                        "REASON_TOO_LONG",
                        "Reason exceeds 4000 characters.",
                    )
                }
            }

    private fun requiredReason(
        value: String,
    ): String =
        optionalReason(value)
            ?: validation(
                "REASON_REQUIRED",
                "A reason is required.",
            )

    private fun version(): Nothing =
        throw ProductApiException(
            "VERSION_CONFLICT",
            "The reviewed object changed before the command was applied.",
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
            "The requested review or project object is unavailable.",
            HttpStatus.NOT_FOUND,
        )
}
