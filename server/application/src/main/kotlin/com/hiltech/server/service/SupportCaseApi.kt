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
 * The immutable opened report remains in service_support_ticket.
 * This separate internal event ledger is NOT an agreed customer service SLA,
 * customer acceptance, equipment service order or warranty entitlement.
 */
data class SupportCaseWorkChoiceDto(
    val id: String,
    val title: String,
    val lifecycleState: String,
)

data class SupportCaseEvidenceChoiceDto(
    val id: String,
    val workOrderId: String,
    val label: String,
)

data class SupportCaseRecordRequest(
    val operationId: String,
    val expectedLatestEventNumber: Int,
    val eventType: String,
    val reason: String,
    val workOrderId: String? = null,
    val evidenceId: String? = null,
)

data class SupportCaseEventDto(
    val eventId: String,
    val ticketId: String,
    val eventNumber: Int,
    val eventType: String,
    val resultingState: String,
    val reason: String,
    val workOrderId: String?,
    val evidenceId: String?,
    val recordedAt: String,
    val visibility: String,
    val duplicateReplay: Boolean,
)

data class SupportCaseSummaryDto(
    val ticketId: String,
    val internalState: String,
    val latestEventNumber: Int,
    val linkedWorkOrderId: String?,
    val coverageState: String = "NOT_EVALUATED",
    val slaState: String = "NOT_CONFIGURED",
)

data class SupportCaseCommand(
    val eventType: String,
    val reason: String,
    val workOrderId: UUID?,
    val evidenceId: UUID?,
)

object SupportCasePolicy {
    fun canLinkWork(state: WorkOrderLifecycle): Boolean =
        state != WorkOrderLifecycle.CANCELLED

    fun next(current: String, eventType: String): String? = when (eventType) {
        "INVESTIGATION_STARTED" -> if (current == "OPEN") "INVESTIGATING" else null
        "WORK_ORDER_LINKED" -> if (current == "OPEN" || current == "INVESTIGATING") current else null
        "RESOLUTION_RECORDED" -> if (current == "INVESTIGATING") "RESOLVED_INTERNAL" else null
        "INTERNAL_CLOSE_RECORDED" -> if (current == "RESOLVED_INTERNAL") "CLOSED_INTERNAL" else null
        "REOPENED" -> if (current == "RESOLVED_INTERNAL" || current == "CLOSED_INTERNAL")
            "INVESTIGATING" else null
        else -> null
    }

    fun validate(
        eventType: String, rawReason: String, workOrderId: String?, evidenceId: String?,
    ): SupportCaseCommand {
        val reason = SupportDeskFieldRules.text(rawReason, 4000)
        val workId = workOrderId?.let {
            runCatching { UUID.fromString(it) }
                .getOrElse { caseProblem("CASE_INVALID_WORK_ID", HttpStatus.BAD_REQUEST) }
        }
        val proofId = evidenceId?.let {
            runCatching { UUID.fromString(it) }
                .getOrElse { caseProblem("CASE_INVALID_EVIDENCE_ID", HttpStatus.BAD_REQUEST) }
        }
        when (eventType) {
            "WORK_ORDER_LINKED" -> if (workId == null || proofId != null)
                caseProblem("CASE_WORK_LINK_FIELDS_INVALID", HttpStatus.BAD_REQUEST)
            "RESOLUTION_RECORDED" -> if (workId == null || proofId == null)
                caseProblem("CASE_RESOLUTION_PROOF_REQUIRED", HttpStatus.BAD_REQUEST)
            "INVESTIGATION_STARTED", "INTERNAL_CLOSE_RECORDED", "REOPENED" ->
                if (workId != null || proofId != null)
                    caseProblem("CASE_EVENT_FIELDS_INVALID", HttpStatus.BAD_REQUEST)
            else -> caseProblem("CASE_EVENT_TYPE_INVALID", HttpStatus.BAD_REQUEST)
        }
        return SupportCaseCommand(eventType, reason, workId, proofId)
    }
}

@Repository
class JdbcSupportCaseStore(private val jdbc: JdbcTemplate) {
    fun lockTicket(ticketId: UUID, projectId: UUID): Boolean =
        jdbc.query(
            "SELECT id FROM service_support_ticket WHERE id=? AND project_id=? FOR UPDATE",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, ticketId, projectId,
        ).isNotEmpty()
    
