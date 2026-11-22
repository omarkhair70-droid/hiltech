package com.hiltech.server.work

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.platform.ProductConflictPayload
import com.hiltech.server.projects.ProjectsPersistencePort
import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class FieldCompletionGate(
    val available: Boolean,
    val satisfied: Boolean,
    val reasonCodes: List<String>,
)

fun interface FieldCompletionGateSourcePort {
    fun evaluate(order: WorkOrderSnapshot): FieldCompletionGate
}

data class CompletionSubmissionRequest(
    val operationId: UUID,
    val assignmentId: UUID,
    val assignmentVersion: Long,
    val baseVersion: Long,
    val bindingRevision: Int,
    val clientOccurredAt: Instant,
)

data class CompletionSubmissionResponse(
    val workOrderId: UUID,
    val lifecycleState: String,
    val version: Long,
    val submittedReviewVersion: Long,
    val duplicateReplay: Boolean,
    val correlationId: String,
)

data class WorkCompletionSubmissionRecord(
    val operationId: UUID,
    val organizationId: UUID,
    val workOrderId: UUID,
    val submittedByUserId: UUID,
    val assignmentId: UUID,
    val assignmentVersion: Long,
    val baseWorkOrderVersion: Long,
    val submittedWorkOrderVersion: Long,
    val policyBindingId: UUID,
    val policyBindingRevision: Int,
    val evidencePolicyId: UUID,
    val evidencePolicyRevision: Int,
    val reviewPolicyId: UUID,
    val reviewPolicyRevision: Int,
    val sourceLifecycleState: String,
    val dependencySnapshotJson: String,
    val clientOccurredAt: Instant,
    val submittedAt: Instant,
    val correlationId: String,
)

interface CompletionSubmissionPersistencePort : FieldCompletionGateSourcePort {
    fun submit(
        record: WorkCompletionSubmissionRecord,
        expectedWorkVersion: Long,
        expectedLifecycleState: WorkOrderLifecycle,
        at: Instant,
    ): Long?
}

