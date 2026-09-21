package com.hiltech.server.work

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.HiltechRequestContextSnapshot
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class ReviewStepResponse(
    val stepKey: String,
    val sequence: Int,
    val selectorType: String,
    val selectorValue: String?,
    val quorumCount: Int?,
    val reauthRequired: Boolean,
    val reasonRequiredOnRework: Boolean,
    val reasonRequiredOnReject: Boolean,
    val evidenceVisibilityMode: String,
)

data class ReviewPolicyResponse(
    val configId: String,
    val revision: Int,
    val code: String,
    val name: String,
    val mode: String,
    val bindExactSubmittedVersion: Boolean,
    val clientAcceptanceSeparate: Boolean,
    val allowDelegation: Boolean,
    val steps: List<ReviewStepResponse>,
)

data class ReviewDecisionResponse(
    val decisionId: String,
    val submittedWorkVersion: Long,
    val reviewStepKey: String?,
    val reviewerUserId: String,
    val decision: String,
    val reason: String?,
    val decidedAt: String,
    val reviewerSourceType: String?,
    val reviewerSourceRef: String?,
)

data class ReviewerEligibilityResponse(
    val eligible: Boolean,
    val reviewStepKey: String?,
    val sourceType: String?,
    val sourceRef: String?,
    val reasonCodes: List<String>,
)

data class ReviewWorkItemResponse(
    val workOrderId: String,
    val workOrderCode: String,
    val projectId: String,
    val projectCode: String,
    val projectName: String,
    val siteId: String,
    val title: String,
    val submittedAt: String?,
    val submittedVersion: Long?,
    val currentVersion: Long,
    val reviewPolicy: ReviewPolicyResponse,
    val decisions: List<ReviewDecisionResponse>,
    val reviewer: ReviewerEligibilityResponse,
    val beforeAcceptEvidenceSatisfied: Boolean,
    val evidenceReasonCodes: List<String>,
)

data class ReviewDecisionRequest(
    val operationId: String,
    val baseVersion: Long,
    val reason: String? = null,
    val clientOccurredAt: String,
)

data class ReviewMutationResponse(
    val workOrderId: String,
    val lifecycleState: String,
    val version: Long,
    val decision: ReviewDecisionResponse,
    val replayed: Boolean,
    val correlationId: String,
)

data class ProjectProgressResponse(
    val projectId: String,
    val projectVersion: Long,
    val baselineVersion: Int,
    val acceptedWeight: String,
    val totalWeight: String,
    val progressPercent: String?,
    val includedWorkCount: Int,
    val acceptedWorkCount: Int,
    val asOf: String,
)

data class ProjectHealthSignalResponse(
    val signalId: String,
    val signalCode: String,
    val severity: String,
    val sourceType: String,
    val sourceId: String,
    val firstObservedAt: String,
    val lastObservedAt: String,
    val summary: String,
    val sourceVersion: Long?,
)

data class ProjectHealthResponse(
    val projectId: String,
    val projectVersion: Long,
    val baselineVersion: Int,
    val state: String,
    val healthPolicyId: String?,
    val healthPolicyRevision: Int?,
    val signals: List<ProjectHealthSignalResponse>,
    val reasonCodes: List<String>,
    val asOf: String,
)

data class CommandCenterMilestoneResponse(
    val milestoneId: String,
    val code: String,
    val name: String,
    val plannedDate: String?,
    val state: String,
)

data class CommandCenterWorkResponse(
    val workOrderId: String,
    val workOrderCode: String,
    val title: String,
    val lifecycleState: String,
    val readinessState: String,
    val actionCode: String,
    val reasonCodes: List<String>,
    val projectSiteId: String,
    val workPackageId: String?,
)

data class ProjectCommandCenterResponse(
    val projectId: String,
    val projectCode: String,
    val projectName: String,
    val lifecycleState: String,
    val projectManagerLabel: String?,
    val baselineVersion: Int,
    val progress: ProjectProgressResponse,
    val health: ProjectHealthResponse,
    val nextMilestone: CommandCenterMilestoneResponse?,
    val waitingOn: List<CommandCenterWorkResponse>,
    val submittedReviewItems: List<ReviewWorkItemResponse>,
    val reworkItems: List<CommandCenterWorkResponse>,
    val projectSiteIds: List<String>,
    val workPackageIds: List<String>,
)

data class ProjectHoldRequest(
    val operationId: String,
    val baseVersion: Long,
    val reason: String,
    val clientOccurredAt: String,
)

data class ProjectResumeRequest(
    val operationId: String,
    val baseVersion: Long,
    val resolution: String,
    val clientOccurredAt: String,
)

