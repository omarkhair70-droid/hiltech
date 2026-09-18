package com.hiltech.server.identity

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals

class OidcIdentityContractTest {
    @Test
    fun disabledOidcDoesNotRequireProductionIssuer() {
        HiltechOidcProperties(
            enabled = false,
        ).validateEnabledConfiguration()
    }

    @Test
    fun enabledOidcRequiresIssuerButDoesNotInventAudienceRequirement() {
        val missingIssuer = HiltechOidcProperties(
            enabled = true,
            issuerUri = "",
        )

        assertThrows<IllegalArgumentException> {
            missingIssuer.validateEnabledConfiguration()
        }

        HiltechOidcProperties(
            enabled = true,
            issuerUri = "https://identity.example/realms/hiltech",
            audience = "",
        ).validateEnabledConfiguration()
    }

    @Test
    fun productIdentityKeyUsesIssuerAndSubjectNotRoleClaims() {
        val resolved = OidcSubjectResolver.fromClaims(
            issuer = " https://identity.example/realms/hiltech ",
            subject = " 8a7d-keycloak-subject ",
        )

        assertEquals(
            AuthenticatedOidcSubject(
                issuer = "https://identity.example/realms/hiltech",
                subject = "8a7d-keycloak-subject",
            ),
            resolved,
        )
    }

    @Test
    fun missingSubjectFailsClosed() {
        assertThrows<IllegalArgumentException> {
            OidcSubjectResolver.fromClaims(
                issuer = "https://identity.example/realms/hiltech",
                subject = " ",
            )
        }
    }
}
