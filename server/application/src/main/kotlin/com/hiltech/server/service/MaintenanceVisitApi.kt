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
import com.hiltech.server.projects.ProjectSnapshot
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
 * P11C starts with a DRAFT proposal referencing an authoritative WorkOrder.
 * It does NOT create a WorkOrder, execute field work, promise a recurrence,
 * grant contract coverage, mark a visit completed, create a PO or invoice.
 */
data class CreateMaintenanceVisitDraftRequest(
    val operationId: String,
    val workOrderId: String,
    val baseWorkOrderVersion: Long,
    val plannedFor: String,
)

data class RecordMaintenanceFindingRequest(
    val operationId: String,
    val evidenceId: String,
    val findingTypeCode: String,
    val severityCode: String,
    val description: String,
)

data class MaintenanceChecklistItemDto(
    val itemKey: String,
    val label: String,
    val required: Boolean,
    val completionState: String,
    val version: Long,
)

data class MaintenanceVisitDto(
    val visitId: String,
    val projectId: String,
    val workOrderId: String,
    val siteId: String,
    val plannedFor: String,
    val state: String,
    val coverageState: String,
    val workOrderVersionAtPlan: Long,
    val sourceChecklist: List<MaintenanceChecklistItemDto>,
    val createdAt: String,
    val duplicateReplay: Boolean,
)

data class MaintenanceFindingDto(
    val findingId: String,
    val visitId: String,
    val evidenceId: String,
    val findingTypeCode: String,
    val severityCode: String,
    val description: String,
    val visibility: String,
    val createdAt: String,
    val duplicateReplay: Boolean,
)

data class MaintenanceVisitRow(
    val id: UUID,
    val projectId: UUID,
    val workOrderId: UUID,
    val siteId: UUID,
    val plannedFor: Instant,
    val state: String,
    val coverageState: String,
    val workOrderVersionAtPlan: Long,
    val createdAt: Instant,
)

object MaintenanceDraftPolicy {
    fun allowedWorkState(state: WorkOrderLifecycle) =
        state !in setOf(WorkOrderLifecycle.CANCELLED, WorkOrderLifecycle.CLOSED)

    fun canRecordEvidenceFinding(state: WorkOrderLifecycle) =
        state in setOf(WorkOrderLifecycle.ACCEPTED, WorkOrderLifecycle.CLOSED)

    fun plannedForIsFuture(plannedFor: Instant, at: Instant) = plannedFor.isAfter(at)

    // This is validation of user-supplied type/severity, NOT a severity-to-SLA map.
    fun code(raw: String): String = SupportDeskFieldRules.code(raw)
}

@Repository
class JdbcMaintenanceVisitStore(private val jdbc: JdbcTemplate) {
    fun lockWorkOrder(workOrderId: UUID): Boolean =
        jdbc.query(
            "SELECT id FROM work_order WHERE id = ? FOR UPDATE",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, workOrderId,
        ).isNotEmpty()

