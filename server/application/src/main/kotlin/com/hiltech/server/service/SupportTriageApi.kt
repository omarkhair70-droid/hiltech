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
 * Management-only internal support triage.
 * No ticket lifecycle change, SLAs, warranty entitlement, client messages,
 * field crew/work assignment, stock reservation or payment.
 */
data class RecordSupportTriageRequest(
    val operationId: String,
    val expectedLatestEventNumber: Int,
    val eventType: String,
    val ownerEmployeeId: String? = null,
    val proposedSeverityCode: String? = null,
    val proposedPriorityCode: String? = null,
    val reason: String,
)

data class InternalSupportTriageEventDto(
    val eventId: String,
    val ticketId: String,
    val eventNumber: Int,
    val eventType: String,
    val ownerEmployeeId: String?,
    val proposedSeverityCode: String?,
    val proposedPriorityCode: String?,
    val reason: String,
    val clientVisibility: String,
    val createdAt: String,
    val duplicateReplay: Boolean,
)

data class ValidatedTriage(
    val eventType: String,
    val ownerEmployeeId: UUID?,
    val proposedSeverityCode: String?,
    val proposedPriorityCode: String?,
    val reason: String,
)

object SupportTriagePolicy {
    fun validate(
        type: String, ownerId: String?, severity: String?, priority: String?, rawReason: String,
    ): ValidatedTriage {
        val reason = SupportDeskFieldRules.text(rawReason, 4000)
        return when (type) {
            "OWNER_ASSIGNED" -> {
                if (!severity.isNullOrBlank() || !priority.isNullOrBlank() || ownerId.isNullOrBlank())
                    triageProblem("TRIAGE_ASSIGNMENT_SHAPE_INVALID", HttpStatus.BAD_REQUEST)
                val employee = runCatching { UUID.fromString(ownerId ?: triageProblem("TRIAGE_OWNER_INVALID", HttpStatus.BAD_REQUEST)) }
                    .getOrElse { triageProblem("TRIAGE_OWNER_INVALID", HttpStatus.BAD_REQUEST) }
                ValidatedTriage(type, employee, null, null, reason)
            }
            "OWNER_RELEASED" -> {
                if (ownerId != null || severity != null || priority != null)
                    triageProblem("TRIAGE_RELEASE_SHAPE_INVALID", HttpStatus.BAD_REQUEST)
                ValidatedTriage(type, null, null, null, reason)
            }
            "CLASSIFICATION_RECORDED" -> {
                if (ownerId != null || severity.isNullOrBlank() || priority.isNullOrBlank())
                    triageProblem("TRIAGE_CLASSIFICATION_SHAPE_INVALID", HttpStatus.BAD_REQUEST)
                ValidatedTriage(type, null, SupportDeskFieldRules.code(severity ?: triageProblem("TRIAGE_SEVERITY_MISSING", HttpStatus.BAD_REQUEST)),
                    SupportDeskFieldRules.code(priority ?: triageProblem("TRIAGE_PRIORITY_MISSING", HttpStatus.BAD_REQUEST)), reason)
            }
            else -> triageProblem("TRIAGE_EVENT_TYPE_INVALID", HttpStatus.BAD_REQUEST)
        }
    }
}

@Repository
class JdbcSupportTriageStore(private val jdbc: JdbcTemplate) {
    fun lockTicket(ticketId: UUID, projectId: UUID): Boolean =
        jdbc.query(
            "SELECT id FROM service_support_ticket WHERE id = ? AND project_id = ? FOR UPDATE",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, ticketId, projectId,
        ).isNotEmpty()

