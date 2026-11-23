package com.hiltech.server.service

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.projects.ProjectAuthorizationPort
import com.hiltech.server.projects.ProjectsPersistencePort
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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

data class MaintenanceAgendaItemDto(
    val visitId: String,
    val workOrderId: String,
    val siteId: String,
    val plannedFor: String,
    val workOrderTitle: String,
    val sourceWorkState: String,
    val sourceWorkVersion: Long,
    val reviewState: String,
    val reviewEventNumber: Int,
    val visitWindowState: String,
    val originalPlanState: String = "DRAFT",
    val coverageState: String = "UNVERIFIED",
    val contractScheduleApproved: Boolean = false,
)

data class MaintenanceAgendaDto(
    val projectId: String,
    val from: String,
    val to: String,
    val items: List<MaintenanceAgendaItemDto>,
    val truncated: Boolean,
    val clientVisibility: String = "INTERNAL_ONLY",
)

object MaintenanceAgendaPolicy {
    fun validWindow(from: Instant, to: Instant): Boolean =
        from.isBefore(to)

    /** This is a temporal observation, never an SLA or a contractual due date. */
    fun visitWindowState(plannedFor: Instant, now: Instant): String =
        if (now.isBefore(plannedFor)) "FUTURE_DRAFT_VISIT"
        else "DRAFT_VISIT_WINDOW_REACHED"
}

@Repository
class JdbcMaintenanceAgendaStore(private val jdbc: JdbcTemplate) {
    /** Index-backed, bounded page. The extra row signals truncated results. */
    fun visitIds(project: UUID, from: Instant, to: Instant): List<UUID> =
        jdbc.query(
            """
            SELECT id FROM service_maintenance_visit_plan
             WHERE project_id=? AND planned_for>=? AND planned_for<?
             ORDER BY planned_for ASC,id ASC LIMIT 201
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            project, OffsetDateTime.ofInstant(from, java.time.ZoneOffset.UTC),
            OffsetDateTime.ofInstant(to, java.time.ZoneOffset.UTC),
        )
}

@Service
class MaintenanceAgendaService(
    private val projects: ProjectsPersistencePort,
    private val authorization: ProjectAuthorizationPort,
    private val work: WorkPersistencePort,
    private val visits: JdbcMaintenanceVisitStore,
    private val reviews: JdbcMaintenanceReviewStore,
    private val agenda: JdbcMaintenanceAgendaStore,
    private val clock: Clock,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(
        projectId: UUID, actor: UUID, from: Instant, to: Instant,
    ): MaintenanceAgendaDto {
        if (!MaintenanceAgendaPolicy.validWindow(from, to))
            agendaProblem("MAINTENANCE_AGENDA_WINDOW_INVALID", HttpStatus.BAD_REQUEST)
        val now = clock.instant()
        val project = projects.project(projectId, now)
            ?: agendaProblem("MAINTENANCE_AGENDA_PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND)
        if (!authorization.canManageProject(actor, project))
            agendaProblem("MAINTENANCE_AGENDA_PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND)
        val ids = agenda.visitIds(projectId, from, to)
        val items = ids.take(200).map { id ->
            val visit = visits.visit(id, projectId)
                ?: agendaProblem("MAINTENANCE_AGENDA_VISIT_NOT_FOUND", HttpStatus.CONFLICT)
            val source = work.workOrder(visit.workOrderId)
                ?: agendaProblem("MAINTENANCE_AGENDA_WORK_NOT_FOUND", HttpStatus.CONFLICT)
            if (source.projectId != projectId || source.organizationId != project.organizationId ||
                source.siteId != visit.siteId)
                agendaProblem("MAINTENANCE_AGENDA_WORK_MISMATCH", HttpStatus.CONFLICT)
            val review = reviews.latest(id)
            MaintenanceAgendaItemDto(
                visitId = id.toString(),
                workOrderId = visit.workOrderId.toString(),
                siteId = visit.siteId.toString(),
                plannedFor = visit.plannedFor.toString(),
                workOrderTitle = source.title,
                sourceWorkState = source.lifecycleState.name,
                sourceWorkVersion = source.version,
                reviewState = review?.resultingState ?: "NOT_STARTED",
                reviewEventNumber = review?.eventNumber ?: 0,
                visitWindowState = MaintenanceAgendaPolicy.visitWindowState(visit.plannedFor, now),
            )
        }
        return MaintenanceAgendaDto(
            projectId.toString(), from.toString(), to.toString(),
            items, truncated = ids.size > 200,
        )
    }
}

@RestController
@RequestMapping("/v1/projects/{projectId}/maintenance-visits/agenda")
@ConditionalOnProperty(prefix = "hiltech.phase11.maintenance",
    name = ["enabled"], havingValue = "true")
class MaintenanceAgendaController(private val service: MaintenanceAgendaService) {
    @GetMapping
    fun list(
        request: HttpServletRequest,
        @PathVariable projectId: UUID,
        @RequestParam("from") rawFrom: String,
        @RequestParam("to") rawTo: String,
    ): MaintenanceAgendaDto {
        val from = runCatching { Instant.parse(rawFrom) }
            .getOrElse { agendaProblem("MAINTENANCE_AGENDA_FROM_INVALID", HttpStatus.BAD_REQUEST) }
        val to = runCatching { Instant.parse(rawTo) }
            .getOrElse { agendaProblem("MAINTENANCE_AGENDA_TO_INVALID", HttpStatus.BAD_REQUEST) }
        val actor = HiltechRequestContext.current(request).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: agendaProblem("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED)
        return service.read(projectId, actor, from, to)
    }
}

private fun agendaProblem(code: String, status: HttpStatus): Nothing =
    throw ProductApiException(
        code = code, message = "Internal maintenance planning agenda unavailable.", status = status,
    )