    fun insertVisit(
        id: UUID, project: ProjectSnapshot, work: WorkOrderSnapshot,
        plannedFor: Instant, operationId: UUID, actor: UUID, now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO service_maintenance_visit_plan (
                id, organization_id, project_id, work_order_id, site_id,
                planned_for, work_order_version_at_plan, state, coverage_state,
                source_operation_id, created_by_user_id, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'DRAFT', 'UNVERIFIED', ?, ?, ?)
            """.trimIndent(),
            id, project.organizationId, project.projectId, work.workOrderId, work.siteId,
            plannedFor.atOffset(ZoneOffset.UTC), work.version,
            operationId, actor, now.atOffset(ZoneOffset.UTC),
        )
    }

    fun visit(id: UUID, projectId: UUID): MaintenanceVisitRow? =
        jdbc.query(
            """
            SELECT id, project_id, work_order_id, site_id, planned_for,
                   state, coverage_state, work_order_version_at_plan, created_at
              FROM service_maintenance_visit_plan WHERE id = ? AND project_id = ?
            """.trimIndent(),
            { rs, _ ->
                MaintenanceVisitRow(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("project_id", UUID::class.java),
                    rs.getObject("work_order_id", UUID::class.java),
                    rs.getObject("site_id", UUID::class.java),
                    rs.getObject("planned_for", OffsetDateTime::class.java).toInstant(),
                    rs.getString("state"),
                    rs.getString("coverage_state"),
                    rs.getLong("work_order_version_at_plan"),
                    rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                )
            },
            id, projectId,
        ).singleOrNull()

    fun visits(projectId: UUID): List<MaintenanceVisitRow> {
        val ids = jdbc.query(
            """
            SELECT id FROM service_maintenance_visit_plan
             WHERE project_id = ? ORDER BY planned_for, id LIMIT 200
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) }, projectId,
        )
        return ids.mapNotNull { visit(it, projectId) }
    }

    // Existing WorkOrder checklist is authoritative. No second checklist entity.
    fun checklist(workOrderId: UUID): List<MaintenanceChecklistItemDto> =
        jdbc.query(
            """
            SELECT item_key, label, required, completion_state, version
              FROM work_checklist_item_instance
             WHERE work_order_id = ? ORDER BY sort_order, item_key
            """.trimIndent(),
            { rs, _ ->
                MaintenanceChecklistItemDto(
                    rs.getString("item_key"), rs.getString("label"),
                    rs.getBoolean("required"), rs.getString("completion_state"),
                    rs.getLong("version"),
                )
            },
            workOrderId,
        )

    fun readyWorkEvidence(evidenceId: UUID, org: UUID, workOrderId: UUID): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS(
                SELECT 1 FROM evidence
                 WHERE id = ? AND organization_id = ?
                   AND work_order_id = ? AND target_type = 'WORK_ORDER'
                   AND target_id = ? AND storage_state = 'READY'
            )
            """.trimIndent(),
            Boolean::class.java, evidenceId, org, workOrderId, workOrderId,
        ) == true

    fun insertFinding(
        id: UUID, visit: MaintenanceVisitRow, org: UUID,
        evidenceId: UUID, typeCode: String, severityCode: String,
        description: String, operationId: UUID, actor: UUID, now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO service_maintenance_finding (
                id, visit_id, organization_id, project_id, work_order_id, finding_type_code,
                severity_code, description, evidence_id, state, visibility,
                source_operation_id, created_by_user_id, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', 'INTERNAL_ONLY', ?, ?, ?)
            """.trimIndent(),
            id, visit.id, org, visit.projectId, visit.workOrderId,
            typeCode, severityCode, description, evidenceId, operationId, actor,
            now.atOffset(ZoneOffset.UTC),
        )
    }

    fun finding(id: UUID, visitId: UUID): MaintenanceFindingDto? =
        jdbc.query(
            """
            SELECT id, visit_id, evidence_id, finding_type_code, severity_code,
                   description, visibility, created_at
              FROM service_maintenance_finding WHERE id = ? AND visit_id = ?
            """.trimIndent(),
            { rs, _ ->
                MaintenanceFindingDto(
                    rs.getObject("id", UUID::class.java).toString(),
                    rs.getObject("visit_id", UUID::class.java).toString(),
                    rs.getObject("evidence_id", UUID::class.java).toString(),
                    rs.getString("finding_type_code"), rs.getString("severity_code"),
                    rs.getString("description"), rs.getString("visibility"),
                    rs.getObject("created_at", OffsetDateTime::class.java).toInstant().toString(),
                    false,
                )
            },
            id, visitId,
        ).singleOrNull()

    fun findings(visitId: UUID): List<MaintenanceFindingDto> {
        val ids = jdbc.query(
            "SELECT id FROM service_maintenance_finding WHERE visit_id = ? ORDER BY created_at, id LIMIT 500",
            { rs, _ -> rs.getObject("id", UUID::class.java) }, visitId,
        )
        return ids.mapNotNull { finding(it, visitId) }
    }
}

