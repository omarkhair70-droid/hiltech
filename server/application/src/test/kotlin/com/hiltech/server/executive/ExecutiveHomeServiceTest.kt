package com.hiltech.server.executive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ExecutiveHomeServiceTest {
    private val now = Instant.parse("2026-11-22T20:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val actor = UUID.randomUUID()
    private val organization = UUID.randomUUID()

    @Test
    fun delegatesCurrentIdentityAndOrganizationWithoutExecutivePrivilege() {
        val sources = CapturingSources()
        val result = ExecutiveHomeService(sources, clock).home(actor, organization, "correlation-123")

        assertEquals(actor, sources.needsActor)
        assertEquals(actor, sources.projectsActor)
        assertEquals(actor, sources.assetsActor)
        assertEquals(organization, sources.projectsOrg)
        assertEquals(organization, sources.assetsOrg)
        assertEquals("CURRENT_ACTOR_ACROSS_AUTHORIZED_MEMBERSHIPS", result.needsYouScope)
        assertEquals(now.toString(), result.asOf)
        assertEquals(organization.toString(), result.organizationId)
        assertEquals(actor.toString(), result.viewerIdentityId)
        assertEquals("correlation-123", result.correlationId)
        assertEquals("INBOX_WORK_QUEUE", result.needsYou.source)
        assertTrue(result.pendingDomains.any { it.domain == "PAYROLL" })
        assertTrue(result.pendingDomains.all { it.status == "AWAITING_AUTHORIZED_SOURCE_CONTRACT" })
    }

    @Test
    fun failedProducerIsUnavailableNotAnInventedZeroOrSuccess() {
        val sources = CapturingSources().apply { failProjects = true }
        val result = ExecutiveHomeService(sources, clock).home(actor, organization, "c")

        assertEquals("UNAVAILABLE", result.projects.status)
        assertTrue(result.projects.items.isEmpty())
        assertEquals("SOURCE_READ_FAILED_OR_NOT_AUTHORIZED", result.projects.unavailableReason)
        assertEquals(null, result.projects.sourceAsOf)
        assertEquals("CURRENT", result.needsYou.status)
        assertEquals("CURRENT", result.assetExceptions.status)
    }

    @Test
    fun returnsSourceOwnedDecisionAndNeverCreatesCommandsOrTotals() {
        val sources = CapturingSources().apply {
            decision = ExecutiveDecision(
                safeTitleCode = "APPROVAL_REQUIRED",
                safeSummary = "Review the original request",
                attentionClass = "DECISION",
                updatedAt = now.toString(),
                reference = ExecutiveSourceReference(
                    module = "INBOX",
                    sourceType = "APPROVAL_REQUEST",
                    sourceId = UUID.randomUUID().toString(),
                    sourceVersion = 9,
                    readPath = "/v1/approvals/safe-source",
                ),
            )
        }
        val result = ExecutiveHomeService(sources, clock).home(actor, organization, "c")
        assertEquals(1, result.needsYou.items.size)
        assertEquals(9L, result.needsYou.items.single().reference.sourceVersion)
        assertTrue(result.needsYou.items.single().reference.readPath.startsWith("/v1/approvals/"))
        assertFalse(result.pendingDomains.isEmpty())
        assertTrue(result.projects.items.isEmpty())
    }

    private class CapturingSources : ExecutiveSources {
        var needsActor: UUID? = null
        var projectsActor: UUID? = null
        var assetsActor: UUID? = null
        var projectsOrg: UUID? = null
        var assetsOrg: UUID? = null
        var failProjects: Boolean = false
        var decision: ExecutiveDecision? = null

        override fun needsYou(actor: UUID, correlationId: String): ExecutiveSection<ExecutiveDecision> {
            needsActor = actor
            return ExecutiveSection(
                status = "CURRENT",
                source = "INBOX_WORK_QUEUE",
                sourceAsOf = "2026-11-22T20:00:00Z",
                partial = false,
                items = listOfNotNull(decision),
            )
        }

        override fun projects(actor: UUID, organizationId: UUID): ExecutiveSection<ExecutiveProject> {
            projectsActor = actor
            projectsOrg = organizationId
            if (failProjects) throw IllegalStateException("Producer down")
            return ExecutiveSection(
                status = "CURRENT",
                source = "PROJECTS_AND_PROJECT_HEALTH",
                sourceAsOf = null,
                partial = false,
                items = emptyList(),
            )
        }

        override fun assetExceptions(actor: UUID, organizationId: UUID): ExecutiveSection<ExecutiveAssetException> {
            assetsActor = actor
            assetsOrg = organizationId
            return ExecutiveSection(
                status = "CURRENT",
                source = "ASSET_SERVICE_QUEUE",
                sourceAsOf = null,
                partial = false,
                items = emptyList(),
            )
        }
    }
}
