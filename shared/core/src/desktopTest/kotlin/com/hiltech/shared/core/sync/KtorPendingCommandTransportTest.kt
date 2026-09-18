package com.hiltech.shared.core.sync

import com.hiltech.shared.core.local.PendingCommandEntity
import com.hiltech.shared.core.local.PendingCommandStates
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KtorPendingCommandTransportTest {
    @Test
    fun frozenWorkCommandCarriesAuthIdempotencyCorrelationAndNativeMetadata() = runBlocking {
        data class CapturedRequest(
            val path: String,
            val authorization: String?,
            val idempotencyKey: String?,
            val correlationId: String?,
            val platform: String?,
            val clientVersion: String?,
            val installationId: String?,
            val traceParent: String?,
            val body: String,
        )

        var captured: CapturedRequest? = null

        val engine = MockEngine { request ->
            val body = (request.body as TextContent).text
            captured = CapturedRequest(
                path = request.url.encodedPath,
                authorization = request.headers[HttpHeaders.Authorization],
                idempotencyKey = request.headers["Idempotency-Key"],
                correlationId = request.headers["X-Correlation-Id"],
                platform = request.headers["X-Client-Platform"],
                clientVersion = request.headers["X-Client-Version"],
                installationId = request.headers["X-Device-Installation-Id"],
                traceParent = request.headers["traceparent"],
                body = body,
            )

            println(
                "HILTECH_KTOR_CAPTURE path=${request.url.encodedPath} " +
                    "headers=${request.headers.entries()} body=$body",
            )

            respond(
                content = """{"workOrderId":"work-42","version":8,"correlationId":"server-corr","duplicateReplay":false}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val result = transport(engine).execute(
            command(
                operationId = "op-42",
                commandType = "StartWork",
                targetType = "WorkOrder",
                targetId = "work-42",
                payloadJson = """{"localSiteSessionRef":"site-session"}""",
            ),
        )

        val request = assertNotNull(captured)
        assertEquals("/v1/work-orders/work-42/start", request.path, "Frozen StartWork route drifted.")
        assertEquals("Bearer access-token", request.authorization, "Bearer token header drifted.")
        assertEquals("op-42", request.idempotencyKey, "Idempotency-Key must equal operationId.")
        assertEquals("corr-42", request.correlationId, "Correlation header drifted.")
        assertEquals("android", request.platform, "Native platform header drifted.")
        assertEquals("0.1.0", request.clientVersion, "Native version header drifted.")
        assertEquals("device-42", request.installationId, "Installation identity header drifted.")
        assertEquals("00-trace-parent", request.traceParent, "W3C traceparent header drifted.")

        val jsonBody = Json.parseToJsonElement(request.body).jsonObject
        assertEquals("op-42", jsonBody["operationId"]?.jsonPrimitive?.content)
        assertEquals("7", jsonBody["baseVersion"]?.jsonPrimitive?.content)
        assertEquals("2023-11-14T22:13:20Z", jsonBody["clientOccurredAt"]?.jsonPrimitive?.content)
        assertEquals("site-session", jsonBody["localSiteSessionRef"]?.jsonPrimitive?.content)

        assertEquals(
            CommandTransportResult.Applied(
                currentVersion = 8,
                correlationId = "server-corr",
                duplicateReplay = false,
            ),
            result,
        )
    }

    @Test
    fun versionConflictMapsToTypedConflictWithoutDiscardingRecoveryData() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """
                    {
                      "code":"VERSION_CONFLICT",
                      "message":"Version changed",
                      "correlationId":"conflict-corr",
                      "retryable":false,
                      "currentVersion":12,
                      "conflict":{
                        "conflictType":"STALE_ASSIGNMENT",
                        "targetType":"WorkOrder",
                        "targetId":"work-42",
                        "attemptedBaseVersion":7,
                        "currentVersion":12,
                        "safeCurrentState":{"state":"REASSIGNED"},
                        "localWorkSafe":true,
                        "allowedRecoveryActions":["REVIEW_LOCAL_WORK","DISCARD_COMMAND"]
                      }
                    }
                """.trimIndent(),
                status = HttpStatusCode.Conflict,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val result = transport(engine).execute(
            command(
                operationId = "op-conflict",
                commandType = "SubmitWorkCompletion",
                targetType = "WorkOrder",
                targetId = "work-42",
            ),
        )

        val conflict = result as CommandTransportResult.Conflict
        assertEquals("STALE_ASSIGNMENT", conflict.conflictType)
        assertEquals(12, conflict.currentVersion)
        assertTrue(conflict.safeServerStateJson.contains("REASSIGNED"))
        assertTrue(conflict.allowedRecoveryActionsJson.contains("REVIEW_LOCAL_WORK"))
    }

    @Test
    fun serviceUnavailableUsesRetryAfterAndReauthStopsAutomaticReplay() = runBlocking {
        var call = 0
        val engine = MockEngine {
            call += 1

            if (call == 1) {
                respond(
                    content = """{"code":"FAILED_RETRYABLE","message":"Unavailable","correlationId":"retry-corr","retryable":true}""",
                    status = HttpStatusCode.ServiceUnavailable,
                    headers = Headers.build {
                        append(HttpHeaders.ContentType, "application/json")
                        append(HttpHeaders.RetryAfter, "7")
                    },
                )
            } else {
                respond(
                    content = """{"code":"REAUTH_REQUIRED","message":"Re-authenticate","correlationId":"reauth-corr","retryable":false}""",
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }

        val transport = transport(engine)

        val retryable = transport.execute(
            command(
                operationId = "op-retry",
                commandType = "ResumeWork",
                targetType = "WorkOrder",
                targetId = "work-42",
            ),
        ) as CommandTransportResult.Retryable

        assertEquals(7_000L, retryable.retryAfterMs)
        assertEquals("FAILED_RETRYABLE", retryable.code)

        val reauth = transport.execute(
            command(
                operationId = "op-reauth",
                commandType = "StartWork",
                targetType = "WorkOrder",
                targetId = "work-42",
            ),
        )

        assertEquals(
            CommandTransportResult.Terminal(
                code = "REAUTH_REQUIRED",
                correlationId = "reauth-corr",
                currentVersion = null,
            ),
            reauth,
        )
    }

    @Test
    fun unknownOfflineCommandIsNeverSentAsGenericCrud() = runBlocking {
        var networkCalled = false
        val engine = MockEngine {
            networkCalled = true
            error("Network must not be called.")
        }

        val result = transport(engine).execute(
            command(
                operationId = "op-unknown",
                commandType = "ArbitraryPatch",
                targetType = "WorkOrder",
                targetId = "work-42",
            ),
        )

        assertEquals(
            CommandTransportResult.Terminal(
                code = "UNSUPPORTED_OFFLINE_COMMAND",
            ),
            result,
        )
        assertEquals(false, networkCalled)
    }

    private fun transport(engine: MockEngine): KtorPendingCommandTransport =
        KtorPendingCommandTransport(
            client = HttpClient(engine),
            baseUrl = "https://hiltech.example",
            tokenProvider = AccessTokenProvider { "access-token" },
            correlationIdProvider = CorrelationIdProvider { "corr-42" },
            traceParentProvider = TraceParentProvider { "00-trace-parent" },
            clientMetadata = NativeClientMetadata(
                platform = "android",
                version = "0.1.0",
                installationId = "device-42",
            ),
        )

    private fun command(
        operationId: String,
        commandType: String,
        targetType: String,
        targetId: String,
        payloadJson: String = "{}",
    ): PendingCommandEntity =
        PendingCommandEntity(
            operationId = operationId,
            actorUserIdentityId = "user-42",
            deviceId = "device-42",
            commandType = commandType,
            targetType = targetType,
            targetId = targetId,
            baseVersion = 7,
            payloadVersion = 1,
            payloadJson = payloadJson,
            clientOccurredAtEpochMs = 1_700_000_000_000,
            enqueuedAtEpochMs = 1_700_000_000_100,
            localSequence = 1,
            state = PendingCommandStates.PENDING,
            retryCount = 0,
            nextRetryAtEpochMs = null,
            lastAttemptAtEpochMs = null,
            lastResultCode = null,
            lastServerVersion = null,
            lastCorrelationId = null,
            contractVersion = 1,
            policyBindingRef = "binding:v1",
            updatedAtEpochMs = 1_700_000_000_100,
        )
}
