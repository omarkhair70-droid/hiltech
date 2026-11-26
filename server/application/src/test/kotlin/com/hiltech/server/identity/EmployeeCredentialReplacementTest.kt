package com.hiltech.server.identity

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Loopback synthetic HTTP provider only; this test cannot issue credentials to a real identity service. */
class EmployeeCredentialReplacementTest {
    private val issuer = "https://identity.example.test/realms/synthetic"

    @Test
    fun enabledExistingUserReceivesOnlyATemporaryPasswordReset() = provider { f ->
        val result = f.client.replaceCurrentPassword(f.subject) as EmployeeCredentialReplacementResult.Issued
        assertEquals(listOf("POST /realms/synthetic/protocol/openid-connect/token", "GET /admin/realms/synthetic/users/${f.subject}",
            "PUT /admin/realms/synthetic/users/${f.subject}/reset-password"), f.calls)
        val body = Json.parseToJsonElement(f.passwordBodies.single()).jsonObject
        assertEquals(setOf("type", "value", "temporary"), body.keys)
        assertEquals("password", body.getValue("type").jsonPrimitive.content)
        assertTrue(body.getValue("temporary").jsonPrimitive.boolean)
        assertEquals(result.temporaryCredential, body.getValue("value").jsonPrimitive.content)
        assertTrue(result.temporaryCredential.length >= 24)
        assertFalse(result.toString().contains(result.temporaryCredential))
    }

    @Test
    fun disabledProviderUserIsNeverEnabledOrReset() = provider { f ->
        f.user = """{"id":"${f.subject}","enabled":false}"""
        val result = f.client.replaceCurrentPassword(f.subject) as EmployeeCredentialReplacementResult.Rejected
        assertEquals("ACCOUNT_CREDENTIAL_PROVIDER_ACCOUNT_RESTRICTED", result.safeCode)
        assertEquals(2, f.calls.size)
        assertTrue(f.passwordBodies.isEmpty())
        assertFalse(f.calls.any { it.startsWith("PUT") })
    }

    @Test
    fun differentProviderSubjectCannotReceiveAReset() = provider { f ->
        f.user = """{"id":"different-synthetic-user","enabled":true}"""
        val result = f.client.replaceCurrentPassword(f.subject) as EmployeeCredentialReplacementResult.Rejected
        assertEquals("ACCOUNT_CREDENTIAL_PROVIDER_ACCOUNT_RESTRICTED", result.safeCode)
        assertTrue(f.passwordBodies.isEmpty())
    }

    @Test
    fun missingEnabledStateDoesNotAssumeTheUserIsActive() = provider { f ->
        f.user = """{"id":"${f.subject}"}"""
        assertTrue(f.client.replaceCurrentPassword(f.subject) is EmployeeCredentialReplacementResult.Rejected)
        assertTrue(f.passwordBodies.isEmpty())
    }

    @Test
    fun failedUserReadRejectsBeforePasswordDispatch() = provider { f ->
        f.userStatus = 503
        val result = f.client.replaceCurrentPassword(f.subject) as EmployeeCredentialReplacementResult.Rejected
        assertEquals("ACCOUNT_CREDENTIAL_PROVIDER_UNAVAILABLE", result.safeCode)
        assertTrue(f.passwordBodies.isEmpty())
    }

    @Test
    fun failedResetResponseIsAnUnconfirmedOutcome() = provider { f ->
        f.resetStatus = 503
        val result = f.client.replaceCurrentPassword(f.subject) as EmployeeCredentialReplacementResult.Unconfirmed
        assertEquals("ACCOUNT_CREDENTIAL_PROVIDER_OUTCOME_UNCONFIRMED", result.safeCode)
        assertEquals(1, f.passwordBodies.size)
        assertEquals(3, f.calls.size)
        assertFalse(result.toString().contains(Json.parseToJsonElement(f.passwordBodies.single()).jsonObject.getValue("value").jsonPrimitive.content))
    }

    @Test
    fun throttledResetIsNotAutomaticallyDispatchedAgain() = provider { f ->
        f.resetStatus = 429
        assertTrue(f.client.replaceCurrentPassword(f.subject) is EmployeeCredentialReplacementResult.Unconfirmed)
        assertEquals(1, f.passwordBodies.size)
        assertEquals(3, f.calls.size)
    }

    @Test
    fun lostResetResponseIsNotAutomaticallyDispatchedAgain() = provider { f ->
        f.dropResetResponse = true
        assertTrue(f.client.replaceCurrentPassword(f.subject) is EmployeeCredentialReplacementResult.Unconfirmed)
        assertEquals(1, f.passwordBodies.size)
        assertEquals(3, f.calls.size)
    }

