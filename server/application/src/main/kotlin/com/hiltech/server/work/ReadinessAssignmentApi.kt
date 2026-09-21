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
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class EvaluateReadinessRequest(
    val operationId: String,
    val baseVersion: Long,
    val clientOccurredAt: String,
)

data class AssignWorkRequest(
    val operationId: String,
    val targetType: String? = null,
    val targetId: String? = null,
    val baseVersion: Long,
    val clientOccurredAt: String,
)

data class ReassignWorkRequest(
    val operationId: String,
    val targetType: String,
    val targetId: String,
    val baseVersion: Long,
    val currentAssignmentId: String,
    val baseAssignmentVersion: Long,
    val reason: String? = null,
    val clientOccurredAt: String,
)

data class WaiveReadinessRequirementRequest(
    val operationId: String,
    val baseWorkOrderVersion: Long,
    val baseRequirementVersion: Long,
    val reason: String? = null,
    val clientOccurredAt: String,
)

data class EligibilityCheckResponse(
    val code: String,
    val satisfied: Boolean,
    val reasonCode: String?,
)

data class EligibleTargetResponse(
    val targetType: String,
    val targetId: String,
    val displayLabel: String,
    val eligible: Boolean,
    val reasonCodes: List<String>,
    val sourceAsOf: String,
    val sourceFreshness: String,
    val requiredRoleChecks: List<EligibilityCheckResponse>,
    val requiredCertificationChecks: List<EligibilityCheckResponse>,
    val currentAssignmentConflict: String?,
)

data class WorkAssignmentRecordResponse(
    val assignmentId: String,
    val workOrderId: String,
    val targetType: String,
    val targetId: String,
    val targetLabel: String,
    val lead: Boolean,
    val state: String,
    val assignedAt: String,
    val assignedBy: String,
    val validFrom: String,
    val validUntil: String?,
    val supersedesAssignmentId: String?,
    val reason: String?,
    val version: Long,
)

data class ReadinessRequirementExecutionResponse(
    val requirementId: String,
    val family: String,
    val key: String,
    val typeCode: String,
    val label: String?,
    val required: Boolean,
    val satisfactionState: String,
    val evaluationReasonCode: String?,
    val sourceAsOf: String?,
    val evaluatedAt: String?,
    val waiverAllowed: Boolean,
    val waiverReasonRequired: Boolean,
    val waived: Boolean,
    val waiverRef: String?,
    val version: Long,
)

data class WorkReadinessBlockerResponse(
    val blockerId: String,
    val requirementId: String?,
    val blockerTypeCode: String,
    val explanationCode: String?,
    val description: String,
    val state: String,
    val version: Long,
)

data class WorkReadinessResponse(
    val workOrderId: String,
    val workOrderVersion: Long,
    val lifecycleState: String,
    val readinessState: String,
    val assignmentMode: String,
    val requirements: List<ReadinessRequirementExecutionResponse>,
    val blockers: List<WorkReadinessBlockerResponse>,
    val eligibleTargets: List<EligibleTargetResponse>,
    val currentAssignment: WorkAssignmentRecordResponse?,
    val assignmentHistory: List<WorkAssignmentRecordResponse>,
    val waitingOnReasonCodes: List<String>,
    val evaluatedAt: String?,
)

data class WorkReadinessMutationResponse(
    val readiness: WorkReadinessResponse,
    val replayed: Boolean,
    val correlationId: String,
)

data class WorkAssignmentMutationResponse(
    val readiness: WorkReadinessResponse,
    val assignmentId: String,
    val replayed: Boolean,
    val correlationId: String,
)

data class WorkQueueContextResponse(
    val workOrderId: String,
    val workOrderCode: String,
    val title: String,
    val actionCode: String,
    val reasonCodes: List<String>,
    val ownerUserId: String?,
)

