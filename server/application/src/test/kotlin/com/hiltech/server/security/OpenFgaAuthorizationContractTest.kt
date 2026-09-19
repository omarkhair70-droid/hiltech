package com.hiltech.server.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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
