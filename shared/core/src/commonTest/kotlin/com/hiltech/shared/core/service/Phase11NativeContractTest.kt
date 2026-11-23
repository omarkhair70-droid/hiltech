package com.hiltech.shared.core.service

import com.hiltech.shared.core.network.HiltechApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Phase11NativeContractTest {
    private val project = "11111111-1111-4111-8111-111111111111"
    private val ticket = "22222222-2222-4222-8222-222222222222"
    private val handover = "33333333-3333-4333-8333-333333333333"
    private val operation = "44444444-4444-4444-8444-444444444444"
    private val work = "55555555-5555-4555-8555-555555555555"
    private val evidence = "66666666-6666-4666-8666-666666666666"
    private val visit = "77777777-7777-4777-8777-777777777777"
    private val installation = "88888888-8888-4888-8888-888888888888"
    private val digest = "a".repeat(64)

    @Test
    fun supportCaseCommandUsesRealAuthenticatedRouteAndOperationKey() = runBlocking {
        val captured = mutableListOf<Pair<String, String?>>()
        val http = HttpClient(MockEngine { request ->
            captured += request.url.encodedPath to request.headers["Idempotency-Key"]
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("Bearer phase11-token", request.headers[HttpHeaders.Authorization])
            assertEquals(installation, request.headers["X-Device-Installation-Id"])
            respond(
                content = """
                  {
                    "eventId":"$operation",
                    "ticketId":"$ticket",
                    "eventNumber":1,
                    "eventType":"INVESTIGATION_STARTED",
                    "resultingState":"INVESTIGATING",
                    "reason":"Real issue investigated",
                    "workOrderId":null,
                    "evidenceId":null,
                    "recordedAt":"2026-11-23T01:00:00Z",
                    "visibility":"INTERNAL_ONLY",
                    "duplicateReplay":false
                  }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val client = SupportCaseApiClient(HiltechApiClient(
            client = http,
            baseUrl = "https://api.hiltech.test",
            accessTokenProvider = { "phase11-token" },
            correlationIdProvider = { "phase11-corr" },
        ))
        val event = client.record(project, ticket,
            SupportCaseRecordDto(
                operationId = operation,
                expectedLatestEventNumber = 0,
                eventType = "INVESTIGATION_STARTED",
                reason = "Real issue investigated",
            ),
            installation)
        assertEquals("INVESTIGATING", event.resultingState)
        assertEquals("INTERNAL_ONLY", event.visibility)
        assertEquals(
            listOf("/v1/projects/$project/service-tickets/$ticket/case/events" to operation),
            captured,
        )
        assertFailsWith<IllegalArgumentException> {
            client.record(project, ticket,
                SupportCaseRecordDto("invalid-id", 0, "REOPENED", "reason"), installation)
        }
    }

    @Test
    fun internalSupportWorkAndProofChoicesReadFromAuthenticatedServer() = runBlocking {
        val paths = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            assertEquals(HttpMethod.Get, request.method)
            val body = when(request.url.encodedPath) {
                "/v1/projects/$project/service-tickets/$ticket/case/work-choices" ->
                    """[{"id":"$work","title":"Replace failed switch","lifecycleState":"ACCEPTED"}]"""
                "/v1/projects/$project/service-tickets/$ticket/case/ready-evidence" ->
                    """[{"id":"$evidence","workOrderId":"$work","label":"SITE_PHOTO · 2026-11-23T01:00:00Z"}]"""
                else -> error("Unexpected unapproved service route")
            }
            respond(
                body, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val client = SupportCaseApiClient(HiltechApiClient(
            client = http,
            baseUrl = "https://api.hiltech.test",
            accessTokenProvider = { "phase11-token" },
            correlationIdProvider = { "phase11-corr" },
        ))
        val works = client.workChoices(project, ticket, installation)
        val evidenceChoices = client.readyEvidenceChoices(project, ticket, installation)
        assertEquals(work, works.single().id)
        assertEquals("ACCEPTED", works.single().lifecycleState)
        assertEquals(evidence, evidenceChoices.single().id)
        assertEquals(work, evidenceChoices.single().workOrderId)
        assertEquals(2, paths.size)
    }

    @Test
    fun frozenHandoverReviewResponseKeepsExternalAcceptanceOff() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/v1/projects/$project/handover/packages/$handover/internal-review",
                request.url.encodedPath)
            respond(
                content = """
                  {
                    "packageVersionId":"$handover",
                    "packageVersionNumber":2,
                    "frozenSourceDigest":"$digest",
                    "currentSourceDigest":"$digest",
                    "sourceStillMatches":true,
                    "isLatestPackageVersion":true,
                    "reviewState":"FINDINGS_RECORDED",
                    "latestEventNumber":3,
                    "canIssueToClient":false,
                    "canAcceptOnBehalfOfClient":false
                  }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val client = HandoverReviewApiClient(HiltechApiClient(
            client = http,
            baseUrl = "https://api.hiltech.test",
            accessTokenProvider = { "phase11-token" },
            correlationIdProvider = { "phase11-corr" },
        ))
        val state = client.summary(project, handover, installation)
        assertEquals(3, state.latestEventNumber)
        assertEquals("FINDINGS_RECORDED", state.reviewState)
        assertTrue(state.sourceStillMatches && state.isLatestPackageVersion)
        assertFalse(state.canIssueToClient)
        assertFalse(state.canAcceptOnBehalfOfClient)
    }

    @Test
    fun signedCommercialSourceReadDoesNotPretendToActivateWarranty() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/v1/projects/$project/service-contract-source", request.url.encodedPath)
            respond(
                content = """
                {
                    "projectId":"$project",
                    "sourceState":"SIGNED_RECORD_STORAGE_READY_AWARD_NOT_REVALIDATED",
                    "contractId":"$handover",
                    "clientPoId":"$ticket",
                    "sourceEffectiveDate":"2026-11-23",
                    "sourceEndDate":"2028-11-23",
                    "hasWrittenWarrantyTerms":true,
                    "hasWrittenMaintenanceTerms":true,
                    "warrantyEntitlementState":"NOT_EVALUATED",
                    "slaPolicyState":"NOT_CONFIGURED",
                    "maintenanceScheduleState":"NOT_APPROVED",
                    "renewalEligibilityState":"NOT_EVALUATED",
                    "clientVisibility":"INTERNAL_ONLY"
                }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val client = ProjectServiceContractSourceApiClient(HiltechApiClient(
            client = http,
            baseUrl = "https://api.hiltech.test",
            accessTokenProvider = { "phase11-token" },
            correlationIdProvider = { "phase11-corr" },
        ))
        val source = client.read(project, installation)
        assertEquals("SIGNED_RECORD_STORAGE_READY_AWARD_NOT_REVALIDATED", source.sourceState)
        assertTrue(source.hasWrittenWarrantyTerms)
        assertEquals("NOT_EVALUATED", source.warrantyEntitlementState)
        assertEquals("NOT_APPROVED", source.maintenanceScheduleState)
        assertEquals("INTERNAL_ONLY", source.clientVisibility)
    }

    @Test
    fun maintenanceFindingLinksToExistingCaseWithoutCreatingAnotherTicket() = runBlocking {
        val finding = "99999999-9999-4999-8999-999999999999"
        val followup = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val paths = mutableListOf<Pair<String, String?>>()
        val http = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath to request.headers["Idempotency-Key"]
            val response = """
            {
                "followupId":"$followup",
                "findingId":"$finding",
                "visitId":"$visit",
                "ticketId":"$ticket",
                "reason":"Observed failed connection during field inspection",
                "visibility":"INTERNAL_ONLY",
                "recordedAt":"2026-11-23T01:00:00Z",
                "duplicateReplay":false
            }
            """.trimIndent()
            respond(
                if (request.method == HttpMethod.Get) "[" + response + "]" else response,
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val client = MaintenanceTicketFollowupApiClient(HiltechApiClient(
            client = http,
            baseUrl = "https://api.hiltech.test",
            accessTokenProvider = { "phase11-token" },
            correlationIdProvider = { "phase11-corr" },
        ))
        val linked = client.link(project, visit, finding,
            LinkMaintenanceFindingTicketDto(
                operationId = operation,
                ticketId = ticket,
                reason = "Observed failed connection during field inspection",
            ), installation)
        assertEquals(ticket, linked.ticketId)
        assertEquals("INTERNAL_ONLY", linked.visibility)
        val history = client.forVisit(project, visit, installation)
        assertEquals(finding, history.single().findingId)
        assertEquals(
            "/v1/projects/$project/maintenance-visits/$visit/ticket-followups/$finding" to operation,
            paths[0],
        )
        assertEquals(
            "/v1/projects/$project/maintenance-visits/$visit/ticket-followups" to null,
            paths[1],
        )
    }

    @Test
    fun fieldOnlyUserNeverReceivesSupportFollowupWriteControl() {
        assertFalse(MaintenanceNativePermissions.mayOfferFollowup(
            managerAuthorized = false, alreadyLinked = false,
        ))
        assertFalse(MaintenanceNativePermissions.mayOfferFollowup(
            managerAuthorized = false, alreadyLinked = true,
        ))
        assertFalse(MaintenanceNativePermissions.mayOfferFollowup(
            managerAuthorized = true, alreadyLinked = true,
        ))
        assertTrue(MaintenanceNativePermissions.mayOfferFollowup(
            managerAuthorized = true, alreadyLinked = false,
        ))
    }

    @Test
    fun ownerUnknownOutsideLatestTriageWindowIsNotMisrepresentedAsUnassigned() {
        val classification = InternalSupportTriageEventDto(
            eventId = operation, ticketId = ticket, eventNumber = 501,
            eventType = "CLASSIFICATION_RECORDED", ownerEmployeeId = null,
            proposedSeverityCode = "HIGH", proposedPriorityCode = "P1",
            reason = "Case reviewed", clientVisibility = "INTERNAL_ONLY",
            createdAt = "2026-11-23T01:00:00Z", duplicateReplay = false,
        )
        assertTrue(SupportTriageRecentOwnerState.ownerUnavailableFromWindow(listOf(classification)))
        assertFalse(SupportTriageRecentOwnerState.ownerUnavailableFromWindow(
            listOf(classification.copy(eventNumber = 1))))
        assertFalse(SupportTriageRecentOwnerState.ownerUnavailableFromWindow(
            listOf(classification.copy(eventType = "OWNER_RELEASED"))))
    }

    @Test
    fun maintenanceSourceStatusDoesNotMisrepresentVisitAsCompleted() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/v1/projects/$project/maintenance-visits/$visit/source-status",
                request.url.encodedPath)
            respond(
                content = """
                  {
                    "visitId":"$visit",
                    "workOrderId":"$work",
                    "workOrderLifecycleState":"IN_PROGRESS",
                    "currentWorkVersion":5,
                    "requiredChecklistTotal":4,
                    "requiredChecklistPending":2,
                    "readyWorkEvidenceCount":1,
                    "canRecordInternalFinding":true,
                    "visitCompletionConfirmed":false,
                    "contractCoverageState":"UNVERIFIED"
                  }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val client = MaintenanceApiClient(HiltechApiClient(
            client = http,
            baseUrl = "https://api.hiltech.test",
            accessTokenProvider = { "phase11-token" },
            correlationIdProvider = { "phase11-corr" },
        ))
        val actual = client.sourceStatus(project, visit, installation)
        assertEquals(work, actual.workOrderId)
        assertEquals(2, actual.requiredChecklistPending)
        assertTrue(actual.canRecordInternalFinding)
        assertFalse(actual.visitCompletionConfirmed)
        assertEquals("UNVERIFIED", actual.contractCoverageState)
    }
}