    @Test
    fun resetRedirectIsNotFollowedOrReportedAsSuccessful() = provider { f ->
        f.resetStatus = 307
        assertTrue(f.client.replaceCurrentPassword(f.subject) is EmployeeCredentialReplacementResult.Unconfirmed)
        assertEquals(1, f.passwordBodies.size)
        assertEquals(3, f.calls.size)
    }

    @Test
    fun facadeAcceptsOnlyTheConfiguredIssuerAndNeverUsesAnArbitraryProvider() {
        var calls = 0
        val beans = DefaultListableBeanFactory()
        beans.registerSingleton("syntheticGateway", object : KeycloakCredentialReplacementGateway {
            override fun replaceCurrentPassword(subject: String): EmployeeCredentialReplacementResult {
                calls += 1
                return EmployeeCredentialReplacementResult.Issued("synthetic-password")
            }
        })
        val oidc = HiltechOidcProperties(enabled = true, issuerUri = issuer)
        val config = KeycloakProvisioningProperties(enabled = true)
        val facade = KeycloakEmployeeCredentialReplacement(oidc, config, beans.getBeanProvider(KeycloakCredentialReplacementGateway::class.java))
        assertTrue(facade.availableFor(issuer))
        assertTrue(facade.availableFor("$issuer/"))
        assertFalse(facade.availableFor("https://unrelated.example.test/realms/synthetic"))
        assertTrue(facade.replace("https://unrelated.example.test/realms/synthetic", "synthetic-subject") is EmployeeCredentialReplacementResult.Rejected)
        assertTrue(facade.replace(issuer, " ") is EmployeeCredentialReplacementResult.Rejected)
        assertEquals(0, calls)
        config.enabled = false
        assertFalse(facade.availableFor(issuer))
        assertTrue(facade.replace(issuer, "synthetic-subject") is EmployeeCredentialReplacementResult.Rejected)
        assertEquals(0, calls)
    }

    @Test
    fun missingConfiguredProviderIsExplicitlyUnavailable() {
        val facade = KeycloakEmployeeCredentialReplacement(HiltechOidcProperties(enabled = true, issuerUri = issuer),
            KeycloakProvisioningProperties(enabled = true), DefaultListableBeanFactory().getBeanProvider(KeycloakCredentialReplacementGateway::class.java))
        assertFalse(facade.availableFor(issuer))
        assertTrue(facade.replace(issuer, "synthetic-subject") is EmployeeCredentialReplacementResult.Rejected)
    }

    private fun provider(action: (Fixture) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val f = Fixture(server)
        try {
            server.start()
            action(f)
        } finally { f.client.close(); server.stop(0) }
    }
    private inner class Fixture(server: HttpServer) {
        val subject = "synthetic-current-user"
        val calls = CopyOnWriteArrayList<String>()
        val passwordBodies = CopyOnWriteArrayList<String>()
        var user = """{"id":"$subject","enabled":true}"""
        var userStatus = 200
        var resetStatus = 204
        var dropResetResponse = false
        val client = KeycloakIdentityAdminClient(KeycloakProvisioningProperties(enabled = true, adminClientId = "synthetic-client",
            adminClientSecret = "synthetic-secret", adminBaseUri = "http://localhost:${server.address.port}"),
            HiltechOidcProperties(enabled = true, issuerUri = issuer))
        init {
            server.createContext("/") { exchange ->
                val path = exchange.requestURI.path
                calls += "${exchange.requestMethod} $path"
                val request = exchange.requestBody.use { it.readAllBytes().toString(Charsets.UTF_8) }
                val (status, body) = when {
                    path == "/realms/synthetic/protocol/openid-connect/token" && exchange.requestMethod == "POST" -> 200 to """{"access_token":"synthetic-admin-token"}"""
                    path == "/admin/realms/synthetic/users/$subject" && exchange.requestMethod == "GET" -> userStatus to user
                    path == "/admin/realms/synthetic/users/$subject/reset-password" && exchange.requestMethod == "PUT" -> {
                        passwordBodies += request
                        if (dropResetResponse) {
                            exchange.close()
                            return@createContext
                        }
                        if (resetStatus == 307) exchange.responseHeaders.add("Location", "/unexpected-reset-redirect")
                        resetStatus to ""
                    }
                    else -> 404 to ""
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, if (status == 204) -1 else bytes.size.toLong())
                exchange.responseBody.use { if (status != 204) it.write(bytes) }
                exchange.close()
            }
        }
    }
}
