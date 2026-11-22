package com.hiltech.server.executive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockHttpServletRequest
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.security.AuthorizationCheckPort
import com.hiltech.server.security.RoleTeamAuthorizationService
import com.hiltech.server.security.RoleTeamSourceAuthorityPort
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
    fun executiveEndpointFailsClosedWhileFeatureApprovalIsMissing() {
        val controller = ExecutiveHomeController(
            service = ExecutiveHomeService(CapturingSources(), AllowedMembership, clock),
            enabled = false,
        )
        val failure = assertThrows<ProductApiException> {
            controller.home(MockHttpServletRequest(), organization.toString())
        }
        assertEquals("EXECUTIVE_HOME_DISABLED", failure.code)
    }

    @Test
    fun authorizedHttpResponseMustNotBeCachedByBrowserOrProxy() {
        val request = MockHttpServletRequest()
        HiltechRequestContext.bindAuthenticatedIdentity(request, actor, UUID.randomUUID())
        val controller = ExecutiveHomeController(
            service = ExecutiveHomeService(CapturingSources(), AllowedMembership, clock),
            enabled = true,
        )
        val response = controller.home(request, organization.toString())
        assertTrue(response.headers.cacheControl.orEmpty().contains("no-store"))
        assertEquals(actor.toString(), response.body?.viewerIdentityId)
        assertEquals(organization.toString(), response.body?.organizationId)
    }

    @Test
    fun enabledEndpointRejectsUnverifiedIdentityBeforeReadingAnyExecutiveData() {
        val sources = CapturingSources()
        val controller = ExecutiveHomeController(
            service = ExecutiveHomeService(sources, AllowedMembership, clock),
            enabled = true,
        )
        val failure = assertThrows<ProductApiException> {
            controller.home(MockHttpServletRequest(), organization.toString())
        }
        assertEquals("UNAUTHENTICATED", failure.code)
        assertEquals(null, sources.needsActor)
        assertEquals(null, sources.projectsActor)
        assertEquals(null, sources.assetsActor)
    }

    @Test
    fun delegatesCurrentIdentityAndOrganizationWithoutExecutivePrivilege() {
        val sources = CapturingSources()
        val result = ExecutiveHomeService(sources, AllowedMembership, clock).home(actor, organization, "correlation-123")

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
        val result = ExecutiveHomeService(sources, AllowedMembership, clock).home(actor, organization, "c")

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
        val result = ExecutiveHomeService(sources, AllowedMembership, clock).home(actor, organization, "c")
        assertEquals(1, result.needsYou.items.size)
        assertEquals(9L, result.needsYou.items.single().reference.sourceVersion)
        assertTrue(result.needsYou.items.single().reference.readPath.startsWith("/v1/approvals/"))
        assertFalse(result.pendingDomains.isEmpty())
        assertTrue(result.projects.items.isEmpty())
    }

    @Test
    fun foreignOrRevokedOrganizationIsDeniedBeforeActorWideInboxRead() {
        val sources = CapturingSources()
        val access = object : ExecutiveOrganizationAccess {
            override fun isCurrentMember(actor: UUID, organizationId: UUID): Boolean = false
        }
        val service = ExecutiveHomeService(sources, access, clock)
        val failure = assertThrows<ProductApiException> {
            service.home(actor, organization, "refused-request")
        }
        assertEquals("EXECUTIVE_HOME_NOT_FOUND", failure.code)
        assertEquals(null, sources.needsActor)
        assertEquals(null, sources.projectsActor)
        assertEquals(null, sources.assetsActor)
    }

    @Test
    fun membershipCheckIsScopedToExactActorAndOrganization() {
        var lastActor: UUID? = null
        var lastOrganization: UUID? = null
        val access = object : ExecutiveOrganizationAccess {
            override fun isCurrentMember(actor: UUID, organizationId: UUID): Boolean {
                lastActor = actor
                lastOrganization = organizationId
                return true
            }
        }
        val sources = CapturingSources()
        ExecutiveHomeService(sources, access, clock).home(actor, organization, "source-checked")
        assertEquals(actor, lastActor)
        assertEquals(organization, lastOrganization)
    }

    @Test
    fun currentOrganizationMembershipAndOpenFgaBothRequiredForExecutiveRead() {
        var currentlyActive = true
        var openFgaPermits = true
        var openFgaCalls = 0
        val sourceAuthority = object : RoleTeamSourceAuthorityPort {
            override fun isOrganizationMemberCurrent(identityId: UUID, organizationId: UUID, at: Instant): Boolean =
                currentlyActive && identityId == actor && organizationId == organization

            override fun isTeamMemberCurrent(identityId: UUID, teamId: UUID, at: Instant): Boolean = false
            override fun isTeamManagerCurrent(identityId: UUID, teamId: UUID, at: Instant): Boolean = false
        }
        val membership = RoleTeamAuthorizationService(
            authorization = AuthorizationCheckPort {
                openFgaCalls++
                openFgaPermits
            },
            sourceAuthority = sourceAuthority,
            clock = clock,
        )
        val sources = CapturingSources()
        val service = ExecutiveHomeService(sources, SourceExecutiveOrganizationAccess(membership), clock)

        // One correctly configured actor + org with a present FGA tuple.
        assertEquals(organization.toString(), service.home(actor, organization, "allowed").organizationId)
        assertEquals(1, openFgaCalls)

        // Neither an invented organization nor a second account can use the
        // authorized actor's source readings.
        assertEquals("EXECUTIVE_HOME_NOT_FOUND", assertThrows<ProductApiException> {
            service.home(actor, UUID.randomUUID(), "foreign-org")
        }.code)
        assertEquals(1, openFgaCalls)

        // Live source revocation must deny BEFORE even asking OpenFGA.
        currentlyActive = false
        val beforeRevocationChecks = openFgaCalls
        assertEquals("EXECUTIVE_HOME_NOT_FOUND", assertThrows<ProductApiException> {
            service.home(actor, organization, "revoked")
        }.code)
        assertEquals(beforeRevocationChecks, openFgaCalls)

        // An active source membership cannot compensate for a missing FGA
        // relation, and neither failure can consult the executive producers.
        currentlyActive = true
        openFgaPermits = false
        val previousInboxActor = sources.needsActor
        sources.needsActor = null
        sources.projectsActor = null
        sources.assetsActor = null
        assertEquals("EXECUTIVE_HOME_NOT_FOUND", assertThrows<ProductApiException> {
            service.home(actor, organization, "fga-denied")
        }.code)
        assertEquals(null, sources.needsActor)
        assertEquals(null, sources.projectsActor)
        assertEquals(null, sources.assetsActor)
        assertEquals(actor, previousInboxActor)
    }

    private object AllowedMembership : ExecutiveOrganizationAccess {
        override fun isCurrentMember(actor: UUID, organizationId: UUID): Boolean = true
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