    fun latestEventNumber(ticketId: UUID): Int =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(event_number), 0) FROM service_support_triage_event WHERE ticket_id = ?",
            Int::class.java, ticketId,
        ) ?: 0

    fun activeEmployee(employeeId: UUID, organizationId: UUID, now: Instant): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS(
              SELECT 1 FROM employee e
              JOIN employment em ON em.employee_id = e.id
              WHERE e.id = ? AND e.organization_id = ? AND e.state = 'ACTIVE'
                AND em.state = 'ACTIVE' AND em.start_date <= ?
            )
            """.trimIndent(),
            Boolean::class.java,
            employeeId, organizationId, java.sql.Date.valueOf(now.atZone(ZoneOffset.UTC).toLocalDate()),
        ) == true

    fun insert(
        id: UUID, ticketId: UUID, projectId: UUID, organizationId: UUID,
        sequence: Int, payload: ValidatedTriage, operationId: UUID,
        actor: UUID, now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO service_support_triage_event (
              id, ticket_id, organization_id, project_id, event_number,
              event_type, assignee_employee_id, proposed_severity_code,
              proposed_priority_code, reason, source_operation_id,
              created_by_user_id, created_at, client_visibility
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'INTERNAL_ONLY')
            """.trimIndent(),
            id, ticketId, organizationId, projectId, sequence, payload.eventType,
            payload.ownerEmployeeId, payload.proposedSeverityCode,
            payload.proposedPriorityCode, payload.reason, operationId,
            actor, now.atOffset(ZoneOffset.UTC),
        )
    }

    fun find(eventId: UUID, ticketId: UUID): InternalSupportTriageEventDto? =
        jdbc.query(
            """
            SELECT id, ticket_id, event_number, event_type, assignee_employee_id,
                   proposed_severity_code, proposed_priority_code, reason,
                   client_visibility, created_at
              FROM service_support_triage_event WHERE id = ? AND ticket_id = ?
            """.trimIndent(),
            { rs, _ ->
                InternalSupportTriageEventDto(
                    eventId = rs.getObject("id", UUID::class.java).toString(),
                    ticketId = rs.getObject("ticket_id", UUID::class.java).toString(),
                    eventNumber = rs.getInt("event_number"),
                    eventType = rs.getString("event_type"),
                    ownerEmployeeId = rs.getObject("assignee_employee_id", UUID::class.java)?.toString(),
                    proposedSeverityCode = rs.getString("proposed_severity_code"),
                    proposedPriorityCode = rs.getString("proposed_priority_code"),
                    reason = rs.getString("reason"),
                    clientVisibility = rs.getString("client_visibility"),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant().toString(),
                    duplicateReplay = false,
                )
            },
            eventId, ticketId,
        ).singleOrNull()

    fun events(ticketId: UUID): List<InternalSupportTriageEventDto> {
        val ids = jdbc.query(
            """
            SELECT id FROM service_support_triage_event
             WHERE ticket_id = ? ORDER BY event_number LIMIT 500
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) }, ticketId,
        )
        return ids.mapNotNull { find(it, ticketId) }
    }
}

@Service
class SupportTriageService(
    private val projects: ProjectsPersistencePort,
    private val authorization: ProjectAuthorizationPort,
    private val tickets: JdbcSupportDeskStore,
    private val cases: JdbcSupportCaseStore,
    private val triage: JdbcSupportTriageStore,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun events(projectId: UUID, ticketId: UUID, actor: UUID): List<InternalSupportTriageEventDto> {
        checkManage(projectId, actor)
        tickets.ticket(ticketId, projectId)
            ?: triageProblem("TRIAGE_TICKET_NOT_FOUND", HttpStatus.NOT_FOUND)
        return triage.events(ticketId)
    }

    @Transactional
    fun record(
        projectId: UUID, ticketId: UUID, actor: UUID, operationId: UUID,
        expectedLatest: Int, payload: ValidatedTriage, correlationId: String,
    ): InternalSupportTriageEventDto {
        if (expectedLatest < 0)
            triageProblem("TRIAGE_EVENT_VERSION_INVALID", HttpStatus.BAD_REQUEST)
        val project = checkManage(projectId, actor)
        tickets.ticket(ticketId, projectId)
            ?: triageProblem("TRIAGE_TICKET_NOT_FOUND", HttpStatus.NOT_FOUND)
        val eventId = UUID.nameUUIDFromBytes(
            ("support-triage:" + operationId).toByteArray(StandardCharsets.UTF_8)
        )
        val result = commands.execute(
            IdempotentCommandSpec(
                operationId = operationId, actorUserId = actor,
                commandType = "SERVICE_SUPPORT_TRIAGE_RECORD",
                targetType = "SERVICE_SUPPORT_TRIAGE_EVENT", targetId = eventId,
                requestFingerprint = IdempotencyKeyContract.fingerprint(
                    listOf(projectId, ticketId, expectedLatest, payload.eventType,
                        payload.ownerEmployeeId, payload.proposedSeverityCode,
                        payload.proposedPriorityCode, payload.reason).joinToString("|")
                ), correlationId = correlationId,
            ),
        ) {
            if (!triage.lockTicket(ticketId, projectId))
                triageProblem("TRIAGE_TICKET_NOT_FOUND", HttpStatus.NOT_FOUND)
            val current = checkManage(projectId, actor)
            val currentTicket = tickets.ticket(ticketId, projectId)
                ?: triageProblem("TRIAGE_TICKET_NOT_FOUND", HttpStatus.NOT_FOUND)
            if (currentTicket.lifecycleState != "OPEN")
                triageProblem("TRIAGE_TICKET_NOT_OPEN", HttpStatus.CONFLICT)
            if (cases.current(ticketId).internalState in setOf("RESOLVED_INTERNAL", "CLOSED_INTERNAL"))
                triageProblem("TRIAGE_CASE_ALREADY_RESOLVED", HttpStatus.CONFLICT)
            val latest = triage.latestEventNumber(ticketId)
            if (latest != expectedLatest)
                triageProblem("TRIAGE_EVENT_VERSION_STALE", HttpStatus.CONFLICT)
            val at = clock.instant()
            val owner = payload.ownerEmployeeId
            if (owner != null && !triage.activeEmployee(owner, current.organizationId, at))
                triageProblem("TRIAGE_OWNER_NOT_ACTIVE", HttpStatus.CONFLICT)
            // Recheck the ticket belongs to the authorized Project inside the write transaction.
            if (currentTicket.projectId != current.projectId.toString())
                triageProblem("TRIAGE_TICKET_NOT_FOUND", HttpStatus.NOT_FOUND)
            triage.insert(eventId, ticketId, current.projectId, current.organizationId,
                latest + 1, payload, operationId, actor, at)
            audit.append(
                AuditEventRecord(actorUserId = actor, action = "SUPPORT_TRIAGE_EVENT_RECORDED",
                    targetType = "SERVICE_SUPPORT_TRIAGE_EVENT", targetId = eventId,
                    newStateRef = payload.eventType, occurredAt = at,
                    correlationId = correlationId,
                    safeDiffJson = """{"visibility":"INTERNAL_ONLY","serviceSlaChanged":false,"fieldWorkAssigned":false}"""),
            )
            IdempotentCommandOutcome("SUPPORT_TRIAGE_EVENT_RECORDED")
        }
        return (triage.find(eventId, ticketId)
            ?: triageProblem("TRIAGE_EVENT_NOT_FOUND", HttpStatus.CONFLICT))
            .copy(duplicateReplay = result.replayed)
    }

    private fun checkManage(projectId: UUID, actor: UUID) =
        (projects.project(projectId, clock.instant())
            ?: triageProblem("TRIAGE_PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND)).also {
            if (!authorization.canManageProject(actor, it))
                triageProblem("TRIAGE_PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND)
        }
}

@RestController
@RequestMapping("/v1/projects/{projectId}/service-tickets/{ticketId}/triage")
@ConditionalOnProperty(prefix = "hiltech.phase11.support", name = ["enabled"], havingValue = "true")
class SupportTriageController(private val service: SupportTriageService) {
    @GetMapping
    fun list(
        request: HttpServletRequest, @PathVariable projectId: UUID, @PathVariable ticketId: UUID,
    ) = service.events(projectId, ticketId, actor(request))

    @PostMapping
    fun record(
        request: HttpServletRequest, @PathVariable projectId: UUID, @PathVariable ticketId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: RecordSupportTriageRequest,
    ): InternalSupportTriageEventDto {
        val operation = runCatching { UUID.fromString(body.operationId) }
            .getOrElse { triageProblem("TRIAGE_OPERATION_ID_INVALID", HttpStatus.BAD_REQUEST) }
        IdempotencyKeyContract.requireMatches(key, operation)
        val validated = SupportTriagePolicy.validate(
            body.eventType, body.ownerEmployeeId, body.proposedSeverityCode,
            body.proposedPriorityCode, body.reason,
        )
        return service.record(projectId, ticketId, actor(request), operation,
            body.expectedLatestEventNumber, validated,
            HiltechRequestContext.current(request).correlationId)
    }

    private fun actor(request: HttpServletRequest): UUID =
        HiltechRequestContext.current(request).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: triageProblem("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED)
}

private fun triageProblem(code: String, status: HttpStatus): Nothing =
    throw ProductApiException(code = code, message = "Support triage operation unavailable.", status = status)
