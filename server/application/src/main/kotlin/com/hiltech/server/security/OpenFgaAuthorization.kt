package com.hiltech.server.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@ConfigurationProperties(prefix = "hiltech.authorization.openfga")
data class HiltechOpenFgaProperties(
    var enabled: Boolean = false,
    var apiUrl: String = "",
    var storeId: String = "",
    var authorizationModelId: String = "",
    var requestTimeoutMs: Long = 2_000,
    var projectorPollDelayMs: Long = 1_000,
    var claimLeaseSeconds: Long = 60,
) {
    fun validateEnabledConfiguration() {
        if (!enabled) {
            return
        }

        require(apiUrl.isNotBlank()) {
            "hiltech.authorization.openfga.api-url must be configured when OpenFGA is enabled."
        }
        require(storeId.isNotBlank()) {
            "hiltech.authorization.openfga.store-id must be configured when OpenFGA is enabled."
        }
        require(authorizationModelId.isNotBlank()) {
            "hiltech.authorization.openfga.authorization-model-id must be configured when OpenFGA is enabled."
        }
        require(requestTimeoutMs in 1..10_000) {
            "hiltech.authorization.openfga.request-timeout-ms must be between 1 and 10000."
        }
        require(claimLeaseSeconds in 5..3_600) {
            "hiltech.authorization.openfga.claim-lease-seconds must be between 5 and 3600."
        }
    }
}

data class OpenFgaTuple(
    val subjectType: String,
    val subjectId: String,
    val relation: String,
    val objectType: String,
    val objectId: String,
) {
    init {
        require(subjectType.isNotBlank())
        require(subjectId.isNotBlank())
        require(relation.isNotBlank())
        require(objectType.isNotBlank())
        require(objectId.isNotBlank())
    }

    val userRef: String
        get() = "$subjectType:$subjectId"

    val objectRef: String
        get() = "$objectType:$objectId"

    val relationKey: String
        get() = "$userRef#$relation@$objectRef"
}

data class AuthorizationCheckRequest(
    val checkTuple: OpenFgaTuple,
    val failClosedGuardTuples: List<OpenFgaTuple> = listOf(checkTuple),
)

fun interface AuthorizationCheckPort {
    fun isAllowed(request: AuthorizationCheckRequest): Boolean
}

enum class AuthorizationDesiredState {
    PRESENT,
    ABSENT,
}

sealed interface OpenFgaMutationResult {
    data object Applied : OpenFgaMutationResult

    data class Retryable(
        val code: String,
        val httpStatus: Int? = null,
    ) : OpenFgaMutationResult

    data class PermanentFailure(
        val code: String,
        val httpStatus: Int? = null,
    ) : OpenFgaMutationResult
}

interface OpenFgaProjectionPort {
    fun apply(
        tuple: OpenFgaTuple,
        desiredState: AuthorizationDesiredState,
    ): OpenFgaMutationResult
}

data class OpenFgaHttpResponse(
    val statusCode: Int,
    val body: String,
)

fun interface OpenFgaHttpTransport {
    fun post(
        path: String,
        body: String,
    ): OpenFgaHttpResponse
}

class JdkOpenFgaHttpTransport(
    properties: HiltechOpenFgaProperties,
) : OpenFgaHttpTransport {
    private val baseUrl = properties.apiUrl.trimEnd('/')
    private val timeout = Duration.ofMillis(properties.requestTimeoutMs)
    private val client = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .build()

    override fun post(
        path: String,
        body: String,
    ): OpenFgaHttpResponse {
        val request = HttpRequest.newBuilder(
            URI.create(baseUrl + path),
        )
            .timeout(timeout)
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = client.send(
            request,
            HttpResponse.BodyHandlers.ofString(),
        )

        return OpenFgaHttpResponse(
            statusCode = response.statusCode(),
            body = response.body(),
        )
    }
}