    fun lockSourceWork(workOrderId: UUID, organizationId: UUID, projectId: UUID): Boolean =
        jdbc.query(
            """
            SELECT id FROM work_order
             WHERE id=? AND organization_id=? AND project_id=?
             FOR SHARE
            """.trimIndent(),
            { rs, _ -> rs.getObject(1, UUID::class.java) },
            workOrderId, organizationId, projectId,
        ).isNotEmpty()

    fun latest(ticketId: UUID): SupportCaseEventDto? =
        jdbc.query(
            """
            SELECT id, ticket_id, event_number, event_type, resulting_state,
                   reason, work_order_id, evidence_id, recorded_at, visibility
              FROM service_support_case_event WHERE ticket_id=?
             ORDER BY event_number DESC LIMIT 1
            """.trimIndent(),
            { rs, _ -> row(rs) },
            ticketId,
        ).singleOrNull()

    fun latestWorkOrder(ticketId: UUID): UUID? =
        jdbc.query(
            """
            SELECT work_order_id FROM service_support_case_event
             WHERE ticket_id=? AND event_type='WORK_ORDER_LINKED'
               AND event_number > COALESCE(
                 (SELECT MAX(event_number) FROM service_support_case_event
                    WHERE ticket_id=? AND event_type='REOPENED'), 0
               )
             ORDER BY event_number DESC LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.getObject(1, UUID::class.java) }, ticketId, ticketId,
        ).singleOrNull()

    fun current(ticketId: UUID): SupportCaseSummaryDto {
        val latest = latest(ticketId)
        return SupportCaseSummaryDto(
            ticketId.toString(), latest?.resultingState ?: "OPEN",
            latest?.eventNumber ?: 0, latestWorkOrder(ticketId)?.toString(),
        )
    }

    fun event(eventId: UUID, ticketId: UUID): SupportCaseEventDto? =
        jdbc.query(
            """
            SELECT id, ticket_id, event_number, event_type, resulting_state,
                   reason, work_order_id, evidence_id, recorded_at, visibility
              FROM service_support_case_event WHERE id=? AND ticket_id=?
            """.trimIndent(),
            { rs, _ -> row(rs) },
            eventId, ticketId,
        ).singleOrNull()

    fun history(ticketId: UUID): List<SupportCaseEventDto> =
        jdbc.query(
            """
            SELECT id, ticket_id, event_number, event_type, resulting_state,
                   reason, work_order_id, evidence_id, recorded_at, visibility
              FROM (
                  SELECT id, ticket_id, event_number, event_type, resulting_state,
                         reason, work_order_id, evidence_id, recorded_at, visibility
                    FROM service_support_case_event WHERE ticket_id=?
                   ORDER BY event_number DESC LIMIT 500
              ) recent
             ORDER BY event_number ASC
            """.trimIndent(),
            { rs, _ -> row(rs) },
            ticketId,
        )

    fun readyEvidence(
        ticketId: UUID, evidenceId: UUID, organizationId: UUID, workOrderId: UUID,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS(
              SELECT 1 FROM evidence e
              JOIN service_support_ticket ticket ON ticket.id=?
              WHERE e.id=? AND e.organization_id=? AND e.work_order_id=?
                AND e.target_type='WORK_ORDER' AND e.target_id=?
                AND e.storage_state='READY'
                AND e.captured_at>=COALESCE(
                  (SELECT MAX(reopened.recorded_at)
                     FROM service_support_case_event reopened
                    WHERE reopened.ticket_id=ticket.id
                      AND reopened.event_type='REOPENED'),ticket.created_at)
                AND NOT EXISTS(
                  SELECT 1 FROM evidence replacement
                   WHERE replacement.supersedes_evidence_id=e.id
                     AND replacement.storage_state='READY'
                )
            )
            """.trimIndent(),
            Boolean::class.java,
            ticketId, evidenceId, organizationId, workOrderId, workOrderId,
        ) == true

