package com.hiltech.server.work

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.projects.ProjectLifecycleState
import com.hiltech.server.projects.ProjectsPersistencePort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class FieldExecutionCommand(
    val operationId: UUID,
    val workOrderId: UUID,
    val action: FieldExecutionAction,
    val assignmentId: UUID,
    val assignmentVersion: Long,
    val baseVersion: Long,
    val clientOccurredAt: Instant,
    val actorUserId: UUID,
    val correlationId: String,
    val reason: String? = null,
    val blockerId: UUID? = null,
)

data class FieldExecutionResult(
    val workOrderId: UUID,
    val lifecycleState: WorkOrderLifecycle,
    val version: Long,
    val blockerId: UUID?,
    val replayed: Boolean,
)

@Component
class FieldExecutionService(
    private val work: WorkPersistencePort,
    private val readiness: ReadinessAssignmentPersistencePort,
    private val source: WorkAssignmentSourceAuthorityPort,
    private val authorization: WorkAssignmentAuthorizationPort,
    private val projects: ProjectsPersistencePort,
    private val persistence: FieldExecutionPersistencePort,
    private val idempotency: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @Transactional
    fun execute(command: FieldExecutionCommand): FieldExecutionResult {
        if (command.baseVersion < 1 || command.assignmentVersion < 1) {
            throw ProductApiException("VERSION_INVALID", "Positive WorkOrder and assignment versions are required.", HttpStatus.BAD_REQUEST)
        }
        val reason = when (command.action) {
            FieldExecutionAction.START -> {
                if (command.reason != null || command.blockerId != null) invalid()
                null
            }
            FieldExecutionAction.BLOCK -> {
                if (command.blockerId != null) invalid()
                requiredReason(command.reason)
            }
            FieldExecutionAction.RESUME -> {
                if (command.blockerId == null) invalid()
                requiredReason(command.reason)
            }
        }
        val type = when (command.action) {
            FieldExecutionAction.START -> "FIELD_START_WORK"
            FieldExecutionAction.BLOCK -> "FIELD_BLOCK_WORK"
            FieldExecutionAction.RESUME -> "FIELD_RESUME_WORK"
        }
        val spec = IdempotentCommandSpec(
            operationId = command.operationId,
            actorUserId = command.actorUserId,
            commandType = type,
            targetType = "WORK_ORDER",
            targetId = command.workOrderId,
            requestFingerprint = IdempotencyKeyContract.fingerprint(
                listOf(command.workOrderId, command.action, command.assignmentId,
                    command.assignmentVersion, command.baseVersion, command.blockerId,
                    reason, command.clientOccurredAt).joinToString("|"),
            ),
            correlationId = command.correlationId,
        )
        val execution = idempotency.execute(spec) {
            val now = clock.instant()
            if (!work.lockWorkOrder(command.workOrderId)) hidden()
            val order = work.workOrder(command.workOrderId) ?: hidden()
            val assignment = readiness.activeAssignment(command.workOrderId) ?: hidden()

            // Recheck current membership and OpenFGA at application time, not when locally queued.
            val relation = when (command.action) {
                FieldExecutionAction.START -> "can_start"
                FieldExecutionAction.BLOCK -> "can_block"
                FieldExecutionAction.RESUME -> "can_resume"
            }
            if (!source.isCurrentAssignedUser(command.actorUserId, command.workOrderId, now) ||
                !authorization.canUseAssignedWork(command.actorUserId, command.workOrderId, relation)) hidden()
            if (assignment.assignmentId != command.assignmentId ||
                assignment.version != command.assignmentVersion) {
                conflict("ASSIGNMENT_CHANGED", "The field assignment changed before replay.")
            }
            if (order.version != command.baseVersion) {
                conflict("VERSION_CONFLICT", "The WorkOrder changed before replay.")
            }
            val project = projects.project(order.projectId, now) ?: hidden()
            if (project.organizationId != order.organizationId) hidden()
            val open = persistence.openFieldExecutionBlockers(order.workOrderId)
            val next = FieldExecutionTransitionPolicy.next(
                command.action, order.lifecycleState, order.readinessState,
                project.lifecycleState == ProjectLifecycleState.ACTIVE, open.size,
            ) ?: conflict("FIELD_TRANSITION_REJECTED", "Current Work, readiness or Project state blocks this field command.")
            if (command.action == FieldExecutionAction.RESUME && open.single().id != command.blockerId) {
                conflict("FIELD_BLOCKER_CHANGED", "The currently open execution blocker is different.")
            }

            val newBlockerId = if (command.action == FieldExecutionAction.BLOCK) {
                UUID.nameUUIDFromBytes(
                    ("hiltech:field-execution-block:" + command.operationId).toByteArray(StandardCharsets.UTF_8),
                )
            } else null
            if (!persistence.transition(order.workOrderId, order.version, order.lifecycleState,
                    next, now, command.action == FieldExecutionAction.START)) {
                conflict("VERSION_CONFLICT", "Concurrent WorkOrder update prevented this field command.")
            }
            if (newBlockerId != null) {
                persistence.recordBlocker(newBlockerId, order.workOrderId, command.actorUserId, requireNotNull(reason))
            }
            if (command.action == FieldExecutionAction.RESUME) {
                if (!persistence.resolveBlocker(order.workOrderId, requireNotNull(command.blockerId),
                        open.single().version, requireNotNull(reason), now)) {
                    conflict("FIELD_BLOCKER_CHANGED", "The field blocker changed before it could be resolved.")
                }
            }
            val updated = work.workOrder(order.workOrderId) ?: hidden()
            audit.append(AuditEventRecord(
                actorUserId = command.actorUserId,
                action = type,
                targetType = "WORK_ORDER",
                targetId = order.workOrderId,
                previousStateRef = order.lifecycleState.name,
                newStateRef = next.name,
                safeDiffJson = """{"fields":["lifecycleState","version","fieldExecution"]}""",
                occurredAt = now,
                correlationId = command.correlationId,
            ))
            events.publishEvent(WorkOrderChanged(
                workOrderId = updated.workOrderId, organizationId = updated.organizationId,
                projectId = updated.projectId, siteId = updated.siteId,
                changeType = when (command.action) {
                    FieldExecutionAction.START -> "field_started"
                    FieldExecutionAction.BLOCK -> "field_blocked"
                    FieldExecutionAction.RESUME -> "field_resumed"
                },
                sourceVersion = updated.version,
                actorUserId = command.actorUserId,
                occurredAt = now,
                correlationId = command.correlationId,
            ))
            IdempotentCommandOutcome("APPLIED", buildJsonObject {
                put("workOrderId", updated.workOrderId.toString())
                put("lifecycleState", next.name)
                put("version", updated.version)
                put("blockerId", (newBlockerId ?: command.blockerId)?.toString())
            }.toString())
        }
        // The idempotency record retains the ORIGINAL result after a response-lost retry.
        val body = Json.parseToJsonElement(
            requireNotNull(execution.outcome.resultPayloadJson) {
                "An applied field command must have a persisted source result."
            },
        ).jsonObject
        return FieldExecutionResult(
            workOrderId = UUID.fromString(body.getValue("workOrderId").jsonPrimitive.content),
            lifecycleState = WorkOrderLifecycle.valueOf(body.getValue("lifecycleState").jsonPrimitive.content),
            version = body.getValue("version").jsonPrimitive.long,
            blockerId = body["blockerId"]?.jsonPrimitive?.content
                ?.takeUnless { it == "null" }?.let(UUID::fromString),
            replayed = execution.replayed,
        )
    }

    private fun requiredReason(reason: String?): String =
        reason?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2000 } ?: invalid()

    private fun invalid(): Nothing = throw ProductApiException(
        "FIELD_COMMAND_INVALID", "The field action payload or explanation is invalid.", HttpStatus.BAD_REQUEST,
    )

    private fun conflict(code: String, message: String): Nothing =
        throw ProductApiException(code, message, HttpStatus.CONFLICT)

    private fun hidden(): Nothing = throw ProductApiException(
        "OBJECT_NOT_VISIBLE", "The assigned WorkOrder is unavailable.", HttpStatus.NOT_FOUND,
    )
}
