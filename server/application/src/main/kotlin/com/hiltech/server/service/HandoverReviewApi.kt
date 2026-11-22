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

/** Review comments only: no invented signed review policy, release or client acceptance. */
data class HandoverReviewRecordRequest(
    val operationId: String,
    val expectedLatestEventNumber: Int,
    val eventType: String,
    val reason: String,
)

data class HandoverReviewEventDto(
    val eventId: String,
    val packageVersionId: String,
    val eventNumber: Int,
    val eventType: String,
    val reviewState: String,
    val sourceDigest: String,
    val reason: String,
    val createdAt: String,
    val clientVisibility: String,
    val duplicateReplay: Boolean,
)

data class HandoverReviewSummaryDto(
    val packageVersionId: String,
    val packageVersionNumber: Int,
    val frozenSourceDigest: String,
    val currentSourceDigest: String,
    val sourceStillMatches: Boolean,
    val reviewState: String,
    val latestEventNumber: Int,
    val canIssueToClient: Boolean = false,
    val canAcceptOnBehalfOfClient: Boolean = false,
)

data class HandoverReviewDraftSource(
    val id: UUID,
    val projectId: UUID,
    val organizationId: UUID,
    val packageId: UUID,
    val versionNumber: Int,
    val frozenDigest: String,
    val state: String,
)

object HandoverInternalReviewPolicy {
    fun next(current: String?, type: String): String? = when(type) {
        "CHECK_STARTED" -> if (current == null) "CHECKING" else null
        "FINDING_RECORDED" ->
            if (current == "CHECKING" || current == "FINDINGS_RECORDED") "FINDINGS_RECORDED"
            else null
        "RECHECK_REQUESTED" -> if (current == "FINDINGS_RECORDED") "RECHECK_REQUIRED" else null
        else -> null
    }
}

@Repository
class JdbcHandoverReviewStore(private val jdbc: JdbcTemplate) {
    fun draft(versionId: UUID, projectId: UUID): HandoverReviewDraftSource? =
        jdbc.query(
            """
            SELECT id,project_id,organization_id,package_id,version_number,source_digest,state
              FROM service_handover_package_version WHERE id=? AND project_id=?
            """.trimIndent(),
            { rs, _ ->
                HandoverReviewDraftSource(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("project_id", UUID::class.java),
                    rs.getObject("organization_id", UUID::class.java),
                    rs.getObject("package_id", UUID::class.java),
                    rs.getInt("version_number"), rs.getString("source_digest"), rs.getString("state"),
                )
            }, versionId, projectId,
        ).singleOrNull()

    fun lockDraft(versionId: UUID, projectId: UUID): Boolean =
        jdbc.query(
            "SELECT id FROM service_handover_package_version WHERE id=? AND project_id=? FOR UPDATE",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, versionId, projectId,
        ).isNotEmpty()