data class ProjectHoldMutationResponse(
    val projectId: String,
    val lifecycleState: String,
    val projectVersion: Long,
    val health: ProjectHealthResponse,
    val replayed: Boolean,
    val correlationId: String,
)

@RestController
@RequestMapping("/v1/review")
class ReviewQueueController(
    private val service: ReviewProgressHealthService,
) {
    @GetMapping("/work-items")
    fun workItems(
        request: HttpServletRequest,
        @RequestParam(
            name = "limit",
            defaultValue = "200",
        )
        limit: Int,
    ): List<ReviewWorkItemResponse> {
        val context =
            HiltechRequestContext.current(request)
        return service.reviewWorkItems(
            context.identity(),
            limit,
        ).map {
            it.response()
        }
    }
}

@RestController
@RequestMapping("/v1/work-orders")
class WorkReviewController(
    private val service: ReviewProgressHealthService,
) {
    @PostMapping("/{id}/accept")
    fun accept(
        request: HttpServletRequest,
        @RequestHeader("Idempotency-Key")
        key: String,
        @PathVariable id: String,
        @RequestBody body: ReviewDecisionRequest,
    ): ReviewMutationResponse =
        command(
            request,
            key,
            body.operationId,
        ) { context, operation ->
            service.accept(
                AcceptWorkCommand(
                    operationId = operation,
                    workOrderId = id.uuid(),
                    baseVersion =
                        body.baseVersion,
                    reason = body.reason,
                    clientOccurredAt =
                        body.clientOccurredAt
                            .instant(),
                    actorUserId =
                        context.identity(),
                    correlationId =
                        context.correlationId,
                ),
            ).let {
                ReviewMutationResponse(
                    workOrderId =
                        it.workOrder
                            .workOrderId
                            .toString(),
                    lifecycleState =
                        it.workOrder
                            .lifecycleState
                            .name,
                    version =
                        it.workOrder.version,
                    decision =
                        it.decision.response(),
                    replayed = it.replayed,
                    correlationId =
                        context.correlationId,
                )
            }
        }

    @PostMapping("/{id}/request-rework")
    fun rework(
        request: HttpServletRequest,
        @RequestHeader("Idempotency-Key")
        key: String,
        @PathVariable id: String,
        @RequestBody body: ReviewDecisionRequest,
    ): ReviewMutationResponse =
        command(
            request,
            key,
            body.operationId,
        ) { context, operation ->
            service.requestRework(
                RequestReworkCommand(
                    operationId = operation,
                    workOrderId = id.uuid(),
                    baseVersion =
                        body.baseVersion,
                    reason = body.reason,
                    clientOccurredAt =
                        body.clientOccurredAt
                            .instant(),
                    actorUserId =
                        context.identity(),
                    correlationId =
                        context.correlationId,
                ),
            ).let {
                ReviewMutationResponse(
                    workOrderId =
                        it.workOrder
                            .workOrderId
                            .toString(),
                    lifecycleState =
                        it.workOrder
                            .lifecycleState
                            .name,
                    version =
                        it.workOrder.version,
                    decision =
                        it.decision.response(),
                    replayed = it.replayed,
                    correlationId =
                        context.correlationId,
                )
            }
        }
}

