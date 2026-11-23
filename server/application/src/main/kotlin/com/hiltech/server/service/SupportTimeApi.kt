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
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Operational internal observations only. No service contract, calendar,
 * promised customer response, allowed SLA pause, or performance claim exists.
 */
data class RecordSupportTimeRequest(
    val operationId: String,
    val expectedLatestEventNumber: Int,
    val eventType: String,
    val reason: String,
)

data class SupportTimeEventDto(
    val eventId: String,
    val ticketId: String,
    val eventNumber: Int,
    val eventType: String,
    val reason: String,
    val recordedAt: String,
    val visibility: String,
    val duplicateReplay: Boolean,
)

data class SupportTimeSourceEvent(
    val eventType: String,
    val recordedAt: Instant,
)

data class SupportTimeObservationDto(
    val ticketId: String,
    val openedAt: String,
    val observedAt: String,
    val caseState: String,
    val latestEventNumber: Int,
    val firstInternalAttentionAt: String?,
    val firstInternalAttentionElapsedSeconds: Long?,
    val elapsedWallSeconds: Long,
    val notedInternalHoldSeconds: Long,
    val internalHoldActive: Boolean,
    val internalHoldStartedAt: String?,
    val contractualResponseDeadline: String? = null,
    val contractualResolutionDeadline: String? = null,
    val contractualSlaState: String = "NOT_CONFIGURED",
    val holdSlaPauseApprovalState: String = "NOT_APPROVED",
    val customerResponseConfirmed: Boolean = false,
    val clientVisibility: String = "INTERNAL_ONLY",
)

/** This clock is solely historical wall-time bookkeeping, not an SLA clock. */
object SupportTimePolicy {
    private val allowedTypes = setOf(
        "INTERNAL_ATTENTION_RECORDED", "INTERNAL_HOLD_STARTED", "INTERNAL_HOLD_ENDED",
    )

    fun validType(type: String) = type in allowedTypes

    fun nextAllowed(
        type: String, unresolved: Boolean, firstAttentionExists: Boolean, holdActive: Boolean,
    ): Boolean = unresolved && when (type) {
        "INTERNAL_ATTENTION_RECORDED" -> !firstAttentionExists
        "INTERNAL_HOLD_STARTED" -> !holdActive
        "INTERNAL_HOLD_ENDED" -> holdActive
        else -> false
    }

    data class Durations(
        val firstAttentionAt: Instant?,
        val firstAttentionElapsedSeconds: Long?,
        val wallSeconds: Long,
        val notedHoldSeconds: Long,
        val activeHoldStartedAt: Instant?,
    )

    fun observe(
        openedAt: Instant, now: Instant, events: List<SupportTimeSourceEvent>,
    ): Durations {
        var firstAttention: Instant? = null
        var activeHoldStart: Instant? = null
        var accumulatedHoldSeconds = 0L
        for (event in events) {
            when (event.eventType) {
                "INTERNAL_ATTENTION_RECORDED" ->
                    if (firstAttention == null) firstAttention = event.recordedAt
                "INTERNAL_HOLD_STARTED" -> activeHoldStart = event.recordedAt
                "INTERNAL_HOLD_ENDED" -> {
                    val started = activeHoldStart
                    if (started != null) {
                        accumulatedHoldSeconds += elapsed(started, event.recordedAt)
                        activeHoldStart = null
                    }
                }
            }
        }
        activeHoldStart?.let { accumulatedHoldSeconds += elapsed(it, now) }
        return Durations(
            firstAttentionAt = firstAttention,
            firstAttentionElapsedSeconds = firstAttention?.let { elapsed(openedAt, it) },
            wallSeconds = elapsed(openedAt, now),
            notedHoldSeconds = accumulatedHoldSeconds,
            activeHoldStartedAt = activeHoldStart,
        )
    }

    private fun elapsed(start: Instant, end: Instant) =
        Duration.between(start, end).seconds.coerceAtLeast(0L)
}

