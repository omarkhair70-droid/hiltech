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

data class CreateInternalSupportTicketRequest(
    val operationId: String,
    val baseProjectVersion: Long,
    val title: String,
    val description: String,
    val severityCode: String,
    val priorityCode: String,
)

data class CreateInternalSupportNoteRequest(
    val operationId: String,
    val body: String,
)

data class InternalSupportTicketDto(
    val ticketId: String,
    val projectId: String,
    val clientOrganizationId: String,
    val title: String,
    val description: String,
    val severityCode: String,
    val priorityCode: String,
    val lifecycleState: String,
    val internalCaseState: String = "OPEN",
    val coverageState: String,
    val slaState: String,
    val clientVisibility: String,
    val openedByUserId: String,
    val createdAt: String,
    val duplicateReplay: Boolean,
)

data class InternalSupportNoteDto(
    val noteId: String,
    val ticketId: String,
    val body: String,
    val clientVisibility: String,
    val createdByUserId: String,
    val createdAt: String,
    val duplicateReplay: Boolean,
)

object SupportDeskFieldRules {
    private val codeRegex = Regex("^[A-Z][A-Z0-9_]{1,63}$")

    fun code(input: String): String {
        val value = input.trim()
        if (!codeRegex.matches(value)) serviceError(
            "SUPPORT_INVALID_CONFIGURED_CODE",
            HttpStatus.BAD_REQUEST,
        )
        return value
    }

    fun text(input: String, max: Int): String {
        val value = input.trim()
        if (value.isEmpty() || value.length > max) serviceError(
            "SUPPORT_INVALID_TEXT",
            HttpStatus.BAD_REQUEST,
        )
        return value
    }
}

@Repository
class JdbcSupportDeskStore(private val jdbc: JdbcTemplate) {
    fun lockProject(projectId: UUID): Boolean =
        jdbc.query("SELECT id FROM project WHERE id = ? FOR UPDATE",
            { rs, _ -> rs.getObject(1, UUID::class.java) },
            projectId,
        ).isNotEmpty()

    fun insertTicket(
        id: UUID, project: ProjectSnapshot, actor: UUID, operation: UUID,
        title: String, description: String, severity: String, priority: String, at: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO service_support_ticket(
                id, organization_id, project_id, client_organization_id, title, description,
                severity_code, priority_code, lifecycle_state, coverage_state, sla_state,
                client_visibility, source_operation_id, opened_by_user_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', 'NOT_EVALUATED',
                    'NOT_CONFIGURED', 'INTERNAL_ONLY', ?, ?, ?, ?)
            """.trimIndent(),
            id, project.organizationId, project.projectId, project.clientOrganizationId,
            title, description, severity, priority, operation, actor,
            at.atOffset(ZoneOffset.UTC), at.atOffset(ZoneOffset.UTC),
        )
    }

    fun ticket(id: UUID, projectId: UUID): InternalSupportTicketDto? =
        jdbc.query(
            """
            SELECT id, project_id, client_organization_id, title, description,
                   severity_code, priority_code, lifecycle_state,
                   COALESCE((SELECT coverage_state FROM service_ticket_coverage_status coverage
                     WHERE coverage.ticket_id=t.id AND coverage.project_id=t.project_id AND coverage.organization_id=t.organization_id),t.coverage_state) AS coverage_state,
                   sla_state, client_visibility, opened_by_user_id, created_at,
                   COALESCE(
                     (SELECT e.resulting_state
                        FROM service_support_case_event e
                       WHERE e.ticket_id=t.id
                       ORDER BY e.event_number DESC LIMIT 1),
                     'OPEN'
                   ) AS internal_case_state
              FROM service_support_ticket t
             WHERE id = ? AND project_id = ?
            """.trimIndent(),
            { rs, _ ->
                InternalSupportTicketDto(
                    ticketId = rs.getObject("id", UUID::class.java).toString(),
                    projectId = rs.getObject("project_id", UUID::class.java).toString(),
                    clientOrganizationId = rs.getObject("client_organization_id", UUID::class.java).toString(),
                    title = rs.getString("title"),
                    description = rs.getString("description"),
                    severityCode = rs.getString("severity_code"),
                    priorityCode = rs.getString("priority_code"),
                    lifecycleState = rs.getString("lifecycle_state"),
                    internalCaseState = rs.getString("internal_case_state"),
                    coverageState = rs.getString("coverage_state"),
                    slaState = rs.getString("sla_state"),
                    clientVisibility = rs.getString("client_visibility"),
                    openedByUserId = rs.getObject("opened_by_user_id", UUID::class.java).toString(),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant().toString(),
                    duplicateReplay = false,
                )
            },
            id, projectId,
        ).singleOrNull()

    fun tickets(projectId: UUID): List<InternalSupportTicketDto> =
        jdbc.query(
            "SELECT id FROM service_support_ticket WHERE project_id = ? ORDER BY created_at DESC LIMIT 200",
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            projectId,
        ).mapNotNull { ticket(it, projectId) }

    fun addInternalNote(
        noteId: UUID, ticketId: UUID, project: ProjectSnapshot,
        operationId: UUID, actor: UUID, body: String, at: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO service_support_internal_note (
                id, ticket_id, organization_id, project_id, body, client_visibility,
                source_operation_id, created_by_user_id, created_at)
            VALUES (?, ?, ?, ?, ?, 'INTERNAL_ONLY', ?, ?, ?)
            """.trimIndent(),
            noteId, ticketId, project.organizationId, project.projectId,
            body, operationId, actor, at.atOffset(ZoneOffset.UTC),
        )
    }

    fun note(noteId: UUID, ticketId: UUID): InternalSupportNoteDto? =
        jdbc.query(
            """
            SELECT id, ticket_id, body, client_visibility, created_by_user_id, created_at
              FROM service_support_internal_note
             WHERE id = ? AND ticket_id = ?
            """.trimIndent(),
            { rs, _ ->
                InternalSupportNoteDto(
                    rs.getObject("id", UUID::class.java).toString(),
                    rs.getObject("ticket_id", UUID::class.java).toString(),
                    rs.getString("body"),
                    rs.getString("client_visibility"),
                    rs.getObject("created_by_user_id", UUID::class.java).toString(),
                    rs.getObject("created_at", OffsetDateTime::class.java).toInstant().toString(),
                    false,
                )
            },
            noteId, ticketId,
        ).singleOrNull()

    fun notes(ticketId: UUID): List<InternalSupportNoteDto> =
        jdbc.query(
            "SELECT id FROM service_support_internal_note WHERE ticket_id = ? ORDER BY created_at, id LIMIT 500",
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            ticketId,
        ).mapNotNull { note(it, ticketId) }
}

