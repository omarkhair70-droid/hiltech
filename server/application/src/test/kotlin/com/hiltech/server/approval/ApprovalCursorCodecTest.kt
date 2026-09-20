package com.hiltech.server.approval

import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ApprovalCursorCodecTest {
    private val codec =
        ApprovalCursorCodec(
            signingKey =
                "approval-cursor-test-signing-key-0000000000000001",
        )

    @Test
    fun nonCanonicalBase64urlAliasOfValidSignatureIsRejected() {
        val cursor =
            ApprovalCursor(
                actorUserId =
                    UUID.fromString(
                        "11111111-1111-1111-1111-111111111111",
                    ),
                asOf =
                    Instant.parse(
                        "2026-09-20T08:00:00Z",
                    ),
                afterCreatedAt =
                    Instant.parse(
                        "2026-09-20T07:59:00Z",
                    ),
                afterRequestId =
                    UUID.fromString(
                        "22222222-2222-2222-2222-222222222222",
                    ),
            )
        val canonical = codec.encode(cursor)
        assertEquals(
            cursor,
            codec.decode(canonical),
        )

        val parts = canonical.split('.')
        val signature = parts[1]
        val alphabet =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val canonicalLastIndex =
            alphabet.indexOf(signature.last())

        // HMAC-SHA256 is 32 bytes. Its unpadded Base64url form ends with
        // four data bits plus two unused bits, so canonical encoders always
        // emit an index divisible by four. Incrementing only an unused bit
        // creates a different textual token that Java decodes to the same
        // signature bytes.
        check(canonicalLastIndex >= 0)
        check(canonicalLastIndex % 4 == 0)
        val aliasedSignature =
            signature.dropLast(1) +
                alphabet[canonicalLastIndex + 1]
        val nonCanonicalAlias =
            parts[0] + "." + aliasedSignature

        val failure =
            assertThrows(
                ProductApiException::class.java,
            ) {
                codec.decode(nonCanonicalAlias)
            }
        assertEquals(
            "CURSOR_INVALID",
            failure.code,
        )
    }
}