@Repository
class JdbcSupportTimeStore(private val jdbc: JdbcTemplate) {
    fun lockTicket(ticketId: UUID, org: UUID, projectId: UUID) =
        jdbc.query(
            "SELECT id FROM service_support_ticket WHERE id=? AND organization_id=? AND project_id=? FOR UPDATE",
            { rs, _ -> rs.getObject(1, UUID::class.java) },
            ticketId, org, projectId,
        ).isNotEmpty()

    fun latest(ticketId: UUID): SupportTimeEventDto? =
        jdbc.query(
            """
            SELECT id,ticket_id,event_number,event_type,reason,recorded_at,visibility
              FROM service_support_time_event WHERE ticket_id=?
             ORDER BY event_number DESC LIMIT 1
            """.trimIndent(),
            { rs, _ -> map(rs) }, ticketId,
        ).singleOrNull()

    fun firstAttentionExists(ticketId: UUID) = jdbc.queryForObject(
        """
        SELECT EXISTS(SELECT 1 FROM service_support_time_event
         WHERE ticket_id=? AND event_type='INTERNAL_ATTENTION_RECORDED')
        """.trimIndent(),
        Boolean::class.java, ticketId,
    ) == true

    fun holdActive(ticketId: UUID): Boolean =
        jdbc.query(
            """
            SELECT event_type FROM service_support_time_event
             WHERE ticket_id=? AND event_type IN ('INTERNAL_HOLD_STARTED','INTERNAL_HOLD_ENDED')
             ORDER BY event_number DESC LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.getString(1) }, ticketId,
        ).singleOrNull() == "INTERNAL_HOLD_STARTED"

    fun history(ticketId: UUID): List<SupportTimeEventDto> =
        jdbc.query(
            """
            SELECT id,ticket_id,event_number,event_type,reason,recorded_at,visibility
              FROM (
                SELECT id,ticket_id,event_number,event_type,reason,recorded_at,visibility
                  FROM service_support_time_event WHERE ticket_id=?
                 ORDER BY event_number DESC LIMIT 500
              ) recent ORDER BY event_number ASC
            """.trimIndent(),
            { rs, _ -> map(rs) }, ticketId,
        )

    /** All events are required to calculate exact observed hold intervals. */
    fun sourceTimes(ticketId: UUID): List<SupportTimeSourceEvent> =
        jdbc.query(
            """
            SELECT event_type,recorded_at FROM service_support_time_event
             WHERE ticket_id=? ORDER BY event_number ASC
            """.trimIndent(),
            { rs, _ ->
                SupportTimeSourceEvent(
                    rs.getString("event_type"),
                    rs.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                )
            }, ticketId,
        )

    fun event(id: UUID, ticketId: UUID) =
        jdbc.query(
            """
            SELECT id,ticket_id,event_number,event_type,reason,recorded_at,visibility
              FROM service_support_time_event WHERE id=? AND ticket_id=?
            """.trimIndent(),
            { rs, _ -> map(rs) }, id, ticketId,
        ).singleOrNull()

    fun insert(
        id: UUID, ticketId: UUID, org: UUID, projectId: UUID, number: Int,
        type: String, reason: String, operation: UUID, actor: UUID, at: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO service_support_time_event (
                id,ticket_id,organization_id,project_id,event_number,
                event_type,reason,source_operation_id,recorded_by_user_id,
                recorded_at,visibility
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'INTERNAL_ONLY')
            """.trimIndent(),
            id,ticketId,org,projectId,number,type,reason,operation,actor,
            at.atOffset(ZoneOffset.UTC),
        )
    }

    private fun map(rs: ResultSet) = SupportTimeEventDto(
        eventId = rs.getObject("id", UUID::class.java).toString(),
        ticketId = rs.getObject("ticket_id", UUID::class.java).toString(),
        eventNumber = rs.getInt("event_number"),
        eventType = rs.getString("event_type"),
        reason = rs.getString("reason"),
        recordedAt = rs.getObject("recorded_at", OffsetDateTime::class.java).toInstant().toString(),
        visibility = rs.getString("visibility"),
        duplicateReplay = false,
    )
}

