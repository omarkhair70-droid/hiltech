package com.hiltech.shared.core.sync

import com.hiltech.shared.core.local.PendingCommandEntity
import com.hiltech.shared.core.network.ConflictPayload
import com.hiltech.shared.core.network.ErrorEnvelope
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

fun interface AccessTokenProvider {
    suspend fun accessToken(): String?
}

fun interface CorrelationIdProvider {
    fun correlationId(): String
}

fun interface TraceParentProvider {
    fun traceParent(): String?
}

data class NativeClientMetadata(
    val platform: String,
    val version: String,
    val installationId: String,
)

object FrozenOfflineCommandRoutes {
    fun resolve(command: PendingCommandEntity): String? =
        when (command.commandType) {
            "StartWork" -> "/v1/work-orders/${command.targetId}/start"
            "BlockWork" -> "/v1/work-orders/${command.targetId}/block"
            "ResumeWork" -> "/v1/work-orders/${command.targetId}/resume"
            "SubmitWorkCompletion" -> "/v1/work-orders/${command.targetId}/submit-completion"
            "ReportAssetDamage" -> "/v1/assets/${command.targetId}/report-damage"
            "FinalizeEvidence" -> "/v1/evidence/${command.targetId}/finalize"
            else -> null
        }
}

class KtorPendingCommandTransport(
    private val client: HttpClient,
    baseUrl: String,
    private val tokenProvider: AccessTokenProvider,
    private val correlationIdProvider: CorrelationIdProvider,
    private val traceParentProvider: TraceParentProvider,
    private val clientMetadata: NativeClientMetadata,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) : PendingCommandTransport {
    private val normalizedBaseUrl = baseUrl.trimEnd('/')

    override suspend fun execute(command: PendingCommandEntity): CommandTransportResult {
        val route = FrozenOfflineCommandRoutes.resolve(command)
            ?: return CommandTransportResult.Terminal(
                code = "UNSUPPORTED_OFFLINE_COMMAND",
            )

        val correlationId = correlationIdProvider.correlationId()
        val accessToken = tokenProvider.accessToken()
            ?.takeIf { it.isNotBlank() }
            ?: return CommandTransportResult.Terminal(
                code = "REAUTH_REQUIRED",
                correlationId = correlationId,
            )

        val response = client.request(normalizedBaseUrl + route) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("Idempotency-Key", command.operationId)
            header("X-Correlation-Id", correlationId)
            header("X-Client-Platform", clientMetadata.platform)
            header("X-Client-Version", clientMetadata.version)
            header("X-Device-Installation-Id", clientMetadata.installationId)
            traceParentProvider.traceParent()?.let { header("traceparent", it) }
            setBody(buildCommandBody(command))
        }

        val body = response.bodyAsText()
        val status = response.status.value

        if (status in 200..299) {
            return parseApplied(body, correlationId)
        }

        val error = parseError(body, correlationId)

        if (status == 409 && error.conflict != null) {
            return error.conflict.toTransportConflict(
                fallbackCorrelationId = error.correlationId,
                fallbackCurrentVersion = error.currentVersion,
            )
        }

        if (status == 429 || status >= 500 || error.retryable || error.code == "FAILED_RETRYABLE") {
            return CommandTransportResult.Retryable(
                code = error.code,
                correlationId = error.correlationId,
                currentVersion = error.currentVersion,
                retryAfterMs = parseRetryAfterMs(response.headers[HttpHeaders.RetryAfter]),
            )
        }

        return CommandTransportResult.Terminal(
            code = error.code,
            correlationId = error.correlationId,
            currentVersion = error.currentVersion,
        )
    }

    private fun buildCommandBody(command: PendingCommandEntity): String {
        val payload = json.parseToJsonElement(command.payloadJson).jsonObject

        val merged = buildJsonObject {
            payload.forEach { (key, value) -> put(key, value) }
            put("operationId", JsonPrimitive(command.operationId))
            if (command.baseVersion != null) {
                put("baseVersion", JsonPrimitive(command.baseVersion))
            } else {
                put("baseVersion", JsonNull)
            }
            put(
                "clientOccurredAt",
                JsonPrimitive(
                    Instant.fromEpochMilliseconds(command.clientOccurredAtEpochMs).toString(),
                ),
            )
        }

        return merged.toString()
    }

    private fun parseApplied(
        body: String,
        fallbackCorrelationId: String,
    ): CommandTransportResult.Applied {
        val objectBody = body.toJsonObjectOrNull()

        return CommandTransportResult.Applied(
            currentVersion = objectBody?.longValue(
                "version",
                "workOrderVersion",
                "assetVersion",
                "currentVersion",
            ),
            correlationId = objectBody?.stringValue("correlationId") ?: fallbackCorrelationId,
            duplicateReplay = objectBody?.get("duplicateReplay")
                ?.jsonPrimitive
                ?.booleanOrNull
                ?: false,
        )
    }

    private fun parseError(
        body: String,
        fallbackCorrelationId: String,
    ): ErrorEnvelope {
        if (body.isBlank()) {
            return ErrorEnvelope(
                code = "HTTP_ERROR",
                message = "Request failed.",
                correlationId = fallbackCorrelationId,
            )
        }

        return runCatching {
            json.decodeFromString<ErrorEnvelope>(body)
        }.getOrElse {
            ErrorEnvelope(
                code = "HTTP_ERROR",
                message = "Request failed.",
                correlationId = fallbackCorrelationId,
            )
        }
    }

    private fun String.toJsonObjectOrNull(): JsonObject? =
        runCatching { json.parseToJsonElement(this).jsonObject }.getOrNull()

    private fun JsonObject.stringValue(key: String): String? =
        get(key)?.jsonPrimitive?.content

    private fun JsonObject.longValue(vararg keys: String): Long? =
        keys.firstNotNullOfOrNull { key ->
            get(key)?.jsonPrimitive?.longOrNull
        }

    private fun ConflictPayload.toTransportConflict(
        fallbackCorrelationId: String?,
        fallbackCurrentVersion: Long?,
    ): CommandTransportResult.Conflict =
        CommandTransportResult.Conflict(
            conflictType = conflictType,
            currentVersion = currentVersion ?: fallbackCurrentVersion,
            safeServerStateJson = safeCurrentState?.toString() ?: "{}",
            allowedRecoveryActionsJson = json.encodeToString(
                allowedRecoveryActions,
            ),
            correlationId = fallbackCorrelationId,
        )

    private fun parseRetryAfterMs(value: String?): Long? {
        val seconds = value?.trim()?.toLongOrNull() ?: return null
        return seconds.coerceAtLeast(0L) * 1_000L
    }
}