class OpenFgaGateway(
    private val properties: HiltechOpenFgaProperties,
    private val transport: OpenFgaHttpTransport,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) : OpenFgaProjectionPort {
    init {
        properties.validateEnabledConfiguration()
    }

    fun isAllowed(tuple: OpenFgaTuple): Boolean =
        runCatching {
            val response = transport.post(
                path = "/stores/${properties.storeId}/check",
                body = checkBody(tuple),
            )

            if (response.statusCode !in 200..299) {
                return@runCatching false
            }

            json.parseToJsonElement(response.body)
                .jsonObject["allowed"]
                ?.jsonPrimitive
                ?.booleanOrNull
                ?: false
        }.getOrElse { failure ->
            if (failure is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            false
        }

    override fun apply(
        tuple: OpenFgaTuple,
        desiredState: AuthorizationDesiredState,
    ): OpenFgaMutationResult =
        try {
            val response = transport.post(
                path = "/stores/${properties.storeId}/write",
                body = mutationBody(tuple, desiredState),
            )

            when {
                response.statusCode in 200..299 -> OpenFgaMutationResult.Applied
                response.statusCode == 408 ||
                    response.statusCode == 425 ||
                    response.statusCode == 429 ||
                    response.statusCode >= 500 ->
                    OpenFgaMutationResult.Retryable(
                        code = "OPENFGA_HTTP_RETRYABLE",
                        httpStatus = response.statusCode,
                    )

                else -> OpenFgaMutationResult.PermanentFailure(
                    code = "OPENFGA_HTTP_PERMANENT",
                    httpStatus = response.statusCode,
                )
            }
        } catch (failure: Throwable) {
            if (failure is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            OpenFgaMutationResult.Retryable(
                code = "OPENFGA_TRANSPORT_FAILURE",
            )
        }

    private fun checkBody(tuple: OpenFgaTuple): String =
        buildJsonObject {
            putJsonObject("tuple_key") {
                put("user", tuple.userRef)
                put("relation", tuple.relation)
                put("object", tuple.objectRef)
            }
            put("authorization_model_id", properties.authorizationModelId)
        }.toString()

    private fun mutationBody(
        tuple: OpenFgaTuple,
        desiredState: AuthorizationDesiredState,
    ): String =
        buildJsonObject {
            when (desiredState) {
                AuthorizationDesiredState.PRESENT -> {
                    putJsonObject("writes") {
                        putJsonArray("tuple_keys") {
                            add(
                                buildJsonObject {
                                    put("user", tuple.userRef)
                                    put("relation", tuple.relation)
                                    put("object", tuple.objectRef)
                                },
                            )
                        }
                        put("on_duplicate", "ignore")
                    }
                }

                AuthorizationDesiredState.ABSENT -> {
                    putJsonObject("deletes") {
                        putJsonArray("tuple_keys") {
                            add(
                                buildJsonObject {
                                    put("user", tuple.userRef)
                                    put("relation", tuple.relation)
                                    put("object", tuple.objectRef)
                                },
                            )
                        }
                        put("on_missing", "ignore")
                    }
                }
            }
            put("authorization_model_id", properties.authorizationModelId)
        }.toString()
}

enum class ProjectionGuardDecision {
    PROCEED_TO_OPENFGA,
    DENY_FAIL_CLOSED,
}

fun interface AuthorizationProjectionGuardPort {
    fun evaluate(tuple: OpenFgaTuple): ProjectionGuardDecision
}

class FailClosedAuthorizationAdapter(
    private val guard: AuthorizationProjectionGuardPort,
    private val openFgaGateway: OpenFgaGateway,
) : AuthorizationCheckPort {
    override fun isAllowed(request: AuthorizationCheckRequest): Boolean =
        runCatching {
            if (
                request.failClosedGuardTuples.any {
                    guard.evaluate(it) == ProjectionGuardDecision.DENY_FAIL_CLOSED
                }
            ) {
                return@runCatching false
            }

            openFgaGateway.isAllowed(request.checkTuple)
        }.getOrDefault(false)
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HiltechOpenFgaProperties::class)
class OpenFgaAuthorizationConfiguration {
    @Bean
    @ConditionalOnProperty(
        prefix = "hiltech.authorization.openfga",
        name = ["enabled"],
        havingValue = "true",
    )
    fun openFgaHttpTransport(
        properties: HiltechOpenFgaProperties,
    ): OpenFgaHttpTransport =
        JdkOpenFgaHttpTransport(properties)

    @Bean
    @ConditionalOnProperty(
        prefix = "hiltech.authorization.openfga",
        name = ["enabled"],
        havingValue = "true",
    )
    fun openFgaGateway(
        properties: HiltechOpenFgaProperties,
        transport: OpenFgaHttpTransport,
    ): OpenFgaGateway =
        OpenFgaGateway(
            properties = properties,
            transport = transport,
        )

    @Bean
    @ConditionalOnProperty(
        prefix = "hiltech.authorization.openfga",
        name = ["enabled"],
        havingValue = "true",
    )
    fun authorizationCheckPort(
        guard: AuthorizationProjectionGuardPort,
        gateway: OpenFgaGateway,
    ): AuthorizationCheckPort =
        FailClosedAuthorizationAdapter(
            guard = guard,
            openFgaGateway = gateway,
        )
}