@RestController
@RequestMapping("/v1/work-orders")
class ReadinessAssignmentController(
    private val service: ReadinessAssignmentService,
) {
    @GetMapping("/{id}/readiness")
    fun readiness(
        request: HttpServletRequest,
        @PathVariable id: String,
    ): WorkReadinessResponse {
        val context = HiltechRequestContext.current(request)
        return service.readiness(
            context.raIdentity(),
            id.raUuid(),
        ).raResponse()
    }

    @GetMapping("/{id}/eligible-targets")
    fun eligibleTargets(
        request: HttpServletRequest,
        @PathVariable id: String,
    ): List<EligibleTargetResponse> {
        val context = HiltechRequestContext.current(request)
        return service.eligibleTargets(
            context.raIdentity(),
            id.raUuid(),
        ).map { it.raResponse() }
    }

    @PostMapping("/{id}/evaluate-readiness")
    fun evaluateReadiness(
        request: HttpServletRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @PathVariable id: String,
        @RequestBody body: EvaluateReadinessRequest,
    ): WorkReadinessMutationResponse =
        raCommand(
            request,
            idempotencyKey,
            body.operationId,
        ) { context, operationId ->
            service.evaluate(
                EvaluateReadinessCommand(
                    operationId = operationId,
                    workOrderId = id.raUuid(),
                    baseVersion = body.baseVersion,
                    clientOccurredAt =
                        body.clientOccurredAt.raInstant(),
                    actorUserId = context.raIdentity(),
                    correlationId = context.correlationId,
                ),
            ).let {
                WorkReadinessMutationResponse(
                    readiness = it.readiness.raResponse(),
                    replayed = it.replayed,
                    correlationId = context.correlationId,
                )
            }
        }

    @PostMapping("/{id}/assign")
    fun assign(
        request: HttpServletRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @PathVariable id: String,
        @RequestBody body: AssignWorkRequest,
    ): WorkAssignmentMutationResponse =
        raCommand(
            request,
            idempotencyKey,
            body.operationId,
        ) { context, operationId ->
            service.assign(
                AssignWorkCommand(
                    operationId = operationId,
                    workOrderId = id.raUuid(),
                    targetType =
                        body.targetType?.raEnum(),
                    targetId = body.targetId?.raUuid(),
                    baseVersion = body.baseVersion,
                    clientOccurredAt =
                        body.clientOccurredAt.raInstant(),
                    actorUserId = context.raIdentity(),
                    correlationId = context.correlationId,
                ),
            ).let {
                WorkAssignmentMutationResponse(
                    readiness = it.readiness.raResponse(),
                    assignmentId =
                        it.assignmentId.toString(),
                    replayed = it.replayed,
                    correlationId = context.correlationId,
                )
            }
        }

    @PostMapping("/{id}/reassign")
    fun reassign(
        request: HttpServletRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @PathVariable id: String,
        @RequestBody body: ReassignWorkRequest,
    ): WorkAssignmentMutationResponse =
        raCommand(
            request,
            idempotencyKey,
            body.operationId,
        ) { context, operationId ->
            service.reassign(
                ReassignWorkCommand(
                    operationId = operationId,
                    workOrderId = id.raUuid(),
                    targetType =
                        body.targetType.raEnum(),
                    targetId = body.targetId.raUuid(),
                    baseVersion = body.baseVersion,
                    currentAssignmentId =
                        body.currentAssignmentId.raUuid(),
                    baseAssignmentVersion =
                        body.baseAssignmentVersion,
                    reason = body.reason,
                    clientOccurredAt =
                        body.clientOccurredAt.raInstant(),
                    actorUserId = context.raIdentity(),
                    correlationId = context.correlationId,
                ),
            ).let {
                WorkAssignmentMutationResponse(
                    readiness = it.readiness.raResponse(),
                    assignmentId =
                        it.assignmentId.toString(),
                    replayed = it.replayed,
                    correlationId = context.correlationId,
                )
            }
        }

    @PostMapping(
        "/{id}/readiness/{requirementId}/waive",
    )
    fun waive(
        request: HttpServletRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @PathVariable id: String,
        @PathVariable requirementId: String,
        @RequestBody body: WaiveReadinessRequirementRequest,
    ): WorkReadinessMutationResponse =
        raCommand(
            request,
            idempotencyKey,
            body.operationId,
        ) { context, operationId ->
            service.waive(
                WaiveReadinessRequirementCommand(
                    operationId = operationId,
                    workOrderId = id.raUuid(),
                    requirementId =
                        requirementId.raUuid(),
                    baseWorkOrderVersion =
                        body.baseWorkOrderVersion,
                    baseRequirementVersion =
                        body.baseRequirementVersion,
                    reason = body.reason,
                    clientOccurredAt =
                        body.clientOccurredAt.raInstant(),
                    actorUserId = context.raIdentity(),
                    correlationId = context.correlationId,
                ),
            ).let {
                WorkReadinessMutationResponse(
                    readiness = it.readiness.raResponse(),
                    replayed = it.replayed,
                    correlationId = context.correlationId,
                )
            }
        }
}

@RestController
@RequestMapping("/v1/projects")
class ProjectWorkQueueContextController(
    private val service: ReadinessAssignmentService,
) {
    @GetMapping("/{projectId}/work-queue-context")
    fun workQueueContext(
        request: HttpServletRequest,
        @PathVariable projectId: String,
    ): List<WorkQueueContextResponse> {
        val context = HiltechRequestContext.current(request)
        return service.workQueueContext(
            context.raIdentity(),
            projectId.raUuid(),
        ).map {
            WorkQueueContextResponse(
                workOrderId = it.workOrderId.toString(),
                workOrderCode = it.workOrderCode,
                title = it.title,
                actionCode = it.actionCode,
                reasonCodes = it.reasonCodes,
                ownerUserId = it.ownerUserId?.toString(),
            )
        }
    }
}

