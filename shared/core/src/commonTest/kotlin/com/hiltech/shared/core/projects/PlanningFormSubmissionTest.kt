package com.hiltech.shared.core.projects

import com.hiltech.shared.core.PlanningFormOperation
import com.hiltech.shared.core.PlanningFormRequest
import com.hiltech.shared.core.PlanningUiAction
import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechApiException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlanningFormSubmissionTest {
    private val projectId = "11111111-1111-4111-8111-111111111111"
    private val operationId = "22222222-2222-4222-8222-222222222222"
    private val installationId = "33333333-3333-4333-8333-333333333333"
    private val objectId = "44444444-4444-4444-8444-444444444444"

    @Test
    fun accountChangeWhileResolvingTokenPreventsDispatch() = runBlocking {
        var current = true
        var dispatched = 0
        val http = HttpClient(MockEngine { dispatched += 1; response() })
        val request = PlanningFormRequest(
            PlanningUiAction.MarkReady(projectId, 7, 1),
            PlanningFormOperation(operationId, "2026-11-25T10:00:00Z"),
        )
        assertFailsWith<CancellationException> {
            submitPlanningFormInSession(http, "https://api.hiltech.test", installationId, request,
                sessionCurrent = { current }, accessToken = { current = false; "new-actor-token" },
                correlationId = { "synthetic" })
        }
        assertEquals(0, dispatched)
        http.close()
    }

    @Test
    fun dispatchedCommandUsesCapturedTokenWithoutReadingAnotherActorToken() = runBlocking {
        var tokenReads = 0
        var authorization: String? = null
        val http = HttpClient(MockEngine { request ->
            authorization = request.headers[HttpHeaders.Authorization]
            response()
        })
        val request = PlanningFormRequest(
            PlanningUiAction.MarkReady(projectId, 7, 1),
            PlanningFormOperation(operationId, "2026-11-25T10:00:00Z"),
        )
        submitPlanningFormInSession(http, "https://api.hiltech.test", installationId, request,
            sessionCurrent = { true }, accessToken = { tokenReads += 1; if (tokenReads == 1) "initiating-actor" else "other-actor" },
            correlationId = { "synthetic" })
        assertEquals(1, tokenReads)
        assertEquals("Bearer initiating-actor", authorization)
        http.close()
    }

    @Test
    fun lostResponseRetryPreservesOperationTimestampVersionsAndExactBody() = runBlocking {
        val requests = mutableListOf<String>()
        val keys = mutableListOf<String?>()
        val client = client { request ->
            keys += request.headers["Idempotency-Key"]
            requests += (request.body as TextContent).text
            assertEquals("/v1/projects/$projectId/milestones", request.url.encodedPath)
            assertEquals("Bearer synthetic-token", request.headers[HttpHeaders.Authorization])
            assertEquals(installationId, request.headers["X-Device-Installation-Id"])
            if (requests.size == 1) error("Simulated response loss after dispatch")
            response()
        }
        val request = PlanningFormRequest(
            PlanningUiAction.CreateMilestone(projectId, "M-2", "اختبار", "2026-11-25", 2, false, null, 7, 1),
            PlanningFormOperation(operationId, "2026-11-25T10:00:00Z"),
        )
        assertFailsWith<HiltechApiException> { client.submitForm(request, installationId) }
        val result = client.submitForm(request, installationId)
        assertTrue(result.replayed)
        assertEquals<List<String?>>(listOf(operationId, operationId), keys.toList())
        assertEquals(requests.first(), requests.last())
        val body = Json.parseToJsonElement(requests.first()).jsonObject
        assertEquals(operationId, body.getValue("operationId").jsonPrimitive.content)
        assertEquals("2026-11-25T10:00:00Z", body.getValue("clientOccurredAt").jsonPrimitive.content)
        assertEquals("7", body.getValue("baseProjectVersion").jsonPrimitive.content)
        assertEquals("1", body.getValue("expectedBaselineVersion").jsonPrimitive.content)
        assertEquals("2026-11-25", body.getValue("plannedDate").jsonPrimitive.content)
    }

    @Test
    fun editPreservesChildAndProjectVersionAndIntentionalZero() = runBlocking {
        var captured = ""
        val client = client { request ->
            assertEquals("/v1/areas/$objectId", request.url.encodedPath)
            captured = (request.body as TextContent).text
            response()
        }
        client.submitForm(PlanningFormRequest(
            PlanningUiAction.UpdateArea(projectId, objectId, null, "ROOM", "A-2", "Room", 0, false, 7, 4, 1),
            PlanningFormOperation(operationId, "2026-11-25T10:00:00Z"),
        ), installationId)
        val payload = Json.parseToJsonElement(captured).jsonObject
        assertEquals(operationId, payload.getValue("operationId").jsonPrimitive.content)
        assertEquals("2026-11-25T10:00:00Z", payload.getValue("clientOccurredAt").jsonPrimitive.content)
        assertEquals("7", payload.getValue("baseProjectVersion").jsonPrimitive.content)
        assertEquals("4", payload.getValue("baseObjectVersion").jsonPrimitive.content)
        assertEquals("1", payload.getValue("expectedBaselineVersion").jsonPrimitive.content)
        assertEquals("0", payload.getValue("sequence").jsonPrimitive.content)
    }

    private fun client(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        PlanningApiClient(HiltechApiClient(
            HttpClient(MockEngine(handler)), "https://api.hiltech.test", { "synthetic-token" }, { "test-correlation" },
        ))

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.response() = respond(
        """{"plan":{"project":{"projectId":"$projectId","projectCode":"P-1","name":"Plan","lifecycleState":"PLANNING","version":8,"baselineVersion":1},"projectSites":[],"areas":[],"milestones":[],"workPackages":[],"dependencies":[],"validationValid":true,"validationReasonCodes":[],"canEditPlan":true,"readyGateReady":false,"readyGateReasonCodes":[],"correlationId":"server"},"targetType":"MILESTONE","targetId":"$objectId","replayed":true,"correlationId":"server"}""",
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, "application/json"),
    )
}