@Service
class MaintenanceVisitService(
    private val projects: ProjectsPersistencePort,
    private val authorization: ProjectAuthorizationPort,
    private val work: WorkPersistencePort,
    private val store: JdbcMaintenanceVisitStore,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun visits(projectId: UUID, actor: UUID): List<MaintenanceVisitDto> {
        manageProject(projectId, actor)
        return store.visits(projectId).map { it.asDto(false) }
    }

    @Transactional(readOnly = true)
    fun findings(projectId: UUID, visitId: UUID, actor: UUID): List<MaintenanceFindingDto> {
        manageProject(projectId, actor)
        store.visit(visitId, projectId) ?: maintenanceError("VISIT_NOT_FOUND", HttpStatus.NOT_FOUND)
        return store.findings(visitId)
    }

    @Transactional
    fun draft(
        projectId: UUID, actor: UUID, operation: UUID, workOrderId: UUID,
        expectedWorkVersion: Long, plannedFor: Instant, correlationId: String,
    ): MaintenanceVisitDto {
        if (expectedWorkVersion < 1) maintenanceError("VISIT_INVALID_SOURCE_VERSION", HttpStatus.BAD_REQUEST)
        if (!MaintenanceDraftPolicy.plannedForIsFuture(plannedFor, clock.instant()))
            maintenanceError("VISIT_PLAN_MUST_BE_FUTURE", HttpStatus.BAD_REQUEST)
        val project = manageProject(projectId, actor)
        matchingWork(project, workOrderId)
        val visitId = maintenanceUuid("maintenance-visit:" + operation)
        val result = commands.execute(
            IdempotentCommandSpec(
                operationId = operation, actorUserId = actor,
                commandType = "SERVICE_MAINTENANCE_VISIT_DRAFT",
                targetType = "SERVICE_MAINTENANCE_VISIT", targetId = visitId,
                requestFingerprint = IdempotencyKeyContract.fingerprint(
                    listOf(projectId, workOrderId, expectedWorkVersion, plannedFor).joinToString("|")
                ), correlationId = correlationId,
            ),
        ) {
            if (!store.lockWorkOrder(workOrderId))
                maintenanceError("VISIT_WORK_NOT_FOUND", HttpStatus.NOT_FOUND)
            val freshProject = manageProject(projectId, actor)
            val freshWork = matchingWork(freshProject, workOrderId)
            if (freshWork.version != expectedWorkVersion)
                maintenanceError("VISIT_WORK_SOURCE_STALE", HttpStatus.CONFLICT)
            if (!MaintenanceDraftPolicy.allowedWorkState(freshWork.lifecycleState))
                maintenanceError("VISIT_WORK_TERMINAL", HttpStatus.CONFLICT)
            val at = clock.instant()
            if (!MaintenanceDraftPolicy.plannedForIsFuture(plannedFor, at))
                maintenanceError("VISIT_PLAN_MUST_BE_FUTURE", HttpStatus.CONFLICT)
            store.insertVisit(visitId, freshProject, freshWork, plannedFor, operation, actor, at)
            audit.append(
                AuditEventRecord(actorUserId = actor, action = "MAINTENANCE_VISIT_DRAFT_CREATED",
                    targetType = "SERVICE_MAINTENANCE_VISIT", targetId = visitId,
                    newStateRef = "DRAFT", occurredAt = at, correlationId = correlationId,
                    safeDiffJson = """{"coverage":"UNVERIFIED","workOrderSource":true,"contractualSchedule":false}"""),
            )
            IdempotentCommandOutcome("MAINTENANCE_VISIT_DRAFT_CREATED")
        }
        return (store.visit(visitId, projectId)
            ?: maintenanceError("VISIT_NOT_FOUND", HttpStatus.CONFLICT)).asDto(result.replayed)
    }

    @Transactional
    fun recordFinding(
        projectId: UUID, visitId: UUID, actor: UUID, operation: UUID,
        evidenceId: UUID, type: String, severity: String,
        rawDescription: String, correlationId: String,
    ): MaintenanceFindingDto {
        val typeCode = MaintenanceDraftPolicy.code(type)
        val severityCode = MaintenanceDraftPolicy.code(severity)
        val description = SupportDeskFieldRules.text(rawDescription, 8000)
        val project = manageProject(projectId, actor)
        val visit = store.visit(visitId, projectId)
            ?: maintenanceError("VISIT_NOT_FOUND", HttpStatus.NOT_FOUND)
        val findingId = maintenanceUuid("maintenance-finding:" + operation)
        val result = commands.execute(
            IdempotentCommandSpec(
                operationId = operation, actorUserId = actor,
                commandType = "SERVICE_MAINTENANCE_FINDING_RECORD",
                targetType = "SERVICE_MAINTENANCE_FINDING", targetId = findingId,
                requestFingerprint = IdempotencyKeyContract.fingerprint(
                    listOf(projectId, visitId, evidenceId, typeCode, severityCode, description)
                        .joinToString("|")
                ), correlationId = correlationId,
            ),
        ) {
            val fresh = manageProject(projectId, actor)
            val currentVisit = store.visit(visitId, projectId)
                ?: maintenanceError("VISIT_NOT_FOUND", HttpStatus.NOT_FOUND)
            val currentWork = matchingWork(fresh, currentVisit.workOrderId)
            if (!MaintenanceDraftPolicy.canRecordEvidenceFinding(currentWork.lifecycleState))
                maintenanceError("FINDING_WORK_NOT_ACCEPTED", HttpStatus.CONFLICT)
            if (!store.readyWorkEvidence(evidenceId, fresh.organizationId, currentWork.workOrderId))
                maintenanceError("FINDING_EVIDENCE_NOT_READY", HttpStatus.CONFLICT)
            val at = clock.instant()
            store.insertFinding(
                findingId, currentVisit, fresh.organizationId, evidenceId,
                typeCode, severityCode, description, operation, actor, at,
            )
            audit.append(
                AuditEventRecord(actorUserId = actor, action = "MAINTENANCE_FINDING_RECORDED",
                    targetType = "SERVICE_MAINTENANCE_FINDING", targetId = findingId,
                    newStateRef = "OPEN", occurredAt = at, correlationId = correlationId,
                    safeDiffJson = """{"visibility":"INTERNAL_ONLY","evidenceRequired":true}"""),
            )
            IdempotentCommandOutcome("MAINTENANCE_FINDING_RECORDED")
        }
        return (store.finding(findingId, visitId)
            ?: maintenanceError("FINDING_NOT_FOUND", HttpStatus.CONFLICT))
            .copy(duplicateReplay = result.replayed)
    }

    private fun manageProject(projectId: UUID, actor: UUID): ProjectSnapshot {
        val project = projects.project(projectId, clock.instant())
            ?: maintenanceError("VISIT_PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND)
        if (!authorization.canManageProject(actor, project))
            maintenanceError("VISIT_PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND)
        return project
    }

    private fun matchingWork(project: ProjectSnapshot, workOrderId: UUID): WorkOrderSnapshot {
        val order = work.workOrder(workOrderId)
            ?: maintenanceError("VISIT_WORK_NOT_FOUND", HttpStatus.NOT_FOUND)
        if (order.projectId != project.projectId || order.organizationId != project.organizationId)
            maintenanceError("VISIT_WORK_NOT_FOUND", HttpStatus.NOT_FOUND)
        return order
    }

    private fun MaintenanceVisitRow.asDto(replayed: Boolean) = MaintenanceVisitDto(
        id.toString(), projectId.toString(), workOrderId.toString(), siteId.toString(),
        plannedFor.toString(), state, coverageState, workOrderVersionAtPlan,
        store.checklist(workOrderId), createdAt.toString(), replayed,
    )
}

@RestController
@RequestMapping("/v1/projects/{projectId}/maintenance-visits")
@ConditionalOnProperty(prefix = "hiltech.phase11.maintenance", name = ["enabled"], havingValue = "true")
class MaintenanceVisitController(private val service: MaintenanceVisitService) {
    @GetMapping
    fun list(request: HttpServletRequest, @PathVariable projectId: UUID) =
        service.visits(projectId, actor(request))

    @PostMapping
    fun draft(
        request: HttpServletRequest, @PathVariable projectId: UUID,
        @RequestHeader("Idempotency-Key") header: String,
        @RequestBody body: CreateMaintenanceVisitDraftRequest,
    ): MaintenanceVisitDto {
        val op = parseId(body.operationId)
        IdempotencyKeyContract.requireMatches(header, op)
        val at = runCatching { Instant.parse(body.plannedFor) }
            .getOrElse { maintenanceError("VISIT_DATE_INVALID", HttpStatus.BAD_REQUEST) }
        return service.draft(
            projectId, actor(request), op, parseId(body.workOrderId),
            body.baseWorkOrderVersion, at,
            HiltechRequestContext.current(request).correlationId,
        )
    }

    @GetMapping("/{visitId}/findings")
    fun findings(
        request: HttpServletRequest, @PathVariable projectId: UUID, @PathVariable visitId: UUID,
    ) = service.findings(projectId, visitId, actor(request))

    @PostMapping("/{visitId}/findings")
    fun recordFinding(
        request: HttpServletRequest, @PathVariable projectId: UUID,
        @PathVariable visitId: UUID,
        @RequestHeader("Idempotency-Key") header: String,
        @RequestBody body: RecordMaintenanceFindingRequest,
    ): MaintenanceFindingDto {
        val op = parseId(body.operationId)
        IdempotencyKeyContract.requireMatches(header, op)
        return service.recordFinding(
            projectId, visitId, actor(request), op, parseId(body.evidenceId),
            body.findingTypeCode, body.severityCode, body.description,
            HiltechRequestContext.current(request).correlationId,
        )
    }

    private fun actor(request: HttpServletRequest) =
        HiltechRequestContext.current(request).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: maintenanceError("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED)
}

private fun parseId(raw: String): UUID =
    runCatching { UUID.fromString(raw) }
        .getOrElse { maintenanceError("VISIT_INVALID_ID", HttpStatus.BAD_REQUEST) }

private fun maintenanceUuid(value: String): UUID =
    UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8))

private fun maintenanceError(code: String, status: HttpStatus): Nothing =
    throw ProductApiException(code = code, message = "Maintenance operation unavailable.", status = status)
