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

data class LinkMaintenanceFindingTicketRequest(
    val operationId: String,
    val ticketId: String,
    val reason: String,
)

data class MaintenanceTicketFollowupDto(
    val followupId: String,
    val findingId: String,
    val visitId: String,
    val ticketId: String,
    val reason: String,
    val visibility: String,
    val recordedAt: String,
    val duplicateReplay: Boolean,
)

object MaintenanceTicketFollowupPolicy {
    fun canLinkToCase(state: String) = state in setOf("OPEN", "INVESTIGATING")
}

@Repository
class JdbcMaintenanceTicketFollowupStore(private val jdbc: JdbcTemplate) {
    fun find(findingId: UUID): MaintenanceTicketFollowupDto? =
        jdbc.query(
            """
            SELECT id,finding_id,visit_id,ticket_id,reason,visibility,recorded_at
              FROM service_maintenance_ticket_followup WHERE finding_id=?
            """.trimIndent(),
            { rs, _ -> mapRow(rs) }, findingId,
        ).singleOrNull()

    fun byVisit(visitId: UUID): List<MaintenanceTicketFollowupDto> =
        jdbc.query(
            """
            SELECT id,finding_id,visit_id,ticket_id,reason,visibility,recorded_at
              FROM service_maintenance_ticket_followup WHERE visit_id=?
             ORDER BY recorded_at, id LIMIT 500
            """.trimIndent(),
            { rs, _ -> mapRow(rs) }, visitId,
        )

    private fun mapRow(rs: java.sql.ResultSet) = MaintenanceTicketFollowupDto(
        rs.getObject("id", UUID::class.java).toString(),
        rs.getObject("finding_id", UUID::class.java).toString(),
        rs.getObject("visit_id", UUID::class.java).toString(),
        rs.getObject("ticket_id", UUID::class.java).toString(),
        rs.getString("reason"), rs.getString("visibility"),
        rs.getObject("recorded_at", OffsetDateTime::class.java).toInstant().toString(),
        false,
    )