@RestController
@RequestMapping("/v1/projects")
class ProjectCommandCenterController(
    private val service: ReviewProgressHealthService,
) {
    @GetMapping("/{id}/progress")
    fun progress(
        request: HttpServletRequest,
        @PathVariable id: String,
    ): ProjectProgressResponse {
        val context =
            HiltechRequestContext.current(request)
        return service.progress(
            context.identity(),
            id.uuid(),
        ).response()
    }

    @GetMapping("/{id}/health")
    fun health(
        request: HttpServletRequest,
        @PathVariable id: String,
    ): ProjectHealthResponse {
        val context =
            HiltechRequestContext.current(request)
        return service.health(
            context.identity(),
            id.uuid(),
        ).response()
    }

    @GetMapping("/{id}/command-center")
    fun commandCenter(
        request: HttpServletRequest,
        @PathVariable id: String,
    ): ProjectCommandCenterResponse {
        val context =
            HiltechRequestContext.current(request)
        return service.commandCenter(
            context.identity(),
            id.uuid(),
        ).response()
    }

    @PostMapping("/{id}/put-on-hold")
    fun putOnHold(
        request: HttpServletRequest,
        @RequestHeader("Idempotency-Key")
        key: String,
        @PathVariable id: String,
        @RequestBody body: ProjectHoldRequest,
    ): ProjectHoldMutationResponse =
        command(
            request,
            key,
            body.operationId,
        ) { context, operation ->
            service.putOnHold(
                PutProjectOnHoldCommand(
                    operationId = operation,
                    projectId = id.uuid(),
                    baseVersion =
                        body.baseVersion,
                    reason = body.reason,
                    clientOccurredAt =
                        body.clientOccurredAt
                            .instant(),
                    actorUserId =
                        context.identity(),
                    correlationId =
                        context.correlationId,
                ),
            ).let {
                ProjectHoldMutationResponse(
                    projectId =
                        it.projectId.toString(),
                    lifecycleState =
                        it.lifecycleState.name,
                    projectVersion =
                        it.projectVersion,
                    health =
                        it.health.response(),
                    replayed = it.replayed,
                    correlationId =
                        context.correlationId,
                )
            }
        }

    @PostMapping("/{id}/resume")
    fun resume(
        request: HttpServletRequest,
        @RequestHeader("Idempotency-Key")
        key: String,
        @PathVariable id: String,
        @RequestBody body: ProjectResumeRequest,
    ): ProjectHoldMutationResponse =
        command(
            request,
            key,
            body.operationId,
        ) { context, operation ->
            service.resume(
                ResumeProjectCommand(
                    operationId = operation,
                    projectId = id.uuid(),
                    baseVersion =
                        body.baseVersion,
                    resolution =
                        body.resolution,
                    clientOccurredAt =
                        body.clientOccurredAt
                            .instant(),
                    actorUserId =
                        context.identity(),
                    correlationId =
                        context.correlationId,
                ),
            ).let {
                ProjectHoldMutationResponse(
                    projectId =
                        it.projectId.toString(),
                    lifecycleState =
                        it.lifecycleState.name,
                    projectVersion =
                        it.projectVersion,
                    health =
                        it.health.response(),
                    replayed = it.replayed,
                    correlationId =
                        context.correlationId,
                )
            }
        }
}

private fun ReviewWorkItemSnapshot.response() =
    ReviewWorkItemResponse(
        workOrderId =
            workOrderId.toString(),
        workOrderCode = workOrderCode,
        projectId =
            projectId.toString(),
        projectCode = projectCode,
        projectName = projectName,
        siteId = siteId.toString(),
        title = title,
        submittedAt =
            submittedAt?.toString(),
        submittedVersion =
            submittedVersion,
        currentVersion =
            currentVersion,
        reviewPolicy =
            reviewPolicy.response(),
        decisions =
            decisions.map {
                it.response()
            },
        reviewer =
            ReviewerEligibilityResponse(
                eligible =
                    reviewer.eligible,
                reviewStepKey =
                    reviewer.reviewStepKey,
                sourceType =
                    reviewer.sourceType,
                sourceRef =
                    reviewer.sourceRef,
                reasonCodes =
                    reviewer.reasonCodes,
            ),
        beforeAcceptEvidenceSatisfied =
            beforeAcceptEvidenceSatisfied,
        evidenceReasonCodes =
            evidenceReasonCodes,
    )

private fun ReviewPolicyExecutionSnapshot.response() =
    ReviewPolicyResponse(
        configId = configId.toString(),
        revision = revision,
        code = code,
        name = name,
        mode = mode.name,
        bindExactSubmittedVersion =
            bindExactSubmittedVersion,
        clientAcceptanceSeparate =
            clientAcceptanceSeparate,
        allowDelegation =
            allowDelegation,
        steps =
            steps.map {
                ReviewStepResponse(
                    stepKey =
                        it.stepKey,
                    sequence =
                        it.sequence,
                    selectorType =
                        it.selectorType.name,
                    selectorValue =
                        it.selectorValue,
                    quorumCount =
                        it.quorumCount,
                    reauthRequired =
                        it.reauthRequired,
                    reasonRequiredOnRework =
                        it.reasonRequiredOnRework,
                    reasonRequiredOnReject =
                        it.reasonRequiredOnReject,
                    evidenceVisibilityMode =
                        it.evidenceVisibilityMode,
                )
            },
    )

private fun ReviewDecisionSnapshot.response() =
    ReviewDecisionResponse(
        decisionId =
            decisionId.toString(),
        submittedWorkVersion =
            submittedWorkVersion,
        reviewStepKey =
            reviewStepKey,
        reviewerUserId =
            reviewerUserId.toString(),
        decision =
            decision.name,
        reason = reason,
        decidedAt =
            decidedAt.toString(),
        reviewerSourceType =
            reviewerSourceType,
        reviewerSourceRef =
            reviewerSourceRef,
    )

