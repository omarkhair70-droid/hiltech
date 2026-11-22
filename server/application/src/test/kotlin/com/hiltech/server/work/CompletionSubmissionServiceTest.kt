package com.hiltech.server.work

import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecution
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.projects.ProjectsPersistencePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class CompletionSubmissionServiceTest {
    private val actor = UUID.randomUUID()
    private val workId = UUID.randomUUID()
    private val operationId = UUID.randomUUID()
    private val assignmentId = UUID.randomUUID()
    private val work = Mockito.mock(WorkPersistencePort::class.java)
    private val readiness = Mockito.mock(ReadinessAssignmentPersistencePort::class.java)
    private val assignmentSource = Mockito.mock(WorkAssignmentSourceAuthorityPort::class.java)
    private val authorization = Mockito.mock(WorkAssignmentAuthorizationPort::class.java)
    private val projects = Mockito.mock(ProjectsPersistencePort::class.java)
    private val persistence = Mockito.mock(CompletionSubmissionPersistencePort::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val events = Mockito.mock(ApplicationEventPublisher::class.java)

    private val request = CompletionSubmissionRequest(
        operationId = operationId,
        assignmentId = assignmentId,
        assignmentVersion = 3,
        baseVersion = 11,
        bindingRevision = 2,
        clientOccurredAt = Instant.parse("2026-11-22T03:00:00Z"),
    )

    @Test
    fun idempotentReplayReturnsOriginalSubmittedVersionAfterSourceHasChanged() {
        var invoked = false
        val commands = object : IdempotentCommandExecutor {
            override fun execute(
                spec: IdempotentCommandSpec,
                action: () -> IdempotentCommandOutcome,
            ): IdempotentCommandExecution {
                invoked = true
                assertEquals(operationId, spec.operationId)
                assertEquals(actor, spec.actorUserId)
                assertEquals("SUBMIT_WORK_COMPLETION", spec.commandType)
                assertEquals(workId, spec.targetId)
                // Existing operation: do NOT invoke action. Source may now be
                // SUBMITTED_FOR_REVIEW/ACCEPTED or reassigned.
                return IdempotentCommandExecution(
                    outcome = IdempotentCommandOutcome(
                        "COMPLETION_SUBMITTED",
                        """{"version":12,"submittedReviewVersion":12,"lifecycleState":"SUBMITTED_FOR_REVIEW"}""",
                    ),
                    replayed = true,
                )
            }
        }
        val result = service(commands).submit(
            actor, workId, request, "corr-test",
        )

        assertTrue(invoked)
        assertTrue(result.duplicateReplay)
        assertEquals("SUBMITTED_FOR_REVIEW", result.lifecycleState)
        assertEquals(12L, result.version)
        assertEquals(12L, result.submittedReviewVersion)
        Mockito.verifyNoInteractions(work, assignmentSource, authorization, readiness, projects, persistence)
    }

    @Test
    fun disabledFeatureIsFailClosedBeforeAnyBusinessCommand() {
        val commands = Mockito.mock(IdempotentCommandExecutor::class.java)
        val error = assertThrows<ProductApiException> {
            service(commands, enabled = false).submit(
                actor, workId, request, "corr-test",
            )
        }
        assertEquals("COMPLETION_UNAVAILABLE", error.code)
        Mockito.verifyNoInteractions(commands, work, persistence)
    }

    private fun service(
        commands: IdempotentCommandExecutor,
        enabled: Boolean = true,
    ) = CompletionSubmissionService(
        persistence = persistence,
        work = work,
        readiness = readiness,
        assignmentSource = assignmentSource,
        authorization = authorization,
        projects = projects,
        commands = commands,
        audit = audit,
        events = events,
        clock = Clock.fixed(
            Instant.parse("2026-11-22T04:00:00Z"),
            ZoneOffset.UTC,
        ),
        enabled = enabled,
    )
}