    fun insert(
        id: UUID, findingId: UUID, visitId: UUID, projectId: UUID,
        organizationId: UUID, ticketId: UUID, operationId: UUID,
        reason: String, actor: UUID, now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO service_maintenance_ticket_followup(
              id,finding_id,visit_id,organization_id,project_id,ticket_id,
              reason,source_operation_id,recorded_by_user_id,recorded_at,visibility
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'INTERNAL_ONLY')
            """.trimIndent(),
            id, findingId, visitId, organizationId, projectId, ticketId,
            reason, operationId, actor, now.atOffset(ZoneOffset.UTC),
        )
    }
}

@Service
class MaintenanceTicketFollowupService(
    private val projects: ProjectsPersistencePort,
    private val authorization: ProjectAuthorizationPort,
    private val visits: JdbcMaintenanceVisitStore,
    private val tickets: JdbcSupportDeskStore,
    private val cases: JdbcSupportCaseStore,
    private val links: JdbcMaintenanceTicketFollowupStore,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun linksForVisit(
        projectId: UUID, visitId: UUID, actor: UUID,
    ): List<MaintenanceTicketFollowupDto> {
        val project = projects.project(projectId, clock.instant())
            ?: maintenanceLinkProblem("FOLLOWUP_PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND)
        if (!authorization.canManageProject(actor, project))
            maintenanceLinkProblem("FOLLOWUP_PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND)
        visits.visit(visitId, projectId)
            ?: maintenanceLinkProblem("FOLLOWUP_VISIT_NOT_FOUND", HttpStatus.NOT_FOUND)
        return links.byVisit(visitId)
    }

    @Transactional
    fun link(
        projectId: UUID, visitId: UUID, findingId: UUID,
        ticketId: UUID, actor: UUID, operationId: UUID,
        rawReason: String, correlationId: String,
    ): MaintenanceTicketFollowupDto {
        val reason = SupportDeskFieldRules.text(rawReason, 4000)
        checkSource(projectId, visitId, findingId, actor)
        tickets.ticket(ticketId, projectId)
            ?: maintenanceLinkProblem("FOLLOWUP_TICKET_NOT_FOUND", HttpStatus.NOT_FOUND)
        val resultId = UUID.nameUUIDFromBytes(
            ("maintenance-support-link:" + operationId).toByteArray(StandardCharsets.UTF_8)
        )
        val result = commands.execute(
            IdempotentCommandSpec(
                operationId = operationId, actorUserId = actor,
                commandType = "SERVICE_MAINTENANCE_FOLLOWUP_LINK",
                targetType = "SERVICE_MAINTENANCE_TICKET_FOLLOWUP", targetId = resultId,
                requestFingerprint = IdempotencyKeyContract.fingerprint(
                    listOf(projectId, visitId, findingId, ticketId, reason).joinToString("|")
                ),
                correlationId = correlationId,
            ),
        ) {
            if (!cases.lockTicket(ticketId, projectId))
                maintenanceLinkProblem("FOLLOWUP_TICKET_NOT_FOUND", HttpStatus.NOT_FOUND)
            val org = checkSource(projectId, visitId, findingId, actor)
            tickets.ticket(ticketId, projectId)
                ?: maintenanceLinkProblem("FOLLOWUP_TICKET_NOT_FOUND", HttpStatus.NOT_FOUND)
            if (!MaintenanceTicketFollowupPolicy.canLinkToCase(cases.current(ticketId).internalState))
                maintenanceLinkProblem("FOLLOWUP_CASE_ALREADY_RESOLVED", HttpStatus.CONFLICT)
            if (links.find(findingId) != null)
                maintenanceLinkProblem("FOLLOWUP_FINDING_ALREADY_LINKED", HttpStatus.CONFLICT)
            val at = clock.instant()
            links.insert(
                resultId, findingId, visitId, projectId, org,
                ticketId, operationId, reason, actor, at,
            )
            audit.append(
                AuditEventRecord(
                    actorUserId = actor, action = "MAINTENANCE_FINDING_LINKED_TO_SUPPORT_TICKET",
                    targetType = "SERVICE_MAINTENANCE_TICKET_FOLLOWUP", targetId = resultId,
                    newStateRef = "INTERNAL_LINK_RECORDED", occurredAt = at,
                    correlationId = correlationId,
                    safeDiffJson = """{"visibility":"INTERNAL_ONLY","ticketCreated":false,"customerNotified":false}""",
                ),
            )
            IdempotentCommandOutcome("MAINTENANCE_TICKET_FOLLOWUP_RECORDED")
        }
        return (links.find(findingId)
            ?: maintenanceLinkProblem("FOLLOWUP_RECORD_MISSING", HttpStatus.CONFLICT))
            .copy(duplicateReplay = result.replayed)
    }

    private fun checkSource(
        projectId: UUID, visitId: UUID, findingId: UUID, actor: UUID,
    ): UUID {
        val project = projects.project(projectId, clock.instant())
            ?: maintenanceLinkProblem("FOLLOWUP_PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND)
        if (!authorization.canManageProject(actor, project))
            maintenanceLinkProblem("FOLLOWUP_PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND)
        visits.visit(visitId, projectId)
            ?: maintenanceLinkProblem("FOLLOWUP_VISIT_NOT_FOUND", HttpStatus.NOT_FOUND)
        visits.finding(findingId, visitId)
            ?: maintenanceLinkProblem("FOLLOWUP_FINDING_NOT_FOUND", HttpStatus.NOT_FOUND)
        return project.organizationId
    }
}

@RestController
@RequestMapping("/v1/projects/{projectId}/maintenance-visits/{visitId}/ticket-followups")
@ConditionalOnProperty(prefix = "hiltech.phase11.maintenance", name = ["enabled"], havingValue = "true")
class MaintenanceTicketFollowupController(
    private val service: MaintenanceTicketFollowupService,
) {
    @GetMapping
    fun linksForVisit(
        request: HttpServletRequest,
        @PathVariable projectId: UUID,
        @PathVariable visitId: UUID,
    ) = service.linksForVisit(projectId, visitId, actor(request))

    @PostMapping("/{findingId}")
    fun link(
        request: HttpServletRequest,
        @PathVariable projectId: UUID,
        @PathVariable visitId: UUID,
        @PathVariable findingId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: LinkMaintenanceFindingTicketRequest,
    ): MaintenanceTicketFollowupDto {
        val operationId = runCatching { UUID.fromString(body.operationId) }
            .getOrElse { maintenanceLinkProblem("FOLLOWUP_OPERATION_ID_INVALID", HttpStatus.BAD_REQUEST) }
        val ticketId = runCatching { UUID.fromString(body.ticketId) }
            .getOrElse { maintenanceLinkProblem("FOLLOWUP_TICKET_ID_INVALID", HttpStatus.BAD_REQUEST) }
        IdempotencyKeyContract.requireMatches(key, operationId)
        return service.link(
            projectId, visitId, findingId, ticketId, actor(request),
            operationId, body.reason, HiltechRequestContext.current(request).correlationId,
        )
    }

    private fun actor(request: HttpServletRequest): UUID =
        HiltechRequestContext.current(request).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: maintenanceLinkProblem("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED)
}

private fun maintenanceLinkProblem(code: String, status: HttpStatus): Nothing =
    throw ProductApiException(
        code = code, message = "Service finding follow-up is unavailable.", status = status,
    )