private fun ProjectProgressSnapshot.response() =
    ProjectProgressResponse(
        projectId =
            projectId.toString(),
        projectVersion =
            projectVersion,
        baselineVersion =
            baselineVersion,
        acceptedWeight =
            acceptedWeight
                .toPlainString(),
        totalWeight =
            totalWeight
                .toPlainString(),
        progressPercent =
            progressPercent
                ?.toPlainString(),
        includedWorkCount =
            includedWorkCount,
        acceptedWorkCount =
            acceptedWorkCount,
        asOf = asOf.toString(),
    )

private fun ProjectHealthSnapshot.response() =
    ProjectHealthResponse(
        projectId =
            projectId.toString(),
        projectVersion =
            projectVersion,
        baselineVersion =
            baselineVersion,
        state = state.name,
        healthPolicyId =
            healthPolicyId?.toString(),
        healthPolicyRevision =
            healthPolicyRevision,
        signals =
            signals.map {
                ProjectHealthSignalResponse(
                    signalId =
                        it.signalId.toString(),
                    signalCode =
                        it.signalCode.name,
                    severity =
                        it.severity.name,
                    sourceType =
                        it.sourceType,
                    sourceId =
                        it.sourceId.toString(),
                    firstObservedAt =
                        it.firstObservedAt
                            .toString(),
                    lastObservedAt =
                        it.lastObservedAt
                            .toString(),
                    summary =
                        it.summary,
                    sourceVersion =
                        it.sourceVersion,
                )
            },
        reasonCodes = reasonCodes,
        asOf = asOf.toString(),
    )

private fun ProjectCommandCenterSnapshot.response() =
    ProjectCommandCenterResponse(
        projectId =
            projectId.toString(),
        projectCode = projectCode,
        projectName = projectName,
        lifecycleState =
            lifecycleState.name,
        projectManagerLabel =
            projectManagerLabel,
        baselineVersion =
            baselineVersion,
        progress =
            progress.response(),
        health =
            health.response(),
        nextMilestone =
            nextMilestone?.let {
                CommandCenterMilestoneResponse(
                    milestoneId =
                        it.milestoneId
                            .toString(),
                    code = it.code,
                    name = it.name,
                    plannedDate =
                        it.plannedDate
                            ?.toString(),
                    state = it.state,
                )
            },
        waitingOn =
            waitingOn.map {
                it.response()
            },
        submittedReviewItems =
            submittedReviewItems
                .map {
                    it.response()
                },
        reworkItems =
            reworkItems.map {
                it.response()
            },
        projectSiteIds =
            projectSiteIds.map {
                it.toString()
            },
        workPackageIds =
            workPackageIds.map {
                it.toString()
            },
    )

private fun CommandCenterWorkReference.response() =
    CommandCenterWorkResponse(
        workOrderId =
            workOrderId.toString(),
        workOrderCode =
            workOrderCode,
        title = title,
        lifecycleState =
            lifecycleState.name,
        readinessState =
            readinessState.name,
        actionCode =
            actionCode,
        reasonCodes =
            reasonCodes,
        projectSiteId =
            projectSiteId.toString(),
        workPackageId =
            workPackageId?.toString(),
    )

private fun String.uuid() =
    runCatching {
        UUID.fromString(this)
    }.getOrElse {
        throw ProductApiException(
            "INVALID_ID",
            "A Slice 05 identifier is invalid.",
            HttpStatus.BAD_REQUEST,
        )
    }

private fun String.instant() =
    runCatching {
        Instant.parse(this)
    }.getOrElse {
        throw ProductApiException(
            "INVALID_INSTANT",
            "A Slice 05 timestamp is invalid.",
            HttpStatus.BAD_REQUEST,
        )
    }

private fun HiltechRequestContextSnapshot.identity() =
    identityId
        ?.let {
            runCatching {
                UUID.fromString(it)
            }.getOrNull()
        }
        ?: throw ProductApiException(
            "AUTHENTICATION_REQUIRED",
            "An authenticated identity is required.",
            HttpStatus.UNAUTHORIZED,
        )

private inline fun <T> command(
    request: HttpServletRequest,
    key: String,
    rawOperationId: String,
    block: (
        HiltechRequestContextSnapshot,
        UUID,
    ) -> T,
): T {
    val context =
        HiltechRequestContext.current(
            request,
        )
    val operation =
        rawOperationId.uuid()
    IdempotencyKeyContract.requireMatches(
        key,
        operation,
    )
    return block(
        context,
        operation,
    )
}
