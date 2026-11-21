package com.hiltech.android.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.hiltech.shared.core.identity.IdentityBootstrapDto
import com.hiltech.shared.core.identity.auth.OfflineFieldReadLeasePolicy
import com.hiltech.shared.core.identity.auth.OfflineFieldReadLeaseProof
import com.hiltech.shared.core.identity.auth.VerifiedOfflineFieldIdentity
import com.hiltech.shared.core.identity.auth.NativeAuthorizationAttempt
import com.hiltech.shared.core.identity.auth.NativeOidcTokenSet
import com.hiltech.shared.core.identity.auth.OidcTokenStore
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidEncryptedOidcStore(
    context: Context,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) : OidcTokenStore {
    private val preferences =
        context.getSharedPreferences(
            "hiltech_oidc_session_v1",
            Context.MODE_PRIVATE,
        )

    override suspend fun load(): NativeOidcTokenSet? =
        readEncrypted(TOKEN_SET_KEY)?.let { encoded ->
            runCatching {
                json.decodeFromString(
                    NativeOidcTokenSet.serializer(),
                    encoded,
                )
            }.getOrElse {
                clearValue(TOKEN_SET_KEY)
                null
            }
        }

    override suspend fun save(tokens: NativeOidcTokenSet) {
        // A newly issued/rotated token requires a fresh successful identity bootstrap.
        clearValue(VERIFIED_FIELD_IDENTITY_KEY)
        writeEncrypted(
            TOKEN_SET_KEY,
            json.encodeToString(
                NativeOidcTokenSet.serializer(),
                tokens,
            ),
        )
    }

    override suspend fun clear() {
        preferences.edit()
            .remove(TOKEN_SET_KEY)
            .remove(PENDING_ATTEMPT_KEY)
            .remove(VERIFIED_FIELD_IDENTITY_KEY)
            .apply()
    }

    fun savePendingAttempt(attempt: NativeAuthorizationAttempt) {
        // A new login flow may select another employee; no old local identity survives it.
        clearValue(VERIFIED_FIELD_IDENTITY_KEY)
        writeEncrypted(
            PENDING_ATTEMPT_KEY,
            json.encodeToString(
                NativeAuthorizationAttempt.serializer(),
                attempt,
            ),
        )
    }

    fun loadPendingAttempt(): NativeAuthorizationAttempt? =
        readEncrypted(PENDING_ATTEMPT_KEY)?.let { encoded ->
            runCatching {
                json.decodeFromString(
                    NativeAuthorizationAttempt.serializer(),
                    encoded,
                )
            }.getOrElse {
                clearPendingAttempt()
                null
            }
        }

    fun clearPendingAttempt() {
        clearValue(PENDING_ATTEMPT_KEY)
    }

    suspend fun saveVerifiedFieldIdentity(identity: IdentityBootstrapDto, installationId: String) {
        val tokens = load() ?: run {
            clearValue(VERIFIED_FIELD_IDENTITY_KEY)
            return
        }
        if (!validLease(
                tokens, identity.identityStatus, identity.organizations.isNotEmpty(),
                installationId, installationId, true,
            )) {
            clearValue(VERIFIED_FIELD_IDENTITY_KEY)
            return
        }
        writeEncrypted(
            VERIFIED_FIELD_IDENTITY_KEY,
            json.encodeToString(
                VerifiedOfflineFieldIdentity.serializer(),
                VerifiedOfflineFieldIdentity(
                    identityId = identity.identityId,
                    identityStatus = identity.identityStatus,
                    hasOrganizationMembership = identity.organizations.isNotEmpty(),
                    installationId = installationId,
                    accessTokenDigest = fingerprint(tokens.accessToken),
                ),
            ),
        )
    }

    suspend fun loadVerifiedOfflineFieldIdentity(installationId: String): String? {
        val tokens = load() ?: return null
        val sealed = readEncrypted(VERIFIED_FIELD_IDENTITY_KEY) ?: return null
        val verified = runCatching {
            json.decodeFromString(VerifiedOfflineFieldIdentity.serializer(), sealed)
        }.getOrElse {
            clearValue(VERIFIED_FIELD_IDENTITY_KEY)
            return null
        }
        val expectedDigest = fingerprint(tokens.accessToken)
        val matches = MessageDigest.isEqual(
            verified.accessTokenDigest.toByteArray(Charsets.UTF_8),
            expectedDigest.toByteArray(Charsets.UTF_8),
        )
        return verified.identityId.takeIf {
            it.isNotBlank() && validLease(
                tokens,
                verified.identityStatus,
                verified.hasOrganizationMembership,
                verified.installationId,
                installationId,
                matches,
            )
        }
    }

    private fun validLease(
        tokens: NativeOidcTokenSet,
        identityStatus: String,
        hasOrganizationMembership: Boolean,
        savedInstallation: String,
        currentInstallation: String,
        tokenMatches: Boolean,
    ): Boolean = OfflineFieldReadLeasePolicy.canRead(
        OfflineFieldReadLeaseProof(
            obtainedAtEpochMs = tokens.obtainedAtEpochMs,
            expiresAtEpochMs = tokens.expiresAtEpochMs,
            nowEpochMs = System.currentTimeMillis(),
            tokenMatchesLastVerifiedBootstrap = tokenMatches && tokens.accessToken.isNotBlank(),
            installationMatches = savedInstallation == currentInstallation,
            identityStatus = identityStatus,
            hasOrganizationMembership = hasOrganizationMembership,
        ),
    )

    private fun fingerprint(token: String): String =
        Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )

    private fun writeEncrypted(key: String, plaintext: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())

        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ciphertext = Base64.encodeToString(
            cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
        preferences.edit()
            .putString(key, "$iv.$ciphertext")
            .apply()
    }

    private fun readEncrypted(key: String): String? {
        val encoded = preferences.getString(key, null) ?: return null
        return runCatching {
            val parts = encoded.split('.', limit = 2)
            require(parts.size == 2)

            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(128, iv),
            )
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        }.getOrElse {
            clearValue(key)
            null
        }
    }

    private fun clearValue(key: String) {
        preferences.edit().remove(key).apply()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)

        val existing =
            keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE,
                )
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TOKEN_SET_KEY = "token_set"
        const val VERIFIED_FIELD_IDENTITY_KEY = "verified_field_identity"
        const val PENDING_ATTEMPT_KEY = "pending_authorization"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "hiltech_oidc_session_aes_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
