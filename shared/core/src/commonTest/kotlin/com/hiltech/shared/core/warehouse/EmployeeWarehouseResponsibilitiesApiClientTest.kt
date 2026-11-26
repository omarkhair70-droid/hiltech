package com.hiltech.shared.core.warehouse

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechApiException
import com.hiltech.shared.core.network.ErrorEnvelope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmployeeWarehouseResponsibilitiesApiClientTest {
    private val org = "11111111-1111-1111-1111-111111111111"
    private val employee = "22222222-2222-2222-2222-222222222222"
    private val identity = "33333333-3333-3333-3333-333333333333"
    private val warehouse = "44444444-4444-4444-4444-444444444444"
    private val operation = "55555555-5555-5555-5555-555555555555"
    private val installation = "66666666-6666-6666-6666-666666666666"
    private val binding = "77777777-7777-7777-7777-777777777777"

    @Test
    fun previewUsesEmployeeRouteAndPreservesScopedDirectAndInheritedSemantics() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("GET", request.method.value)
            assertEquals("/v1/employees/$employee/warehouse-responsibilities", request.url.encodedPath)
            assertEquals("Bearer synthetic-token", request.headers[HttpHeaders.Authorization])
            assertEquals(installation, request.headers["X-Device-Installation-Id"])
            respond("""{
                "employeeId":"$employee","organizationId":"$org","employeeVersion":4,
                "linkedIdentityId":"$identity","accountStatus":"CURRENT",
                "scope":"MANAGED_WAREHOUSES_ONLY","asOf":"2026-11-25T12:00:00Z",
                "warehouses":[{"warehouseId":"$warehouse","code":"SYNTH-WH","name":"Synthetic warehouse",
                  "grantableAuthorityKeys":["WAREHOUSE_VIEWER","WAREHOUSE_OPERATOR"],
                  "bindings":[{"bindingId":"$binding","authorityKey":"WAREHOUSE_VIEWER",
                    "assignmentSource":"TEAM","canEnd":false,"version":2}]}]
            }""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val result = client(http).employeeWarehouseResponsibilities(employee, installation)
        assertEquals("MANAGED_WAREHOUSES_ONLY", result.scope)
        assertEquals(identity, result.linkedIdentityId)
        val inherited = result.warehouses.single().bindings.single()
        assertEquals("TEAM", inherited.assignmentSource)
        assertFalse(inherited.canEnd)
        http.close()
    }

    @Test
    fun lostGrantResponseRetriesExactOperationAndBodyAfterIntentSerialization() = runBlocking {
        val bodies = mutableListOf<String>()
        val operationIds = mutableListOf<String?>()
        var previews = 0
        val http = HttpClient(MockEngine { request ->
            if (request.method.value == "GET") {
                previews += 1
                return@MockEngine respond(preview(), HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            }
            assertEquals("/v1/warehouses/$warehouse/authority", request.url.encodedPath)
            assertEquals("POST", request.method.value)
            bodies += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            operationIds += request.headers["Idempotency-Key"]
            if (bodies.size == 1) throw IllegalStateException("Synthetic lost response")
            respond(receipt(current = true, replayed = true), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val api = client(http)
        val original = intent()
        assertFailsWith<HiltechApiException> { api.applyEmployeeWarehouseAuthority(original, installation) }
        val restored = Json.decodeFromString(EmployeeWarehouseAuthorityIntent.serializer(),
            Json.encodeToString(EmployeeWarehouseAuthorityIntent.serializer(), original))
        val result = api.applyEmployeeWarehouseAuthority(restored, installation, priorOutcomeUnknown = true)
        assertTrue(result.replayed)
        assertEquals(1, previews)
        assertEquals(employee, restored.employeeId)
        assertEquals(4L, restored.employeeVersion)
        assertEquals("Synthetic employee", restored.employeeDisplayName)
        assertEquals<List<String?>>(listOf(operation, operation), operationIds)
        assertEquals(bodies[0], bodies[1])
        val body = Json.parseToJsonElement(bodies[1]).jsonObject
        assertEquals("USER", body.getValue("principalType").jsonPrimitive.content)
        assertEquals(identity, body.getValue("principalId").jsonPrimitive.content)
        assertEquals(original.effectiveFrom, body.getValue("effectiveFrom").jsonPrimitive.content)
        assertEquals(original.clientOccurredAt, body.getValue("clientOccurredAt").jsonPrimitive.content)
        http.close()
    }

    @Test
    fun endKeepsExactBindingVersionAndRejectsAnAcknowledgementForAnotherEmployee() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            if (request.method.value == "GET") return@MockEngine respond(preview(), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
            assertEquals("/v1/warehouses/$warehouse/authority/$binding/end", request.url.encodedPath)
            assertEquals(operation, request.headers["Idempotency-Key"])
            val body = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
            assertEquals("3", body.getValue("baseVersion").jsonPrimitive.content)
            respond(receipt(current = false, replayed = false, responsePrincipal = employee),
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertFailsWith<IllegalArgumentException> {
            client(http).applyEmployeeWarehouseAuthority(intent().copy(action = "END", bindingId = binding, baseVersion = 3), installation)
        }
        http.close()
    }

    @Test
    fun managerAndValuePrivilegesCannotBeProposedThroughEmployeeWorkspace() = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine { calls += 1; error("No request expected") })
        for (key in listOf("WAREHOUSE_MANAGER", "ASSET_MASTER_MANAGER", "INVENTORY_VALUE_VIEWER", "INVENTORY_ADJUSTMENT_APPROVER")) {
            assertFailsWith<IllegalArgumentException> {
                client(http).applyEmployeeWarehouseAuthority(intent().copy(authorityKey = key), installation)
            }
        }
        assertEquals(0, calls)
        http.close()
    }

    @Test
    fun lostResponseThenVersionConflictKeepsOriginalOutcomeUnknown() {
        val lost = employeeWarehouseFailureDisposition(false, IllegalStateException("Lost response"))
        assertTrue(lost.hasUnknownOutcome)
        assertFalse(lost.rejectionConfirmed)
        val conflict = employeeWarehouseFailureDisposition(lost.hasUnknownOutcome, apiFailure("WAREHOUSE_AUTHORITY_VERSION_CONFLICT"))
        assertTrue(conflict.hasUnknownOutcome)
        assertFalse(conflict.rejectionConfirmed)
        val storedFailure = employeeWarehouseFailureDisposition(conflict.hasUnknownOutcome, apiFailure("OPERATION_PREVIOUSLY_FAILED"))
        assertFalse(storedFailure.hasUnknownOutcome)
        assertTrue(storedFailure.rejectionConfirmed)
    }

    @Test
    fun onlyAConfirmedFirstRejectionMayReleaseAnUnappliedIntent() {
        val rejected = employeeWarehouseFailureDisposition(false, apiFailure("WAREHOUSE_AUTHORITY_VERSION_CONFLICT"))
        assertFalse(rejected.hasUnknownOutcome)
        assertTrue(rejected.rejectionConfirmed)
        val unverifiedFailure = apiFailure("OPERATION_PREVIOUSLY_FAILED").let {
            HiltechApiException(it.code, it.correlationId, it.httpStatus, false, null, requireNotNull(it.message))
        }
        assertFalse(employeeWarehouseFailureDisposition(true, unverifiedFailure).rejectionConfirmed)
    }

    @Test
    fun receiptForAnotherOrganizationOrOperationIsNotAnAcknowledgement() = runBlocking {
        for (wrongReceipt in listOf(
            receipt(true, false).replace("\"organizationId\":\"$org\"", "\"organizationId\":\"$employee\""),
            receipt(true, false).replace("\"operationId\":\"$operation\"", "\"operationId\":\"$employee\""),
        )) {
            val http = HttpClient(MockEngine { request -> respond(
                if (request.method.value == "GET") preview() else wrongReceipt, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")) })
            val failure = assertFailsWith<IllegalArgumentException> {
                client(http).applyEmployeeWarehouseAuthority(intent(), installation)
            }
            assertTrue(employeeWarehouseFailureDisposition(false, failure).hasUnknownOutcome)
            http.close()
        }
    }

    @Test
    fun changedAccountOrEmployeeVersionPreventsFirstDispatch() = runBlocking {
        for (current in listOf(preview(linkedIdentity = binding), preview(employeeVersion = 5))) {
            var commands = 0
            var sending = false
            val http = HttpClient(MockEngine { request ->
                if (request.method.value == "POST") commands += 1
                respond(current, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            })
            val failure = assertFailsWith<EmployeeWarehousePreparationException> {
                client(http).applyEmployeeWarehouseAuthority(intent(), installation, onCommandSending = { sending = true })
            }
            assertEquals("EMPLOYEE_RESPONSIBILITY_TARGET_CHANGED", failure.safeCode)
            assertEquals(0, commands)
            assertFalse(sending)
            assertFalse(employeeWarehouseFailureDisposition(false, failure).hasUnknownOutcome)
            assertTrue(employeeWarehouseFailureDisposition(false, failure).rejectionConfirmed)
            // Even a preflight failure cannot settle a distinct earlier unknown result.
            assertTrue(employeeWarehouseFailureDisposition(true, failure).hasUnknownOutcome)
            http.close()
        }
    }

    @Test
    fun replayedGrantAcknowledgesOriginalOperationEvenAfterAssignmentEnded() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("POST", request.method.value)
            val body = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
            assertEquals(identity, body.getValue("principalId").jsonPrimitive.content)
            respond(receipt(current = false, replayed = true), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val result = client(http).applyEmployeeWarehouseAuthority(intent(), installation, priorOutcomeUnknown = true)
        assertTrue(result.replayed)
        assertFalse(result.current)
        http.close()
    }

    @Test
    fun unacknowledgedNewGrantCannotPretendAnAlreadyEndedBindingSucceeded() = runBlocking {
        val http = HttpClient(MockEngine { request -> respond(
            if (request.method.value == "GET") preview() else receipt(current = false, replayed = false),
            HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
        ) })
        assertFailsWith<IllegalArgumentException> { client(http).applyEmployeeWarehouseAuthority(intent(), installation) }
        http.close()
    }

    private fun intent() = EmployeeWarehouseAuthorityIntent(
        operationId = operation, organizationId = org, action = "GRANT", warehouseId = warehouse,
        employeeId = employee, employeeVersion = 4, employeeDisplayName = "Synthetic employee",
        warehouseName = "Synthetic warehouse", principalId = identity, authorityKey = "WAREHOUSE_OPERATOR",
        effectiveFrom = "2026-11-25T12:00:00Z", clientOccurredAt = "2026-11-25T12:00:05Z",
    )

    private fun preview(linkedIdentity: String = identity, employeeVersion: Long = 4) = """{
        "employeeId":"$employee","organizationId":"$org","employeeVersion":$employeeVersion,
        "linkedIdentityId":"$linkedIdentity","accountStatus":"CURRENT",
        "scope":"MANAGED_WAREHOUSES_ONLY","asOf":"2026-11-25T12:00:00Z","warehouses":[]
    }"""

    private fun receipt(current: Boolean, replayed: Boolean, responsePrincipal: String = identity) = """{
        "bindingId":"$binding","warehouseId":"$warehouse","authorityKey":"WAREHOUSE_OPERATOR",
        "principalType":"USER","principalId":"$responsePrincipal","current":$current,
        "version":4,"replayed":$replayed,"correlationId":"synthetic-correlation"
        ,"organizationId":"$org","operationId":"$operation"
    }"""

    private fun apiFailure(code: String) = HiltechApiException(
        code, "synthetic-correlation", 409, false,
        ErrorEnvelope(code, "Synthetic rejection", "synthetic-correlation", retryable = false),
        "Synthetic rejection",
    )

    private fun client(http: HttpClient) = WarehouseApiClient(HiltechApiClient(
        client = http, baseUrl = "https://synthetic.invalid", accessTokenProvider = { "synthetic-token" },
        correlationIdProvider = { "synthetic-correlation" },
    ))
}
