package com.hiltech.server.platform

import kotlinx.serialization.json.Json
import org.springframework.http.HttpStatus
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

data class IdempotentCommandSpec(
    val operationId: UUID,
    val actorUserId: UUID,
    val commandType: String,
    val targetType: String? = null,
    val targetId: UUID? = null,
    val requestFingerprint: String? = null,
    val correlationId: String,
) {
    init {
        require(commandType.isNotBlank())
        require(commandType.length <= 120)
        require(targetType == null || targetType.length <= 80)
        require(requestFingerprint == null || requestFingerprint.length <= 128)
        require(correlationId.isNotBlank())
        require(correlationId.length <= 128)
    }
}

data class IdempotentCommandOutcome(
    val resultCode: String,
    val resultPayloadJson: String? = null,
) {
    init {
        require(resultCode.isNotBlank())
        require(resultCode.length <= 120)

        resultPayloadJson?.let { payload ->
            require(payload.length <= MAX_RESULT_PAYLOAD_CHARS) {
                "Idempotent result payload exceeds safe storage bound."
            }
            Json.parseToJsonElement(payload)
        }
    }

    companion object {
        const val MAX_RESULT_PAYLOAD_CHARS = 32_768
    }
}

data class IdempotentCommandExecution(
    val outcome: IdempotentCommandOutcome,
    val replayed: Boolean,
)

object IdempotencyKeyContract {
    fun requireMatches(
        rawHeader: String?,
        operationId: UUID,
    ): UUID {
        val raw =
            rawHeader
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw ProductApiException(
                    code = "IDEMPOTENCY_KEY_REQUIRED",
                    message = "Idempotency-Key is required for this command.",
                    status = HttpStatus.BAD_REQUEST,
                )

        val headerId =
            runCatching {
                UUID.fromString(raw)
            }.getOrElse {
                throw ProductApiException(
                    code = "INVALID_IDEMPOTENCY_KEY",
                    message = "Idempotency-Key must be a UUID.",
                    status = HttpStatus.BAD_REQUEST,
                )
            }

        if (headerId != operationId) {
            throw ProductApiException(
                code = "IDEMPOTENCY_KEY_MISMATCH",
                message = "Idempotency-Key must match operationId.",
                status = HttpStatus.CONFLICT,
            )
        }

        return headerId
    }

    fun fingerprint(
        canonicalSemanticRequest: String,
    ): String =
        MessageDigest.getInstance("SHA-256")
            .digest(
                canonicalSemanticRequest.toByteArray(
                    StandardCharsets.UTF_8,
                ),
            )
            .joinToString("") { byte ->
                "%02x".format(byte)
            }
}

interface IdempotentCommandExecutor {
    fun execute(
        spec: IdempotentCommandSpec,
        action: () -> IdempotentCommandOutcome,
    ): IdempotentCommandExecution
}
