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

class MaintenanceAgendaNativeContractTest {
    private val project = "11111111-1111-4111-8111-111111111111"
    private val visit = "77777777-7777-4777-8777-777777777777"
    private val installation = "88888888-8888-4888-8888-888888888888"
    private val from = "2026-11-23T00:00:00Z"
    private val to = "2026-12-01T00:00:00Z"

    @Test
    fun agendaReadsActualManagerSourceWithExplicitRangeAndNoCoverageClaims() = runBlocking {
        var calls = 0
        val engine = HttpClient(MockEngine { req ->
            calls++
            assertEquals(HttpMethod.Get, req.method)
            assertEquals("Bearer phase11-token", req.headers[HttpHeaders.Authorization])
            assertEquals(installation, req.headers["X-Device-Installation-Id"])
            assertEquals("/v1/projects/$project/maintenance-visits/agenda", req.url.encodedPath)
            assertEquals(from, req.url.parameters["from"])
            assertEquals(to, req.url.parameters["to"])
            respond(
                content = """{"projectId":"11111111-1111-4111-8111-111111111111","from":"2026-11-23T00:00:00Z","to":"2026-12-01T00:00:00Z","items":[{"visitId":"77777777-7777-4777-8777-777777777777","workOrderId":"55555555-5555-4555-8555-555555555555","siteId":"33333333-3333-4333-8333-333333333333","plannedFor":"2026-11-24T12:00:00Z","workOrderTitle":"Inspect network rack","sourceWorkState":"IN_PROGRESS","sourceWorkVersion":3,"reviewState":"REVIEWING","reviewEventNumber":1,"visitWindowState":"FUTURE_DRAFT_VISIT","originalPlanState":"DRAFT","coverageState":"UNVERIFIED","contractScheduleApproved":false}],"truncated":false,"clientVisibility":"INTERNAL_ONLY"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val client = MaintenanceAgendaApiClient(HiltechApiClient(
            client = engine,
            baseUrl = "https://api.hiltech.test",
            accessTokenProvider = { "phase11-token" },
            correlationIdProvider = { "phase11-agenda" },
        ))
        val result = client.agenda(project, from, to, installation)
        assertEquals(1, calls)
        assertEquals(1, result.items.size)
        assertEquals(visit, result.items.single().visitId)
        assertFalse(result.items.single().contractScheduleApproved)
        assertEquals("UNVERIFIED", result.items.single().coverageState)
        assertEquals("FUTURE_DRAFT_VISIT", result.items.single().visitWindowState)
        assertFalse(result.truncated)
        assertEquals("INTERNAL_ONLY", result.clientVisibility)
        assertFailsWith<IllegalArgumentException> {
            client.agenda(project, "2026-11-23T00:00:00Z&override=true", to, installation)
        }
        assertEquals(1, calls)
    }
}