@Service
class SupportTimeService(
    private val projects: ProjectsPersistencePort,
    private val authorization: ProjectAuthorizationPort,
    private val tickets: JdbcSupportDeskStore,
    private val cases: JdbcSupportCaseStore,
    private val store: JdbcSupportTimeStore,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun observation(projectId: UUID, ticketId: UUID, actor: UUID): SupportTimeObservationDto {
        val opened = authorizedTicket(projectId, ticketId, actor)
        val now = clock.instant()
        val durations = SupportTimePolicy.observe(
            Instant.parse(opened.createdAt), now, store.sourceTimes(ticketId),
        )
        val case = cases.current(ticketId)
        return SupportTimeObservationDto(
            ticketId = ticketId.toString(),
            openedAt = opened.createdAt, observedAt = now.toString(),
            caseState = case.internalState,
            latestEventNumber = store.latest(ticketId)?.eventNumber ?: 0,
            firstInternalAttentionAt = durations.firstAttentionAt?.toString(),
            firstInternalAttentionElapsedSeconds = durations.firstAttentionElapsedSeconds,
            elapsedWallSeconds = durations.wallSeconds,
            notedInternalHoldSeconds = durations.notedHoldSeconds,
            internalHoldActive = durations.activeHoldStartedAt != null,
            internalHoldStartedAt = durations.activeHoldStartedAt?.toString(),
        )
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun events(projectId: UUID, ticketId: UUID, actor: UUID): List<SupportTimeEventDto> {
        authorizedTicket(projectId, ticketId, actor)
        return store.history(ticketId)
    }

    @Transactional
    fun record(
        projectId: UUID, ticketId: UUID, actor: UUID, operation: UUID,
        expected: Int, eventType: String, rawReason: String,
        correlationId: String,
    ): SupportTimeEventDto {
        if (expected < 0 || !SupportTimePolicy.validType(eventType))
            timeProblem("SUPPORT_TIME_EVENT_INVALID", HttpStatus.BAD_REQUEST)
        val reason = SupportDeskFieldRules.text(rawReason, 4000)
        authorizedTicket(projectId, ticketId, actor)
        val eventId = UUID.nameUUIDFromBytes(
            ("support-time:" + operation).toByteArray(StandardCharsets.UTF_8)
        )
        val execution = commands.execute(
            IdempotentCommandSpec(
                operationId = operation, actorUserId = actor,
                commandType = "SERVICE_SUPPORT_TIME_RECORD",
                targetType = "SERVICE_SUPPORT_TIME_EVENT", targetId = eventId,
                requestFingerprint = IdempotencyKeyContract.fingerprint(
                    listOf(projectId,ticketId,expected,eventType,reason).joinToString("|"),
                ),
                correlationId = correlationId,
            ),
        ) {
            val (org, ticket) = authorizedTicketWithOrg(projectId, ticketId, actor)
            if (!store.lockTicket(ticketId, org, projectId))
                timeProblem("SUPPORT_TIME_TICKET_NOT_FOUND", HttpStatus.NOT_FOUND)
            val currentCase = cases.current(ticketId)
            val latest = store.latest(ticketId)
            if ((latest?.eventNumber ?: 0) != expected)
                timeProblem("SUPPORT_TIME_EVENT_STALE", HttpStatus.CONFLICT)
            if (!SupportTimePolicy.nextAllowed(
                    eventType,
                    currentCase.internalState in setOf("OPEN", "INVESTIGATING"),
                    store.firstAttentionExists(ticketId), store.holdActive(ticketId),
                ))
                timeProblem("SUPPORT_TIME_EVENT_TRANSITION_INVALID", HttpStatus.CONFLICT)
            val at = clock.instant()
            store.insert(
                eventId,ticketId,org,projectId,expected+1,
                eventType,reason,operation,actor,at,
            )
            audit.append(
                AuditEventRecord(
                    actorUserId = actor,
                    action = "SUPPORT_INTERNAL_TIME_OBSERVATION_RECORDED",
                    targetType = "SERVICE_SUPPORT_TIME_EVENT", targetId = eventId,
                    newStateRef = eventType, occurredAt = at, correlationId = correlationId,
                    safeDiffJson = """{"visibility":"INTERNAL_ONLY","clientResponse":false,"contractualSlaPause":false}""",
                ),
            )
            IdempotentCommandOutcome("SUPPORT_TIME_EVENT_RECORDED")
        }
        return (store.event(eventId,ticketId)
            ?: timeProblem("SUPPORT_TIME_EVENT_NOT_FOUND", HttpStatus.CONFLICT))
            .copy(duplicateReplay = execution.replayed)
    }

    private fun authorizedTicket(
        projectId: UUID, ticketId: UUID, actor: UUID,
    ): InternalSupportTicketDto = authorizedTicketWithOrg(projectId,ticketId,actor).second

    private fun authorizedTicketWithOrg(
        projectId: UUID, ticketId: UUID, actor: UUID,
    ): Pair<UUID,InternalSupportTicketDto> {
        val project = projects.project(projectId, clock.instant())
            ?: timeProblem("SUPPORT_TIME_PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND)
        if (!authorization.canManageProject(actor, project))
            timeProblem("SUPPORT_TIME_PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND)
        val ticket = tickets.ticket(ticketId, projectId)
            ?: timeProblem("SUPPORT_TIME_TICKET_NOT_FOUND", HttpStatus.NOT_FOUND)
        if (ticket.projectId != projectId.toString() ||
            ticket.clientOrganizationId != project.clientOrganizationId.toString())
            timeProblem("SUPPORT_TIME_TICKET_CONTEXT_MISMATCH", HttpStatus.NOT_FOUND)
        return project.organizationId to ticket
    }
}

@RestController
@RequestMapping("/v1/projects/{projectId}/support-tickets/{ticketId}/internal-time")
@ConditionalOnProperty(prefix = "hiltech.phase11.support",
    name = ["enabled", "time.enabled"], havingValue = "true")
class SupportTimeController(private val service: SupportTimeService) {
    @GetMapping
    fun observation(request: HttpServletRequest,
        @PathVariable projectId: UUID, @PathVariable ticketId: UUID) =
        service.observation(projectId,ticketId,actor(request))

    @GetMapping("/events")
    fun events(request: HttpServletRequest,
        @PathVariable projectId: UUID, @PathVariable ticketId: UUID) =
        service.events(projectId,ticketId,actor(request))

    @PostMapping("/events")
    fun record(
        request: HttpServletRequest, @PathVariable projectId: UUID,
        @PathVariable ticketId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody input: RecordSupportTimeRequest,
    ): SupportTimeEventDto {
        val operation = runCatching { UUID.fromString(input.operationId) }
            .getOrElse { timeProblem("SUPPORT_TIME_OPERATION_INVALID", HttpStatus.BAD_REQUEST) }
        IdempotencyKeyContract.requireMatches(key,operation)
        return service.record(
            projectId,ticketId,actor(request),operation,
            input.expectedLatestEventNumber,input.eventType,input.reason,
            HiltechRequestContext.current(request).correlationId,
        )
    }

    private fun actor(request: HttpServletRequest): UUID =
        HiltechRequestContext.current(request).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: timeProblem("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED)
}

private fun timeProblem(code: String, status: HttpStatus): Nothing =
    throw ProductApiException(
        code=code,message="Internal support time observation unavailable.",status=status,
    )
