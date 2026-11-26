package com.hiltech.server.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class OpenFgaAuthorizationContractTest {
    private val tuple = OpenFgaTuple(
        subjectType = "user",
        subjectId = "tech-42",
        relation = "can_start",
        objectType = "work_order",
        objectId = "wo-42",
    )

    @Test
    fun enabledOpenFgaRequiresApiStoreAndPinnedModel() {
        assertThrows<IllegalArgumentException> {
            HiltechOpenFgaProperties(
                enabled = true,
                apiUrl = "http://openfga",
                storeId = "store-1",
                authorizationModelId = "",
            ).validateEnabledConfiguration()
        }
    }

    @Test
    fun checkPinsAuthorizationModelAndFailsClosed() {
        val transport = CapturingTransport(
            responses = ArrayDeque(
                listOf(
                    OpenFgaHttpResponse(200, """{"allowed":true}"""),
                    OpenFgaHttpResponse(503, """{}"""),
                    OpenFgaHttpResponse(200, """{"unexpected":true}"""),
                ),
            ),
        )
        val gateway = gateway(transport)

        assertTrue(gateway.isAllowed(tuple))
        assertFalse(gateway.isAllowed(tuple))
        assertFalse(gateway.isAllowed(tuple))

        val request = Json.parseToJsonElement(
            transport.requests.first().second,
        ).jsonObject

        assertEquals(
            "model-locked",
            request["authorization_model_id"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "user:tech-42",
            request["tuple_key"]
                ?.jsonObject
                ?.get("user")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun writesAndDeletesArePinnedAndIdempotent() {
        val transport = CapturingTransport(
            responses = ArrayDeque(
                listOf(
                    OpenFgaHttpResponse(200, "{}"),
                    OpenFgaHttpResponse(200, "{}"),
                ),
            ),
        )
        val gateway = gateway(transport)

        assertEquals(
            OpenFgaMutationResult.Applied,
            gateway.apply(tuple, AuthorizationDesiredState.PRESENT),
        )
        assertEquals(
            OpenFgaMutationResult.Applied,
            gateway.apply(tuple, AuthorizationDesiredState.ABSENT),
        )

        val write = Json.parseToJsonElement(
            transport.requests[0].second,
        ).jsonObject
        val delete = Json.parseToJsonElement(
            transport.requests[1].second,
        ).jsonObject

        assertEquals(
            "model-locked",
            write["authorization_model_id"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "ignore",
            write["writes"]
                ?.jsonObject
                ?.get("on_duplicate")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "ignore",
            delete["deletes"]
                ?.jsonObject
                ?.get("on_missing")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun localProjectionGuardDeniesBeforeOpenFgaIsConsulted() {
        var transportCalled = false
        val gateway = gateway(
            OpenFgaHttpTransport { _, _ ->
                transportCalled = true
                OpenFgaHttpResponse(200, """{"allowed":true}""")
            },
        )

        val adapter = FailClosedAuthorizationAdapter(
            guard = AuthorizationProjectionGuardPort {
                ProjectionGuardDecision.DENY_FAIL_CLOSED
            },
            openFgaGateway = gateway,
        )

        assertFalse(
            adapter.isAllowed(
                AuthorizationCheckRequest(tuple),
            ),
        )
        assertFalse(transportCalled)
    }

    @Test
    fun presharedModeRejectsMissingKeyAndUnsupportedMethod() {
        val base = HiltechOpenFgaProperties(
            enabled = true,
            apiUrl = "http://localhost:8080",
            storeId = "store-locked",
            authorizationModelId = "model-locked",
        )
        assertThrows<IllegalArgumentException> {
            base.copy(authnMethod = "preshared").validateEnabledConfiguration()
        }
        assertThrows<IllegalArgumentException> {
            base.copy(authnMethod = "typo", presharedKey = "test-only").validateEnabledConfiguration()
        }
        assertThrows<IllegalArgumentException> {
            base.copy(authnMethod = "none", presharedKey = "test-only").validateEnabledConfiguration()
        }
    }

    @Test
    fun jdkTransportSendsPresharedBearerHeaderWithoutChangingPayload() {
        val authorization = AtomicReference<String?>()
        val requestBody = AtomicReference<String?>()
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.createContext("/stores/store-locked/check") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            requestBody.set(exchange.requestBody.bufferedReader().use { it.readText() })
            val responseBytes = """{"allowed":true}""".toByteArray()
            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }
        httpServer.start()
        try {
            val properties = HiltechOpenFgaProperties(
                enabled = true,
                apiUrl = "http://127.0.0.1:${httpServer.address.port}",
                storeId = "store-locked",
                authorizationModelId = "model-locked",
                authnMethod = "preshared",
                presharedKey = "staging-test-only",
            )
            val response = JdkOpenFgaHttpTransport(properties).post(
                path = "/stores/store-locked/check",
                body = "{}",
            )
            assertEquals(200, response.statusCode)
            assertEquals("Bearer staging-test-only", authorization.get())
            assertEquals("{}", requestBody.get())
        } finally {
            httpServer.stop(0)
        }
    }

    @Test
    fun jdkTransportOmitsAuthorizationWhenNoneSelected() {
        val authorization = AtomicReference<String?>()
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.createContext("/test") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            exchange.requestBody.use { it.readAllBytes() }
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        httpServer.start()
        try {
            val properties = HiltechOpenFgaProperties(
                enabled = true,
                apiUrl = "http://127.0.0.1:${httpServer.address.port}",
                storeId = "store-locked",
                authorizationModelId = "model-locked",
                authnMethod = "none",
            )
            assertEquals(204, JdkOpenFgaHttpTransport(properties).post("/test", "{}").statusCode)
            assertEquals(null, authorization.get())
        } finally {
            httpServer.stop(0)
        }
    }

    private fun gateway(
        transport: OpenFgaHttpTransport,
    ): OpenFgaGateway =
        OpenFgaGateway(
            properties = HiltechOpenFgaProperties(
                enabled = true,
                apiUrl = "http://openfga:8080",
                storeId = "store-locked",
                authorizationModelId = "model-locked",
            ),
            transport = transport,
        )

    private class CapturingTransport(
        private val responses: ArrayDeque<OpenFgaHttpResponse>,
    ) : OpenFgaHttpTransport {
        val requests = mutableListOf<Pair<String, String>>()

        override fun post(
            path: String,
            body: String,
        ): OpenFgaHttpResponse {
            requests += path to body
            return responses.removeFirst()
        }
    }
}