private fun WorkReadinessSnapshot.raResponse() =
    WorkReadinessResponse(
        workOrderId = workOrderId.toString(),
        workOrderVersion = workOrderVersion,
        lifecycleState = lifecycleState.name,
        readinessState = readinessState.name,
        assignmentMode = assignmentMode.name,
        requirements =
            requirements.map { it.raResponse() },
        blockers = blockers.map { it.raResponse() },
        eligibleTargets =
            eligibleTargets.map { it.raResponse() },
        currentAssignment =
            currentAssignment?.raResponse(),
        assignmentHistory =
            assignmentHistory.map { it.raResponse() },
        waitingOnReasonCodes =
            waitingOnReasonCodes,
        evaluatedAt = evaluatedAt?.toString(),
    )

private fun ReadinessRequirementExecutionSnapshot.raResponse() =
    ReadinessRequirementExecutionResponse(
        requirementId = requirementId.toString(),
        family = family,
        key = key,
        typeCode = typeCode,
        label = label,
        required = required,
        satisfactionState = satisfactionState,
        evaluationReasonCode =
            evaluationReasonCode,
        sourceAsOf = sourceAsOf?.toString(),
        evaluatedAt = evaluatedAt?.toString(),
        waiverAllowed = waiverAllowed,
        waiverReasonRequired =
            waiverReasonRequired,
        waived = waived,
        waiverRef = waiverRef?.toString(),
        version = version,
    )

private fun WorkReadinessBlockerSnapshot.raResponse() =
    WorkReadinessBlockerResponse(
        blockerId = blockerId.toString(),
        requirementId =
            requirementId?.toString(),
        blockerTypeCode = blockerTypeCode,
        explanationCode = explanationCode,
        description = description,
        state = state,
        version = version,
    )

private fun EligibleTargetResult.raResponse() =
    EligibleTargetResponse(
        targetType = targetType.name,
        targetId = targetId.toString(),
        displayLabel = displayLabel,
        eligible = eligible,
        reasonCodes = reasonCodes,
        sourceAsOf = sourceAsOf.toString(),
        sourceFreshness = sourceFreshness,
        requiredRoleChecks =
            requiredRoleChecks.map { it.raResponse() },
        requiredCertificationChecks =
            requiredCertificationChecks.map {
                it.raResponse()
            },
        currentAssignmentConflict =
            currentAssignmentConflict,
    )

private fun EligibilityCheck.raResponse() =
    EligibilityCheckResponse(
        code = code,
        satisfied = satisfied,
        reasonCode = reasonCode,
    )

private fun WorkAssignmentRecord.raResponse() =
    WorkAssignmentRecordResponse(
        assignmentId = assignmentId.toString(),
        workOrderId = workOrderId.toString(),
        targetType = targetType.name,
        targetId = targetId.toString(),
        targetLabel = targetLabel,
        lead = lead,
        state = state,
        assignedAt = assignedAt.toString(),
        assignedBy = assignedBy.toString(),
        validFrom = validFrom.toString(),
        validUntil = validUntil?.toString(),
        supersedesAssignmentId =
            supersedesAssignmentId?.toString(),
        reason = reason,
        version = version,
    )

private fun HiltechRequestContextSnapshot.raIdentity(): UUID =
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

private fun String.raUuid(): UUID =
    runCatching {
        UUID.fromString(this)
    }.getOrElse {
        throw ProductApiException(
            "INVALID_ID",
            "A readiness or assignment identifier is invalid.",
            HttpStatus.BAD_REQUEST,
        )
    }

private fun String.raInstant(): Instant =
    runCatching {
        Instant.parse(this)
    }.getOrElse {
        throw ProductApiException(
            "INVALID_INSTANT",
            "A readiness or assignment timestamp is invalid.",
            HttpStatus.BAD_REQUEST,
        )
    }

private inline fun <reified T : Enum<T>> String.raEnum(): T =
    runCatching {
        enumValueOf<T>(this)
    }.getOrElse {
        throw ProductApiException(
            "INVALID_ENUM",
            "A readiness or assignment enum value is invalid.",
            HttpStatus.BAD_REQUEST,
        )
    }

private inline fun <T> raCommand(
    request: HttpServletRequest,
    idempotencyKey: String,
    rawOperationId: String,
    block: (
        HiltechRequestContextSnapshot,
        UUID,
    ) -> T,
): T {
    val context =
        HiltechRequestContext.current(request)
    val operationId =
        rawOperationId.raUuid()
    IdempotencyKeyContract.requireMatches(
        idempotencyKey,
        operationId,
    )
    return block(
        context,
        operationId,
    )
}