    fun readyEvidenceChoices(
        ticketId: UUID, org: UUID, workId: UUID,
    ): List<SupportCaseEvidenceChoiceDto> =
        jdbc.query(
            """
            SELECT e.id,e.work_order_id,e.evidence_type_code,e.captured_at
              FROM evidence e
              JOIN service_support_ticket t ON t.id=?
             WHERE e.organization_id=? AND e.work_order_id=?
               AND e.target_type='WORK_ORDER' AND e.target_id=?
               AND e.storage_state='READY' AND e.captured_at>=COALESCE(
                 (SELECT MAX(reopened.recorded_at)
                    FROM service_support_case_event reopened
                   WHERE reopened.ticket_id=t.id
                     AND reopened.event_type='REOPENED'),t.created_at)
               AND NOT EXISTS(
                 SELECT 1 FROM evidence replacement
                  WHERE replacement.supersedes_evidence_id=e.id
                    AND replacement.storage_state='READY'
               )
             ORDER BY e.captured_at DESC,e.id LIMIT 100
            """.trimIndent(),
            { rs, _ ->
                SupportCaseEvidenceChoiceDto(
                    rs.getObject("id", UUID::class.java).toString(),
                    rs.getObject("work_order_id", UUID::class.java).toString(),
                    rs.getString("evidence_type_code") + " · " +
                        rs.getObject("captured_at", OffsetDateTime::class.java).toInstant(),
                )
            }, ticketId, org, workId, workId,
        )

