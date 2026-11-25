package com.hiltech.shared.core.people

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingWorkspaceTest {
    private val employee = "11111111-1111-1111-1111-111111111111"
    private val org = "22222222-2222-2222-2222-222222222222"
    private val policy = "33333333-3333-3333-3333-333333333333"
    private val caseId = "44444444-4444-4444-4444-444444444444"
    private val operation = "55555555-5555-5555-5555-555555555555"
    private val installation = "66666666-6666-6666-6666-666666666666"
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun startUsesSelectedPolicyAndEmployeeVersionWithoutEnteringAnIdentifier() = runBlocking {
        val intent = intent(OnboardingWorkspaceAction.START)
        var sent = 0
        val http = HttpClient(MockEngine { request ->
            assertEquals("Bearer synthetic-token", request.headers[HttpHeaders.Authorization])
            if (request.method.value == "GET") {
                assertEquals("/v1/employees/$employee/onboarding/workspace", request.url.encodedPath)
                respond(Json.encodeToString(workspace(start = true)), HttpStatusCode.OK, headers)
            } else {
                sent++
                assertEquals("/v1/employees/$employee/onboarding", request.url.encodedPath)
                assertEquals(operation, request.headers["Idempotency-Key"])
                val body = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
                assertEquals(policy, body.getValue("onboardingPolicyRevisionId").jsonPrimitive.content)
                assertEquals("4", body.getValue("baseEmployeeVersion").jsonPrimitive.content)
                respond(Json.encodeToString(receipt(intent, version = 1)), HttpStatusCode.OK, headers)
            }
        })
        assertTrue(onboardingReceiptMatches(intent, client(http).applyWorkspaceIntent(intent, installation, false, { true }, {})))
        assertEquals(1, sent)
        http.close()
    }

    @Test
    fun lostResolutionResponseRestoresTheExactIntentAndDoesNotPreflightAnOldVersionAgain() = runBlocking {
        val original = intent().copy(resolution = "WAIVED", reason = "Synthetic reviewed exception")
        val bodies = mutableListOf<String>()
        val keys = mutableListOf<String?>()
        var previews = 0
        val http = HttpClient(MockEngine { request ->
            if (request.method.value == "GET") {
                previews++
                respond(Json.encodeToString(workspace()), HttpStatusCode.OK, headers)
            } else {
                assertEquals("/v1/onboarding/$caseId/requirements/MANUAL/resolve", request.url.encodedPath)
                bodies += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                keys += request.headers["Idempotency-Key"]
                if (bodies.size == 1) throw IllegalStateException("Synthetic lost response")
                respond(Json.encodeToString(receipt(original, replayed = true)), HttpStatusCode.OK, headers)
            }
        })
        assertFailsWith<HiltechApiException> { client(http).applyWorkspaceIntent(original, installation, false, { true }, {}) }
        val restored = Json.decodeFromString(OnboardingWorkspaceIntent.serializer(), Json.encodeToString(original))
        assertTrue(client(http).applyWorkspaceIntent(restored, installation, true, { true }, {}).replayed)
        assertEquals(1, previews)
        assertEquals(bodies[0], bodies[1])
        assertEquals<List<String?>>(listOf(operation, operation), keys)
        assertEquals(original.reason, restored.reason)
        assertEquals(6L, restored.caseVersion)
        http.close()
    }

    @Test
    fun freshCommandStopsWhenEmployeeVersionOrPolicyPermissionChanged() = runBlocking {
        for (source in listOf(workspace().copy(employeeVersion = 5),
            workspace().copy(currentCase = case().copy(requirements = listOf(requirement().copy(allowedResolutions = emptyList())))))) {
            var sends = 0
            val http = HttpClient(MockEngine { request ->
                if (request.method.value != "GET") sends++
                respond(Json.encodeToString(source), HttpStatusCode.OK, headers)
            })
            assertFailsWith<OnboardingPreparationException> {
                client(http).applyWorkspaceIntent(intent(), installation, false, { true }, {})
            }
            assertEquals(0, sends)
            http.close()
        }
    }

    @Test
    fun aWrongEmployeeOrOrganizationSnapshotNeverReachesPost() = runBlocking {
        for (source in listOf(workspace().copy(employeeId = org), workspace().copy(organizationId = employee))) {
            var sends = 0
            val http = HttpClient(MockEngine { request ->
                if (request.method.value != "GET") sends++
                respond(Json.encodeToString(source), HttpStatusCode.OK, headers)
            })
            assertFailsWith<OnboardingPreparationException> {
                client(http).applyWorkspaceIntent(intent(), installation, false, { true }, {})
            }
            assertEquals(0, sends)
            http.close()
        }
    }

    @Test
    fun activationRequiresCurrentReadySourceAndBothCapturedVersions() = runBlocking {
        val activation = intent(OnboardingWorkspaceAction.ACTIVATE)
        var posted = false
        val http = HttpClient(MockEngine { request ->
            if (request.method.value == "GET") respond(Json.encodeToString(workspace()), HttpStatusCode.OK, headers)
            else {
                posted = true
                assertEquals("/v1/onboarding/$caseId/activate", request.url.encodedPath)
                val body = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
                assertEquals("6", body.getValue("caseBaseVersion").jsonPrimitive.content)
                assertEquals("4", body.getValue("employeeBaseVersion").jsonPrimitive.content)
                respond(Json.encodeToString(receipt(activation, activated = true)), HttpStatusCode.OK, headers)
            }
        })
        assertTrue(onboardingReceiptMatches(activation, client(http).applyWorkspaceIntent(activation, installation, false, { true }, {})))
        assertTrue(posted)
        http.close()
        assertFalse(onboardingIntentIsCurrent(activation, workspace().copy(currentCase = case().copy(canActivate = false))))
    }

    @Test
    fun wrongOperationOrTargetReceiptRemainsUnconfirmed() = runBlocking {
        for (response in listOf(receipt(intent()).copy(operationId = employee),
            receipt(intent()).copy(onboarding = case(version = 7).copy(organizationId = employee)))) {
            val http = HttpClient(MockEngine { respond(Json.encodeToString(response), HttpStatusCode.OK, headers) })
            val failure = assertFailsWith<IllegalStateException> {
                client(http).applyWorkspaceIntent(intent(), installation, true, { true }, {})
            }
            assertTrue(onboardingFailureDisposition(true, failure).hasUnknownOutcome)
            http.close()
        }
    }

    @Test
    fun aLaterVersionOrAccessRejectionCannotSettleAnEarlierUnknownOutcome() {
        for (code in listOf("ONBOARDING_VERSION_CONFLICT", "EMPLOYEE_VERSION_CONFLICT", "PEOPLE_ACCESS_DENIED")) {
            val failure = error(code)
            val unknown = onboardingFailureDisposition(true, failure)
            assertTrue(unknown.hasUnknownOutcome)
            assertFalse(unknown.rejectionConfirmed)
            assertTrue(onboardingFailureDisposition(false, failure).rejectionConfirmed)
        }
        assertTrue(onboardingFailureDisposition(true, OnboardingPreparationException("SESSION_CHANGED")).hasUnknownOutcome)
    }

    @Test
    fun onlyTheConfirmedStoredFailureReleasesAnUnknownFailedOperation() {
        val result = onboardingFailureDisposition(true, error("OPERATION_PREVIOUSLY_FAILED"))
        assertFalse(result.hasUnknownOutcome)
        assertTrue(result.rejectionConfirmed)
        assertTrue(onboardingFailureDisposition(true, error("IDEMPOTENCY_KEY_REUSED")).hasUnknownOutcome)
    }

    @Test
    fun replayedActivationReceiptMayBeConfirmedAfterCurrentEmploymentChangesAgain() {
        val activation = intent(OnboardingWorkspaceAction.ACTIVATE)
        val historical = receipt(activation, activated = true, replayed = true).let {
            it.copy(onboarding = it.onboarding.copy(employeeState = "FORMER", employeeVersion = 9))
        }
        assertTrue(onboardingReceiptMatches(activation, historical))
        assertEquals("FORMER", historical.onboarding.employeeState)
    }

    @Test
    fun commandPinsOneTokenAcrossItsSourceReadAndWrite() = runBlocking {
        var token = "first-actor-token"
        var tokenReads = 0
        val http = HttpClient(MockEngine { request ->
            assertEquals("Bearer first-actor-token", request.headers[HttpHeaders.Authorization])
            if (request.method.value == "GET") {
                token = "later-token-must-not-be-used"
                respond(Json.encodeToString(workspace()), HttpStatusCode.OK, headers)
            } else respond(Json.encodeToString(receipt(intent())), HttpStatusCode.OK, headers)
        })
        submitOnboardingInSession(http, "https://synthetic.invalid", installation, intent(), false, { true },
            { tokenReads++; token }, { "synthetic-correlation" }, {})
        assertEquals(1, tokenReads)
        http.close()
    }

    @Test
    fun sessionChangeDuringSourceReadStopsTheMutation() = runBlocking {
        var active = true
        var writes = 0
        val http = HttpClient(MockEngine { request ->
            if (request.method.value != "GET") writes++
            active = false
            respond(Json.encodeToString(workspace()), HttpStatusCode.OK, headers)
        })
        assertFailsWith<OnboardingPreparationException> {
            submitOnboardingInSession(http, "https://synthetic.invalid", installation, intent(), false, { active },
                { "synthetic-token" }, { "synthetic-correlation" }, {})
        }
        assertEquals(0, writes)
        http.close()
    }

    @Test
    fun sessionChangeAfterDispatchCannotAcknowledgeIntoTheNewSession() = runBlocking {
        var active = true
        val http = HttpClient(MockEngine {
            active = false
            respond(Json.encodeToString(receipt(intent())), HttpStatusCode.OK, headers)
        })
        assertFailsWith<CancellationException> {
            submitOnboardingInSession(http, "https://synthetic.invalid", installation, intent(), true, { active },
                { "synthetic-token" }, { "synthetic-correlation" }, {})
        }
        http.close()
    }

    private fun intent(action: OnboardingWorkspaceAction = OnboardingWorkspaceAction.RESOLVE) = OnboardingWorkspaceIntent(
        operation, employee, org, "Synthetic employee", 4, policy, action, "Synthetic policy",
        caseId.takeUnless { action == OnboardingWorkspaceAction.START }, 6L.takeUnless { action == OnboardingWorkspaceAction.START },
        "MANUAL".takeIf { action == OnboardingWorkspaceAction.RESOLVE }, "Synthetic manual requirement",
        "SATISFIED".takeIf { action == OnboardingWorkspaceAction.RESOLVE },
    )

    private fun requirement() = OnboardingRequirementDto("MANUAL", "Synthetic manual requirement", "HILTECH", true,
        "SATISFIED", sortOrder = 1, allowedResolutions = listOf("SATISFIED", "WAIVED"))

    private fun case(version: Long = 6) = OnboardingCaseDto(caseId, org, employee, "SYNTH-EMP", "Synthetic employee",
        "PREBOARDING", 4, policy, "SYNTHETIC", 1, "OPEN", listOf(requirement()), true, version,
        "2026-11-25T12:00:00Z", canActivate = true)

    private fun workspace(start: Boolean = false) = OnboardingWorkspaceDto(employee, org, "Synthetic employee", "PREBOARDING", 4,
        if (start) "AVAILABLE" else "CASE_EXISTS", if (start) listOf(OnboardingPolicyOptionDto(policy, "SYNTHETIC", "Synthetic policy", 1)) else emptyList(),
        if (start) null else case())

    private fun receipt(intent: OnboardingWorkspaceIntent, version: Long = 7, activated: Boolean = false, replayed: Boolean = false) =
        OnboardingCommandResponseDto(case(version).let {
            if (activated) it.copy(state = "ACTIVATED", employeeState = "ACTIVE", employeeVersion = 5, canActivate = false) else it
        }, replayed, "synthetic-correlation", intent.operationId)

    private fun client(http: HttpClient) = OnboardingApiClient(HiltechApiClient(http, "https://synthetic.invalid",
        { "synthetic-token" }, { "synthetic-correlation" }))

    private fun error(code: String): HiltechApiException {
        val envelope = ErrorEnvelope(code, "Synthetic rejection", "synthetic-correlation", false)
        return HiltechApiException(code, envelope.correlationId, 409, false, envelope, envelope.message)
    }
}
