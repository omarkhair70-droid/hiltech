package com.hiltech.server.service

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.projects.ProjectAuthorizationPort
import com.hiltech.server.projects.ProjectsPersistencePort
import com.hiltech.server.work.WorkOrderLifecycle
import com.hiltech.server.work.WorkOrderSnapshot
import com.hiltech.server.work.WorkPersistencePort
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Internal technical visit confirmation, NOT contractual visit completion,
 * client sign-off, warranty activation, maintenance renewal, or field dispatch.
 */
data class RecordMaintenanceReviewRequest(
    val operationId: String,
    val expectedLatestEventNumber: Int,
    val sourceWorkVersion: Long,
    val eventType: String,
    val evidenceId: String? = null,
    val reason: String,
)

data class MaintenanceReviewEventDto(
    val eventId: String,
    val visitId: String,
    val eventNumber: Int,
    val eventType: String,
    val resultingState: String,
    val sourceWorkVersion: Long,
    val evidenceId: String?,
    val reason: String,
    val visibility: String,
    val recordedAt: String,
    val duplicateReplay: Boolean,
)

data class MaintenanceReviewReadinessDto(
    val visitId: String,
    val workOrderId: String,
    val currentWorkVersion: Long,
    val currentWorkState: String,
    val reviewState: String,
    val latestEventNumber: Int,
    val requiredChecklistTotal: Int,
    val requiredChecklistPending: Int,
    val readyVisitEvidenceCount: Int,
    val missingReasons: List<String>,
    val canConfirmInternalTechnicalCompletion: Boolean,
    val clientAcceptanceRecorded: Boolean = false,
    val warrantyEntitlementConfirmed: Boolean = false,
)

/** Explicit source decisions; no guessed customer-facing contract rights. */
object MaintenanceReviewPolicy {
    fun next(previous: String?, type: String): String? = when (type) {
        "REVIEW_STARTED" ->
            if (previous == null || previous == "RECHECK_REQUIRED") "REVIEWING" else null
        "INTERNAL_TECHNICAL_COMPLETION" ->
            if (previous == "REVIEWING") "COMPLETED_INTERNAL" else null
        "RECHECK_REQUESTED" ->
            if (previous == "COMPLETED_INTERNAL") "RECHECK_REQUIRED" else null
        else -> null
    }

    fun missing(
        visit: MaintenanceVisitRow,
        workState: WorkOrderLifecycle,
        requiredChecklistTotal: Int,
        requiredChecklistPending: Int,
        readyEvidenceCount: Int,
        at: Instant,
    ) = buildList {
        if (at.isBefore(visit.plannedFor)) add("PLANNED_VISIT_WINDOW_NOT_REACHED")
        if (workState !in setOf(WorkOrderLifecycle.ACCEPTED, WorkOrderLifecycle.CLOSED))
            add("CANONICAL_WORK_NOT_TECHNICALLY_ACCEPTED")
        if (requiredChecklistTotal == 0) add("REQUIRED_WORK_CHECKLIST_NOT_DEFINED")
        if (requiredChecklistPending > 0) add("REQUIRED_WORK_CHECKLIST_INCOMPLETE")
        if (readyEvidenceCount == 0) add("CURRENT_VISIT_READY_EVIDENCE_MISSING")
    }
}

@Repository
class JdbcMaintenanceReviewStore(private val jdbc: JdbcTemplate) {
    fun lockVisit(id: UUID, project: UUID): Boolean =
        jdbc.query(
            "SELECT id FROM service_maintenance_visit_plan WHERE id=? AND project_id=? FOR UPDATE",
            { rs, _ -> rs.getObject(1, UUID::class.java) },
            id, project,
        ).isNotEmpty()

    fun latest(visitId: UUID): MaintenanceReviewEventDto? =
        jdbc.query(
            """
            SELECT id,visit_id,event_number,event_type,resulting_state,
                   source_work_version,evidence_id,reason,visibility,recorded_at
              FROM service_maintenance_review_event WHERE visit_id=?
             ORDER BY event_number DESC LIMIT 1
            """.trimIndent(),
            { rs, _ -> map(rs) }, visitId,
        ).singleOrNull()

