package com.hiltech.shared.core.identity

import com.hiltech.shared.core.HiltechShellState
import com.hiltech.shared.core.design.HiltechLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityErrorPresentationTest {
    @Test
    fun aMissingAttemptStartsANewSignInInsteadOfRestoringTheSameFailedCallback() {
        for (language in HiltechLanguage.entries) {
            val result = failure("OIDC_ATTEMPT_MISSING", language)
            assertEquals(IdentityRecoveryAction.SignIn, result.primaryAction)
            assertEquals("OIDC_ATTEMPT_MISSING", result.supportCode)
            assertFalse(result.message.contains("OIDC"))
            assertFalse(result.message.contains("attempt exists"))
            assertNotNull(result.primaryLabel)
        }
        assertEquals("تسجيل الدخول من جديد", failure("OIDC_ATTEMPT_MISSING").primaryLabel)
        assertEquals("Start sign-in again", failure("OIDC_ATTEMPT_MISSING", HiltechLanguage.English).primaryLabel)
    }

    @Test
    fun invalidCallbackAndUnverifiedTokensNeverOfferContinuationOrOrdinaryRetry() {
        for (code in listOf(
            "OIDC_CODE_MISSING", "OIDC_AUTHORIZATION_ERROR", "OIDC_CALLBACK_INVALID", "OIDC_STATE_MISMATCH",
            "OIDC_CODE_EXCHANGE_FAILED", "OIDC_TOKEN_TYPE_UNSUPPORTED", "OIDC_TOKEN_RESPONSE_INVALID", "OIDC_ID_TOKEN_MISSING",
        )) {
            assertEquals(IdentityRecoveryAction.SignIn, failure(code).primaryAction, code)
        }
    }

    @Test
    fun endedSessionsRequireFreshSignInWithoutClaimingAccessHasBeenRestored() {
        for (code in listOf(
            "UNAUTHENTICATED", "SESSION_EXPIRED", "SESSION_NOT_ACTIVE", "SESSION_REVOKED",
            "OIDC_REFRESH_UNAVAILABLE", "OIDC_REFRESH_FAILED", "DEVICE_NOT_REGISTERED",
        )) {
            assertEquals(IdentityRecoveryAction.SignIn, failure(code).primaryAction, code)
        }
    }

    @Test
    fun sensitiveActionConfirmationUsesTheDedicatedReauthenticationCallback() {
        for (code in listOf(
            "REAUTH_REQUIRED", "MFA_REQUIRED", "REAUTH_PROOF_INVALID", "REAUTH_PROOF_STALE",
            "REAUTH_AUTH_TIME_MISSING", "REAUTH_SUBJECT_MISMATCH", "REAUTH_SESSION_MISMATCH",
        )) {
            val result = identityErrorPresentation(code, IdentityErrorKind.AccessDenied, HiltechLanguage.Arabic)
            assertEquals(IdentityRecoveryAction.Reauthenticate, result.primaryAction, code)
            assertEquals(IdentityRecoveryAction.SignOut, result.secondaryAction, code)
        }
    }

    @Test
    fun stoppedAccountMembershipOrDeviceAccessDoesNotOfferAnAutomaticRetry() {
        for (code in listOf(
            "IDENTITY_NOT_PROVISIONED", "IDENTITY_NOT_ACTIVE", "NO_ACTIVE_ORGANIZATION_MEMBERSHIP",
            "DEVICE_REVOKED", "DEVICE_INSTALLATION_CONFLICT", "PERMISSION_DENIED", "OBJECT_NOT_VISIBLE",
            "ACTION_NOT_ALLOWED_IN_CONTEXT",
        )) {
            for (kind in listOf(IdentityErrorKind.Failure, IdentityErrorKind.AccessDenied)) {
                val result = identityErrorPresentation(code, kind, HiltechLanguage.Arabic)
                assertEquals(IdentityRecoveryAction.SignOut, result.primaryAction, code)
                assertNull(result.secondaryAction, code)
                assertTrue(result.message.contains("المسؤول"), code)
            }
        }
    }

    @Test
    fun connectionAndTemporaryServiceFailuresHaveAnExplicitRetry() {
        for (code in listOf(
            "NETWORK_UNAVAILABLE", "TEMPORARY_UNAVAILABLE", "INTERNAL_ERROR", "HTTP_ERROR",
            "AUTHORIZATION_UNAVAILABLE", "RATE_LIMITED", "IDENTITY_RUNTIME_FAILURE",
        )) {
            val result = failure(code)
            assertEquals(IdentityRecoveryAction.Retry, result.primaryAction, code)
            assertNull(result.secondaryAction, code)
        }
    }

    @Test
    fun changedSecurityRecordsAskForReloadRatherThanReissuingARevocation() {
        for (code in listOf("SESSION_NOT_FOUND", "DEVICE_NOT_FOUND", "STATE_CONFLICT", "VERSION_CONFLICT")) {
            val result = failure(code, HiltechLanguage.English)
            assertEquals(IdentityRecoveryAction.Retry, result.primaryAction, code)
            assertTrue(result.message.contains("Reload"), code)
        }
    }

    @Test
    fun setupAndInvalidClientRequestsDoNotInventARepairButton() {
        for (code in listOf(
            "OIDC_ISSUER_MISMATCH", "OIDC_AUTHORIZATION_CODE_UNAVAILABLE", "OIDC_PKCE_S256_UNAVAILABLE",
            "PROVIDER_SESSION_REFERENCE_MISSING", "IDENTITY_RUNTIME_COMPONENT_MISSING", "DEVICE_CONTEXT_REQUIRED",
            "INVALID_DEVICE_INSTALLATION_ID", "INVALID_INSTALLATIONID", "INVALID_DEVICE_PLATFORM", "INVALID_DEVICE_METADATA",
            "INVALID_SESSION_ID", "INVALID_DEVICE_ID", "MALFORMED_REQUEST", "REJECTED_VALIDATION",
        )) {
            val result = failure(code)
            assertEquals(IdentityRecoveryAction.None, result.primaryAction, code)
            assertNull(result.primaryLabel, code)
            assertNull(result.secondaryAction, code)
            assertEquals(code, result.supportCode)
        }
    }

    @Test
    fun browserAndLogoutFailuresCanStartANewVerifiedSession() {
        for (code in listOf("OIDC_SYSTEM_BROWSER_UNAVAILABLE", "OIDC_LOGOUT_FAILED")) {
            assertEquals(IdentityRecoveryAction.SignIn, failure(code).primaryAction, code)
        }
    }

    @Test
    fun serverAndProviderMessagesCannotEnterAnyIdentityErrorPresentation() {
        val raw = "SYNTHETIC_PROVIDER_DETAIL https://synthetic.invalid/callback?code=synthetic-value"
        val states = listOf(
            HiltechShellState.Failure("OIDC_AUTHORIZATION_ERROR", raw),
            HiltechShellState.Failure("UNEXPECTED_SYNTHETIC_FAILURE", raw),
            HiltechShellState.AccessDenied("IDENTITY_NOT_PROVISIONED", raw),
            HiltechShellState.AccessDenied("UNEXPECTED_SYNTHETIC_DENIAL", raw),
            HiltechShellState.ConfigurationRequired(raw),
        )
        for (state in states) for (language in HiltechLanguage.entries) {
            val result = assertNotNull(identityErrorPresentation(state, language))
            val visible = listOf(result.title, result.message, result.primaryLabel, result.secondaryLabel, result.supportCode)
                .filterNotNull().joinToString(" ")
            assertFalse(visible.contains(raw))
            assertFalse(visible.contains("SYNTHETIC_PROVIDER_DETAIL"))
            assertFalse(visible.contains("synthetic.invalid"))
        }
    }

    @Test
    fun malformedSupportCodesCannotBecomeARawDiagnosticChannel() {
        for (code in listOf("", "provider=synthetic", "https://synthetic.invalid", "DETAIL\nNEXT_LINE", "A".repeat(81))) {
            val result = failure(code)
            assertEquals("IDENTITY_ERROR", result.supportCode)
            assertFalse(result.message.contains(code.takeIf { it.isNotEmpty() } ?: "https://"))
        }
        assertEquals("UNEXPECTED_CODE_42", failure("UNEXPECTED_CODE_42").supportCode)
    }

    @Test
    fun unknownDenialAndUnknownFailureKeepDifferentRecoveryMeaning() {
        val failure = failure("UNEXPECTED_CODE_42")
        val denial = identityErrorPresentation("UNEXPECTED_CODE_42", IdentityErrorKind.AccessDenied, HiltechLanguage.Arabic)
        assertEquals(IdentityRecoveryAction.Retry, failure.primaryAction)
        assertEquals(IdentityRecoveryAction.SignOut, denial.primaryAction)
        assertNotEquals(failure.message, denial.message)
    }

    @Test
    fun missingConfigurationHasLocalizedInstructionsAndNoFabricatedSupportReference() {
        for (language in HiltechLanguage.entries) {
            val result = assertNotNull(identityErrorPresentation(HiltechShellState.ConfigurationRequired("raw ignored"), language))
            assertEquals(IdentityRecoveryAction.None, result.primaryAction)
            assertNull(result.primaryLabel)
            assertNull(result.supportCode)
        }
    }

    @Test
    fun bothLanguagesAreIndependentOfThePlatformLocale() {
        val arabic = failure("OIDC_ATTEMPT_MISSING", HiltechLanguage.Arabic)
        val english = failure("OIDC_ATTEMPT_MISSING", HiltechLanguage.English)
        assertTrue(arabic.title.any { it in '\u0600'..'\u06ff' })
        assertTrue(arabic.message.any { it in '\u0600'..'\u06ff' })
        assertFalse(english.title.any { it in '\u0600'..'\u06ff' })
        assertFalse(english.message.any { it in '\u0600'..'\u06ff' })
        assertEquals(arabic.supportCode, english.supportCode)
        assertEquals(arabic.primaryAction, english.primaryAction)
    }

    @Test
    fun normalAndWorkingStatesAreNotReclassifiedAsErrors() {
        assertNull(identityErrorPresentation(HiltechShellState.SignedOut, HiltechLanguage.Arabic))
        assertNull(identityErrorPresentation(HiltechShellState.Working("synthetic progress"), HiltechLanguage.Arabic))
    }

    private fun failure(code: String, language: HiltechLanguage = HiltechLanguage.Arabic): IdentityErrorPresentation =
        assertNotNull(identityErrorPresentation(HiltechShellState.Failure(code, "RAW_MESSAGE_MUST_NOT_RENDER"), language))
}
