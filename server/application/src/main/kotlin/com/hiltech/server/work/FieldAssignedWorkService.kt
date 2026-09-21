package com.hiltech.server.work

import com.hiltech.server.platform.ProductApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Component
class FieldAssignedWorkService(
    private val work: WorkPersistencePort,
    private val readiness:
        ReadinessAssignmentPersistencePort,
    private val persistence:
        FieldAssignedWorkPersistencePort,
    private val source:
        WorkAssignmentSourceAuthorityPort,
    private val authorization:
        WorkAssignmentAuthorizationPort,
    private val clock: Clock,
) {
    fun today(
        actorUserId: UUID,
    ): List<FieldTodayItem> {
        val now = clock.instant()
        return persistence
            .activeAssignmentCandidateWorkOrderIds(
                now,
            )
            .asSequence()
            .filter {
                source.isCurrentAssignedUser(
                    actorUserId,
                    it,
                    now,
                )
            }
            .filter {
                authorization.canUseAssignedWork(
                    actorUserId,
                    it,
                    "can_view",
                )
            }
            .mapNotNull {
                todayItem(
                    actorUserId,
                    it,
                    now,
                )
            }
            .sortedWith(
                compareBy<FieldTodayItem>(
                    { it.plannedStart == null },
                    { it.plannedStart },
                    { priorityRank(it.priorityCode) },
                    { it.workOrderCode },
                ),
            )
            .toList()
    }

    fun jobBundle(
        actorUserId: UUID,
        workOrderId: UUID,
    ): TechnicianJobBundle {
        val now = clock.instant()
        requireCurrentAssignedRead(
            actorUserId,
            workOrderId,
            now,
        )
        val order =
            work.workOrder(workOrderId)
                ?: hidden()
        val context =
            persistence.fieldContext(
                workOrderId,
            ) ?: hidden()
        val assignment =
            readiness.activeAssignment(
                workOrderId,
            ) ?: hidden()
        val binding = order.binding
        val requirements =
            if (binding == null) {
                emptyList()
            } else {
                readiness.readinessRequirements(
                    workOrderId =
                        workOrderId,
                    readinessPolicyId =
                        binding.readinessPolicy.id,
                )
            }
        val blockers =
            readiness.blockers(
                workOrderId,
            )
        val waiting =
            waitingReasons(
                order,
                requirements,
                blockers,
            )
        val evidenceRequirements =
            binding?.let {
                persistence.evidenceRequirements(
                    it.evidencePolicy.id,
                )
            } ?: emptyList()
        val evidence =
            binding?.let {
                persistence.readyEvidenceMetadata(
                    workOrderId =
                        workOrderId,
                    evidencePolicyId =
                        it.evidencePolicy.id,
                    evidencePolicyRevision =
                        it.evidencePolicy.revision,
                )
            } ?: emptyList()

        return TechnicianJobBundle(
            workOrderId =
                order.workOrderId,
            workOrderCode =
                order.workOrderCode,
            title = order.title,
            description =
                order.description,
            lifecycleState =
                order.lifecycleState,
            readinessState =
                order.readinessState,
            workOrderVersion =
                order.version,
            project = context.project,
            site = context.site,
            area = context.area,
            assignment =
                assignment.context(),
            binding = binding,
            instruction =
                order.instruction,
            tasks =
                order.tasks.filter {
                    it.state !=
                        WorkTaskState.CANCELLED
                },
            checklist =
                order.checklist,
            readinessRequirements =
                requirements,
            evidenceRequirements =
                evidenceRequirements,
            existingEvidence =
                evidence,
            resourceRequirements =
                resourceRequirements(
                    requirements,
                ),
            safeDocumentRefs =
                persistence.safeDocumentRefs(
                    workOrderId,
                ),
            blockers = blockers,
            waitingReasonCodes =
                waiting,
            executionCapabilities =
                FieldExecutionCapabilities(),
            asOf = now,
        )
    }

    private fun todayItem(
        actorUserId: UUID,
        workOrderId: UUID,
        now: Instant,
    ): FieldTodayItem? {
        requireCurrentAssignedRead(
            actorUserId,
            workOrderId,
            now,
        )
        val order =
            work.workOrder(workOrderId)
                ?: return null
        val context =
            persistence.fieldContext(
                workOrderId,
            ) ?: return null
        val assignment =
            readiness.activeAssignment(
                workOrderId,
            ) ?: return null
        val requirements =
            order.binding?.let {
                readiness.readinessRequirements(
                    workOrderId =
                        workOrderId,
                    readinessPolicyId =
                        it.readinessPolicy.id,
                )
            } ?: emptyList()
        val blockers =
            readiness.blockers(
                workOrderId,
            )
        val waiting =
            waitingReasons(
                order,
                requirements,
                blockers,
            )
        val actionable =
            order.lifecycleState ==
                WorkOrderLifecycle.ASSIGNED &&
                order.readinessState ==
                    WorkReadiness.READY

        return FieldTodayItem(
            workOrderId =
                order.workOrderId,
            workOrderCode =
                order.workOrderCode,
            title = order.title,
            lifecycleState =
                order.lifecycleState,
            readinessState =
                order.readinessState,
            plannedStart =
                order.plannedStart,
            plannedEnd =
                order.plannedEnd,
            priorityCode =
                order.priorityCode,
            project = context.project,
            site = context.site,
            area = context.area,
            assignment =
                assignment.context(),
            waitingReasonCodes =
                waiting,
            importantBlocker =
                blockers.firstOrNull()
                    ?.description
                    ?: waiting.firstOrNull(),
            instructionRevision =
                order.instruction?.revision,
            workOrderVersion =
                order.version,
            bundleAsOf = now,
            currentlyActionable =
                actionable,
        )
    }

    private fun requireCurrentAssignedRead(
        actorUserId: UUID,
        workOrderId: UUID,
        at: Instant,
    ) {
        if (
            !source.isCurrentAssignedUser(
                actorUserId,
                workOrderId,
                at,
            )
        ) {
            hidden()
        }
        if (
            !authorization.canUseAssignedWork(
                actorUserId,
                workOrderId,
                "can_view",
            )
        ) {
            hidden()
        }
    }

    private fun waitingReasons(
        order: WorkOrderSnapshot,
        requirements:
            List<ReadinessRequirementExecutionSnapshot>,
        blockers:
            List<WorkReadinessBlockerSnapshot>,
    ): List<String> =
        buildList {
            if (
                order.lifecycleState !=
                WorkOrderLifecycle.ASSIGNED
            ) {
                add(
                    "WORK_STATE_" +
                        order.lifecycleState.name,
                )
            }
            if (
                order.readinessState !=
                WorkReadiness.READY
            ) {
                add(
                    "READINESS_" +
                        order.readinessState.name,
                )
            }
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
                .forEach {
                    add(
                        it.evaluationReasonCode
                            ?: (
                                "REQUIREMENT_" +
                                    it.typeCode +
                                    "_" +
                                    it.satisfactionState
                            ),
                    )
                }
            blockers.forEach {
                add(
                    it.explanationCode
                        ?: it.blockerTypeCode,
                )
            }
        }.distinct()

    private fun resourceRequirements(
        requirements:
            List<ReadinessRequirementExecutionSnapshot>,
    ): List<FieldResourceRequirement> =
        requirements
            .filter {
                it.family in
                    setOf(
                        "MATERIAL",
                        "ASSET",
                    )
            }
            .map {
                FieldResourceRequirement(
                    requirementId =
                        it.requirementId,
                    family = it.family,
                    key = it.key,
                    typeCode =
                        it.typeCode,
                    required =
                        it.required,
                    state =
                        it.satisfactionState,
                    integrationState =
                        when {
                            !it.required ->
                                "NOT_REQUIRED"

                            it.satisfactionState ==
                                "WAIVED" ->
                                "WAIVED"

                            it.satisfactionState ==
                                "NOT_APPLICABLE" ->
                                "NOT_APPLICABLE"

                            else ->
                                "SOURCE_PENDING_PHASE5"
                        },
                    reasonCode =
                        it.evaluationReasonCode,
                    version = it.version,
                )
            }

    private fun WorkAssignmentRecord.context() =
        FieldAssignmentContext(
            assignmentId =
                assignmentId,
            targetType =
                targetType,
            targetId =
                targetId,
            targetLabel =
                targetLabel,
            validFrom =
                validFrom,
            validUntil =
                validUntil,
            version = version,
        )

    private fun priorityRank(
        priority: String,
    ): Int =
        when (priority.uppercase()) {
            "CRITICAL" -> 0
            "HIGH" -> 1
            "NORMAL" -> 2
            "LOW" -> 3
            else -> 4
        }

    private fun hidden(): Nothing =
        throw ProductApiException(
            "OBJECT_NOT_VISIBLE",
            "The assigned Work context is unavailable.",
            HttpStatus.NOT_FOUND,
        )
}
