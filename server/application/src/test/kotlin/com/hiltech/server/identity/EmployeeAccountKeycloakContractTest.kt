package com.hiltech.server.identity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Real, opt-in provider contract. The companion CI driver owns a random realm on
 * a disposable HTTPS loopback Keycloak. Secrets cross only a private temporary
 * directory; they never appear in assertions, stdout, test names or reports.
 * Local employee/member transaction behavior is exercised by the PostgreSQL contracts.
 */
class EmployeeAccountKeycloakContractTest {
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun productionGatewayCreatesRecoversAndReplacesCredentialsThroughRealBrowserLogin() {
        assumeTrue(System.getenv("HILTECH_ACCOUNT_KEYCLOAK_CONTRACT_TEST") == "1")
        val channel = try {
            PrivateProviderChannel.load()
        } catch (failure: Exception) {
            throw AssertionError("Isolated account provider fixture was invalid (${failure.javaClass.simpleName}).")
        }
        val oidc = HiltechOidcProperties(
            enabled = true,
            issuerUri = channel.value("issuer"),
            nativeClientId = channel.value("nativeClientId"),
        )
        val properties = KeycloakProvisioningProperties(
            enabled = true,
            adminClientId = channel.value("adminClientId"),
            adminClientSecret = channel.value("adminClientSecret"),
            emailActionLinkEnabled = false,
        )
        val client = KeycloakIdentityAdminClient(properties, oidc)
        val beans = DefaultListableBeanFactory().apply { registerSingleton("ownedProvider", client) }
        val replacement = KeycloakEmployeeCredentialReplacement(
            oidc, properties, beans.getBeanProvider(KeycloakCredentialReplacementGateway::class.java),
        )
        var stage = "create-disabled"
        try {
            val invitation = UUID.fromString(channel.value("invitationId"))
            val login = channel.value("login")
            val handle = client.createOrRecoverDisabledUser(invitation, login)
            val recovered = client.createOrRecoverDisabledUser(invitation, login)
            check(handle.subject.isNotBlank() && recovered.subject == handle.subject) {
                "Repeated provisioning must recover the same disabled subject."
            }
            channel.exchange("created") { put("subject", handle.subject) }

            stage = "first-delivery"
            val delivery = client.enableAndPrepareDelivery(
                handle, login, IdentityProvisioningDeliveryMode.TEMPORARY_PASSWORD_HANDOFF,
            )
            check(delivery.state == IdentityProvisioningDeliveryState.READY) { "Temporary delivery was not ready." }
            val firstSecret = requireNotNull(delivery.temporaryCredential) { "Temporary delivery had no credential." }
            check(firstSecret.length >= 24) { "Temporary credential was unexpectedly short." }
            val firstLogin = channel.exchange("delivered") {
                put("subject", handle.subject)
                put("temporaryCredential", firstSecret)
            }
            verifyBrowserProof(oidc, firstLogin, handle.subject)

            stage = "replacement"
            check(replacement.availableFor(oidc.issuerUri)) { "Current provider replacement was unavailable." }
            val reset = replacement.replace(oidc.issuerUri, handle.subject)
            check(reset is EmployeeCredentialReplacementResult.Issued) { "Credential replacement was not issued." }
            check(reset.temporaryCredential.length >= 24 && reset.temporaryCredential != firstSecret) {
                "Replacement must supply a new temporary credential."
            }
            val nextLogin = channel.exchange("replaced") {
                put("subject", handle.subject)
                put("temporaryCredential", reset.temporaryCredential)
            }
            verifyBrowserProof(oidc, nextLogin, handle.subject)

            stage = "disable-owned-user"
            channel.exchange("disable") { put("subject", handle.subject) }
            stage = "preserve-disabled"
            val restricted = replacement.replace(oidc.issuerUri, handle.subject)
            check(restricted is EmployeeCredentialReplacementResult.Rejected &&
                restricted.safeCode == "ACCOUNT_CREDENTIAL_PROVIDER_ACCOUNT_RESTRICTED") {
                "Replacing credentials must not restore a disabled provider account."
            }
            channel.exchange("restricted") { put("subject", handle.subject) }
            channel.publish("jvm-pass") { put("passed", true) }
        } catch (failure: Exception) {
            val safeCode = (failure as? KeycloakProvisioningException)?.safeCode
                ?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) } ?: "PROVIDER_CONTRACT_ASSERTION"
            channel.publish("jvm-failure") {
                put("stage", stage); put("kind", failure.javaClass.simpleName); put("code", safeCode)
            }
            // Some HTTP/HTML exceptions include credentials or authorization URLs.
            // Deliberately omit their message and cause from public JUnit reports.
            throw AssertionError("Isolated account provider contract failed at $stage ($safeCode, ${failure.javaClass.simpleName}).")
        } finally {
            client.close()
        }
    }

    private fun verifyBrowserProof(oidc: HiltechOidcProperties, response: JsonObject, subject: String) {
        val token = requireNotNull(response["idToken"]?.jsonPrimitive?.content) { "Browser proof was absent." }
        val jwt = OidcIdTokenProofVerifier(oidc).verify(token)
        check(jwt.subject == subject && jwt.issuer?.toString() == oidc.issuerUri &&
            oidc.nativeClientId in jwt.audience.orEmpty() && jwt.claims["auth_time"] != null &&
            jwt.claims["nonce"] == response["nonce"]?.jsonPrimitive?.content) {
            "The browser proof must belong to the exact provisioned subject and PKCE session."
        }
    }

    private class PrivateProviderChannel private constructor(
        private val directory: Path,
        private val fixture: JsonObject,
    ) {
        fun value(key: String): String = requireNotNull(fixture[key]?.jsonPrimitive?.content) { "Missing fixture field." }

        fun exchange(stage: String, fields: JsonObjectBuilder.() -> Unit): JsonObject {
            publish("jvm-$stage", fields)
            val path = directory.resolve("browser-$stage.json")
            val deadline = System.nanoTime() + Duration.ofSeconds(80).toNanos()
            while (System.nanoTime() < deadline) {
                check(!Files.exists(directory.resolve("browser-failure.json"))) { "Browser contract failed." }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    assertPrivate(path, directory = false)
                    val response = Json.parseToJsonElement(Files.readString(path)).jsonObject
                    check(response["owner"]?.jsonPrimitive?.content == value("owner")) { "Unowned browser response." }
                    check(response["stage"]?.jsonPrimitive?.content == stage) { "Unexpected browser response." }
                    return response
                }
                Thread.sleep(100)
            }
            error("Browser provider contract response timed out.")
        }

        fun publish(name: String, fields: JsonObjectBuilder.() -> Unit) {
            val body = buildJsonObject { put("owner", value("owner")); fields() }
            val temp = Files.createTempFile(directory, "private-", ".tmp", PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS))
            try {
                Files.writeString(temp, body.toString())
                Files.move(temp, directory.resolve("$name.json"), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temp)
            }
        }

        companion object {
            private val FILE_PERMISSIONS = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            private val DIRECTORY_PERMISSIONS = FILE_PERMISSIONS + PosixFilePermission.OWNER_EXECUTE

            fun load(): PrivateProviderChannel {
                val directory = Path.of(requireNotNull(System.getenv("HILTECH_ACCOUNT_KEYCLOAK_CONTRACT_DIR")))
                    .toAbsolutePath().normalize()
                check(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "Private fixture directory is absent." }
                assertPrivate(directory, directory = true)
                val manifest = directory.resolve("fixture.json")
                check(Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) { "Private fixture manifest is absent." }
                assertPrivate(manifest, directory = false)
                val fixture = Json.parseToJsonElement(Files.readString(manifest)).jsonObject
                val channel = PrivateProviderChannel(directory, fixture)
                val owner = channel.value("owner")
                check(owner.matches(Regex("[a-f0-9]{32}"))) { "Fixture ownership is invalid." }
                val realm = "hiltech-account-contract-$owner"
                check(channel.value("realm") == realm) { "This test requires a newly owned disposable realm." }
                val base = URI.create(channel.value("base"))
                check(base.scheme == "https" && base.host == "127.0.0.1" && base.port in 1..65535 &&
                    base.userInfo == null && base.query == null && base.fragment == null && base.path.isNullOrEmpty()) {
                    "This test can address only the disposable HTTPS loopback provider."
                }
                check(channel.value("issuer") == "$base/realms/$realm") { "Fixture issuer does not match its owned realm." }
                check(channel.value("adminClientId") == "hiltech-account-provisioner" &&
                    channel.value("nativeClientId") == "hiltech-account-native") { "Unexpected fixture clients." }
                return channel
            }

            private fun assertPrivate(path: Path, directory: Boolean) {
                check(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) ==
                    if (directory) DIRECTORY_PERMISSIONS else FILE_PERMISSIONS) { "Provider fixture permissions must be private." }
            }
        }
    }
}