    fun events(visitId: UUID): List<MaintenanceReviewEventDto> =
        jdbc.query(
            """
            SELECT id,visit_id,event_number,event_type,resulting_state,
                   source_work_version,evidence_id,reason,visibility,recorded_at
              FROM (
                SELECT id,visit_id,event_number,event_type,resulting_state,
                       source_work_version,evidence_id,reason,visibility,recorded_at
                  FROM service_maintenance_review_event WHERE visit_id=?
                 ORDER BY event_number DESC LIMIT 500
              ) recent
             ORDER BY event_number ASC
            """.trimIndent(),
            { rs, _ -> map(rs) }, visitId,
        )

    fun event(id: UUID, visit: UUID): MaintenanceReviewEventDto? =
        jdbc.query(
            """
            SELECT id,visit_id,event_number,event_type,resulting_state,
                   source_work_version,evidence_id,reason,visibility,recorded_at
              FROM service_maintenance_review_event WHERE id=? AND visit_id=?
            """.trimIndent(),
            { rs, _ -> map(rs) }, id, visit,
        ).singleOrNull()

    fun insert(
        eventId: UUID, visit: MaintenanceVisitRow, organization: UUID,
        sequence: Int, type: String, state: String, workVersion: Long,
        evidenceId: UUID?, reason: String, operation: UUID, actor: UUID, at: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO service_maintenance_review_event (
              id,visit_id,organization_id,project_id,work_order_id,event_number,
              event_type,resulting_state,source_work_version,evidence_id,
              reason,source_operation_id,recorded_by_user_id,recorded_at,visibility
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'INTERNAL_ONLY')
            """.trimIndent(),
            eventId, visit.id, organization, visit.projectId, visit.workOrderId,
            sequence, type, state, workVersion, evidenceId, reason,
            operation, actor, at.atOffset(ZoneOffset.UTC),
        )
    }

    private fun map(rs: java.sql.ResultSet) = MaintenanceReviewEventDto(
        eventId = rs.getObject("id", UUID::class.java).toString(),
        visitId = rs.getObject("visit_id", UUID::class.java).toString(),
        eventNumber = rs.getInt("event_number"),
        eventType = rs.getString("event_type"),
        resultingState = rs.getString("resulting_state"),
        sourceWorkVersion = rs.getLong("source_work_version"),
        evidenceId = rs.getObject("evidence_id", UUID::class.java)?.toString(),
        reason = rs.getString("reason"),
        visibility = rs.getString("visibility"),
        recordedAt = rs.getObject("recorded_at", OffsetDateTime::class.java).toInstant().toString(),
        duplicateReplay = false,
    )
}

@Service
class MaintenanceReviewService(
    private val projects: ProjectsPersistencePort,
    private val authorization: ProjectAuthorizationPort,
    private val work: WorkPersistencePort,
    private val visits: JdbcMaintenanceVisitStore,
    private val reviews: JdbcMaintenanceReviewStore,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun readiness(projectId: UUID, visitId: UUID, actor: UUID): MaintenanceReviewReadinessDto {
        val (visit, snapshot, org) = source(projectId, visitId, actor)
        val checklist = visits.checklist(snapshot.workOrderId)
        val total = checklist.count { it.required }
        val pending = checklist.count { it.required && it.completionState != "COMPLETE" }
        val evidenceCount = visits.readyEvidenceCount(
            org, snapshot.workOrderId, maxOf(visit.createdAt, visit.plannedFor),
        )
        val missing = MaintenanceReviewPolicy.missing(
            visit, snapshot.lifecycleState, total, pending, evidenceCount, clock.instant(),
        )
        val latest = reviews.latest(visitId)
        return MaintenanceReviewReadinessDto(
            visitId.toString(), visit.workOrderId.toString(),
            snapshot.version, snapshot.lifecycleState.name,
            latest?.resultingState ?: "NOT_STARTED", latest?.eventNumber ?: 0,
            total, pending, evidenceCount, missing,
            latest?.resultingState == "REVIEWING" && missing.isEmpty(),
        )
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun events(projectId: UUID, visitId: UUID, actor: UUID): List<MaintenanceReviewEventDto> {
        source(projectId, visitId, actor)
        return reviews.events(visitId)
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun record(
        projectId: UUID, visitId: UUID, actor: UUID, operationId: UUID,
        expectedEvent: Int, expectedWorkVersion: Long, type: String,
        evidenceId: UUID?, rawReason: String, correlationId: String,
    ): MaintenanceReviewEventDto {
        if (expectedEvent < 0 || expectedWorkVersion < 1)
            fail("MAINTENANCE_REVIEW_BASE_INVALID", HttpStatus.BAD_REQUEST)
        if (type == "INTERNAL_TECHNICAL_COMPLETION" && evidenceId == null)
            fail("MAINTENANCE_REVIEW_EVIDENCE_REQUIRED", HttpStatus.BAD_REQUEST)
        if (type != "INTERNAL_TECHNICAL_COMPLETION" && evidenceId != null)
            fail("MAINTENANCE_REVIEW_EVIDENCE_NOT_ALLOWED", HttpStatus.BAD_REQUEST)
        val reason = SupportDeskFieldRules.text(rawReason, 4000)
        source(projectId, visitId, actor)
        val eventId = UUID.nameUUIDFromBytes(
            ("maintenance-technical-review:" + operationId).toByteArray(StandardCharsets.UTF_8)
        )
        val execution = commands.execute(
            IdempotentCommandSpec(
                operationId = operationId, actorUserId = actor,
                commandType = "MAINTENANCE_INTERNAL_REVIEW_EVENT",
                targetType = "SERVICE_MAINTENANCE_REVIEW_EVENT", targetId = eventId,
                requestFingerprint = IdempotencyKeyContract.fingerprint(
                    listOf(projectId, visitId, expectedEvent, expectedWorkVersion,
                        type, evidenceId, reason).joinToString("|"),
                ),
                correlationId = correlationId,
            ),
        ) {
            if (!reviews.lockVisit(visitId, projectId))
                fail("MAINTENANCE_REVIEW_VISIT_NOT_FOUND", HttpStatus.NOT_FOUND)
            val (visit, snapshot, org) = source(projectId, visitId, actor)
            if (snapshot.version != expectedWorkVersion)
                fail("MAINTENANCE_REVIEW_WORK_CHANGED", HttpStatus.CONFLICT)
            val latest = reviews.latest(visitId)
            if ((latest?.eventNumber ?: 0) != expectedEvent)
                fail("MAINTENANCE_REVIEW_EVENT_STALE", HttpStatus.CONFLICT)
            val next = MaintenanceReviewPolicy.next(latest?.resultingState, type)
                ?: fail("MAINTENANCE_REVIEW_TRANSITION_INVALID", HttpStatus.CONFLICT)
            if (type == "INTERNAL_TECHNICAL_COMPLETION") {
                val checklist = visits.checklist(snapshot.workOrderId)
                val readyCount = visits.readyEvidenceCount(
                    org, snapshot.workOrderId, maxOf(visit.createdAt, visit.plannedFor),
                )
                val missing = MaintenanceReviewPolicy.missing(
                    visit, snapshot.lifecycleState, checklist.count { it.required },
                    checklist.count { it.required && it.completionState != "COMPLETE" },
                    readyCount, clock.instant(),
                )
                if (missing.isNotEmpty()) fail("MAINTENANCE_REVIEW_SOURCE_NOT_READY", HttpStatus.CONFLICT)
                if (!visits.readyWorkEvidence(
                        evidenceId!!, org, snapshot.workOrderId, maxOf(visit.createdAt, visit.plannedFor),
                    ))
                    fail("MAINTENANCE_REVIEW_EVIDENCE_NOT_READY", HttpStatus.CONFLICT)
            }
            val now = clock.instant()
            reviews.insert(eventId, visit, org, expectedEvent + 1,
                type, next, snapshot.version, evidenceId, reason, operationId, actor, now)
            audit.append(
                AuditEventRecord(
                    actorUserId = actor, action = "MAINTENANCE_INTERNAL_REVIEW_EVENT_RECORDED",
                    targetType = "SERVICE_MAINTENANCE_REVIEW_EVENT", targetId = eventId,
                    newStateRef = next, occurredAt = now, correlationId = correlationId,
                    safeDiffJson = """{"visibility":"INTERNAL_ONLY","clientAcceptance":false,"warrantyActivated":false,"invoiceCreated":false}""",
                ),
            )
            IdempotentCommandOutcome("MAINTENANCE_INTERNAL_REVIEW_EVENT_RECORDED")
        }
        return (reviews.event(eventId, visitId)
            ?: fail("MAINTENANCE_REVIEW_EVENT_NOT_FOUND", HttpStatus.CONFLICT))
            .copy(duplicateReplay = execution.replayed)
    }

    private fun source(
        projectId: UUID, visitId: UUID, actor: UUID,
    ): Triple<MaintenanceVisitRow, WorkOrderSnapshot, UUID> {
        val project = projects.project(projectId, clock.instant())
            ?: fail("MAINTENANCE_REVIEW_PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND)
        if (!authorization.canManageProject(actor, project))
            fail("MAINTENANCE_REVIEW_PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND)
        val visit = visits.visit(visitId, projectId)
            ?: fail("MAINTENANCE_REVIEW_VISIT_NOT_FOUND", HttpStatus.NOT_FOUND)
        val snapshot = work.workOrder(visit.workOrderId)
            ?: fail("MAINTENANCE_REVIEW_WORK_NOT_FOUND", HttpStatus.NOT_FOUND)
        if (snapshot.organizationId != project.organizationId || snapshot.projectId != projectId ||
            snapshot.siteId != visit.siteId)
            fail("MAINTENANCE_REVIEW_WORK_NOT_FOUND", HttpStatus.NOT_FOUND)
        return Triple(visit, snapshot, project.organizationId)
    }
}

@RestController
@RequestMapping("/v1/projects/{projectId}/maintenance-visits/{visitId}/review")
@ConditionalOnProperty(prefix = "hiltech.phase11.maintenance",
    name = ["enabled", "review.enabled"], havingValue = "true")
class MaintenanceReviewController(private val service: MaintenanceReviewService) {
    @GetMapping("/readiness")
    fun readiness(request: HttpServletRequest,
        @PathVariable projectId: UUID, @PathVariable visitId: UUID) =
        service.readiness(projectId, visitId, actor(request))

    @GetMapping("/events")
    fun events(request: HttpServletRequest,
        @PathVariable projectId: UUID, @PathVariable visitId: UUID) =
        service.events(projectId, visitId, actor(request))

    @PostMapping("/events")
    fun record(request: HttpServletRequest,
        @PathVariable projectId: UUID, @PathVariable visitId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody body: RecordMaintenanceReviewRequest): MaintenanceReviewEventDto {
        val operation = runCatching { UUID.fromString(body.operationId) }
            .getOrElse { fail("MAINTENANCE_REVIEW_OPERATION_ID_INVALID", HttpStatus.BAD_REQUEST) }
        val evidence = body.evidenceId?.let {
            runCatching { UUID.fromString(it) }
                .getOrElse { fail("MAINTENANCE_REVIEW_EVIDENCE_ID_INVALID", HttpStatus.BAD_REQUEST) }
        }
        IdempotencyKeyContract.requireMatches(idempotencyKey, operation)
        return service.record(
            projectId, visitId, actor(request), operation,
            body.expectedLatestEventNumber, body.sourceWorkVersion,
            body.eventType, evidence, body.reason,
            HiltechRequestContext.current(request).correlationId,
        )
    }

    private fun actor(request: HttpServletRequest): UUID =
        HiltechRequestContext.current(request).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: fail("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED)
}

private fun fail(code: String, status: HttpStatus): Nothing =
    throw ProductApiException(code = code, message = "Internal maintenance review unavailable.", status = status)
