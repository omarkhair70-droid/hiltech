package com.hiltech.shared.core.identity.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

actual object NativeOidcPlatform {
    private val secureRandom = SecureRandom()
    private val encoder =
        Base64.getUrlEncoder().withoutPadding()

    actual fun randomUrlToken(
        byteCount: Int,
    ): String {
        require(byteCount >= 16)
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    actual fun s256Challenge(
        verifier: String,
    ): String {
        require(verifier.isNotBlank())
        val digest = MessageDigest
            .getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return encoder.encodeToString(digest)
    }

    actual fun currentTimeMillis(): Long =
        System.currentTimeMillis()
}
