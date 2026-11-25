package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.network.ErrorEnvelope
import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechApiException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SupplierRegistryWorkflowTest {
    private val org = "11111111-1111-1111-1111-111111111111"
    private val supplier = "22222222-2222-2222-2222-222222222222"
    private val operation = "33333333-3333-3333-3333-333333333333"
    private val installation = "44444444-4444-4444-4444-444444444444"
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(engine: MockEngine) = SupplierMasterDataApiClient(HiltechApiClient(
        HttpClient(engine), "https://api.hiltech.test", { "registrar-token" }, { "supplier-workflow-test" },
    ))

    @Test fun createIntentSurvivesSerializationAndUnknownRetryWithExactBodyAndKey() = runBlocking {
        val profile = normalizedSupplierProfile(SupplierProfileDto(displayName = "  مورد تجريبي  ", email = " supplier@example.test "))
        val original = SupplierRegistryIntent(operation, org, "CREATE", profile.displayName, profile = profile)
        val restored = Json.decodeFromString<SupplierRegistryIntent>(Json.encodeToString(SupplierRegistryIntent.serializer(), original))
        val requests = mutableListOf<String>()
        val api = client(MockEngine { call ->
            assertEquals("/v1/sourcing/organizations/$org/supplier-registry", call.url.encodedPath)
            assertEquals(operation, call.headers["Idempotency-Key"])
            assertEquals(installation, call.headers["X-Device-Installation-Id"])
            assertEquals("Bearer registrar-token", call.headers[HttpHeaders.Authorization])
            val body = (call.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            requests += body
            assertEquals(CreateSupplierDto(operation, profile), Json.decodeFromString<CreateSupplierDto>(body))
            if (requests.size == 1) respond("service unavailable", HttpStatusCode.ServiceUnavailable)
            else respond("""{"operationId":"$operation","organizationId":"$org","supplierOrganizationId":"$supplier",
                "version":1,"action":"CREATED","replayed":true}""", HttpStatusCode.OK, headers)
        })
        assertFailsWith<Exception> { api.applyRegistryIntent(restored, installation) }
        val receipt = api.applyRegistryIntent(restored, installation)
        assertEquals(2, requests.size)
        assertEquals(requests[0], requests[1])
        assertEquals(supplier, receipt.supplierId)
        assertTrue(receipt.replayed)
        assertEquals("مورد تجريبي", profile.legalName)
    }

    @Test fun updateAndSuspensionUseFrozenSupplierAndVersions() = runBlocking {
        val api = client(MockEngine { call ->
            val body = (call.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            when (call.url.encodedPath) {
                "/v1/sourcing/organizations/$org/supplier-registry/$supplier/profile" -> {
                    val request = Json.decodeFromString<UpdateSupplierProfileDto>(body)
                    assertEquals(7L, request.baseVersion)
                    assertEquals("Corrected supplier", request.profile.displayName)
                    respond("""{"operationId":"$operation","organizationId":"$org","supplierOrganizationId":"$supplier",
                        "version":8,"action":"UPDATED","replayed":false}""", HttpStatusCode.OK, headers)
                }
                "/v1/sourcing/organizations/$org/supplier-links" -> {
                    val request = Json.decodeFromString<ChangeSupplierLinkDto>(body)
                    assertEquals(5, request.expectedRevisionNumber)
                    assertEquals("INACTIVE", request.state)
                    assertEquals("Contract under review", request.sourceReference)
                    respond("""{"eventId":"$operation","organizationId":"$org","supplierOrganizationId":"$supplier",
                        "revisionNumber":5,"state":"INACTIVE","replayed":false}""", HttpStatusCode.OK, headers)
                }
                else -> error("Unexpected request")
            }
        })
        val updated = api.applyRegistryIntent(SupplierRegistryIntent(operation, org, "UPDATE", "Corrected supplier", supplier,
            SupplierProfileDto("Legal supplier", "Corrected supplier"), baseVersion = 7), installation)
        assertEquals("UPDATED", updated.action)
        val suspended = api.applyRegistryIntent(SupplierRegistryIntent(operation, org, "LINK", "Corrected supplier", supplier,
            relationshipRevision = 5, relationshipState = "INACTIVE", reason = "Contract under review"), installation)
        assertEquals("INACTIVE", suspended.action)
    }

    @Test fun foreignOrStaleReceiptsCannotBePresentedAsConfirmed() = runBlocking {
        val wrongOrg = "55555555-5555-5555-5555-555555555555"
        val api = client(MockEngine {
            respond("""{"operationId":"$operation","organizationId":"$wrongOrg","supplierOrganizationId":"$supplier",
                "version":1,"action":"CREATED","replayed":false}""", HttpStatusCode.OK, headers)
        })
        assertFailsWith<Exception> {
            api.createSupplier(org, CreateSupplierDto(operation, SupplierProfileDto("Supplier", "Supplier")), installation)
        }
        val stale = client(MockEngine {
            respond("""{"eventId":"$operation","organizationId":"$org","supplierOrganizationId":"$supplier",
                "revisionNumber":3,"state":"ACTIVE","replayed":false}""", HttpStatusCode.OK, headers)
        })
        assertFailsWith<Exception> {
            stale.changeSupplierLink(org, ChangeSupplierLinkDto(operation, supplier, 4, "INACTIVE", "Contract review needed"), installation)
        }
    }

    @Test fun capabilityAndDetailCannotCrossOrganizationScope() = runBlocking {
        val api = client(MockEngine { call ->
            if (call.url.encodedPath.endsWith("/capabilities")) respond(
                """{"organizationId":"$supplier","available":true,"canMaintain":true}""", HttpStatusCode.OK, headers,
            ) else respond("""{"organizationId":"$org","supplierOrganizationId":"$operation","organizationCode":"SUP-SYNTHETIC",
                "supplierStatus":"ACTIVE","relationshipRevision":1,"relationshipState":"ACTIVE","version":1,
                "profile":{"legalName":"Supplier","displayName":"Supplier"}}""", HttpStatusCode.OK, headers)
        })
        assertFailsWith<Exception> { api.capabilities(org, installation) }
        assertFailsWith<Exception> { api.detail(org, supplier, installation) }
    }

    @Test fun laterDenialCannotEraseAnEarlierUnknownOutcome() {
        val timeout = supplierRegistryFailureDisposition(false, IllegalStateException("transport interrupted"))
        assertTrue(timeout.hasUnknownOutcome)
        assertFalse(timeout.rejectionConfirmed)
        val denial = failure("SUPPLIER_MASTER_DATA_UNAVAILABLE", 404)
        assertTrue(supplierRegistryFailureDisposition(true, denial).hasUnknownOutcome)
        assertFalse(supplierRegistryFailureDisposition(true, denial).rejectionConfirmed)
        assertTrue(supplierRegistryFailureDisposition(false, denial).rejectionConfirmed)
        assertTrue(supplierRegistryFailureDisposition(true, failure("OPERATION_PREVIOUSLY_FAILED", 409)).rejectionConfirmed)
        assertTrue(supplierRegistryFailureDisposition(false, failure("IDEMPOTENCY_KEY_REUSED", 409)).hasUnknownOutcome)
    }

    @Test fun optionalContactFieldsDoNotBlockRegistrationButMalformedDataIsExplained() {
        assertTrue(supplierProfileProblems(SupplierProfileDto(displayName = "مورد جديد")).isEmpty())
        assertEquals(setOf("displayName", "legalName"), supplierProfileProblems(SupplierProfileDto()))
        assertEquals(setOf("email"), supplierProfileProblems(SupplierProfileDto(displayName = "Supplier", email = "wrong")))
        assertEquals(setOf("phone"), supplierProfileProblems(SupplierProfileDto(displayName = "Supplier", phone = "bad\u001fphone")))
        assertTrue(supplierProfileProblems(SupplierProfileDto(displayName = "Supplier", address = "Floor 1\nBuilding 2")).isEmpty())
    }

    private fun failure(code: String, status: Int) = HiltechApiException(code, "synthetic", status, false,
        ErrorEnvelope(code, "Synthetic failure", "synthetic"), "Synthetic failure")
}