@Repository
class JdbcCompletionSubmissionPersistence(
    private val jdbc: JdbcTemplate,
) : CompletionSubmissionPersistencePort {
    override fun evaluate(order: WorkOrderSnapshot): FieldCompletionGate {
        val binding =
            order.binding
                ?: return FieldCompletionGate(
                    available = true,
                    satisfied = false,
                    reasonCodes =
                        listOf(
                            "COMPLETION_POLICY_BINDING_REQUIRED",
                        ),
                )

        val reasons = mutableListOf<String>()

        order.checklist
            .filter {
                it.required &&
                    it.completionState != "COMPLETE" &&
                    it.completionState != "NOT_APPLICABLE"
            }
            .forEach {
                reasons +=
                    "REQUIRED_CHECKLIST_PENDING:" +
                        it.itemKey
            }

        order.requirements
            .filter {
                it.required &&
                    it.family == "DOCUMENT" &&
                    it.satisfactionState !in
                    setOf(
                        "SATISFIED",
                        "WAIVED",
                        "NOT_APPLICABLE",
                    )
            }
            .forEach {
                reasons +=
                    "DOCUMENT_REQUIREMENT_UNSATISFIED:" +
                        it.key
            }

        reasons +=
            beforeSubmitEvidenceReasons(
                order.workOrderId,
                binding.evidencePolicy.id,
                binding.evidencePolicy.revision,
            )
        reasons +=
            engineeringTestReasons(
                order.workOrderId,
                binding.evidencePolicy.id,
                binding.evidencePolicy.revision,
            )

        val blockerCount =
            jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM work_blocker
                WHERE work_order_id = ?
                  AND blocker_type_code = 'FIELD_EXECUTION'
                  AND state = 'OPEN'
                """.trimIndent(),
                Int::class.java,
                order.workOrderId,
            ) ?: 0
        if (blockerCount > 0) {
            reasons +=
                "OPEN_FIELD_EXECUTION_BLOCKER"
        }

        return FieldCompletionGate(
            available = true,
            satisfied = reasons.isEmpty(),
            reasonCodes = reasons.distinct(),
        )
    }

    private fun beforeSubmitEvidenceReasons(
        workOrderId: UUID,
        evidencePolicyId: UUID,
        evidencePolicyRevision: Int,
    ): List<String> =
        jdbc.query(
            """
            SELECT
                epr.requirement_key,
                epr.min_count,
                epr.structured_test_template_id,
                COALESCE(
                    (td.structured_definition ->> 'rawEvidenceRequired')::boolean,
                    false
                ) AS raw_evidence_required,
                (
                    SELECT COUNT(*)
                    FROM evidence e
                    WHERE e.work_order_id = ?
                      AND e.target_type = 'WORK_ORDER'
                      AND e.target_id = ?
                      AND e.evidence_policy_id = ?
                      AND e.evidence_policy_revision = ?
                      AND e.evidence_requirement_key =
                          epr.requirement_key
                      AND e.storage_state = 'READY'
                      AND NOT EXISTS (
                          SELECT 1
                          FROM evidence newer
                          WHERE newer.supersedes_evidence_id = e.id
                            AND newer.storage_state = 'READY'
                      )
                ) AS ready_count
            FROM evidence_policy_requirement epr
            LEFT JOIN config_revision tr
              ON tr.id = epr.structured_test_template_id
            LEFT JOIN template_definition td
              ON td.config_revision_id = tr.id
             AND td.template_type = 'ENGINEERING_TEST_SCHEMA'
            WHERE epr.config_revision_id = ?
              AND epr.stage = 'BEFORE_SUBMIT'
            ORDER BY epr.requirement_key
            """.trimIndent(),
            { rs, _ ->
                val structured =
                    rs.getObject(
                        "structured_test_template_id",
                        UUID::class.java,
                    ) != null
                val rawRequired =
                    rs.getBoolean(
                        "raw_evidence_required",
                    )
                val ready =
                    rs.getInt(
                        "ready_count",
                    )
                val minimum =
                    rs.getInt(
                        "min_count",
                    )
                if (
                    (!structured || rawRequired) &&
                    ready < minimum
                ) {
                    "BEFORE_SUBMIT_EVIDENCE_MISSING:" +
                        rs.getString(
                            "requirement_key",
                        )
                } else {
                    null
                }
            },
            workOrderId,
            workOrderId,
            evidencePolicyId,
            evidencePolicyRevision,
            evidencePolicyId,
        ).filterNotNull()

    private fun engineeringTestReasons(
        workOrderId: UUID,
        evidencePolicyId: UUID,
        evidencePolicyRevision: Int,
    ): List<String> {
        val requirements =
            jdbc.query(
                """
                SELECT
                    epr.requirement_key,
                    epr.min_count,
                    tr.id AS template_id,
                    tr.revision_number AS template_revision,
                    td.schema_version
                FROM evidence_policy_requirement epr
                JOIN config_revision tr
                  ON tr.id = epr.structured_test_template_id
                 AND tr.family = 'templates'
                JOIN template_definition td
                  ON td.config_revision_id = tr.id
                 AND td.template_type = 'ENGINEERING_TEST_SCHEMA'
                WHERE epr.config_revision_id = ?
                  AND epr.stage = 'BEFORE_SUBMIT'
                ORDER BY epr.requirement_key
                """.trimIndent(),
                { rs, _ ->
                    EngineeringGateRequirement(
                        requirementKey =
                            rs.getString(
                                "requirement_key",
                            ),
                        minCount =
                            rs.getInt(
                                "min_count",
                            ),
                        templateId =
                            rs.getObject(
                                "template_id",
                                UUID::class.java,
                            ),
                        templateRevision =
                            rs.getInt(
                                "template_revision",
                            ),
                        schemaVersion =
                            rs.getInt(
                                "schema_version",
                            ),
                    )
                },
                evidencePolicyId,
            )

        return buildList {
            requirements.forEach { requirement ->
                val current =
                    jdbc.query(
                        """
                        SELECT
                            r.operation_id,
                            r.test_subject_ref,
                            r.evaluated_status,
                            r.device_reported_status
                        FROM field_engineering_test_result r
                        WHERE r.work_order_id = ?
                          AND r.evidence_policy_id = ?
                          AND r.evidence_policy_revision = ?
                          AND r.requirement_key = ?
                          AND r.schema_template_id = ?
                          AND r.schema_template_revision = ?
                          AND r.schema_version = ?
                          AND NOT EXISTS (
                              SELECT 1
                              FROM field_engineering_test_result successor
                              WHERE successor.supersedes_result_id =
                                    r.operation_id
                          )
                        ORDER BY
                            r.test_subject_ref,
                            r.created_at DESC,
                            r.operation_id DESC
                        """.trimIndent(),
                        { rs, _ ->
                            EngineeringGateResult(
                                operationId =
                                    rs.getObject(
                                        "operation_id",
                                        UUID::class.java,
                                    ),
                                subject =
                                    rs.getString(
                                        "test_subject_ref",
                                    ),
                                evaluatedStatus =
                                    rs.getString(
                                        "evaluated_status",
                                    ),
                                deviceStatus =
                                    rs.getString(
                                        "device_reported_status",
                                    ),
                            )
                        },
                        workOrderId,
                        evidencePolicyId,
                        evidencePolicyRevision,
                        requirement.requirementKey,
                        requirement.templateId,
                        requirement.templateRevision,
                        requirement.schemaVersion,
                    )

                current
                    .groupBy {
                        it.subject
                    }
                    .filterValues {
                        it.size > 1
                    }
                    .keys
                    .forEach { subject ->
                        add(
                            "ENGINEERING_TEST_AMBIGUOUS:" +
                                requirement.requirementKey +
                                ":" +
                                subject,
                        )
                    }

                val latestBySubject =
                    current
                        .groupBy {
                            it.subject
                        }
                        .mapValues {
                            it.value.first()
                        }
                latestBySubject
                    .values
                    .filter {
                        it.evaluatedStatus != "PASS" ||
                            it.deviceStatus == "FAIL"
                    }
                    .forEach {
                        add(
                            "ENGINEERING_TEST_NOT_ACCEPTABLE:" +
                                requirement.requirementKey +
                                ":" +
                                it.subject,
                        )
                    }

                val acceptableCount =
                    latestBySubject
                        .values
                        .count {
                            it.evaluatedStatus == "PASS" &&
                                it.deviceStatus != "FAIL"
                        }
                if (
                    acceptableCount <
                    requirement.minCount
                ) {
                    add(
                        "ENGINEERING_TEST_MISSING:" +
                            requirement.requirementKey,
                    )
                }
            }
        }
    }

    override fun submit(
        record: WorkCompletionSubmissionRecord,
        expectedWorkVersion: Long,
        expectedLifecycleState: WorkOrderLifecycle,
        at: Instant,
    ): Long? {
        val submittedVersion =
            jdbc.query(
                """
                UPDATE work_order
                SET lifecycle_state = 'SUBMITTED_FOR_REVIEW',
                    submitted_at = ?,
                    submitted_review_version = version + 1,
                    updated_at = ?,
                    version = version + 1
                WHERE id = ?
                  AND organization_id = ?
                  AND version = ?
                  AND lifecycle_state = ?
                  AND current_policy_binding_id = ?
                RETURNING version
                """.trimIndent(),
                { rs, _ ->
                    rs.getLong(
                        "version",
                    )
                },
                at.atOffset(
                    ZoneOffset.UTC,
                ),
                at.atOffset(
                    ZoneOffset.UTC,
                ),
                record.workOrderId,
                record.organizationId,
                expectedWorkVersion,
                expectedLifecycleState.name,
                record.policyBindingId,
            ).singleOrNull()
                ?: return null

        check(
            submittedVersion ==
                record.submittedWorkOrderVersion,
        ) {
            "Completion submission version drift."
        }

        val inserted =
            jdbc.update(
                """
                INSERT INTO work_completion_submission (
                    operation_id,
                    organization_id,
                    work_order_id,
                    submitted_by_user_id,
                    assignment_id,
                    assignment_version,
                    base_work_order_version,
                    submitted_work_order_version,
                    policy_binding_id,
                    policy_binding_revision,
                    evidence_policy_id,
                    evidence_policy_revision,
                    review_policy_id,
                    review_policy_revision,
                    source_lifecycle_state,
                    dependency_snapshot_json,
                    client_occurred_at,
                    submitted_at,
                    correlation_id,
                    version
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?::jsonb, ?, ?, ?, 1
                )
                """.trimIndent(),
                record.operationId,
                record.organizationId,
                record.workOrderId,
                record.submittedByUserId,
                record.assignmentId,
                record.assignmentVersion,
                record.baseWorkOrderVersion,
                record.submittedWorkOrderVersion,
                record.policyBindingId,
                record.policyBindingRevision,
                record.evidencePolicyId,
                record.evidencePolicyRevision,
                record.reviewPolicyId,
                record.reviewPolicyRevision,
                record.sourceLifecycleState,
                record.dependencySnapshotJson,
                record.clientOccurredAt.atOffset(
                    ZoneOffset.UTC,
                ),
                record.submittedAt.atOffset(
                    ZoneOffset.UTC,
                ),
                record.correlationId,
            )
        check(inserted == 1) {
            "Completion submission insert must affect one row."
        }
        return submittedVersion
    }

    private data class EngineeringGateRequirement(
        val requirementKey: String,
        val minCount: Int,
        val templateId: UUID,
        val templateRevision: Int,
        val schemaVersion: Int,
    )

    private data class EngineeringGateResult(
        val operationId: UUID,
        val subject: String,
        val evaluatedStatus: String,
        val deviceStatus: String?,
    )
}

@Service
class CompletionSubmissionService(
    private val persistence:
        CompletionSubmissionPersistencePort,
    private val work: WorkPersistencePort,
    private val readiness:
        ReadinessAssignmentPersistencePort,
    private val assignmentSource:
        WorkAssignmentSourceAuthorityPort,
    private val authorization:
        WorkAssignmentAuthorizationPort,
    private val projects:
        ProjectsPersistencePort,
    private val commands:
        IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val events:
        ApplicationEventPublisher,
    private val clock: Clock,
    @Value("\${hiltech.field.completion.enabled:false}")
    private val enabled: Boolean = false,
) {
    @Transactional
    fun submit(
        actor: UUID,
        workOrderId: UUID,
        request:
            CompletionSubmissionRequest,
        correlationId: String,
    ): CompletionSubmissionResponse {
        if (!enabled) {
            unavailable()
        }
        validateRequest(request)
        val now = clock.instant()

        // No mutable Work/assignment/dependency preflight before the idempotency
        // check: a lost response must replay the original success after Work moves
        // to SUBMITTED_FOR_REVIEW (or a later reviewer state).
        val fingerprint =
            IdempotencyKeyContract
                .fingerprint(
                    listOf(
                        workOrderId,
                        request.assignmentId,
                        request.assignmentVersion,
                        request.baseVersion,
                        request.bindingRevision,
                        request.clientOccurredAt,
                    ).joinToString("|"),
                )

        val execution =
            commands.execute(
                IdempotentCommandSpec(
                    operationId =
                        request.operationId,
                    actorUserId = actor,
                    commandType =
                        "SUBMIT_WORK_COMPLETION",
                    targetType =
                        "WORK_ORDER",
                    targetId =
                        workOrderId,
                    requestFingerprint =
                        fingerprint,
                    correlationId =
                        correlationId,
                ),
            ) {
                work.lockWorkOrder(
                    workOrderId,
                )
                val locked =
                    work.workOrder(
                        workOrderId,
                    ) ?: hidden()
                validateSource(
                    locked,
                    request,
                )
                if (
                    !assignmentSource
                        .isCurrentAssignedUser(
                            actor,
                            workOrderId,
                            clock.instant(),
                        ) ||
                    !authorization
                        .canUseAssignedWork(
                            actor,
                            workOrderId,
                            "can_submit_completion",
                        )
                ) {
                    hidden()
                }
                val lockedAssignment =
                    readiness.activeAssignment(
                        workOrderId,
                    ) ?: hidden()
                if (
                    lockedAssignment.assignmentId !=
                    request.assignmentId ||
                    lockedAssignment.version !=
                    request.assignmentVersion
                ) {
                    conflict(
                        "COMPLETION_ASSIGNMENT_CHANGED",
                        locked.version,
                    )
                }
                val project =
                    projects.project(
                        locked.projectId,
                        clock.instant(),
                    ) ?: hidden()
                if (project.lifecycleState.name != "ACTIVE") {
                    conflict(
                        "COMPLETION_PROJECT_NOT_ACTIVE",
                        locked.version,
                    )
                }
                val lockedBinding =
                    locked.binding
                        ?: conflict(
                            "COMPLETION_POLICY_BINDING_REQUIRED",
                            locked.version,
                        )
                val gate =
                    persistence.evaluate(
                        locked,
                    )
                if (!gate.satisfied) {
                    dependencyConflict(
                        locked,
                        gate,
                    )
                }
                val submittedVersion =
                    locked.version + 1
                val snapshot =
                    buildJsonObject {
                        put(
                            "sourceWorkVersion",
                            locked.version,
                        )
                        put(
                            "sourceLifecycleState",
                            locked.lifecycleState.name,
                        )
                        put(
                            "assignmentId",
                            lockedAssignment.assignmentId
                                .toString(),
                        )
                        put(
                            "assignmentVersion",
                            lockedAssignment.version,
                        )
                        put(
                            "bindingRevision",
                            lockedBinding.bindingRevision,
                        )
                        put(
                            "evidencePolicyId",
                            lockedBinding.evidencePolicy.id
                                .toString(),
                        )
                        put(
                            "evidencePolicyRevision",
                            lockedBinding.evidencePolicy.revision,
                        )
                        put(
                            "reviewPolicyId",
                            lockedBinding.reviewPolicy.id
                                .toString(),
                        )
                        put(
                            "reviewPolicyRevision",
                            lockedBinding.reviewPolicy.revision,
                        )
                        putJsonArray(
                            "reasonCodes",
                        ) {
                            gate.reasonCodes.forEach {
                                add(JsonPrimitive(it))
                            }
                        }
                    }.toString()

                val applied =
                    persistence.submit(
                        WorkCompletionSubmissionRecord(
                            operationId =
                                request.operationId,
                            organizationId =
                                locked.organizationId,
                            workOrderId =
                                workOrderId,
                            submittedByUserId =
                                actor,
                            assignmentId =
                                lockedAssignment.assignmentId,
                            assignmentVersion =
                                lockedAssignment.version,
                            baseWorkOrderVersion =
                                locked.version,
                            submittedWorkOrderVersion =
                                submittedVersion,
                            policyBindingId =
                                lockedBinding.bindingId,
                            policyBindingRevision =
                                lockedBinding.bindingRevision,
                            evidencePolicyId =
                                lockedBinding.evidencePolicy.id,
                            evidencePolicyRevision =
                                lockedBinding.evidencePolicy.revision,
                            reviewPolicyId =
                                lockedBinding.reviewPolicy.id,
                            reviewPolicyRevision =
                                lockedBinding.reviewPolicy.revision,
                            sourceLifecycleState =
                                locked.lifecycleState.name,
                            dependencySnapshotJson =
                                snapshot,
                            clientOccurredAt =
                                request.clientOccurredAt,
                            submittedAt =
                                now,
                            correlationId =
                                correlationId,
                        ),
                        expectedWorkVersion =
                            locked.version,
                        expectedLifecycleState =
                            locked.lifecycleState,
                        at = now,
                    ) ?: conflict(
                        "COMPLETION_SOURCE_CHANGED",
                        locked.version,
                    )

                audit.append(
                    AuditEventRecord(
                        actorUserId = actor,
                        action =
                            "WORK_COMPLETION_SUBMITTED",
                        targetType =
                            "WORK_ORDER",
                        targetId =
                            workOrderId,
                        previousStateRef =
                            locked.lifecycleState.name,
                        newStateRef =
                            "SUBMITTED_FOR_REVIEW",
                        safeDiffJson =
                            buildJsonObject {
                                put(
                                    "submittedReviewVersion",
                                    applied,
                                )
                                put(
                                    "assignmentVersion",
                                    lockedAssignment.version,
                                )
                                put(
                                    "bindingRevision",
                                    lockedBinding.bindingRevision,
                                )
                            }.toString(),
                        occurredAt = now,
                        correlationId =
                            correlationId,
                        configRevisionRefsJson =
                            buildJsonArray {
                                add(
                                    JsonPrimitive(
                                        lockedBinding
                                            .evidencePolicy.id
                                            .toString(),
                                    ),
                                )
                                add(
                                    JsonPrimitive(
                                        lockedBinding
                                            .reviewPolicy.id
                                            .toString(),
                                    ),
                                )
                            }.toString(),
                    ),
                )
                events.publishEvent(
                    WorkOrderChanged(
                        workOrderId =
                            workOrderId,
                        organizationId =
                            locked.organizationId,
                        projectId =
                            locked.projectId,
                        siteId =
                            locked.siteId,
                        changeType =
                            "completion_submitted",
                        sourceVersion =
                            applied,
                        actorUserId = actor,
                        occurredAt = now,
                        correlationId =
                            correlationId,
                    ),
                )

                IdempotentCommandOutcome(
                    resultCode =
                        "COMPLETION_SUBMITTED",
                    resultPayloadJson =
                        buildJsonObject {
                            put(
                                "workOrderId",
                                workOrderId
                                    .toString(),
                            )
                            put(
                                "lifecycleState",
                                "SUBMITTED_FOR_REVIEW",
                            )
                            put(
                                "version",
                                applied,
                            )
                            put(
                                "submittedReviewVersion",
                                applied,
                            )
                        }.toString(),
                )
            }

        val version =
            execution.outcome.resultPayloadJson
                ?.let { payload ->
                    runCatching {
                        Json.parseToJsonElement(payload)
                            .jsonObject["version"]
                            ?.jsonPrimitive
                            ?.long
                    }.getOrNull()
                }
                ?: conflict(
                    "COMPLETION_RESULT_INVALID",
                    request.baseVersion,
                )

        return CompletionSubmissionResponse(
            workOrderId = workOrderId,
            lifecycleState =
                "SUBMITTED_FOR_REVIEW",
            version = version,
            submittedReviewVersion =
                version,
            duplicateReplay =
                execution.replayed,
            correlationId =
                correlationId,
        )
    }

    private fun validateRequest(
        request:
            CompletionSubmissionRequest,
    ) {
        if (
            request.assignmentVersion < 1 ||
            request.baseVersion < 1 ||
            request.bindingRevision < 1
        ) {
            throw ProductApiException(
                "COMPLETION_REQUEST_INVALID",
                "The completion submission request is invalid.",
                HttpStatus.BAD_REQUEST,
            )
        }
    }

    private fun validateSource(
        order: WorkOrderSnapshot,
        request:
            CompletionSubmissionRequest,
    ) {
        if (
            order.lifecycleState !in
            setOf(
                WorkOrderLifecycle.IN_PROGRESS,
                WorkOrderLifecycle.REWORK_REQUIRED,
            )
        ) {
            conflict(
                "COMPLETION_STATE_INVALID",
                order.version,
            )
        }
        if (
            order.version !=
            request.baseVersion
        ) {
            conflict(
                "COMPLETION_WORK_VERSION_CHANGED",
                order.version,
            )
        }
        if (
            order.binding?.bindingRevision !=
            request.bindingRevision
        ) {
            conflict(
                "COMPLETION_POLICY_BINDING_CHANGED",
                order.version,
            )
        }
    }

    private fun dependencyConflict(
        order: WorkOrderSnapshot,
        gate: FieldCompletionGate,
    ): Nothing =
        throw ProductApiException(
            "COMPLETION_DEPENDENCIES_UNSATISFIED",
            "Completion dependencies are not satisfied.",
            HttpStatus.CONFLICT,
            conflict =
                ProductConflictPayload(
                    conflictType =
                        "COMPLETION_DEPENDENCIES_UNSATISFIED",
                    currentVersion =
                        order.version,
                    safeCurrentState =
                        buildJsonObject {
                            put(
                                "lifecycleState",
                                order.lifecycleState.name,
                            )
                            putJsonArray(
                                "reasonCodes",
                            ) {
                                gate.reasonCodes
                                    .forEach {
                                        add(JsonPrimitive(it))
                                    }
                            }
                        },
                    allowedRecoveryActions =
                        listOf(
                            "REFRESH_JOB_BUNDLE",
                            "COMPLETE_REQUIRED_EVIDENCE",
                            "COMPLETE_REQUIRED_TESTS",
                            "RESOLVE_CHECKLIST",
                        ),
                ),
        )

    private fun unavailable(): Nothing =
        throw ProductApiException(
            "COMPLETION_UNAVAILABLE",
            "Completion submission is not enabled.",
            HttpStatus.NOT_FOUND,
        )

    private fun hidden(): Nothing =
        throw ProductApiException(
            "COMPLETION_UNAVAILABLE",
            "Completion submission is not available.",
            HttpStatus.NOT_FOUND,
        )

    private fun conflict(
        code: String,
        currentVersion: Long,
    ): Nothing =
        throw ProductApiException(
            code,
            "Completion source changed or is not eligible.",
            HttpStatus.CONFLICT,
            conflict =
                ProductConflictPayload(
                    conflictType = code,
                    currentVersion =
                        currentVersion,
                    safeCurrentState = null,
                    allowedRecoveryActions =
                        listOf(
                            "REFRESH_JOB_BUNDLE",
                        ),
                ),
        )
}

@RestController
@RequestMapping("/v1/work-orders")
class CompletionSubmissionController(
    private val service:
        CompletionSubmissionService,
) {
    @PostMapping(
        "/{workOrderId}/submit-completion",
    )
    fun submit(
        servletRequest: HttpServletRequest,
        @PathVariable
        workOrderId: UUID,
        @RequestHeader("Idempotency-Key")
        idempotencyKey: String,
        @RequestBody
        request:
            CompletionSubmissionRequest,
    ): CompletionSubmissionResponse {
        IdempotencyKeyContract
            .requireMatches(
                idempotencyKey,
                request.operationId,
            )
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        val actor =
            context.identityId
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
        return service.submit(
            actor,
            workOrderId,
            request,
            context.correlationId,
        )
    }
}