@Service
class SupportDeskService(
    private val projects: ProjectsPersistencePort,
    private val authorization: ProjectAuthorizationPort,
    private val store: JdbcSupportDeskStore,
    private val cases: JdbcSupportCaseStore,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun tickets(projectId: UUID, actor: UUID): List<InternalSupportTicketDto> {
        managedProject(projectId, actor)
        return store.tickets(projectId)
    }

    @Transactional(readOnly = true)
    fun notes(projectId: UUID, ticketId: UUID, actor: UUID): List<InternalSupportNoteDto> {
        managedProject(projectId, actor)
        store.ticket(ticketId, projectId) ?: serviceError("SUPPORT_TICKET_NOT_FOUND", HttpStatus.NOT_FOUND)
        return store.notes(ticketId)
    }

    @Transactional
    fun open(
        projectId: UUID, actor: UUID, operationId: UUID, baseVersion: Long,
        title: String, description: String, severity: String, priority: String,
        correlationId: String,
    ): InternalSupportTicketDto {
        val sanitizedTitle = SupportDeskFieldRules.text(title, 240)
        val sanitizedDescription = SupportDeskFieldRules.text(description, 8000)
        val severityCode = SupportDeskFieldRules.code(severity)
        val priorityCode = SupportDeskFieldRules.code(priority)
        if (baseVersion < 1) serviceError("SUPPORT_INVALID_PROJECT_VERSION", HttpStatus.BAD_REQUEST)
        managedProject(projectId, actor)
        val id = serviceUuid("ticket:" + operationId)
        val result = commands.execute(
            IdempotentCommandSpec(
                operationId = operationId,
                actorUserId = actor,
                commandType = "SERVICE_SUPPORT_TICKET_OPEN",
                targetType = "SERVICE_SUPPORT_TICKET",
                targetId = id,
                requestFingerprint = IdempotencyKeyContract.fingerprint(
                    listOf(projectId, baseVersion, sanitizedTitle, sanitizedDescription,
                           severityCode, priorityCode).joinToString("|")
                ),
                correlationId = correlationId,
            ),
        ) {
            if (!store.lockProject(projectId)) serviceError("SUPPORT_PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND)
            val current = managedProject(projectId, actor)
            if (current.version != baseVersion) serviceError("SUPPORT_PROJECT_STALE", HttpStatus.CONFLICT)
            val at = clock.instant()
            store.insertTicket(id, current, actor, operationId, sanitizedTitle,
                sanitizedDescription, severityCode, priorityCode, at)
            audit.append(
                AuditEventRecord(actorUserId = actor, action = "SUPPORT_INTERNAL_TICKET_OPENED",
                    targetType = "SERVICE_SUPPORT_TICKET", targetId = id,
                    newStateRef = "OPEN", occurredAt = at, correlationId = correlationId,
                    safeDiffJson = """{"coverage":"NOT_EVALUATED","sla":"NOT_CONFIGURED","visibility":"INTERNAL_ONLY"}"""),
            )
            IdempotentCommandOutcome("SUPPORT_TICKET_OPENED")
        }
        return (store.ticket(id, projectId) ?: serviceError("SUPPORT_TICKET_NOT_FOUND", HttpStatus.CONFLICT))
            .copy(duplicateReplay = result.replayed)
    }

    @Transactional
    fun note(
        projectId: UUID, ticketId: UUID, actor: UUID,
        operationId: UUID, body: String, correlationId: String,
    ): InternalSupportNoteDto {
        val text = SupportDeskFieldRules.text(body, 8000)
        managedProject(projectId, actor)
        val noteId = serviceUuid("internal-note:" + operationId)
        val result = commands.execute(
            IdempotentCommandSpec(
                operationId = operationId, actorUserId = actor,
                commandType = "SERVICE_SUPPORT_INTERNAL_NOTE_ADD",
                targetType = "SERVICE_SUPPORT_NOTE", targetId = noteId,
                requestFingerprint = IdempotencyKeyContract.fingerprint(
                    listOf(projectId, ticketId, text).joinToString("|")
                ), correlationId = correlationId,
            ),
        ) {
            val project = managedProject(projectId, actor)
            store.ticket(ticketId, projectId) ?: serviceError("SUPPORT_TICKET_NOT_FOUND", HttpStatus.NOT_FOUND)
            val at = clock.instant()
            store.addInternalNote(noteId, ticketId, project, operationId, actor, text, at)
            audit.append(
                AuditEventRecord(actorUserId = actor, action = "SUPPORT_INTERNAL_NOTE_ADDED",
                    targetType = "SERVICE_SUPPORT_NOTE", targetId = noteId,
                    newStateRef = "INTERNAL_ONLY", occurredAt = at,
                    correlationId = correlationId,
                    safeDiffJson = """{"visibility":"INTERNAL_ONLY"}"""),
            )
            IdempotentCommandOutcome("SUPPORT_INTERNAL_NOTE_ADDED")
        }
        return (store.note(noteId, ticketId) ?: serviceError("SUPPORT_NOTE_NOT_FOUND", HttpStatus.CONFLICT))
            .copy(duplicateReplay = result.replayed)
    }

    private fun managedProject(projectId: UUID, actor: UUID): ProjectSnapshot {
        val project = projects.project(projectId, clock.instant())
            ?: serviceError("SUPPORT_PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND)
        // Internal PM/admin lane only. Never broaden to external clients or
        // all field members simply because they can view the parent project.
        if (!authorization.canManageProject(actor, project))
            serviceError("SUPPORT_PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND)
        return project
    }
}

@RestController
@RequestMapping("/v1/projects/{projectId}/service-tickets")
@ConditionalOnProperty(prefix = "hiltech.phase11.support", name = ["enabled"], havingValue = "true")
class InternalSupportDeskController(private val service: SupportDeskService) {
    @GetMapping
    fun list(request: HttpServletRequest, @PathVariable projectId: UUID) =
        service.tickets(projectId, requireSupportActor(request))

    @PostMapping
    fun open(
        request: HttpServletRequest, @PathVariable projectId: UUID,
        @RequestHeader("Idempotency-Key") header: String,
        @RequestBody body: CreateInternalSupportTicketRequest,
    ): InternalSupportTicketDto {
        val operation = supportOperationId(body.operationId)
        IdempotencyKeyContract.requireMatches(header, operation)
        val context = HiltechRequestContext.current(request)
        return service.open(projectId, requireSupportActor(request), operation,
            body.baseProjectVersion, body.title, body.description,
            body.severityCode, body.priorityCode, context.correlationId)
    }

    @GetMapping("/{ticketId}/notes")
    fun notes(request: HttpServletRequest, @PathVariable projectId: UUID,
              @PathVariable ticketId: UUID) =
        service.notes(projectId, ticketId, requireSupportActor(request))

    @PostMapping("/{ticketId}/notes")
    fun addNote(
        request: HttpServletRequest, @PathVariable projectId: UUID,
        @PathVariable ticketId: UUID,
        @RequestHeader("Idempotency-Key") header: String,
        @RequestBody body: CreateInternalSupportNoteRequest,
    ): InternalSupportNoteDto {
        val operation = supportOperationId(body.operationId)
        IdempotencyKeyContract.requireMatches(header, operation)
        return service.note(projectId, ticketId, requireSupportActor(request),
            operation, body.body, HiltechRequestContext.current(request).correlationId)
    }

    private fun requireSupportActor(request: HttpServletRequest): UUID =
        HiltechRequestContext.current(request).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: serviceError("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED)
}

private fun supportOperationId(value: String): UUID =
    runCatching { UUID.fromString(value) }.getOrElse {
        serviceError("SUPPORT_INVALID_OPERATION_ID", HttpStatus.BAD_REQUEST)
    }

private fun serviceUuid(value: String): UUID =
    UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8))

private fun serviceError(code: String, status: HttpStatus): Nothing =
    throw ProductApiException(code = code, message = "Support operation unavailable.", status = status)