    fun latestVersion(packageId: UUID): Int =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(version_number),0) FROM service_handover_package_version WHERE package_id=?",
            Int::class.java, packageId,
        ) ?: 0

    fun latest(versionId: UUID): HandoverReviewEventDto? =
        jdbc.query(
            """
            SELECT id,package_version_id,event_number,event_type,review_state,
                   source_digest,reason,created_at,client_visibility
              FROM service_handover_review_event WHERE package_version_id=?
             ORDER BY event_number DESC LIMIT 1
            """.trimIndent(),
            { rs, _ -> row(rs) }, versionId,
        ).singleOrNull()

    fun events(versionId: UUID): List<HandoverReviewEventDto> =
        jdbc.query(
            """
            SELECT id,package_version_id,event_number,event_type,review_state,
                   source_digest,reason,created_at,client_visibility
              FROM service_handover_review_event WHERE package_version_id=?
             ORDER BY event_number LIMIT 500
            """.trimIndent(),
            { rs, _ -> row(rs) }, versionId,
        )

    fun event(id: UUID, versionId: UUID): HandoverReviewEventDto? =
        jdbc.query(
            """
            SELECT id,package_version_id,event_number,event_type,review_state,
                   source_digest,reason,created_at,client_visibility
              FROM service_handover_review_event WHERE id=? AND package_version_id=?
            """.trimIndent(),
            { rs, _ -> row(rs) }, id, versionId,
        ).singleOrNull()

    fun insert(
        id: UUID, source: HandoverReviewDraftSource, nextNumber: Int,
        type: String, state: String, reason: String,
        operation: UUID, actor: UUID, at: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO service_handover_review_event(
              id,package_version_id,organization_id,project_id,event_number,event_type,
              review_state,source_digest,reason,created_by_user_id,created_at,operation_id,client_visibility
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'INTERNAL_ONLY')
            """.trimIndent(),
            id, source.id, source.organizationId, source.projectId,
            nextNumber, type, state, source.frozenDigest, reason,
            actor, at.atOffset(ZoneOffset.UTC), operation,
        )
    }

    private fun row(rs: java.sql.ResultSet) = HandoverReviewEventDto(
        eventId = rs.getObject("id", UUID::class.java).toString(),
        packageVersionId = rs.getObject("package_version_id", UUID::class.java).toString(),
        eventNumber = rs.getInt("event_number"), eventType = rs.getString("event_type"),
        reviewState = rs.getString("review_state"), sourceDigest = rs.getString("source_digest"),
        reason = rs.getString("reason"),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant().toString(),
        clientVisibility = rs.getString("client_visibility"), duplicateReplay = false,
    )
}

@Service
class HandoverReviewService(
    private val projects: ProjectsPersistencePort,
    private val authorization: ProjectAuthorizationPort,
    private val sources: JdbcHandoverSource,
    private val reviews: JdbcHandoverReviewStore,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun summary(projectId: UUID, versionId: UUID, actor: UUID): HandoverReviewSummaryDto {
        val project = manageProject(projectId, actor)
        val source = reviews.draft(versionId, projectId)
            ?: reviewProblem("HANDOVER_REVIEW_DRAFT_NOT_FOUND", HttpStatus.NOT_FOUND)
        if (source.organizationId != project.organizationId)
            reviewProblem("HANDOVER_REVIEW_DRAFT_NOT_FOUND", HttpStatus.NOT_FOUND)
        val current = currentSourceDigest(projectId, project.organizationId)
        val latest = reviews.latest(versionId)
        return HandoverReviewSummaryDto(
            versionId.toString(), source.versionNumber, source.frozenDigest, current,
            source.frozenDigest == current,
            latest?.reviewState ?: "NOT_STARTED", latest?.eventNumber ?: 0,
        )
    }

    @Transactional(readOnly = true)
    fun history(projectId: UUID, versionId: UUID, actor: UUID): List<HandoverReviewEventDto> {
        val project = manageProject(projectId, actor)
        val source = reviews.draft(versionId, projectId)
            ?: reviewProblem("HANDOVER_REVIEW_DRAFT_NOT_FOUND", HttpStatus.NOT_FOUND)
        if (source.organizationId != project.organizationId)
            reviewProblem("HANDOVER_REVIEW_DRAFT_NOT_FOUND", HttpStatus.NOT_FOUND)
        return reviews.events(versionId)
    }

    @Transactional
    fun record(
        projectId: UUID, versionId: UUID, actor: UUID, operationId: UUID,
        expectedNumber: Int, type: String, reasonRaw: String, correlationId: String,
    ): HandoverReviewEventDto {
        if (expectedNumber < 0)
            reviewProblem("HANDOVER_REVIEW_VERSION_INVALID", HttpStatus.BAD_REQUEST)
        val reason = SupportDeskFieldRules.text(reasonRaw, 4000)
        val project = manageProject(projectId, actor)
        val source = reviews.draft(versionId, projectId)
            ?: reviewProblem("HANDOVER_REVIEW_DRAFT_NOT_FOUND", HttpStatus.NOT_FOUND)
        if (source.organizationId != project.organizationId)
            reviewProblem("HANDOVER_REVIEW_DRAFT_NOT_FOUND", HttpStatus.NOT_FOUND)
        val eventId = UUID.nameUUIDFromBytes(
            ("handover-internal-review:" + operationId).toByteArray(StandardCharsets.UTF_8)
        )
        val result = commands.execute(
            IdempotentCommandSpec(
                operationId = operationId, actorUserId = actor,
                commandType = "SERVICE_HANDOVER_REVIEW_EVENT_RECORD",
                targetType = "SERVICE_HANDOVER_REVIEW_EVENT", targetId = eventId,
                requestFingerprint = IdempotencyKeyContract.fingerprint(
                    listOf(projectId, versionId, expectedNumber, type, reason).joinToString("|")
                ), correlationId = correlationId,
            ),
        ) {
            if (!reviews.lockDraft(versionId, projectId))
                reviewProblem("HANDOVER_REVIEW_DRAFT_NOT_FOUND", HttpStatus.NOT_FOUND)
            val freshProject = manageProject(projectId, actor)
            val fresh = reviews.draft(versionId, projectId)
                ?: reviewProblem("HANDOVER_REVIEW_DRAFT_NOT_FOUND", HttpStatus.NOT_FOUND)
            if (fresh.organizationId != freshProject.organizationId || fresh.state != "DRAFT")
                reviewProblem("HANDOVER_REVIEW_DRAFT_NOT_FOUND", HttpStatus.NOT_FOUND)
            if (reviews.latestVersion(fresh.packageId) != fresh.versionNumber)
                reviewProblem("HANDOVER_REVIEW_SUPERSEDED", HttpStatus.CONFLICT)
            if (currentSourceDigest(projectId, fresh.organizationId) != fresh.frozenDigest)
                reviewProblem("HANDOVER_REVIEW_SOURCE_CHANGED", HttpStatus.CONFLICT)
            val current = reviews.latest(versionId)
            if ((current?.eventNumber ?: 0) != expectedNumber)
                reviewProblem("HANDOVER_REVIEW_EVENT_STALE", HttpStatus.CONFLICT)
            val next = HandoverInternalReviewPolicy.next(current?.reviewState, type)
                ?: reviewProblem("HANDOVER_REVIEW_TRANSITION_INVALID", HttpStatus.CONFLICT)
            val now = clock.instant()
            reviews.insert(eventId, fresh, expectedNumber + 1,
                type, next, reason, operationId, actor, now)
            audit.append(
                AuditEventRecord(
                    actorUserId = actor, action = "HANDOVER_INTERNAL_REVIEW_EVENT_RECORDED",
                    targetType = "SERVICE_HANDOVER_REVIEW_EVENT", targetId = eventId,
                    newStateRef = next, occurredAt = now, correlationId = correlationId,
                    safeDiffJson = """{"visibility":"INTERNAL_ONLY","approved":false,"clientReleased":false}""",
                ),
            )
            IdempotentCommandOutcome("HANDOVER_INTERNAL_REVIEW_EVENT_RECORDED")
        }
        return (reviews.event(eventId, versionId)
            ?: reviewProblem("HANDOVER_REVIEW_EVENT_NOT_FOUND", HttpStatus.CONFLICT))
            .copy(duplicateReplay = result.replayed)
    }

    private fun currentSourceDigest(projectId: UUID, org: UUID) =
        HandoverReadinessPolicy.digest(
            sources.approvedDocuments(projectId, org),
            sources.work(projectId),
            sources.requirements(projectId),
        )

    private fun manageProject(projectId: UUID, actor: UUID): ProjectSnapshot =
        (projects.project(projectId, clock.instant())
            ?: reviewProblem("HANDOVER_REVIEW_PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND)).also {
            if (!authorization.canManageProject(actor, it))
                reviewProblem("HANDOVER_REVIEW_PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND)
        }
}

@RestController
@RequestMapping("/v1/projects/{projectId}/handover/packages/{versionId}/internal-review")
@ConditionalOnProperty(prefix = "hiltech.phase11.handover", name = ["enabled"], havingValue = "true")
class HandoverReviewController(private val service: HandoverReviewService) {
    @GetMapping
    fun summary(
        request: HttpServletRequest, @PathVariable projectId: UUID,
        @PathVariable versionId: UUID,
    ) = service.summary(projectId, versionId, actor(request))

    @GetMapping("/events")
    fun events(
        request: HttpServletRequest, @PathVariable projectId: UUID,
        @PathVariable versionId: UUID,
    ) = service.history(projectId, versionId, actor(request))

    @PostMapping("/events")
    fun record(
        request: HttpServletRequest, @PathVariable projectId: UUID, @PathVariable versionId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: HandoverReviewRecordRequest,
    ): HandoverReviewEventDto {
        val operation = runCatching { UUID.fromString(body.operationId) }
            .getOrElse { reviewProblem("HANDOVER_REVIEW_OPERATION_ID_INVALID", HttpStatus.BAD_REQUEST) }
        IdempotencyKeyContract.requireMatches(key, operation)
        return service.record(projectId, versionId, actor(request), operation,
            body.expectedLatestEventNumber, body.eventType, body.reason,
            HiltechRequestContext.current(request).correlationId)
    }

    private fun actor(request: HttpServletRequest): UUID =
        HiltechRequestContext.current(request).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: reviewProblem("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED)
}

private fun reviewProblem(code: String, status: HttpStatus): Nothing =
    throw ProductApiException(code = code, message = "Internal handover review unavailable.", status = status)
