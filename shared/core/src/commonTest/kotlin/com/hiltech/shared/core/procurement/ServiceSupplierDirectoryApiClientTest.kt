package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechApiException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceSupplierDirectoryApiClientTest {
    private val org = "11111111-1111-1111-1111-111111111111"
    private val supplier = "22222222-2222-2222-2222-222222222222"
    private val installation = "33333333-3333-3333-3333-333333333333"
    private val candidate = SourcingSupplierCandidateDto(supplier, "Synthetic service supplier", 3)
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(http: HttpClient) = ServiceSupplierSourcingApiClient(
        HiltechApiClient(
            client = http,
            baseUrl = "https://api.hiltech.test",
            accessTokenProvider = { "synthetic-service-buyer-token" },
            correlationIdProvider = { "service-supplier-directory-test" },
        ),
    )

    private fun directory(query: String = "Supplier", candidates: List<SourcingSupplierCandidateDto> = listOf(candidate)) =
        ServiceSupplierDirectoryDto(org, query, candidates)

    private fun body(view: ServiceSupplierDirectoryDto) =
        Json.encodeToString(ServiceSupplierDirectoryDto.serializer(), view)

    @Test
    fun serviceBuyerSearchUsesScopedOperationalGetAndEncodesTheLiteralName() = runBlocking {
        val query = "توريد %_ & خدمات"
        var calls = 0
        val http = HttpClient(MockEngine { call ->
            calls++
            assertEquals("GET", call.method.value)
            assertEquals("/v1/service-procurement/organizations/$org/sourcing-suppliers", call.url.encodedPath)
            assertEquals(query, call.url.parameters["query"])
            assertEquals("Bearer synthetic-service-buyer-token", call.headers[HttpHeaders.Authorization])
            assertEquals(installation, call.headers["X-Device-Installation-Id"])
            assertNull(call.headers["Idempotency-Key"])
            assertFalse(call.url.encodedPath.contains("supplier-registry"))
            respond(body(directory(query)), HttpStatusCode.OK, headers)
        })

        assertEquals(listOf(candidate), client(http).searchSuppliers(org, "  $query  ", installation))
        assertEquals(1, calls)
    }

    @Test
    fun directoryForAnotherOrganizationOrQueryIsRejectedBeforeSelection() = runBlocking {
        for (wrong in listOf(directory().copy(organizationId = supplier), directory().copy(query = "Other"))) {
            val http = HttpClient(MockEngine { respond(body(wrong), HttpStatusCode.OK, headers) })
            assertFailsWith<IllegalArgumentException> {
                client(http).searchSuppliers(org, "Supplier", installation)
            }
        }
    }

    @Test
    fun malformedOrUnboundedCandidatesCannotBecomeSelectable() = runBlocking {
        val invalid = listOf(
            listOf(candidate.copy(supplierOrganizationId = "../foreign")),
            listOf(candidate.copy(displayName = " ")),
            listOf(candidate.copy(relationshipRevision = 0)),
            listOf(candidate, candidate),
            List(101) { index -> candidate.copy(supplierOrganizationId = "22222222-2222-2222-2222-" + index.toString().padStart(12, '0')) },
        )
        for (candidates in invalid) {
            val http = HttpClient(MockEngine { respond(body(directory(candidates = candidates)), HttpStatusCode.OK, headers) })
            assertFailsWith<IllegalArgumentException> {
                client(http).searchSuppliers(org, "Supplier", installation)
            }
        }
    }

    @Test
    fun deniedBuyerSearchDoesNotFallBackToTheAdministrativeRegistryOrAnEmptySuccess() = runBlocking {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.Forbidden)) {
            var calls = 0
            val http = HttpClient(MockEngine { call ->
                calls++
                assertEquals("GET", call.method.value)
                assertEquals("/v1/service-procurement/organizations/$org/sourcing-suppliers", call.url.encodedPath)
                respond("""{"code":"SERVICE_SOURCING_UNAVAILABLE","message":"Unavailable","correlationId":"service-buyer-denied","retryable":false}""", status, headers)
            })
            val error = assertFailsWith<HiltechApiException> {
                client(http).searchSuppliers(org, "Supplier", installation)
            }
            assertEquals("SERVICE_SOURCING_UNAVAILABLE", error.code)
            assertEquals(status.value, error.httpStatus)
            assertEquals(1, calls)
        }
    }

    @Test
    fun refreshedEmptySourceDoesNotReuseThePreviouslyEligibleSupplier() = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine {
            calls++
            respond(body(directory(candidates = if (calls == 1) listOf(candidate) else emptyList())), HttpStatusCode.OK, headers)
        })
        val service = client(http)
        assertEquals(listOf(candidate), service.searchSuppliers(org, "Supplier", installation))
        assertTrue(service.searchSuppliers(org, "Supplier", installation).isEmpty())
        assertEquals(2, calls)
    }

    @Test
    fun invalidQueryAndOrganizationCannotReachTheNetwork() = runBlocking {
        var called = false
        val http = HttpClient(MockEngine { called = true; error("Invalid directory request reached network") })
        val service = client(http)
        for (query in listOf("", " ", "a", "x".repeat(81), "ab\ncd", "ab\u007fcd")) {
            assertFailsWith<IllegalArgumentException> { service.searchSuppliers(org, query, installation) }
        }
        assertFailsWith<IllegalArgumentException> { service.searchSuppliers("../organization", "Supplier", installation) }
        assertFalse(called)
    }
}