    fun insert(
        eventId: UUID, ticketId: UUID, project: ProjectSnapshot,
        number: Int, newState: String, command: SupportCaseCommand,
        operationId: UUID, actor: UUID, at: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO service_support_case_event(
              id, ticket_id, organization_id, project_id, event_number, event_type,
              resulting_state, work_order_id, evidence_id, reason,
              source_operation_id, recorded_by_user_id, recorded_at, visibility
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'INTERNAL_ONLY')
            """.trimIndent(),
            eventId, ticketId, project.organizationId, project.projectId,
            number, command.eventType, newState, command.workOrderId,
            command.evidenceId, command.reason, operationId, actor,
            at.atOffset(ZoneOffset.UTC),
        )
    }

    private fun row(rs: java.sql.ResultSet) = SupportCaseEventDto(
        eventId = rs.getObject("id", UUID::class.java).toString(),
        ticketId = rs.getObject("ticket_id", UUID::class.java).toString(),
        eventNumber = rs.getInt("event_number"),
        eventType = rs.getString("event_type"),
        resultingState = rs.getString("resulting_state"),
        reason = rs.getString("reason"),
        workOrderId = rs.getObject("work_order_id", UUID::class.java)?.toString(),
        evidenceId = rs.getObject("evidence_id", UUID::class.java)?.toString(),
        recordedAt = rs.getObject("recorded_at", OffsetDateTime::class.java).toInstant().toString(),
        visibility = rs.getString("visibility"),
        duplicateReplay = false,
    )
}

@Service
class SupportCaseService(
    private val projects: ProjectsPersistencePort,
    private val authorization: ProjectAuthorizationPort,
    private val tickets: JdbcSupportDeskStore,
    private val work: WorkPersistencePort,
    private val store: JdbcSupportCaseStore,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun summary(projectId: UUID, ticketId: UUID, actor: UUID): SupportCaseSummaryDto {
        managedProject(projectId, actor)
        tickets.ticket(ticketId, projectId)
            ?: caseProblem("CASE_TICKET_NOT_FOUND", HttpStatus.NOT_FOUND)
        return store.current(ticketId)
    }

    @Transactional(readOnly = true)
    fun workChoices(
        projectId: UUID, ticketId: UUID, actor: UUID,
    ): List<SupportCaseWorkChoiceDto> {
        val project = managedProject(projectId, actor)
        tickets.ticket(ticketId, projectId)
            ?: caseProblem("CASE_TICKET_NOT_FOUND", HttpStatus.NOT_FOUND)
        return work.workOrders(projectId).filter {
            it.organizationId == project.organizationId &&
                it.projectId == projectId && SupportCasePolicy.canLinkWork(it.lifecycleState)
        }.map {
            SupportCaseWorkChoiceDto(it.workOrderId.toString(), it.title, it.lifecycleState.name)
        }
    }

    @Transactional(readOnly = true)
    fun readyEvidenceChoices(
        projectId: UUID, ticketId: UUID, actor: UUID,
    ): List<SupportCaseEvidenceChoiceDto> {
        val project = managedProject(projectId, actor)
        tickets.ticket(ticketId, projectId)
            ?: caseProblem("CASE_TICKET_NOT_FOUND", HttpStatus.NOT_FOUND)
        val workId = store.latestWorkOrder(ticketId) ?: return emptyList()
        val source = work.workOrder(workId) ?: return emptyList()
        if (source.organizationId != project.organizationId || source.projectId != projectId ||
            source.lifecycleState !in setOf(WorkOrderLifecycle.ACCEPTED, WorkOrderLifecycle.CLOSED))
            return emptyList()
        return store.readyEvidenceChoices(ticketId, project.organizationId, workId)
    }

    @Transactional(readOnly = true)
    fun events(projectId: UUID, ticketId: UUID, actor: UUID): List<SupportCaseEventDto> {
        managedProject(projectId, actor)
        tickets.ticket(ticketId, projectId)
            ?: caseProblem("CASE_TICKET_NOT_FOUND", HttpStatus.NOT_FOUND)
        return store.history(ticketId)
    }

    @Transactional
    fun record(
        projectId: UUID, ticketId: UUID, actor: UUID, operationId: UUID,
        expectedLatest: Int, payload: SupportCaseCommand, correlationId: String,
    ): SupportCaseEventDto {
        if (expectedLatest < 0)
            caseProblem("CASE_EVENT_VERSION_INVALID", HttpStatus.BAD_REQUEST)
        managedProject(projectId, actor)
        tickets.ticket(ticketId, projectId)
            ?: caseProblem("CASE_TICKET_NOT_FOUND", HttpStatus.NOT_FOUND)
        val eventId = UUID.nameUUIDFromBytes(
            ("support-case:" + operationId).toByteArray(StandardCharsets.UTF_8)
        )
        val result = commands.execute(
            IdempotentCommandSpec(
                operationId = operationId, actorUserId = actor,
                commandType = "SERVICE_SUPPORT_CASE_EVENT_RECORD",
                targetType = "SERVICE_SUPPORT_CASE_EVENT", targetId = eventId,
                requestFingerprint = IdempotencyKeyContract.fingerprint(
                    listOf(projectId, ticketId, expectedLatest,
                        payload.eventType, payload.reason, payload.workOrderId,
                        payload.evidenceId).joinToString("|")
                ), correlationId = correlationId,
            ),
        ) {
            if (!store.lockTicket(ticketId, projectId))
                caseProblem("CASE_TICKET_NOT_FOUND", HttpStatus.NOT_FOUND)
            val project = managedProject(projectId, actor)
            tickets.ticket(ticketId, projectId)
                ?: caseProblem("CASE_TICKET_NOT_FOUND", HttpStatus.NOT_FOUND)
            val current = store.current(ticketId)
            if (current.latestEventNumber != expectedLatest)
                caseProblem("CASE_EVENT_VERSION_STALE", HttpStatus.CONFLICT)
            val next = SupportCasePolicy.next(current.internalState, payload.eventType)
                ?: caseProblem("CASE_INVALID_TRANSITION", HttpStatus.CONFLICT)
            payload.workOrderId?.let { workId ->
                if (!store.lockSourceWork(workId, project.organizationId, projectId))
                    caseProblem("CASE_WORK_NOT_FOUND", HttpStatus.NOT_FOUND)
                val source = work.workOrder(workId)
                    ?: caseProblem("CASE_WORK_NOT_FOUND", HttpStatus.NOT_FOUND)
                if (source.organizationId != project.organizationId || source.projectId != projectId)
                    caseProblem("CASE_WORK_NOT_FOUND", HttpStatus.NOT_FOUND)
                if (payload.eventType == "WORK_ORDER_LINKED" &&
                    !SupportCasePolicy.canLinkWork(source.lifecycleState))
                    caseProblem("CASE_CANCELLED_WORK_NOT_LINKABLE", HttpStatus.CONFLICT)
                if (payload.eventType == "RESOLUTION_RECORDED") {
                    if (store.latestWorkOrder(ticketId) != workId)
                        caseProblem("CASE_UNLINKED_RESOLUTION_WORK", HttpStatus.CONFLICT)
                    if (source.lifecycleState !in setOf(
                        WorkOrderLifecycle.ACCEPTED, WorkOrderLifecycle.CLOSED
                    )) caseProblem("CASE_WORK_NOT_ACCEPTED", HttpStatus.CONFLICT)
                    val evidenceId = payload.evidenceId
                        ?: caseProblem("CASE_EVIDENCE_REQUIRED", HttpStatus.BAD_REQUEST)
                    if (!store.readyEvidence(ticketId, evidenceId, project.organizationId, workId))
                        caseProblem("CASE_EVIDENCE_NOT_READY", HttpStatus.CONFLICT)
                }
            }
            val now = clock.instant()
            store.insert(eventId, ticketId, project, expectedLatest + 1,
                next, payload, operationId, actor, now)
            audit.append(
                AuditEventRecord(
                    actorUserId = actor,
                    action = "SUPPORT_INTERNAL_CASE_EVENT_RECORDED",
                    targetType = "SERVICE_SUPPORT_CASE_EVENT", targetId = eventId,
                    newStateRef = next, occurredAt = now, correlationId = correlationId,
                    safeDiffJson = """{"visibility":"INTERNAL_ONLY","customerAccepted":false,"slaChanged":false}""",
                ),
            )
            IdempotentCommandOutcome("SUPPORT_CASE_EVENT_RECORDED")
        }
        return (store.event(eventId, ticketId)
            ?: caseProblem("CASE_EVENT_NOT_FOUND", HttpStatus.CONFLICT))
            .copy(duplicateReplay = result.replayed)
    }

    private fun managedProject(projectId: UUID, actor: UUID): ProjectSnapshot {
        val project = projects.project(projectId, clock.instant())
            ?: caseProblem("CASE_PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND)
        if (!authorization.canManageProject(actor, project))
            caseProblem("CASE_PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND)
        return project
    }
}

@RestController
@RequestMapping("/v1/projects/{projectId}/service-tickets/{ticketId}/case")
@ConditionalOnProperty(prefix = "hiltech.phase11.support", name = ["enabled"], havingValue = "true")
class SupportCaseController(private val service: SupportCaseService) {
    @GetMapping
    fun summary(
        request: HttpServletRequest,
        @PathVariable projectId: UUID, @PathVariable ticketId: UUID,
    ) = service.summary(projectId, ticketId, actor(request))

    @GetMapping("/work-choices")
    fun workChoices(
        request: HttpServletRequest,
        @PathVariable projectId: UUID, @PathVariable ticketId: UUID,
    ) = service.workChoices(projectId, ticketId, actor(request))

    @GetMapping("/ready-evidence")
    fun readyEvidenceChoices(
        request: HttpServletRequest,
        @PathVariable projectId: UUID, @PathVariable ticketId: UUID,
    ) = service.readyEvidenceChoices(projectId, ticketId, actor(request))

    @GetMapping("/events")
    fun events(
        request: HttpServletRequest,
        @PathVariable projectId: UUID, @PathVariable ticketId: UUID,
    ) = service.events(projectId, ticketId, actor(request))

    @PostMapping("/events")
    fun record(
        request: HttpServletRequest,
        @PathVariable projectId: UUID, @PathVariable ticketId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: SupportCaseRecordRequest,
    ): SupportCaseEventDto {
        val operationId = runCatching { UUID.fromString(body.operationId) }
            .getOrElse { caseProblem("CASE_OPERATION_ID_INVALID", HttpStatus.BAD_REQUEST) }
        IdempotencyKeyContract.requireMatches(key, operationId)
        val payload = SupportCasePolicy.validate(
            body.eventType, body.reason, body.workOrderId, body.evidenceId,
        )
        return service.record(projectId, ticketId, actor(request), operationId,
            body.expectedLatestEventNumber, payload,
            HiltechRequestContext.current(request).correlationId)
    }

    private fun actor(request: HttpServletRequest): UUID =
        HiltechRequestContext.current(request).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: caseProblem("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED)
}

private fun caseProblem(code: String, status: HttpStatus): Nothing =
    throw ProductApiException(code = code, message = "Internal support case operation unavailable.", status = status)
