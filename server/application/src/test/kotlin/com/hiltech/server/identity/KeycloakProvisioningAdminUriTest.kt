package com.hiltech.server.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KeycloakProvisioningAdminUriTest {
    @Test
    fun acceptsOptionalServiceBaseForAccountSetup() {
        assertNull(KeycloakProvisioningProperties().privateAdminBaseUri())
        assertEquals(
            "http://hiltech-keycloak-private:8080",
            KeycloakProvisioningProperties(adminBaseUri = "http://hiltech-keycloak-private:8080/").privateAdminBaseUri().toString(),
        )
        assertEquals(
            "https://identity.example.test/keycloak",
            KeycloakProvisioningProperties(adminBaseUri = "https://identity.example.test/keycloak/").privateAdminBaseUri().toString(),
        )
    }

    @Test
    fun rejectsPublicCleartextAndCredentialBearingAddresses() {
        listOf(
            "http://identity.example.test",
            "https://user:password@identity.example.test",
            "https://identity.example.test?mode=debug",
            "file:///opt/keycloak",
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                KeycloakProvisioningProperties(adminBaseUri = value).privateAdminBaseUri()
            }
        }
    }
}
