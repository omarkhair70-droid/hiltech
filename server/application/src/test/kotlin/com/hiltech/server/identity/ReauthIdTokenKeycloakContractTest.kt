package com.hiltech.server.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ReauthIdTokenKeycloakContractTest {
    @Test
    fun realKeycloakIdTokenVerifiesForNativeClientAndCarriesFreshAuthContext() {
        assumeTrue(
            System.getenv(
                "HILTECH_REAUTH_ID_TOKEN_CONTRACT_TEST",
            ) == "1",
        )

        val issuer = requireNotNull(
            System.getenv(
                "HILTECH_OIDC_ISSUER_URI",
            ),
        )
        val tokenPath = Path.of(
            requireNotNull(
                System.getenv(
                    "HILTECH_REAUTH_ID_TOKEN_FILE",
                ),
            ),
        )
        val token =
            Files.readString(tokenPath).trim()
        assertTrue(token.isNotBlank())

        val verifier =
            OidcIdTokenProofVerifier(
                HiltechOidcProperties(
                    enabled = true,
                    issuerUri = issuer,
                    nativeClientId =
                        "hiltech-native",
                ),
            )
        val jwt = verifier.verify(token)

        assertEquals(
            issuer.trimEnd('/'),
            jwt.issuer?.toString()?.trimEnd('/'),
        )
        assertTrue(
            "hiltech-native" in jwt.audience,
        )
        assertNotNull(jwt.subject)
        assertNotNull(jwt.claims["sid"])
        assertNotNull(jwt.claims["auth_time"])
    }
}
