package com.hiltech.server.identity

import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

sealed interface EmployeeCredentialReplacementResult {
    class Issued(val temporaryCredential: String) : EmployeeCredentialReplacementResult {
        override fun toString() = "Issued(credential=redacted)"
    }
    data class Rejected(val safeCode: String) : EmployeeCredentialReplacementResult
    data class Unconfirmed(val safeCode: String) : EmployeeCredentialReplacementResult
}
interface EmployeeCredentialReplacementPort {
    fun availableFor(issuer: String): Boolean
    fun replace(issuer: String, subject: String): EmployeeCredentialReplacementResult
}
internal interface KeycloakCredentialReplacementGateway {
    /** Replaces a password of an already enabled account; never creates or enables a user. */
    fun replaceCurrentPassword(subject: String): EmployeeCredentialReplacementResult
}

@Component
internal class KeycloakEmployeeCredentialReplacement(
    private val oidc: HiltechOidcProperties,
    private val properties: KeycloakProvisioningProperties,
    private val gateway: ObjectProvider<KeycloakCredentialReplacementGateway>,
) : EmployeeCredentialReplacementPort {
    override fun availableFor(issuer: String): Boolean = oidc.enabled && properties.enabled &&
        oidc.issuerUri.trim().trimEnd('/').isNotEmpty() && issuer.trimEnd('/') == oidc.issuerUri.trim().trimEnd('/') && gateway.ifAvailable != null
    override fun replace(issuer: String, subject: String): EmployeeCredentialReplacementResult {
        if (!availableFor(issuer) || subject.isBlank()) return EmployeeCredentialReplacementResult.Rejected("ACCOUNT_CREDENTIAL_PROVIDER_UNAVAILABLE")
        return requireNotNull(gateway.ifAvailable).replaceCurrentPassword(subject)
    }
}
